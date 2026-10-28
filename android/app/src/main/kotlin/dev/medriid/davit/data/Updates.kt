package dev.medriid.davit.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Check for updates": one request to GitHub's releases API, made only when you tap the
 * button. Nothing about you is sent; it's the same public JSON anyone can fetch.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/medriid/davit/releases/latest"

    data class Release(val version: String, val url: String)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    /** The latest published release; throws on network or API errors. Blocking. */
    fun latest(): Release {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "GitHub answered ${res.code}" }
            val o = JSONObject(res.body?.string().orEmpty())
            return Release(o.getString("tag_name").removePrefix("v"), o.getString("html_url"))
        }
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
