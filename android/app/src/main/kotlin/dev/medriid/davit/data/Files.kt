package dev.medriid.davit.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.medriid.davit.vtop.Download
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Files fetched from VTOP (course materials, question papers, your submissions, letters),
 * kept in the app's private storage so they open again offline. Each lives in its own
 * folder named by a key ("mat:2026039827"), under VTOP's own file name.
 */
class FileStore(private val context: Context) {
    private val root = File(context.filesDir, "vtop-files").apply { mkdirs() }

    /** Bytes so far and the total when VTOP says it; null while it hasn't. */
    data class Progress(val done: Long, val total: Long?) {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
    }

    private val _saved = MutableStateFlow(scan())
    /** Key → the downloaded file. */
    val saved: StateFlow<Map<String, File>> = _saved

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    fun file(key: String): File? = _saved.value[key]?.takeIf { it.exists() }

    private fun dir(key: String) = File(root, key.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    private fun scan(): Map<String, File> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
            val key = File(d, KEY).takeIf { it.exists() }?.readText() ?: return@mapNotNull null
            val file = d.listFiles().orEmpty().firstOrNull { it.isFile && !it.name.startsWith(".") } ?: return@mapNotNull null
            key to file
        }.toMap()

    /**
     * Downloads into [key]'s folder. [fetch] writes into the temp file it's given (it may
     * be called again after a re-login, so it opens the file itself) and reports progress.
     */
    suspend fun fetch(key: String, fetch: suspend (File, (Long, Long?) -> Unit) -> Download): File {
        if (key in _progress.value) throw IllegalStateException("Already downloading.")
        _progress.update { it + (key to Progress(0, null)) }
        val tmp = File(root, ".part-" + dir(key).name)
        try {
            var lastShown = 0L
            val d = fetch(tmp) { done, total ->
                // A screen redraw per 64 KB is plenty.
                if (done - lastShown >= 64 * 1024 || done == total) {
                    lastShown = done
                    _progress.update { it + (key to Progress(done, total)) }
                }
            }
            val folder = dir(key)
            folder.deleteRecursively()
            folder.mkdirs()
            File(folder, KEY).writeText(key)
            File(folder, TYPE).writeText(d.mimeType)
            val target = File(folder, cleanName(d.fileName, d.mimeType))
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            _saved.update { it + (key to target) }
            return target
        } finally {
            tmp.delete()
            _progress.update { it - key }
        }
    }

    fun delete(key: String) {
        dir(key).deleteRecursively()
        _saved.update { it - key }
    }

    /** Everything downloaded, in bytes. */
    fun size(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear(prefix: String = "") {
        _saved.value.keys.filter { it.startsWith(prefix) }.forEach(::delete)
    }

    /** Hands the file to whatever opens its type (a PDF viewer, Office…). False if nothing can. */
    fun open(file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val type = File(file.parentFile, TYPE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Shares the file with another app (Drive, WhatsApp, a printer). */
    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Sign-out: nothing of the account stays behind. */
    fun wipe() {
        root.deleteRecursively()
        root.mkdirs()
        _saved.value = emptyMap()
    }

    companion object {
        private const val KEY = ".key"
        private const val TYPE = ".type"

        /** VTOP's name, made safe for a file system; with an extension from the type when it has none. */
        internal fun cleanName(name: String, mime: String): String {
            val base = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimStart('.').take(120).ifEmpty { "file" }
            if (base.contains('.')) return base
            val ext = when (mime) {
                "application/pdf" -> "pdf"
                else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            }
            return if (ext != null) "$base.$ext" else base
        }
    }
}

/** "1.2 MB", "340 KB". */
fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ENGLISH, bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
