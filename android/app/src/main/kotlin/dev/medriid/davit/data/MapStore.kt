package dev.medriid.davit.data

import android.content.Context
import dev.medriid.davit.map.CampusMap
import dev.medriid.davit.map.CampusMapParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The campus map's data: public static files (OpenStreetMap buildings,
 * paths and places, PRP room ranges, mess menus, a walking graph). Fetched when the map
 * is first opened, about 600 KB, then kept on the phone and refreshed weekly. Nothing
 * about you is sent: they're plain GETs of static files.
 */
class MapStore(private val context: Context, private val scope: CoroutineScope) {
    data class State(val map: CampusMap? = null, val loading: Boolean = false, val error: String? = null, val at: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val dir get() = File(context.filesDir, "campus-map").apply { mkdirs() }
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    /** Loads the cached copy (or fetches the first one); refreshes in the background once it's a week old. */
    fun ensure() {
        val s = _state.value
        if (s.loading) return
        if (s.map != null && System.currentTimeMillis() - s.at < WEEK) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch(Dispatchers.IO) {
            val cached = runCatching { read() }.getOrNull()
            if (cached != null) _state.update { it.copy(map = cached.first, at = cached.second) }
            val stale = cached == null || System.currentTimeMillis() - cached.second > WEEK
            if (stale) fetch(quiet = cached != null) else _state.update { it.copy(loading = false) }
        }
    }

    /** Pull to refresh: fetch the files again now. */
    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch(Dispatchers.IO) { fetch(quiet = false) }
    }

    private fun read(): Pair<CampusMap, Long>? {
        val files = Files.map { File(dir, "$it.json") }
        if (!files.all { it.exists() && it.length() > 0 }) return null
        val map = CampusMapParser.parse(files[0].readText(), files[1].readText(), files[2].readText())
        return map to files.minOf { it.lastModified() }
    }

    private fun fetch(quiet: Boolean) {
        try {
            val texts = Files.map { name ->
                http.newCall(Request.Builder().url("$BASE/$name.json").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("The map server said ${r.code}.")
                    r.body?.string().orEmpty()
                }
            }
            // Parse first: a broken download never replaces a good copy.
            val map = CampusMapParser.parse(texts[0], texts[1], texts[2])
            Files.zip(texts).forEach { (name, text) ->
                val tmp = File(dir, "$name.part")
                tmp.writeText(text)
                tmp.renameTo(File(dir, "$name.json").also { it.delete() })
            }
            _state.value = State(map = map, at = System.currentTimeMillis())
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = if (quiet && it.map != null) null else "Couldn't fetch the campus map (${e.javaClass.simpleName}). Check your connection.",
                )
            }
        }
    }

    fun wipe() {
        dir.deleteRecursively()
        _state.value = State()
    }

    companion object {
        private const val BASE = "https://vit-map.vercel.app/data"
        private val Files = listOf("campus", "geo", "graph")
        private const val WEEK = 7 * 24 * 3_600_000L
    }
}
