package vn.thucac.reader.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import vn.thucac.reader.App
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Small reader / shelf options added in batch 2. Kept apart from Settings to avoid JVM name clashes. */
class ExtraPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("thucac_settings", Context.MODE_PRIVATE)

    private inner class Pref(private val key: String, def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(sp.getBoolean(key, def))
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            sp.edit().putBoolean(key, value).apply()
        }
    }

    /** Move to the next chapter when the user scrolls past the end. */
    var autoLoadNext by Pref("autoLoadNext", false)
    /** "Tắt xuống dòng": join lines that the site broke in the middle of a sentence. */
    var joinBrokenLines by Pref("joinBrokenLines", false)
    /** "Trình bày kiểu sách": indent + justify paragraphs, big chapter title, small header and footer. */
    var bookLayout by Pref("bookLayout", true)
    /** Translate a Vietnamese search keyword to Chinese before searching. */
    var searchToChinese by Pref("searchToChinese", false)
    /** Translate English / Korean book names. */
    var translateForeignTitles by Pref("translateForeignTitles", true)
}

private val sentenceEnd = Regex("[.!?…。！？”」』\"'）)》:：~～*]$")

/** Joins a line to the next one when it does not end a sentence. */
fun joinBrokenLines(paras: List<String>): List<String> {
    val out = ArrayList<String>()
    val buf = StringBuilder()
    for (p in paras) {
        val t = p.trim()
        if (t.isEmpty()) continue
        if (buf.isNotEmpty()) {
            val cjk = buf.last().code > 0x2e80 || t.first().code > 0x2e80
            if (!cjk) buf.append(' ')
        }
        buf.append(t)
        if (sentenceEnd.containsMatchIn(t)) { out.add(buf.toString()); buf.setLength(0) }
    }
    if (buf.isNotEmpty()) out.add(buf.toString())
    return out
}

data class Bookmark(
    val id: Long,
    val pluginId: String,
    val bookUrl: String,
    val bookName: String,
    val chapterIndex: Int,
    val chapterName: String,
    val para: Int,
    val excerpt: String,
    val note: String,
    val time: Long,
) {
    val bookKey: String get() = "$pluginId|$bookUrl"
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("pluginId", pluginId); put("bookUrl", bookUrl); put("bookName", bookName)
        put("chapterIndex", chapterIndex); put("chapterName", chapterName); put("para", para)
        put("excerpt", excerpt); put("note", note); put("time", time)
    }

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            o.optLong("id"), o.optString("pluginId"), o.optString("bookUrl"), o.optString("bookName"),
            o.optInt("chapterIndex"), o.optString("chapterName"), o.optInt("para"),
            o.optString("excerpt"), o.optString("note"), o.optLong("time"),
        )
    }
}

/** Bookmarks and notes, stored in files/bookmarks.json. */
class Bookmarks(app: App) {
    private val file = File(app.filesDir, "bookmarks.json")
    val all = mutableStateListOf<Bookmark>()

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) all.add(Bookmark.fromJson(arr.getJSONObject(i)))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        runCatching { file.writeText(arr.toString()) }
    }

    fun of(bookKey: String): List<Bookmark> =
        all.filter { it.bookKey == bookKey }.sortedWith(compareBy({ it.chapterIndex }, { it.para }))

    fun add(b: Bookmark) { all.add(0, b); save() }

    fun updateNote(id: Long, note: String) {
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0) { all[i] = all[i].copy(note = note); save() }
    }

    fun remove(id: Long) { all.removeAll { it.id == id }; save() }

    fun exportJson(): JSONArray = JSONArray().apply { all.forEach { put(it.toJson()) } }

    fun importJson(arr: JSONArray) {
        val have = all.map { it.id }.toHashSet()
        for (i in 0 until arr.length()) {
            val b = runCatching { Bookmark.fromJson(arr.getJSONObject(i)) }.getOrNull() ?: continue
            if (b.id !in have) all.add(b)
        }
        save()
    }
}

/** Translates every downloaded chapter of a book ahead of time, so reading later needs no network. */
class BatchTranslator(private val app: App) {
    data class Progress(val done: Int, val total: Int, val failed: Int, val running: Boolean, val mode: TransMode)

    val progress = mutableStateMapOf<String, Progress>()
    private val cancelled = HashSet<String>()

    fun start(pluginId: String, bookUrl: String, mode: TransMode) {
        val key = "$pluginId|$bookUrl"
        if (progress[key]?.running == true || mode == TransMode.ORIGINAL) return
        synchronized(cancelled) { cancelled.remove(key) }
        progress[key] = Progress(0, 0, 0, true, mode)
        Thread {
            val toc = app.sources.cachedToc(pluginId, bookUrl)
                ?: runCatching { app.sources.loadToc(pluginId, bookUrl, false) }.getOrNull()
                ?: emptyList()
            val cached = toc.filter { app.chapters.hasChapter(it.url) }
            var done = 0
            var failed = 0
            for (c in cached) {
                if (synchronized(cancelled) { key in cancelled }) break
                val raw = app.chapters.get(c.url)
                if (raw == null || runCatching { app.translator.chapter(raw, mode, key, c.url) }.isFailure) failed++
                done++
                progress[key] = Progress(done, cached.size, failed, true, mode)
            }
            progress[key] = Progress(done, cached.size, failed, false, mode)
        }.apply { isDaemon = true }.start()
    }

    fun cancel(pluginId: String, bookUrl: String) {
        synchronized(cancelled) { cancelled.add("$pluginId|$bookUrl") }
    }
}
