package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import java.io.File

/**
 * Reads Claude Code's slash commands and skills off disk.
 *
 * OpenCode serves these over HTTP; Claude Code keeps them as Markdown files, personal ones under
 * the sandbox home and project ones under the workspace. Reading the files rather than waiting for
 * the CLI to announce them at session start means the settings screen has an answer before any chat
 * has been opened.
 */
object ClaudeCommandCatalog {
    /** `commands/git/commit.md` is invoked as `/git:commit`, matching Claude Code's own naming. */
    fun commands(roots: List<File>): List<OpenCodeCommand> =
        roots
            .flatMap { root -> markdownFiles(File(root, "commands")).map { root to it } }
            .map { (root, file) ->
                OpenCodeCommand(
                    name = commandName(File(root, "commands"), file),
                    description = frontMatter(file)["description"],
                )
            }
            .distinctBy(OpenCodeCommand::name)
            .sortedBy(OpenCodeCommand::name)

    fun skills(roots: List<File>): List<OpenCodeSkill> =
        roots
            .flatMap { root ->
                File(root, "skills").listFiles().orEmpty()
                    .filter(File::isDirectory)
                    .mapNotNull { directory -> File(directory, "SKILL.md").takeIf(File::isFile) }
            }
            .map { file ->
                val fields = frontMatter(file)
                OpenCodeSkill(
                    name = fields["name"] ?: file.parentFile?.name.orEmpty(),
                    description = fields["description"],
                    location = file.parent,
                )
            }
            .filter { it.name.isNotEmpty() }
            .distinctBy(OpenCodeSkill::name)
            .sortedBy(OpenCodeSkill::name)

    private fun markdownFiles(directory: File): List<File> =
        directory.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile && it.extension == "md" }
            .toList()

    private fun commandName(
        root: File,
        file: File,
    ): String = file.relativeTo(root).path.removeSuffix(".md").replace(File.separatorChar, ':')

    /**
     * Reads the `key: value` pairs of a leading `---` block.
     *
     * Only the first block counts, and only up to [MAX_FRONT_MATTER_LINES], so a large command body
     * is never read into memory just to find its description.
     */
    internal fun frontMatter(file: File): Map<String, String> =
        runCatching {
            file.useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext() || iterator.next().trim() != "---") return@useLines emptyMap()
                buildMap {
                    var read = 0
                    while (iterator.hasNext() && read++ < MAX_FRONT_MATTER_LINES) {
                        val line = iterator.next()
                        if (line.trim() == "---") break
                        val separator = line.indexOf(':')
                        if (separator <= 0) continue
                        val value = line.substring(separator + 1).trim().trim('"', '\'')
                        if (value.isNotEmpty()) put(line.take(separator).trim(), value)
                    }
                }
            }
        }.getOrDefault(emptyMap())

    private const val MAX_DEPTH = 4
    private const val MAX_FRONT_MATTER_LINES = 40
}
