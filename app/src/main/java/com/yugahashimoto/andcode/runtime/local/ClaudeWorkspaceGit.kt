package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo

/**
 * Git information for the Claude Code runtime.
 *
 * OpenCode exposes this over HTTP; Claude Code has no server, so the sandbox's own `git` is asked
 * instead. Parsing lives here, apart from the process plumbing, so it can be tested without a
 * device.
 */
object ClaudeWorkspaceGit {
    /**
     * Current branch on the first line, the remote's default branch on the second.
     *
     * `git symbolic-ref` fails when no remote HEAD is configured, which is common in a workspace
     * cloned shallowly or created locally, so its failure is swallowed and the line left empty
     * rather than failing the whole command.
     */
    const val INFO_SCRIPT =
        "git rev-parse --abbrev-ref HEAD && " +
            "{ git symbolic-ref --quiet refs/remotes/origin/HEAD || echo; }"

    const val STATUS_SCRIPT = "git status --porcelain=v1"

    /**
     * Lines added and removed per file, which `git status` does not report.
     *
     * Without it the changes list shows every file as `+0 / -0`, which reads as "nothing changed"
     * next to a file the user was told is modified.
     */
    const val NUMSTAT_SCRIPT = "git --no-pager diff --numstat HEAD"

    /** Path to its added/removed counts. Binary files report `-` and are left at zero. */
    fun parseNumstat(output: String): Map<String, Pair<Int, Int>> =
        output.lineSequence()
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size < 3) return@mapNotNull null
                val path = fields[2].substringAfterLast(" => ").trim('"', '{', '}')
                if (path.isEmpty()) return@mapNotNull null
                path to (fields[0].toIntOrNull().orZero() to fields[1].toIntOrNull().orZero())
            }
            .toMap()

    private fun Int?.orZero(): Int = this ?: 0

    /**
     * Working-tree changes, staged and unstaged, as one patch.
     *
     * Nothing here writes to the repository: a viewer that quietly staged the user's files would be
     * changing work it was only asked to show. Untracked files are therefore absent from the patch,
     * and reach the UI through [parseStatus] instead.
     */
    fun diffScript(context: Int?): String {
        val lines = context ?: DEFAULT_DIFF_CONTEXT
        return "git --no-pager diff --no-color -U$lines HEAD"
    }

    private const val DEFAULT_DIFF_CONTEXT = 3

    /** `git rev-parse --abbrev-ref HEAD` plus the remote head, one per line. */
    fun parseInfo(output: String): OpenCodeVcsInfo {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val branch = lines.firstOrNull()?.takeIf { it != "HEAD" }
        // `origin/main` from `git symbolic-ref refs/remotes/origin/HEAD`, or nothing when unset.
        val defaultBranch = lines.getOrNull(1)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return OpenCodeVcsInfo(branch = branch, defaultBranch = defaultBranch)
    }

    /**
     * Parses `git status --porcelain=v1`.
     *
     * The two status characters are index and worktree state; renames carry `old -> new` and only
     * the new path is of interest to the UI.
     */
    fun parseStatus(
        output: String,
        counts: Map<String, Pair<Int, Int>> = emptyMap(),
    ): List<OpenCodeFileChange> =
        output.lineSequence()
            .filter { it.length > 3 }
            .mapNotNull { line ->
                val code = line.take(2).trim().ifEmpty { return@mapNotNull null }
                val path = line.drop(3).trim().substringAfterLast(" -> ").trim('"')
                if (path.isEmpty()) return@mapNotNull null
                val (added, removed) = counts[path] ?: (0 to 0)
                OpenCodeFileChange(
                    file = path,
                    path = path,
                    added = added,
                    removed = removed,
                    additions = added.toDouble(),
                    deletions = removed.toDouble(),
                    status = statusName(code),
                )
            }
            .toList()

    /**
     * Splits `git diff` output into one entry per file, keeping each file's patch.
     *
     * Counting +/- lines here rather than asking git for a second numstat keeps it to one command.
     */
    fun parseDiff(output: String): List<OpenCodeFileChange> {
        if (output.isBlank()) return emptyList()
        val changes = mutableListOf<OpenCodeFileChange>()
        val patch = StringBuilder()
        var path: String? = null

        fun flush() {
            val current = path ?: return
            val text = patch.toString()
            val added = text.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
            val removed = text.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
            changes +=
                OpenCodeFileChange(
                    file = current,
                    path = current,
                    patch = text,
                    added = added,
                    removed = removed,
                    // Both spellings: the API carries the counts twice and the changes list reads
                    // the decimal pair, so filling only the integers renders every file as +0/-0.
                    additions = added.toDouble(),
                    deletions = removed.toDouble(),
                    status = "modified",
                )
            patch.setLength(0)
        }

        output.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                flush()
                // "diff --git a/x b/x" — the b-side is the current name.
                path = line.substringAfter(" b/", "").trim().ifEmpty { null }
            }
            if (path != null) patch.append(line).append('\n')
        }
        flush()
        return changes
    }

    private fun statusName(code: String): String =
        when {
            code.contains('?') -> "added"
            code.contains('A') -> "added"
            code.contains('D') -> "deleted"
            code.contains('R') -> "renamed"
            else -> "modified"
        }
}
