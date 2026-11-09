package com.pythonrunner.app.bot

import com.pythonrunner.app.files.BotImporter
import java.io.File

/**
 * Moves the trial version's single bot into the bot list, once, as "MyBot" (the name its shared
 * folder Documents/PythonRunner/MyBot already has). Every step can be repeated, so a kill halfway
 * just means the next run finishes the job; nothing already moved is overwritten.
 */
object SpikeMigration {
    const val ID = "mybot"
    const val NAME = "MyBot"

    private val OLD_KEYS = listOf("spike.mainFile", "spike.envVars", "spike.wasRunning")

    /** True when it moved or changed anything. */
    fun run(filesDir: File, registry: BotRegistry, oldPrefs: KeyValue, newPrefs: KeyValue): Boolean {
        val oldCode = File(filesDir, "bots/spike")
        val oldLibs = File(filesDir, "bots/spike-libs")
        val oldManifest = File(filesDir, "bots/spike-shared.manifest")
        // An import the trial version did not live to finish is undone where it happened, first.
        BotImporter.recover(oldCode, File(filesDir, "import-backup"), File(filesDir, "import-staging"))

        val oldKeys = OLD_KEYS.filter { oldPrefs.get(it) != null }
        if (!oldCode.exists() && !oldLibs.exists() && !oldManifest.exists() && oldKeys.isEmpty()) return false

        var changed = false
        if (registry.get(ID) == null) {
            registry.add(NAME, ID)
            changed = true
        }
        val store = BotStore(ID, File(filesDir, "bots"), newPrefs)
        store.dir.mkdirs()
        for ((from, to) in listOf(oldCode to store.codeDir, oldLibs to store.libsDir, oldManifest to store.manifestFile)) {
            if (from.exists() && !to.exists() && from.renameTo(to)) changed = true
        }
        for ((old, new) in OLD_KEYS.zip(BotStore.keys(ID))) {
            val value = oldPrefs.get(old) ?: continue
            if (newPrefs.get(new) == null) newPrefs.put(new, value)
            oldPrefs.put(old, null)
            changed = true
        }
        return changed
    }
}
