package dev.optimizer.android.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Storage Access Framework folder chosen by the user (ACTION_OPEN_DOCUMENT_TREE) for backup copies. Works on Android 5+ without any storage
 * permission and survives scoped storage; the grant is persisted. Backups are copied to <folder>/AndroidOptimizer/<backup id>/.
 */
object SafFolder {
    private const val KEY = "saf_tree"
    fun uri(): Uri? = Store.getStr(KEY).takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
    fun label(): String = uri()?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "not set"

    fun set(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        Store.putStr(KEY, uri.toString())
    }
    fun clear(ctx: Context) {
        uri()?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        Store.putStr(KEY, "")
    }
    /** Is the persisted grant still valid? */
    fun usable(ctx: Context): Boolean { val u = uri() ?: return false; return ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }

    private fun treeDoc(u: Uri) = DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u))

    private fun child(ctx: Context, parent: Uri, name: String): Uri? {
        val tree = uri() ?: return null
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }
    private fun ensureDir(ctx: Context, parent: Uri, name: String): Uri? =
        child(ctx, parent, name) ?: DocumentsContract.createDocument(ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)

    /** Copies every file of the backup directory. Returns an error text or null. */
    fun copyBackup(ctx: Context, dir: File, id: String): String? {
        val tree = uri() ?: return "No folder chosen"
        if (!usable(ctx)) return "Folder permission was lost; choose the folder again"
        return runCatching {
            val root = treeDoc(tree)
            val app = ensureDir(ctx, root, "AndroidOptimizer") ?: error("cannot create AndroidOptimizer")
            val d = ensureDir(ctx, app, id) ?: error("cannot create $id")
            for (f in dir.listFiles().orEmpty().filter { it.isFile }) {
                child(ctx, d, f.name)?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) }
                val mime = if (f.name.endsWith(".json")) "application/json" else if (f.name.endsWith(".txt")) "text/plain" else "application/octet-stream"
                val t = DocumentsContract.createDocument(ctx.contentResolver, d, mime, f.name) ?: error("cannot create ${f.name}")
                ctx.contentResolver.openOutputStream(t, "wt")!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    /** Copies backups that are in the folder but not in the app (e.g. after reinstall). Returns the number imported. */
    fun importMissing(ctx: Context): Pair<Int, String?> {
        val tree = uri() ?: return 0 to "No folder chosen"
        if (!usable(ctx)) return 0 to "Folder permission was lost; choose the folder again"
        return runCatching {
            val app = child(ctx, treeDoc(tree), "AndroidOptimizer") ?: return 0 to "No AndroidOptimizer folder inside the chosen folder"
            val have = Backups.list().map { it.id }.toSet(); var n = 0
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(app))
            val ids = ArrayList<Pair<String, String>>()
            ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) ids += c.getString(0) to c.getString(1)
            }
            for ((docId, name) in ids) {
                if (name in have || name.contains('/') || name.contains("..")) continue
                val dst = File(Store.backupsDir(), name).apply { mkdirs() }
                val du = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val fk = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                var got = false
                ctx.contentResolver.query(fk, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val fn = c.getString(1); if (fn.contains('/') || fn.contains("..")) continue
                        val src = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        ctx.contentResolver.openInputStream(src)?.use { i -> File(dst, fn).outputStream().use { o -> i.copyTo(o) } }; got = true
                    }
                }
                if (got && File(dst, "manifest.json").exists()) n++ else dst.deleteRecursively()
                @Suppress("UNUSED_VARIABLE") val unused = du
            }
            n to null
        }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
    }
}
