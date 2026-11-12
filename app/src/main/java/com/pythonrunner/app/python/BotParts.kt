package com.pythonrunner.app.python

import com.pythonrunner.app.files.BotImporter
import java.io.File
import java.nio.file.Files

/**
 * The parts a bot made of several programs needs before it starts (SPIKE): node_modules for each
 * Node.js project in it (npm), and for each requirements.txt the libraries plus a ".venv/bin/python"
 * next to it, for start scripts that run their Python from a virtual env. That python is a link to
 * the app's own: it gets the libraries through PYTHONPATH like the bot itself.
 */
object BotParts {
    /** Folders with a package.json, outside installed parts and other skipped folders. */
    fun nodeProjects(codeDir: File): List<File> = find(codeDir, "package.json").map { it.parentFile!! }

    fun requirementFiles(codeDir: File): List<File> = find(codeDir, "requirements.txt")

    /** npm ci when there is a lockfile (exactly the tested versions), else npm install. */
    fun npmArgs(project: File): List<String> =
        listOf(if (File(project, "package-lock.json").isFile) "ci" else "install") +
            listOf("--ignore-scripts", "--no-audit", "--no-fund")

    fun venvPython(dir: File): File = File(dir, ".venv/bin/python")

    /** Points [dir]/.venv/bin/python at [python], replacing an earlier link. */
    fun linkVenvPython(dir: File, python: File) {
        val link = venvPython(dir)
        link.parentFile!!.mkdirs()
        Files.deleteIfExists(link.toPath())
        Files.createSymbolicLink(link.toPath(), python.toPath())
    }

    private fun find(codeDir: File, name: String): List<File> =
        codeDir.walkTopDown()
            .onEnter { it == codeDir || it.name !in BotImporter.SKIPPED_FOLDERS }
            .filter { it.isFile && it.name == name }
            .sortedBy { it.relativeTo(codeDir).invariantSeparatorsPath }
            .toList()
}
