package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TerminalLineType { INPUT, OUTPUT, ERROR, SYSTEM }

data class TerminalLine(
    val text: String,
    val type: TerminalLineType,
)

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = false,
    val currentInput: String = "",
    val workingDirectory: String = "/root",
)

class TerminalViewModel(
    private val commandRunner: LocalRuntimeCommandRunner,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            TerminalUiState(
                lines =
                    listOf(
                        TerminalLine("OpenCode Terminal - PRoot Alpine Linux", TerminalLineType.SYSTEM),
                    ),
            ),
        )
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        _state.update { s ->
            s.copy(
                lines = appendLine(s.lines, TerminalLine("${s.workingDirectory} $ $trimmed", TerminalLineType.INPUT)),
                currentInput = "",
                isRunning = true,
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val fullCommand =
                        if (_state.value.workingDirectory != "/root") {
                            "cd ${_state.value.workingDirectory} && $trimmed"
                        } else {
                            trimmed
                        }
                    commandRunner.runShell(fullCommand, timeoutSeconds = 30L)
                }

            _state.update { s ->
                val newLines = s.lines.toMutableList()
                if (result.output.isNotBlank()) {
                    result.output.lines().forEach { line ->
                        val type = if (result.exitCode != 0) TerminalLineType.ERROR else TerminalLineType.OUTPUT
                        newLines.add(TerminalLine(line, type))
                    }
                }
                if (result.exitCode != 0 && result.output.isBlank()) {
                    newLines.add(TerminalLine("exit code: ${result.exitCode}", TerminalLineType.ERROR))
                }
                s.copy(
                    lines = trimScrollback(newLines),
                    isRunning = false,
                    workingDirectory = resolveWorkingDirectory(s.workingDirectory, trimmed),
                )
            }
        }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(currentInput = text) }
    }

    fun clear() {
        _state.update {
            it.copy(
                lines =
                    listOf(
                        TerminalLine("OpenCode Terminal - PRoot Alpine Linux", TerminalLineType.SYSTEM),
                    ),
            )
        }
    }

    private fun resolveWorkingDirectory(
        current: String,
        command: String,
    ): String {
        val cdPattern = Regex("""^cd\s+(.*)$""")
        val match = cdPattern.find(command.trim()) ?: return current
        val target = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
        return when {
            target.startsWith("/") -> target
            target == "~" -> "/root"
            target == ".." -> current.substringBeforeLast("/", "").ifEmpty { "/" }.ifEmpty { "/" }
            target == "." -> current
            else -> if (current == "/") "/$target" else "$current/$target"
        }
    }

    private fun appendLine(
        lines: List<TerminalLine>,
        line: TerminalLine,
    ): List<TerminalLine> {
        return trimScrollback(lines + line)
    }

    private fun trimScrollback(lines: List<TerminalLine>): List<TerminalLine> {
        return if (lines.size > MAX_SCROLLBACK) lines.takeLast(MAX_SCROLLBACK) else lines
    }

    private companion object {
        const val MAX_SCROLLBACK = 500
    }
}
