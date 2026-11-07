package com.pythonrunner.app.files

import java.io.File

/** Files earlier test builds left in app-private storage that this version no longer uses. */
object LeftoverFiles {
    /** Deletes the AI model folder (about 1.25GB) of the set-aside AI. True if there was one. */
    fun removeAiModels(filesDir: File): Boolean {
        val models = File(filesDir, "llm-models")
        return models.exists() && models.deleteRecursively()
    }
}
