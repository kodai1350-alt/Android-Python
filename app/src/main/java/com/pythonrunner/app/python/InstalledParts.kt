package com.pythonrunner.app.python

import java.io.File
import java.util.Locale

data class Lib(val name: String, val version: String)

enum class PartState { OK, MISSING, STALE }

/**
 * One installed part of a bot: the libraries of a requirements.txt, or the npm modules of a package.json.
 * [key] is the requirements.txt's path or the package folder's path ("." for the top); [label] names its
 * folder in words; [installedAt] is when 部品を入れる last finished it.
 */
data class Part(val key: String, val label: String, val isNode: Boolean, val state: PartState, val installedAt: Long?)

/** What the library tab shows as installed: read from the files themselves, never from memory alone. */
object InstalledParts {
    const val TOP = "いちばん上"

    /** Name and version of each library in [libsDir] (pip's *.dist-info/METADATA), by name. */
    fun pythonLibs(libsDir: File): List<Lib> =
        libsDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(".dist-info") }
            .mapNotNull { info -> File(info, "METADATA").takeIf { it.isFile }?.let { metadata(it.readText()) } }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    /** The dependencies of [project]'s package.json, each with the version npm put in node_modules ("?" if none). */
    fun nodeLibs(project: File): List<Lib> {
        val pkg = File(project, "package.json").takeIf { it.isFile }?.readText() ?: return emptyList()
        val block = DEPENDENCIES.find(pkg)?.groupValues?.get(1) ?: return emptyList()
        return KEY.findAll(block).map { it.groupValues[1] }.map { name ->
            val installed = File(project, "node_modules/$name/package.json").takeIf { it.isFile }?.readText()
            Lib(name, installed?.let { VERSION.find(it)?.groupValues?.get(1) } ?: "?")
        }.sortedBy { it.name.lowercase(Locale.ROOT) }.toList()
    }

    /** Every part of the bot in [codeDir] with its state, Python ones first. */
    fun parts(codeDir: File, installed: Map<String, Long>): List<Part> {
        val python = BotParts.requirementFiles(codeDir).map { req ->
            val key = req.relativeTo(codeDir).invariantSeparatorsPath
            val dir = req.parentFile!!
            part(key, label(codeDir, dir), false, installed[key], BotParts.venvPython(dir).exists(), listOf(req))
        }
        val node = BotParts.nodeProjects(codeDir).map { project ->
            val key = project.relativeTo(codeDir).invariantSeparatorsPath.ifEmpty { "." }
            val manifests = listOf(File(project, "package.json"), File(project, "package-lock.json")).filter { it.isFile }
            part(key, label(codeDir, project), true, installed[key], File(project, "node_modules").isDirectory, manifests)
        }
        return python + node
    }

    /** The library called [name] in [libs], however it is spelled (PEP 503: case, "-", "_" and "." alike). */
    fun find(libs: List<Lib>, name: String): Lib? = libs.firstOrNull { normalize(it.name) == normalize(name) }

    private fun part(key: String, label: String, isNode: Boolean, at: Long?, present: Boolean, manifests: List<File>): Part {
        val state = when {
            at == null || !present -> PartState.MISSING
            manifests.any { it.lastModified() > at } -> PartState.STALE
            else -> PartState.OK
        }
        return Part(key, label, isNode, state, at)
    }

    private fun label(codeDir: File, dir: File): String = dir.relativeTo(codeDir).invariantSeparatorsPath.ifEmpty { TOP }

    private fun metadata(text: String): Lib? {
        var name: String? = null
        var version: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) break
            if (line.startsWith("Name:")) name = line.removePrefix("Name:").trim()
            if (line.startsWith("Version:")) version = line.removePrefix("Version:").trim()
        }
        return if (name != null && version != null) Lib(name, version) else null
    }

    private fun normalize(name: String) = name.lowercase(Locale.ROOT).replace(Regex("[-_.]+"), "-")

    private val DEPENDENCIES = Regex("\"dependencies\"\\s*:\\s*\\{([^}]*)\\}")
    private val KEY = Regex("\"([^\"]+)\"\\s*:")
    private val VERSION = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
}
