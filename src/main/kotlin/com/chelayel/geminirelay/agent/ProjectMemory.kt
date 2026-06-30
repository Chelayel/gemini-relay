package com.chelayel.geminirelay.agent

import java.io.File

/**
 * Discovers a project memory file to feed Gemini as standing context — the
 * Gemini-ecosystem analogue of Claude's `CLAUDE.md`. Recognises `GEMINI.md`
 * (Gemini CLI), the cross-tool `AGENTS.md`, and `CLAUDE.md`, in that order.
 */
object ProjectMemory {

    private val NAMES = listOf("GEMINI.md", "AGENTS.md", "CLAUDE.md")
    private const val MAX_CHARS = 20_000

    data class Memory(val file: File, val text: String)

    fun find(workingDir: String): File? {
        val root = File(workingDir)
        return NAMES.map { File(root, it) }.firstOrNull { it.isFile }
            ?: File(root, ".gemini/GEMINI.md").takeIf { it.isFile }
    }

    fun read(workingDir: String): Memory? {
        val file = find(workingDir) ?: return null
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val clipped = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n… (truncated)" else text
        return Memory(file, clipped)
    }
}
