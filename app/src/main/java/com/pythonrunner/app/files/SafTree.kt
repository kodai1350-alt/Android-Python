package com.pythonrunner.app.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream

/** One file of a picked folder: its path inside the folder, its size, and how to read it. */
class SafFile(val path: String, val size: Long, val open: () -> InputStream)

/** Reading what the user picked through Android's file and folder pickers. */
object SafTree {
    /** Every file under a folder picked with OpenDocumentTree, at any depth. */
    fun list(context: Context, treeUri: Uri): List<SafFile> {
        val resolver = context.contentResolver
        val files = mutableListOf<SafFile>()
        fun walk(documentId: String, prefix: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Never descend into .git or venv: thousands of queries for files never imported.
                        if (name !in BotImporter.SKIPPED_FOLDERS) walk(id, path)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        files += SafFile(path, size) { resolver.openInputStream(uri) ?: error("cannot open $path") }
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri), "")
        return files
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** The picked file's size, or 0 when the provider does not say. */
    fun size(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
}
