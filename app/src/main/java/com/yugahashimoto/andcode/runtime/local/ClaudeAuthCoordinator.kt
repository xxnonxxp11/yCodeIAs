package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives Claude Code's browser sign-in from inside the Android app.
 *
 * `claude auth login` is an interactive flow: it prints an authorization URL, waits for the user to
 * approve it in a browser, and then reads back the code the browser shows. There is no browser
 * inside the PRoot sandbox, so the app plays that role — it captures the URL, hands it to Android's
 * browser via an intent, and writes the code the user pastes back into the CLI's terminal.
 */
class ClaudeAuthCoordinator(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val messages: ClaudeMessages = ClaudeMessages,
    private val githubToken: () -> String? = { null },
) {
    sealed interface State {
        data object Idle : State

        /** The CLI has started but has not printed a URL yet. */
        data object Starting : State

        /** The user needs to approve [url] in a browser and paste the resulting code back. */
        data class AwaitingBrowser(val url: String, val transcript: String) : State

        data object Verifying : State

        data class SignedIn(val account: String) : State

        data class Failed(val message: String, val transcript: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var readerJob: Job? = null
    private val transcript = StringBuilder()

    /** Reports the currently signed-in account, or null when Claude Code is not authenticated. */
    fun signedInAccount(): String? {
        val result = runCommand("${ClaudeCodeInstaller.CLAUDE_BINARY} auth status --text", timeoutSeconds = 60)
        if (result.exitCode != 0) return null
        val text = result.output.replace(ANSI_ESCAPE, "")
        if (NOT_LOGGED_IN.containsMatchIn(text)) return null
        return text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.contains('@') || it.startsWith("Account", ignoreCase = true) }
            ?: text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
    }

    @Synchronized
    fun begin() {
        if (mutableState.value is State.Starting || mutableState.value is State.AwaitingBrowser) return
        cancel()
        transcript.setLength(0)
        mutableState.value = State.Starting
        val runtime = installedRuntimeProvider()
        if (runtime == null) {
            mutableState.value = State.Failed(messages.runtimeMissing, "")
            return
        }
        ClaudeCodeInstaller.ensureDnsPreload(runtime.rootfs)
        val started =
            runCatching {
                ProcessBuilder(
                    ClaudeSandboxLauncher.command(
                        runtime = runtime,
                        workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() },
                        workingDirectory = "/root",
                        arguments = listOf("auth", "login"),
                        pty = true,
                    ),
                ).directory(runtimeDirectory)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(
                            ClaudeSandboxLauncher.environment(
                                runtime,
                                File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                githubToken(),
                            ),
                        )
                    }
                    .start()
            }.getOrElse { error ->
                mutableState.value = State.Failed(error.message ?: messages.signInStartFailed, "")
                return
            }
        process = started
        readerJob =
            scope.launch {
                runCatching {
                    started.inputStream.bufferedReader().forEachChunk { chunk -> onOutput(chunk) }
                }
                started.waitFor()
                onProcessExit(started.exitValue())
            }
    }

    /** Sends the code the user copied from the browser back to the waiting CLI. */
    @Synchronized
    fun submitCode(code: String) {
        val target = process ?: return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        mutableState.value = State.Verifying
        runCatching {
            target.outputStream.write((trimmed + "\n").toByteArray())
            target.outputStream.flush()
        }.onFailure { error ->
            mutableState.value = State.Failed(error.message ?: messages.submitCodeFailed, transcript.toString())
        }
    }

    @Synchronized
    fun cancel() {
        readerJob?.cancel()
        readerJob = null
        process?.takeIf(Process::isAlive)?.destroyForcibly()
        process = null
    }

    fun reset() {
        cancel()
        mutableState.value = State.Idle
    }

    fun signOut(): LocalRuntimeCommandResult =
        runCommand("${ClaudeCodeInstaller.CLAUDE_BINARY} auth logout", timeoutSeconds = 60).also {
            mutableState.value = State.Idle
        }

    private fun onOutput(chunk: String) {
        synchronized(this) {
            transcript.append(chunk)
            if (transcript.length > MAX_TRANSCRIPT) transcript.delete(0, transcript.length - MAX_TRANSCRIPT)
        }
        val clean = transcript.toString().replace(ANSI_ESCAPE, "")
        val current = mutableState.value
        if (current is State.Starting || current is State.AwaitingBrowser) {
            AUTH_URL.find(clean)?.value?.let { url ->
                if (current !is State.AwaitingBrowser || current.url != url) {
                    mutableState.value = State.AwaitingBrowser(url, clean.takeLast(VISIBLE_TRANSCRIPT))
                    return
                }
            }
        }
        if (current is State.AwaitingBrowser) {
            mutableState.value = current.copy(transcript = clean.takeLast(VISIBLE_TRANSCRIPT))
        }
    }

    private fun onProcessExit(exitCode: Int) {
        synchronized(this) { process = null }
        val clean = transcript.toString().replace(ANSI_ESCAPE, "")
        // The CLI can exit 0 having only printed help, so success is confirmed against auth status
        // rather than inferred from the exit code.
        val account = if (exitCode == 0) signedInAccount() else null
        mutableState.value =
            when {
                account != null -> State.SignedIn(account)
                exitCode == 0 -> State.Failed(messages.signInIncomplete, clean.takeLast(VISIBLE_TRANSCRIPT))
                else -> State.Failed(messages.signInExited(exitCode), clean.takeLast(VISIBLE_TRANSCRIPT))
            }
    }

    private fun runCommand(
        command: String,
        timeoutSeconds: Long,
    ): LocalRuntimeCommandResult =
        LocalRuntimeCommandRunner(
            runtimeDirectory = runtimeDirectory,
            installedRuntimeProvider = installedRuntimeProvider,
            accessCoordinator = accessCoordinator,
            timeoutSeconds = timeoutSeconds,
        ).runShell(command, timeoutSeconds)

    private companion object {
        const val MAX_TRANSCRIPT = 8_000
        const val VISIBLE_TRANSCRIPT = 1_200
        val ANSI_ESCAPE = Regex("\\u001B\\[[;?\\d]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007")
        val AUTH_URL = Regex("https://[\\w.-]*(?:anthropic\\.com|claude\\.(?:ai|com))/[^\\s\"'()\\[\\]]*")
        val NOT_LOGGED_IN = Regex("not\\s+(logged|signed)\\s+in|no\\s+credentials", RegexOption.IGNORE_CASE)

        /**
         * Reads incrementally rather than by line: the CLI leaves its final prompt unterminated
         * while it waits for input, so a line-oriented reader would never surface it.
         */
        fun java.io.BufferedReader.forEachChunk(onChunk: (String) -> Unit) {
            val buffer = CharArray(1024)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                if (read > 0) onChunk(String(buffer, 0, read))
            }
        }
    }
}
