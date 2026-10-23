package com.pythonrunner.app.files

import java.io.File

/** Moves a bot folder to its new home by copying; never overwrites a folder that already has files. */
object FolderMigration {
    /** Returns true when files were copied. The source is left in place. */
    fun copyIfTargetEmpty(from: File, to: File): Boolean {
        val sources = from.listFiles()?.filter { it.name != "__pycache__" }.orEmpty()
        if (sources.isEmpty()) return false
        if (to.listFiles()?.isNotEmpty() == true) return false
        to.mkdirs()
        for (source in sources) {
            source.copyRecursively(File(to, source.name), overwrite = false) { _, _ -> OnErrorAction.SKIP }
        }
        File(to, "__pycache__").deleteRecursively()
        to.walkTopDown().filter { it.isDirectory && it.name == "__pycache__" }.toList().forEach { it.deleteRecursively() }
        return true
    }
}
