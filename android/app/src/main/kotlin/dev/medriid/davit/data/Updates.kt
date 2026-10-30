package dev.medriid.davit.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * daVIT's own updates, from GitHub's releases API: the same public JSON anyone can fetch,
 * so nothing about you is sent. The APK is checked against the SHA-256 GitHub lists for
 * it, and Android itself refuses one that isn't signed with the same key as this app.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/medriid/davit/releases/latest"

    /** [apk] is the download link and [sha256] its hash, when the release has an APK. */
    data class Release(val version: String, val url: String, val apk: String?, val sha256: String?)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** The latest published release; throws on network or API errors. Blocking. */
    fun latest(): Release {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "GitHub answered ${res.code}" }
            return parse(res.body?.string().orEmpty())
        }
    }

    fun parse(json: String): Release {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
        return Release(
            version = o.getString("tag_name").removePrefix("v"),
            url = o.getString("html_url"),
            apk = apk?.optString("browser_download_url")?.ifBlank { null },
            sha256 = apk?.optString("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        )
    }

    /**
     * Downloads [release]'s APK to [into], through a temporary file so a half-finished
     * download never looks ready. Throws if it fails or the hash doesn't match. Blocking.
     */
    fun download(release: Release, into: File): File {
        val url = checkNotNull(release.apk) { "This release has no APK" }
        into.parentFile?.mkdirs()
        val part = File(into.path + ".part")
        val sha = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            check(res.isSuccessful) { "GitHub answered ${res.code}" }
            val body = checkNotNull(res.body)
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sha.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !got.equals(release.sha256, ignoreCase = true)) {
            part.delete()
            error("The download didn't match GitHub's checksum")
        }
        check(part.renameTo(into) || (into.delete() && part.renameTo(into))) { "Couldn't save the download" }
        return into
    }

    /** "0.10.0" is newer than "0.9.2"; anything after a '-' is ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
