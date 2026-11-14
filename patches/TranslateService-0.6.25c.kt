package vn.thucac.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import vn.thucac.engine.AiProvider
import vn.thucac.engine.AiTranslator
import vn.thucac.engine.BingTranslator
import vn.thucac.engine.ConvertOptions
import vn.thucac.engine.Converter
import vn.thucac.engine.GoogleTranslator
import vn.thucac.engine.NumberRules
import vn.thucac.engine.PhraseDict
import vn.thucac.engine.RuleSet
import vn.thucac.engine.urlHash
import vn.thucac.reader.App
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class DictKind(val label: String, val fileName: String) {
    NAMES("Tên riêng", "Names.txt"),
    VIETPHRASE("Cụm từ", "VietPhrase.txt"),
    PHIENAM("Phiên âm Hán Việt", "ChinesePhienAmWords.txt"),
    PRONOUNS("Đại từ", "Pronouns.txt"),
    LUATNHAN("Luật nhân", "LuatNhan.txt"),
    RULE("Quy tắc số, đơn vị", "Rule.txt");

    companion object {
        /** Guesses the dictionary from a file name such as "VietPhrase.txt" or "Names2.txt". */
        fun fromFileName(name: String): DictKind? {
            val n = name.lowercase()
            return when {
                "luatnhan" in n || "luat_nhan" in n || "luật nhân" in n -> LUATNHAN
                "rule" in n || "quytac" in n -> RULE
                "pronoun" in n || "daitu" in n || "đại từ" in n -> PRONOUNS
                "phienam" in n || "hanviet" in n || "phiên âm" in n -> PHIENAM
                "name" in n -> NAMES
                "vietphrase" in n || "vp" in n -> VIETPHRASE
                else -> null
            }
        }
    }
}

class TranslateService(private val app: App) {
    private val dictDir = File(app.filesDir, "dict").apply { mkdirs() }
    internal val bookNamesDir = File(app.filesDir, "booknames").apply { mkdirs() }

    @Volatile private var baseNames = PhraseDict()
    @Volatile private var baseVietPhrase = PhraseDict()
    @Volatile private var phienAm = PhraseDict()
    @Volatile private var pronouns = PhraseDict()
    @Volatile private var luatNhan = RuleSet()
    @Volatile private var numberRules = NumberRules()
    private val bookNames = ConcurrentHashMap<String, PhraseDict>()

    /** One-shot message about the last chapter() call, e.g. an AI fallback. */
    @Volatile var notice: String? = null
    fun takeNotice(): String? = notice.also { notice = null }

    private val google by lazy { GoogleTranslator(app.sources.fetcher) }
    private val bing by lazy { BingTranslator(app.sources.fetcher) }
    private val ai by lazy { AiTranslator(app.sources.fetcher) }

    var ready by mutableStateOf(false)
        private set
    var version by mutableIntStateOf(0)
        private set
    /** Entries loaded per dictionary, for the Settings list. */
    var counts by mutableStateOf<Map<DictKind, Int>>(emptyMap())
        private set

    fun reloadAsync() {
        thread(name = "dict-load", isDaemon = true) {
            reload()
            // First run: no dictionary yet, fetch the default set once.
            if (DictKind.entries.none { dictFile(it).exists() }) runCatching { importDictionaries() }
        }
    }

    @Synchronized
    fun reload() {
        val names = PhraseDict()
        val vp = PhraseDict()
        val pa = PhraseDict()
        val pr = PhraseDict()
        val ln = RuleSet()
        val nr = NumberRules()

        loadDictAssetOrFile(DictKind.NAMES, "dict/Names.txt.xz", names)
        loadDictAssetOrFile(DictKind.VIETPHRASE, "dict/VietPhrase.txt.xz", vp)
        loadDictAssetOrFile(DictKind.PHIENAM, "dict/ChinesePhienAmWords.txt.xz", pa)
        loadDictAssetOrFile(DictKind.PRONOUNS, "dict/Pronouns.txt.xz", pr)
        loadRulesAssetOrFile("dict/LuatNhan.txt.xz", ln)
        for (f in listOf(dictFile(DictKind.RULE), userFile(DictKind.RULE))) {
            if (f.exists()) runCatching { f.bufferedReader(Charsets.UTF_8).use { nr.load(it) } }
        }

        baseNames = names
        baseVietPhrase = vp
        phienAm = pa
        pronouns = pr
        luatNhan = ln
        numberRules = nr
        counts = mapOf(DictKind.RULE to nr.size,
            DictKind.NAMES to names.size, DictKind.VIETPHRASE to vp.size, DictKind.PHIENAM to pa.size,
            DictKind.PRONOUNS to pr.size, DictKind.LUATNHAN to ln.size,
        )
        bookNames.clear()
        ready = true
        version++
    }

