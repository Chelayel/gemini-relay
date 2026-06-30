package com.chelayel.geminirelay.agent

import java.io.File

/**
 * Discovers project-local **agents** and **skills** from the codebase, so a
 * persona or a skill defined in the repo is available without re-typing it in
 * Settings. Recognises both a Gemini-native layout and the Claude layout:
 *
 *  - Agents:  markdown files under `.gemini/agents/` or `.claude/agents/`
 *             (YAML frontmatter name/description; the body is the prompt)
 *  - Skills:  a `SKILL.md` inside each `.gemini/skills/<name>/` (or `.claude/...`)
 *             folder (frontmatter name/description; the body is the instructions)
 */
object ProjectAssets {

    data class Agent(val name: String, val description: String?, val prompt: String, val file: File)
    data class Skill(val name: String, val description: String?, val instructions: String, val file: File)

    data class Snapshot(val agents: List<Agent>, val skills: List<Skill>) {
        val isEmpty: Boolean get() = agents.isEmpty() && skills.isEmpty()
    }

    fun scan(workingDir: String): Snapshot {
        val root = File(workingDir)
        return Snapshot(scanAgents(root), scanSkills(root))
    }

    /** Personas are flat markdown files under `.gemini/personas/`, `.gemini/agents/`, or `.claude/agents/`. */
    private fun scanAgents(root: File): List<Agent> {
        val dir = listOf(".gemini/personas", ".gemini/agents", ".claude/agents")
            .map { File(root, it) }.firstOrNull { it.isDirectory }
            ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.extension == "md" } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { file ->
            val (fm, body) = parse(file)
            Agent(fm["name"] ?: file.nameWithoutExtension, fm["description"], body, file)
        }
    }

    /** Skills are directories each holding a `SKILL.md`. */
    private fun scanSkills(root: File): List<Skill> {
        val dir = listOf(".gemini/skills", ".claude/skills").map { File(root, it) }.firstOrNull { it.isDirectory }
            ?: return emptyList()
        val subs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return subs.sortedBy { it.name.lowercase() }.mapNotNull { sub ->
            val md = File(sub, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
            val (fm, body) = parse(md)
            Skill(fm["name"] ?: sub.name, fm["description"], body, md)
        }
    }

    /** Split a leading `---` YAML frontmatter block from the markdown body. */
    private fun parse(file: File): Pair<Map<String, String>, String> {
        val text = runCatching { file.readText() }.getOrElse { return emptyMap<String, String>() to "" }
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to text.trim()

        val fm = HashMap<String, String>()
        var i = 1
        while (i < lines.size && lines[i].trim() != "---") {
            val line = lines[i]
            val sep = line.indexOf(':')
            if (sep > 0) {
                val key = line.substring(0, sep).trim().lowercase()
                val value = line.substring(sep + 1).trim().trim('"', '\'')
                if (key.isNotEmpty() && value.isNotEmpty()) fm[key] = value
            }
            i++
        }
        val body = if (i < lines.size) lines.subList(i + 1, lines.size).joinToString("\n").trim() else ""
        return fm to body.ifBlank { text.trim() }
    }
}