    fun clearBookNameCache() {
        bookNames.clear()
        version++
    }

    fun dictFile(kind: DictKind): File = File(dictDir, "${kind.name.lowercase()}.txt")

    /** Entries the user added in the app; kept when imported dictionaries are deleted or replaced. */
    fun userFile(kind: DictKind): File = File(dictDir, "${kind.name.lowercase()}_user.txt")

    private fun loadDictAssetOrFile(kind: DictKind, assetPath: String, out: PhraseDict) {
        // "Hiện mọi nghĩa VietPhrase": keep "nghĩa 1/nghĩa 2" instead of cutting at the first one.
        val all = kind == DictKind.VIETPHRASE && !app.settings.firstMeaningOnly
        runCatching {
            app.assets.open(assetPath).use { raw ->
                val stream: InputStream = if (assetPath.endsWith(".xz")) XZInputStream(raw) else raw
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                    out.load(br, all)
                }
            }
        }
        for (f in listOf(dictFile(kind), userFile(kind))) {
            if (f.exists()) runCatching { f.bufferedReader(Charsets.UTF_8).use { br -> out.load(br, all) } }
        }
        out.trim()
    }

    private fun loadRulesAssetOrFile(assetPath: String, out: RuleSet) {
        val f = dictFile(DictKind.LUATNHAN)
        val u = userFile(DictKind.LUATNHAN)
        if (u.exists()) runCatching { u.bufferedReader(Charsets.UTF_8).use { br -> out.load(br) } }
        if (f.exists()) {
            runCatching {
                f.bufferedReader(Charsets.UTF_8).use { br -> out.load(br) }
                return
            }
        }
        runCatching {
            app.assets.open(assetPath).use { raw ->
                val stream: InputStream = if (assetPath.endsWith(".xz")) XZInputStream(raw) else raw
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br -> out.load(br) }
            }
        }
    }

    private fun bookNameFile(bookKey: String): File = File(bookNamesDir, "${urlHash(bookKey)}.txt")

    private fun bookDict(bookKey: String?): PhraseDict? {
        if (bookKey.isNullOrBlank()) return null
        return bookNames.getOrPut(bookKey) {
            val d = PhraseDict()
            val f = bookNameFile(bookKey)
            if (f.exists()) {
                runCatching { f.bufferedReader(Charsets.UTF_8).use { d.load(it) } }
            }
            d
        }
    }

    fun bookNameMap(bookKey: String): Map<String, String> = bookDict(bookKey)?.entries() ?: emptyMap()

    fun addBookName(bookKey: String, zh: String, vi: String) {
        val k = zh.trim()
        val v = vi.trim()
        if (k.isEmpty() || v.isEmpty()) return
        val f = bookNameFile(bookKey)
        val lines = if (f.exists()) f.readLines(Charsets.UTF_8).filter { !it.startsWith("$k=") } else emptyList()
        f.writeText((lines + "$k=$v").joinToString("\n") + "\n", Charsets.UTF_8)
        bookNames.remove(bookKey)
        version++
    }

    fun removeBookName(bookKey: String, zh: String) {
        val f = bookNameFile(bookKey)
        if (!f.exists()) return
        val lines = f.readLines(Charsets.UTF_8).filter { !it.startsWith("${zh.trim()}=") }
        f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n", Charsets.UTF_8)
        bookNames.remove(bookKey)
        version++
    }

    fun customEntryCount(kind: DictKind): Int {
        val f = userFile(kind)
        if (!f.exists()) return 0
        return f.readLines(Charsets.UTF_8).count { it.isNotBlank() && !it.startsWith("#") }
    }

    fun addCustomEntry(kind: DictKind, key: String, value: String) {
        val k = key.trim()
        val v = value.trim()
        if (k.isEmpty()) return
        val f = userFile(kind)
        val lines = if (f.exists()) f.readLines(Charsets.UTF_8).filter { !it.startsWith("$k=") } else emptyList()
        f.writeText((lines + "$k=$v").joinToString("\n") + "\n", Charsets.UTF_8)
        reload()
    }

    fun removeCustomEntry(kind: DictKind, key: String) {
        val f = userFile(kind)
        if (!f.exists()) return
        val lines = f.readLines(Charsets.UTF_8).filter { !it.startsWith("${key.trim()}=") }
        f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n", Charsets.UTF_8)
        reload()
    }

    fun importDictionaries(urls: Map<DictKind, String> = DEFAULT_DICT_URLS): Int {
        var count = 0
        for ((kind, url) in urls) {
            if (url.isBlank()) continue
            val req = Request.Builder().url(url).header("User-Agent", app.userAgent).build()
            app.http.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.bytes() ?: return@use
                    val text = if (url.endsWith(".xz")) {
                        XZInputStream(body.inputStream()).bufferedReader(Charsets.UTF_8).readText()
                    } else {
                        String(body, Charsets.UTF_8)
                    }
                    dictFile(kind).writeText(text, Charsets.UTF_8)
                    count++
                }
            }
        }
        reload()
        return count
    }

    /** Deletes the downloaded or imported dictionaries; the user's own entries and book names stay. */
    fun clearDictionaries() {
        DictKind.entries.forEach { dictFile(it).delete() }
        reload()
    }

    /** Replaces one dictionary with a file the user picked (.txt, or .txt.xz). */
    fun importFile(kind: DictKind, input: InputStream, name: String): Int {
        val bytes = input.use { it.readBytes() }
        var text = if (name.endsWith(".xz", true)) XZInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).readText()
        else if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) String(bytes, Charsets.UTF_16LE)
        else String(bytes, Charsets.UTF_8)
        text = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = text.lineSequence().count { it.contains('=') }
        require(lines > 0) { "File không có dòng dạng 中文=nghĩa" }
        dictFile(kind).writeText(text, Charsets.UTF_8)
        reload()
        return lines
    }

    /** Entries whose Chinese or Vietnamese contains [query]; user entries first. */
    fun search(kind: DictKind, query: String, limit: Int = 200): List<Pair<String, String>> {
        val q = query.trim()
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (f in listOf(userFile(kind), dictFile(kind))) {
            if (!f.exists()) continue
            f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (line in seq) {
                    if (out.size >= limit) break
                    val i = line.indexOf('=')
                    if (i <= 0 || line.startsWith("#")) continue
                    val k = line.substring(0, i).trim()
                    if (q.isNotEmpty() && !line.contains(q, ignoreCase = true)) continue
                    if (seen.add(k)) out.add(k to line.substring(i + 1).trim())
                }
            }
        }
        return out
    }

    private val tsMap: Map<Char, Char> by lazy {
        val m = HashMap<Char, Char>(4096)
        runCatching {
            app.assets.open("dict/TSCharacters.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.forEach { l -> if (l.length == 2 && !l.startsWith("#")) m[l[0]] = l[1] }
            }
        }
        m
    }

    /** Traditional to simplified, one character at a time (OpenCC table), when the option is on. */
    fun prep(text: String): String {
        if (!app.settings.autoSimplified || text.isEmpty()) return text
        val m = tsMap
        var changed = false
        val chars = CharArray(text.length) { i -> val c = text[i]; val r = m[c]; if (r != null) { changed = true; r } else c }
        return if (changed) String(chars) else text
    }

    /** Splits paragraphs after each sentence end (。！？…), keeping closing quotes with their sentence. */
    fun splitSentences(paras: List<String>): List<String> {
        val ends = "。！？!?…"
        val closers = "”」』’）)\"'"
        val out = ArrayList<String>(paras.size * 3)
        for (p in paras) {
            var start = 0
            var i = 0
            while (i < p.length) {
                if (p[i] in ends) {
                    var j = i + 1
                    while (j < p.length && (p[j] in ends || p[j] in closers)) j++
                    val piece = p.substring(start, j).trim()
                    if (piece.isNotEmpty() && p.substring(j).isNotBlank()) { out.add(piece); start = j }
                    i = j
                } else i++
            }
            val rest = p.substring(start).trim()
            if (rest.isNotEmpty() || start == 0) out.add(if (start == 0) p else rest)
        }
        return out
    }

    /** Changes when convert options or any dictionary change, so cached converted chapters are redone. */
    private fun convertSignature(bookKey: String?): String {
        val s = app.settings
        val dictStamp = (dictDir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L)
        val bookStamp = bookKey?.takeIf { it.isNotBlank() }?.let { bookNameFile(it).lastModified() } ?: 0L
        val opt = "${s.applyNumberRules}${s.namesFirst}${s.bookNamesFirst}${s.longestFirst}${s.luatNhanMode}${s.maxPhrase}${s.autoSimplified}"
        return Integer.toHexString("$opt|$dictStamp|$bookStamp".hashCode())
    }

    fun converter(): Converter {
        val s = app.settings
        return Converter(
            hanViet = phienAm,
            vietPhrase = baseVietPhrase,
            names = baseNames,
            pronouns = pronouns,
            rules = luatNhan,
            options = ConvertOptions(
                namesFirst = s.namesFirst,
                bookFirst = s.bookNamesFirst,
                longestFirst = s.longestFirst,
                luatNhan = s.luatNhanMode,
                maxPhrase = s.maxPhrase,
                numberRules = s.applyNumberRules,
            ),
            numberRules = numberRules,
        )
    }

    fun title(src: String, bookKey: String? = null): String {
        if (src.isBlank()) return src
        if (src.any { Converter.isCjk(it) }) return convertTitle(src, bookKey)
        @Suppress("UNUSED_VARIABLE") val v = titleVersion // recompose when a title comes back
        val lang = foreignTitleLang(src) ?: return src
        titleCache[src]?.let { v ->
            // Shown from Google because AI failed: after the wait, try AI again (the Google text stays meanwhile).
            val g = titleGoogle[src]
            if (g != null && aiConfigured() && System.currentTimeMillis() - g >= TITLE_RETRY_MS) queueTitle(src, lang)
            return v
        }
        queueTitle(src, lang)
        return src
    }

    // English/Korean titles are translated with Google in the background and kept on disk.
    var titleVersion by mutableIntStateOf(0)
        private set
    private val titleFile = File(app.filesDir, "title-cache.json")
    private val titleCache: ConcurrentHashMap<String, String> by lazy {
        val m = ConcurrentHashMap<String, String>()
        runCatching {
            val o = org.json.JSONObject(titleFile.readText())
            // Entries saved untranslated by older versions would stick forever: drop them so they are retried.
            for (k in o.keys()) { val v = o.getString(k); if (v.trim() != k.trim() && v.isNotBlank()) m[k] = v }
        }
        m
    }
    private val titleQueue = java.util.LinkedHashMap<String, String>()
    private val titleFailed = ConcurrentHashMap<String, Long>()
    private val titleTries = ConcurrentHashMap<String, Int>()
    /** Last error per title (AI or Google), for the log. */
    val titleErrors = ConcurrentHashMap<String, String>()
    private val TITLE_RETRY_MS = 3 * 60_000L
    private val titleGoogleFile = File(app.filesDir, "title-google.json")
    /** Titles whose cached text came from Google because AI failed: source -> time. */
    private val titleGoogle: ConcurrentHashMap<String, Long> by lazy {
        val m = ConcurrentHashMap<String, Long>()
        runCatching { val o = org.json.JSONObject(titleGoogleFile.readText()); for (k in o.keys()) m[k] = o.getLong(k) }
        m
    }
    private fun titleError(srcs: List<String>, where: String, e: Throwable?) {
        val msg = where + ": " + (e?.message ?: e?.javaClass?.simpleName ?: "không rõ")
        android.util.Log.w("title-tr", msg + " (" + srcs.size + " tên)")
        srcs.forEach { titleErrors[it] = msg }
    }
    @Volatile private var titleWorker = false
    private val VI_MARKS = Regex("[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]", RegexOption.IGNORE_CASE)

    private fun foreignTitleLang(src: String): String? {
        if (src.length > 300 || src.contains('\n') || VI_MARKS.containsMatchIn(src)) return null
        val lang = ImageOcr.detectLang(src) ?: return null
        return lang.takeIf { it == "en" || it == "ko" }
    }

    private fun queueTitle(src: String, lang: String) {
        if (titleFailed[src]?.let { System.currentTimeMillis() - it < TITLE_RETRY_MS } == true) return
        if ((titleTries[src] ?: 0) >= 4) return
        synchronized(titleQueue) {
            if (titleQueue.containsKey(src)) return
            titleQueue[src] = lang
            if (titleWorker) return
            titleWorker = true
        }
        thread(name = "title-tr", isDaemon = true) {
            Thread.sleep(700) // gather the whole visible page into one batch
            while (true) {
                val batch = synchronized(titleQueue) {
                    if (titleQueue.isEmpty()) { titleWorker = false; null }
                    else titleQueue.entries.take(25).map { it.key to it.value }
                } ?: break
                for ((lang, items) in batch.groupBy { it.second }) {
                    val srcs = items.map { it.first }
                    // AI batch first; titles it got wrong go to Google in one call, the rest keep the AI result.
                    val viaGoogle = HashSet<String>()
                    var res: List<String>? = null
                    if (aiConfigured()) {
                        try {
                            val provider = AiProvider.byId(app.settings.aiProvider)
                            val m = withAiModel(provider) { mdl ->
                                ai.titles(srcs, app.settings.aiKey(provider.id).trim(), mdl, provider,
                                    app.settings.aiBaseUrl(provider.id).ifBlank { provider.baseUrl }, lang)
                            }
                            res = vn.thucac.engine.AiTranslator.fillBad(srcs, { m[it] }) { bad ->
                                viaGoogle += bad
                                try { google.translateEach(bad, from = lang) } catch (e: Exception) { titleError(bad, "Google", e); throw e }
                            }
                        } catch (e: Exception) {
                            titleError(srcs, "AI", e)
                        }
                    }
                    if (res == null) {
                        // AI not set up or failed: show Google now; AI is tried again after the wait.
                        res = try { google.translateEach(srcs, from = lang) } catch (e: Exception) { titleError(srcs, "Google", e); null }
                        if (aiConfigured()) viaGoogle += srcs
                    }
                    val now = System.currentTimeMillis()
                    // Only real translations are cached; a title that came back unchanged is retried after the wait.
                    srcs.forEachIndexed { i, s ->
                        val v = res?.getOrNull(i)?.trim().orEmpty()
                        if (res != null && res.size == srcs.size && v.isNotEmpty() && v != s.trim()) {
                            titleCache[s] = v
                            if (s in viaGoogle) { titleGoogle[s] = now; titleTries.merge(s, 1, Int::plus) }
                            else { titleGoogle.remove(s); titleTries.remove(s); titleErrors.remove(s) }
                        } else { titleFailed[s] = now; titleTries.merge(s, 1, Int::plus) }
                    }
                }
                synchronized(titleQueue) { batch.forEach { titleQueue.remove(it.first) } }
                runCatching { titleFile.writeText(org.json.JSONObject(titleCache as Map<*, *>).toString()) }
                runCatching { titleGoogleFile.writeText(org.json.JSONObject(titleGoogle as Map<*, *>).toString()) }
                android.os.Handler(android.os.Looper.getMainLooper()).post { titleVersion++ }
            }
        }
    }

    /**
     * Runs an AI call with the model for [provider]. Gemini without a model picked by the user: take the newest
     * "flash" model from models.list (fallback gemini-2.5-flash). A model that answers 404 is swapped for another
     * flash model from the list, which is then saved.
     */
    fun <T> withAiModel(provider: AiProvider, block: (String) -> T): T {
        val s = app.settings
        val isGemini = provider.id == "gemini"
        if (isGemini && !s.sp.contains("geminiModel") && s.aiKey(provider.id).isNotBlank()) {
            val auto = runCatching { aiModels(provider.id).firstOrNull { it.contains("flash") } }.getOrNull() ?: "gemini-2.5-flash"
            s.setAiModel(provider.id, auto)
        }
        val model = s.aiModel(provider.id).ifBlank { provider.defaultModel }
        return try {
            block(model)
        } catch (e: vn.thucac.engine.AiHttpException) {
            if (e.status != 404) throw e
            val other = runCatching { aiModels(provider.id) }.getOrDefault(emptyList())
                .filter { it != model && (!isGemini || it.contains("flash")) }.firstOrNull()
                ?: (if (isGemini && model != "gemini-2.5-flash") "gemini-2.5-flash" else throw e)
            val r = block(other)
            s.setAiModel(provider.id, other)
            notice = "Model $model không tồn tại, đã chuyển sang $other"
            r
        }
    }

    fun aiModels(providerId: String): List<String> {
        val p = AiProvider.byId(providerId)
        return ai.listModels(p, app.settings.aiKey(p.id), app.settings.aiBaseUrl(p.id).ifBlank { p.baseUrl })
    }

    fun segments(text: String, bookKey: String? = null): List<Pair<String, String>> =
        converter().segments(prep(text), bookDict(bookKey))

    fun hanVietOf(text: String): String =
        converter().hanVietOf(prep(text))

    fun convertTitle(src: String, bookKey: String? = null): String {
        if (src.isBlank()) return src
        if (!src.any { Converter.isCjk(it) }) return src
        return converter().convert(prep(src), bookDict(bookKey))
    }

    /** Book name for display: Chinese through the dictionary, English/Korean translated in the background (setting can turn it off). */
    fun bookTitle(src: String, bookKey: String? = null): String {
        if (src.isBlank()) return src
        if (src.any { Converter.isCjk(it) }) return convertTitle(src, bookKey)
        if (!app.prefs.translateForeignTitles) return src
        return title(src, bookKey)
    }

    fun text(src: String, mode: TransMode = TransMode.CONVERT, bookKey: String? = null): String {
        if (src.isBlank() || mode == TransMode.ORIGINAL) return src
        return when (mode) {
            TransMode.ORIGINAL -> src
            TransMode.CONVERT -> if (src.any { Converter.isCjk(it) }) converter().convert(prep(src), bookDict(bookKey)) else title(src, bookKey)
            TransMode.GOOGLE -> google.translate(listOf(src)).firstOrNull() ?: src
            TransMode.GEMINI -> aiTranslate(listOf(src), bookKey).firstOrNull() ?: src
            TransMode.EXT -> {
                val extId = app.settings.activeExtension
                val ext = app.sources.extension(extId) ?: return converter().convert(prep(src), bookDict(bookKey))
                app.sources.currentBookKey = bookKey
                app.sources.currentBook = app.sources.bookContext(bookKey)
                ext.translate(listOf(src), "zh-CN", "vi", bookKey ?: "", null, null).firstOrNull() ?: src
            }
        }
    }

    fun chapter(
        input: List<String>,
        mode: TransMode,
        bookKey: String? = null,
        chapterUrl: String? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<String> {
        val paras = if (app.settings.splitSentences) splitSentences(input) else input
        if (paras.isEmpty() || mode == TransMode.ORIGINAL) return paras
        if (mode == TransMode.CONVERT) {
            val sig = "conv" + convertSignature(bookKey)
            chapterUrl?.let { url ->
                app.chapters.getAi(url, sig)?.let { if (it.size == paras.size) return it }
            }
            val conv = converter()
            val bd = bookDict(bookKey)
            val out = paras.mapIndexed { idx, p ->
                onProgress?.invoke(idx + 1, paras.size)
                conv.convert(prep(p), bd)
            }
            chapterUrl?.let { app.chapters.putAi(it, sig, out) }
            return out
        }
        if (mode == TransMode.GOOGLE) {
            chapterUrl?.let { url -> app.chapters.getTranslated(url)?.let { if (it.size == paras.size) return it } }
            val out = google.translate(paras)
            if (chapterUrl != null && out.size == paras.size && out != paras) app.chapters.putTranslated(chapterUrl, out)
            return out
        }
        if (mode == TransMode.EXT) {
            val ext = app.sources.extension(app.settings.activeExtension)
                ?: return chapter(input, TransMode.CONVERT, bookKey, chapterUrl, onProgress)
            app.sources.currentBookKey = bookKey
            app.sources.currentBook = app.sources.bookContext(bookKey)
            return ext.translate(paras, "zh-CN", "vi", bookKey ?: "", null, null)
        }
        if (mode == TransMode.GEMINI) {
            val model = "${app.settings.aiProvider}:${app.settings.aiModel(app.settings.aiProvider)}"
            chapterUrl?.let { url ->
                app.chapters.getAi(url, model)?.let { if (it.size == paras.size) return it }
            }
            val out = try {
                aiTranslate(paras, bookKey)
            } catch (e: Exception) {
                // AI failed (quota, key, network): fall back to Google for this chapter, don't cache it.
                val g = runCatching { google.translate(paras) }.getOrNull() ?: throw e
                notice = "AI lỗi, tạm dùng Google: " + (e.message ?: e.javaClass.simpleName)
                return g
            }
            chapterUrl?.let { app.chapters.putAi(it, model, out) }
            return out
        }
        return paras
    }

    fun aiConfigured(): Boolean = app.settings.aiKey(app.settings.aiProvider).isNotBlank()
    fun aiName(): String = AiProvider.byId(app.settings.aiProvider).name

    /** Google fill-in for the items an AI batch got wrong: one batched call. */
    private fun googleFill(source: String, onFill: ((Int) -> Unit)? = null): (List<String>) -> List<String> = { bad ->
        onFill?.invoke(bad.size)
        google.translate(bad, from = if (source == "zh") "zh-CN" else source, to = "vi")
    }

    private fun aiTranslate(paras: List<String>, bookKey: String?, source: String = "zh", onFill: ((Int) -> Unit)? = null): List<String> {
        val s = app.settings
        val provider = AiProvider.byId(s.aiProvider)
        val key = s.aiKey(provider.id).trim()
        val baseUrl = s.aiBaseUrl(provider.id).ifBlank { provider.baseUrl }
        val glossary = bookNameMap(bookKey ?: "")
        return withAiModel(provider) { model ->
            ai.translate(
                paragraphs = paras,
                key = key,
                model = model,
                glossary = glossary,
                extraPrompt = s.geminiPrompt,
                provider = provider,
                baseUrl = baseUrl,
                source = source,
                fill = googleFill(source, onFill),
            )
        }
    }

    /**
     * Translates the bubbles of one comic page. [engine]: "auto" (Chinese by dictionary,
     * English/Korean by AI when a key is set, otherwise Google), "convert", "google", "ai".
     * Reports each step through [onStep]; failures throw [ComicStepException] naming the step.
     */
    fun translateComic(
        texts: List<String>,
        engine: String,
        bookKey: String?,
        onStep: (String) -> Unit,
    ): ComicTranslation {
        val out = texts.toMutableList()
        val used = LinkedHashSet<String>()
        val warns = ArrayList<String>()
        val byLang = texts.indices.groupBy { ImageOcr.detectLang(texts[it]) ?: "zh" }
        for ((lang, idx) in byLang) {
            val src = idx.map { texts[it] }
            val langName = LANG_NAMES[lang] ?: lang
            val pick = when (engine) {
                "convert" -> if (lang == "zh") "dict" else "google"
                "google" -> "google"
                "ai" -> "ai"
                else -> if (lang == "zh") "dict" else if (aiConfigured()) "ai" else "google"
            }
            var res: List<String>? = null
            if (pick == "dict") {
                onStep("Dịch $langName bằng Từ điển")
                res = runCatching { val c = converter(); val bd = bookDict(bookKey); src.map { c.convert(prep(it), bd) } }
                    .getOrElse { throw ComicStepException("Dịch bằng Từ điển", it) }
                used += "Từ điển"
            }
            if (pick == "ai") {
                onStep("Dịch $langName bằng AI (${aiName()})")
                res = try {
                    var filled = 0
                    aiTranslate(src, bookKey, lang) { filled += it }.also {
                        used += "AI (${aiName()})"
                        if (filled > 0) used += "Google ($filled ô)"
                    }
                } catch (e: Exception) {
                    warns += "AI lỗi: " + (e.message ?: e.javaClass.simpleName) + " → dùng Google"
                    null
                }
            }
            if (res == null) {
                onStep("Dịch $langName bằng Google")
                res = try {
                    google.translate(src, from = if (lang == "zh") "zh-CN" else lang).also { used += "Google" }
                } catch (e: Exception) {
                    onStep("Google lỗi, thử dịch offline (ML Kit)")
                    try {
                        src.map { MlKitTranslator.translate(it, lang) }.also { used += "ML Kit"; warns += "Google lỗi: " + (e.message ?: e.javaClass.simpleName) }
                    } catch (e2: Exception) {
                        throw ComicStepException("Dịch bằng Google", e)
                    }
                }
            }
            idx.forEachIndexed { k, i -> out[i] = res.getOrNull(k) ?: texts[i] }
        }
        val marker = Regex("""^\s*[\[(（【]\s*\d+\s*[\])）】]\s*[:.]?\s*""")
        for (i in out.indices) out[i] = out[i].replace(marker, "").trim()
        return ComicTranslation(out, byLang.keys.mapNotNull { LANG_NAMES[it] }, used.toList(), warns)
    }

    /** Vietnamese search keyword to Chinese, for sources that only understand Chinese. */
    fun toChinese(text: String): String =
        runCatching { google.translate(listOf(text), from = "vi", to = "zh-CN").firstOrNull() }.getOrNull()?.trim()?.ifEmpty { null } ?: text

    fun translateBubbles(bubbles: List<OcrBlock>, mode: TransMode, bookKey: String? = null): List<String> {
        if (bubbles.isEmpty()) return emptyList()
        val texts = bubbles.map { it.text }
        if (mode == TransMode.ORIGINAL) return texts
        if (mode == TransMode.GEMINI) return aiTranslate(texts, bookKey)
        val conv = converter()
        val bd = bookDict(bookKey)
        return texts.map { t ->
            val lang = ImageOcr.detectLang(t) ?: "zh"
            when {
                lang == "zh" && mode == TransMode.CONVERT -> conv.convert(prep(t), bd)
                else -> runCatching { MlKitTranslator.translate(t, lang) }
                    .getOrElse { google.translate(listOf(t), from = lang).firstOrNull() ?: t }
            }
        }
    }

    companion object {
        val LANG_NAMES = mapOf("zh" to "tiếng Trung", "ko" to "tiếng Hàn", "en" to "tiếng Anh")
        val DEFAULT_DICT_URLS = mapOf(
            DictKind.NAMES to "https://raw.githubusercontent.com/luonggato1412/thucac-plugins/main/dict/Names.txt.xz",
            DictKind.VIETPHRASE to "https://raw.githubusercontent.com/luonggato1412/thucac-plugins/main/dict/VietPhrase.txt.xz",
            DictKind.PHIENAM to "https://raw.githubusercontent.com/luonggato1412/thucac-plugins/main/dict/ChinesePhienAmWords.txt.xz",
            DictKind.PRONOUNS to "https://raw.githubusercontent.com/luonggato1412/thucac-plugins/main/dict/Pronouns.txt.xz",
            DictKind.LUATNHAN to "https://raw.githubusercontent.com/luonggato1412/thucac-plugins/main/dict/LuatNhan.txt.xz",
            DictKind.RULE to "https://raw.githubusercontent.com/luonggato1412/thucac-plugins/main/dict/Rule.txt.xz",
        )
    }
}

data class ComicTranslation(val texts: List<String>, val langs: List<String>, val used: List<String>, val warnings: List<String>)

class ComicStepException(val step: String, cause: Throwable) :
    Exception(step + ": " + (cause.message ?: cause.javaClass.simpleName), cause)

object MlKitTranslator {
    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    fun translate(text: String, fromLang: String): String {
        val src = when (fromLang) {
            "ko" -> TranslateLanguage.KOREAN
            "en" -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.CHINESE
        }
        val client = clients.getOrPut(src) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()
            )
        }
        Tasks.await(client.downloadModelIfNeeded(DownloadConditions.Builder().build()), 25, TimeUnit.SECONDS)
        return Tasks.await(client.translate(text), 20, TimeUnit.SECONDS)
    }
}
