package vn.thucac.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import vn.thucac.engine.Converter
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val bg: Int,
    val lines: Int,
    val light: Float,
    /** Boxes of the original text lines (left, top, right, bottom), used to cover only the old text. */
    val parts: List<IntArray> = emptyList(),
)

data class OcrResult(
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlock>,
) {
    fun toJson(): String {
        val root = JSONObject().put("w", width).put("h", height)
        val arr = JSONArray()
        for (b in blocks) {
            arr.put(
                JSONObject()
                    .put("t", b.text)
                    .put("l", b.left)
                    .put("u", b.top)
                    .put("r", b.right)
                    .put("d", b.bottom)
                    .put("c", b.bg)
                    .put("n", b.lines)
                    .put("g", b.light.toDouble())
                    .put("p", JSONArray().apply { b.parts.forEach { r -> put(JSONArray().put(r[0]).put(r[1]).put(r[2]).put(r[3])) } })
            )
        }
        return root.put("b", arr).toString()
    }

    companion object {
        fun fromJson(s: String): OcrResult? = runCatching {
            val o = JSONObject(s)
            val w = o.getInt("w")
            val h = o.getInt("h")
            val arr = o.getJSONArray("b")
            val list = ArrayList<OcrBlock>(arr.length())
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                list.add(
                    OcrBlock(
                        text = b.getString("t"),
                        left = b.getInt("l"),
                        top = b.getInt("u"),
                        right = b.getInt("r"),
                        bottom = b.getInt("d"),
                        bg = b.optInt("c", Color.WHITE),
                        lines = b.optInt("n", 1),
                        light = b.optDouble("g", 1.0).toFloat(),
                        parts = b.optJSONArray("p")?.let { pa ->
                            (0 until pa.length()).mapNotNull { k -> pa.optJSONArray(k)?.let { r -> intArrayOf(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)) } }
                        } ?: emptyList(),
                    )
                )
            }
            OcrResult(w, h, list)
        }.getOrNull()
    }
}

class ImageOcr(private val ctx: Context) {
    private val dir = File(ctx.cacheDir, "ocr").apply { mkdirs() }
    private val mem = ConcurrentHashMap<String, OcrResult>()

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    fun clearCache() {
        mem.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun ocr(
        url: String,
        http: OkHttpClient,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): OcrResult? = runCatching { ocrSteps(url, http, headers, referer, null) {} }.getOrNull()

    /** True when the page was already read before (memory or disk cache). */
    fun cached(url: String, nextUrl: String? = null): Boolean = sha1(if (nextUrl != null) "$url|$nextUrl" else url).let { mem.containsKey(it) || File(dir, "o10-$it.json").exists() }

    /**
     * OCR one image, reporting each step. [langHint] ("zh"/"ko"/"en") limits OCR to the matching
     * recognizer; null runs all of them. Failures throw [ComicStepException] naming the step.
     */
    fun ocrSteps(
        url: String,
        http: OkHttpClient,
        headers: Map<String, String>,
        referer: String?,
        langHint: String?,
        nextUrl: String? = null,
        nextReferer: String? = null,
        onStep: (String) -> Unit,
    ): OcrResult {
        // The result depends on the next image too (its top is read together with this one).
        val key = sha1(if (nextUrl != null) "$url|$nextUrl" else url)
        mem[key]?.let { return it }
        val file = File(dir, "o10-$key.json")
        if (file.exists()) {
            OcrResult.fromJson(file.readText())?.let { mem[key] = it; return it }
        }
        onStep("Tải ảnh")
        val bytes = try {
            recent[url] ?: fetchBytesOrThrow(url, http, headers, referer).also { remember(url, it) }
        } catch (e: Exception) {
            throw ComicStepException("Tải ảnh", e)
        }
        // Chinese comics cut long strips into short images, often through a speech bubble.
        // Read the top of the next image together with this one so a cut bubble is read whole.
        val nextBytes = nextUrl?.let { n -> recent[n] ?: runCatching { fetchBytesOrThrow(n, http, headers, nextReferer) }.getOrNull()?.also { remember(n, it) } }
        val res = try {
            runOcrOnBytes(bytes, nextBytes, langHint, onStep) ?: throw IllegalStateException("không giải mã được ảnh (${bytes.size / 1024} KB)")
        } catch (e: ComicStepException) {
            throw e
        } catch (e: Throwable) {
            throw ComicStepException("Nhận dạng chữ", e)
        }
        mem[key] = res
        runCatching { file.writeText(res.toJson()) }
        return res
    }

    /** The last few downloaded images: page i downloads page i+1, which is then read next. */
    private val recent = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 3
    })

    private fun remember(url: String, b: ByteArray) { recent[url] = b }

    private fun fetchBytesOrThrow(
        url: String,
        http: OkHttpClient,
        headers: Map<String, String>,
        referer: String?,
    ): ByteArray {
        val req = Request.Builder().url(url).apply {
            for ((k, v) in headers) header(k, v)
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) header("User-Agent", vn.thucac.reader.App.instance.userAgent)
            if (referer != null && headers.keys.none { it.equals("Referer", ignoreCase = true) }) header("Referer", referer)
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val b = resp.body?.bytes() ?: throw IllegalStateException("không có dữ liệu")
            if (b.isEmpty()) throw IllegalStateException("ảnh rỗng")
            return b
        }
    }

    private fun fetchBytes(
        url: String,
        http: OkHttpClient,
        headers: Map<String, String>,
        referer: String?,
    ): ByteArray? = runCatching {
        val req = Request.Builder().url(url).apply {
            for ((k, v) in headers) header(k, v)
            if (referer != null && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                header("Referer", referer)
            }
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }.getOrNull()

    private fun runOcrOnBytes(bytes: ByteArray, nextBytes: ByteArray?, langHint: String?, onStep: (String) -> Unit): OcrResult? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val origW = bounds.outWidth
        val origH = bounds.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sample = 1
        while (origW / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val page = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val bmp = withNextStrip(page, nextBytes)
        try {
            val scaleX = origW.toFloat() / page.width
            val scaleY = origH.toFloat() / page.height
            // A hint only narrows the recognizers. "en" keeps the Chinese one too (watermarks are often Latin
            // on Chinese comics), and an empty page is read again with all of them.
            val hinted = when (langHint) {
                "zh" -> listOf(chinese)
                "ko" -> listOf(korean)
                "en" -> listOf(latin, chinese)
                else -> null
            }
            val all = listOf(latin, korean, chinese)
            fun read(recognizers: List<com.google.mlkit.vision.text.TextRecognizer>): List<OcrBlock> {
                val raw = mutableListOf<OcrBlock>()

                val tileH = 1600
                val overlap = 240
                val tiles = if (bmp.height <= tileH) 1 else 1 + (bmp.height - overlap - 1) / (tileH - overlap)
                var errors = 0
                var lastErr: Throwable? = null
                var y0 = 0
                var t = 0
                while (y0 < bmp.height) {
                    t++
                    onStep(if (tiles > 1) "Nhận dạng chữ (phần $t/$tiles)" else "Nhận dạng chữ")
                    val y1 = min(bmp.height, y0 + tileH)
                    val tile = Bitmap.createBitmap(bmp, 0, y0, bmp.width, y1 - y0)
                    try {
                        val img = InputImage.fromBitmap(tile, 0)
                        for (r in recognizers) {
                            val res = try {
                                Tasks.await(r.process(img), 20, TimeUnit.SECONDS)
                            } catch (e: Exception) {
                                errors++; lastErr = e; null
                            }
                            collectLines(res, bmp, y0, scaleX, scaleY, raw)
                        }
                    } finally {
                        tile.recycle()
                    }
                    if (y1 >= bmp.height) break
                    y0 = y1 - overlap
                }

                if (raw.isEmpty() && errors > 0) {
                    val m = (lastErr?.cause ?: lastErr)?.let { it.message ?: it.javaClass.simpleName } ?: ""
                    throw ComicStepException("Nhận dạng chữ", IllegalStateException(if (lastErr is java.util.concurrent.TimeoutException) "quá 20 giây (máy chậm hoặc chưa có mô hình ML Kit)" else m))
                }
                val deduped = dedupLines(raw)
                return mergeBlocks(OcrResult(origW, origH, deduped))
            }
            var merged = read(hinted ?: all)
            if (merged.isEmpty() && hinted != null) merged = read(all)
            // Bubbles that start in the next image belong to it; ones starting here may run past the bottom.
            merged = merged.filter { it.top < origH - 4 }
            return OcrResult(origW, origH, merged)
        } finally {
            if (bmp !== page) bmp.recycle()
            page.recycle()
        }
    }

    /**
     * Manual mode: OCR only the part of the image the reader circled. The region is given as fractions
     * (0..1) of the image; the found lines become one bubble in the image's own pixel coordinates.
     */
    fun ocrRegion(
        url: String,
        http: OkHttpClient,
        referer: String?,
        fx0: Float, fy0: Float, fx1: Float, fy1: Float,
    ): OcrResult {
        val bytes = try {
            fetchBytesOrThrow(url, http, emptyMap(), referer)
        } catch (e: Exception) {
            throw ComicStepException("Tải ảnh", e)
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw IllegalStateException("không giải mã được ảnh")
            val rect = android.graphics.Rect(
                (fx0 * w).toInt().coerceIn(0, w - 1), (fy0 * h).toInt().coerceIn(0, h - 1),
                (fx1 * w).toInt().coerceIn(1, w), (fy1 * h).toInt().coerceIn(1, h),
            )
            if (rect.width() < 4 || rect.height() < 4) return OcrResult(w, h, emptyList())
            var sample = 1
            while (rect.width() / sample > 1600 || rect.height() / sample > 3200) sample *= 2
            @Suppress("DEPRECATION")
            val dec = android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                ?: throw IllegalStateException("không giải mã được ảnh")
            val part = try {
                dec.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } finally {
                dec.recycle()
            }
            // Small regions are enlarged: ML Kit misses characters only a few pixels high.
            val up = if (part.width < 700) min(3f, 700f / part.width) else 1f
            val bmp = if (up > 1.05f) Bitmap.createScaledBitmap(part, (part.width * up).toInt(), (part.height * up).toInt(), true).also { part.recycle() } else part
            try {
                val sx = rect.width().toFloat() / bmp.width
                val sy = rect.height().toFloat() / bmp.height
                val raw = mutableListOf<OcrBlock>()
                val img = InputImage.fromBitmap(bmp, 0)
                for (r in listOf(chinese, korean, latin)) {
                    val res = runCatching { Tasks.await(r.process(img), 20, TimeUnit.SECONDS) }.getOrNull()
                    collectLines(res, bmp, 0, sx, sy, raw)
                }
                val lines = dedupLines(raw).map {
                    it.copy(left = it.left + rect.left, top = it.top + rect.top, right = it.right + rect.left, bottom = it.bottom + rect.top)
                }
                val merged = mergeBlocks(OcrResult(w, h, lines))
                if (merged.isEmpty()) return OcrResult(w, h, emptyList())
                val sorted = merged.sortedWith(compareBy({ it.top }, { it.left }))
                val one = OcrBlock(
                    text = sorted.joinToString(" ") { it.text },
                    left = sorted.minOf { it.left }, top = sorted.minOf { it.top },
                    right = sorted.maxOf { it.right }, bottom = sorted.maxOf { it.bottom },
                    bg = sorted.first().bg, lines = sorted.sumOf { it.lines },
                    light = sorted.map { it.light }.average().toFloat(),
                    parts = sorted.flatMap { it.parts },
                )
                return OcrResult(w, h, listOf(one))
            } finally {
                bmp.recycle()
            }
        } catch (e: ComicStepException) {
            throw e
        } catch (e: Throwable) {
            throw ComicStepException("Nhận dạng chữ", e)
        }
    }

    /** [page] with the top of the next image (scaled to the same width) glued under it, or [page] itself. */
    private fun withNextStrip(page: Bitmap, nextBytes: ByteArray?): Bitmap {
        if (nextBytes == null) return page
        return runCatching {
            val nb = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(nextBytes, 0, nextBytes.size, nb)
            if (nb.outWidth <= 0 || nb.outHeight <= 0) return page
            var sample = 1
            while (nb.outWidth / (sample * 2) >= page.width) sample *= 2
            val next = BitmapFactory.decodeByteArray(nextBytes, 0, nextBytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return page
            try {
                val k = page.width.toFloat() / next.width
                val stripH = min((next.height * k).toInt(), max(400, (page.width * 0.6f).toInt()))
                if (stripH < 8) return page
                val out = Bitmap.createBitmap(page.width, page.height + stripH, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.drawBitmap(page, 0f, 0f, null)
                c.drawBitmap(
                    next, android.graphics.Rect(0, 0, next.width, (stripH / k).toInt().coerceIn(1, next.height)),
                    android.graphics.Rect(0, page.height, page.width, page.height + stripH), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                )
                out
            } finally {
                next.recycle()
            }
        }.getOrDefault(page)
    }

    private fun collectLines(
        text: Text?,
        bmp: Bitmap,
        yOffset: Int,
        scaleX: Float,
        scaleY: Float,
        out: MutableList<OcrBlock>,
    ) {
        if (text == null) return
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val t = line.text.trim()
                if (t.isEmpty() || detectLang(t) == null) continue
                val l = (box.left * scaleX).toInt().coerceAtLeast(0)
                val u = ((box.top + yOffset) * scaleY).toInt().coerceAtLeast(0)
                val r = (box.right * scaleX).toInt().coerceAtLeast(l + 1)
                val d = ((box.bottom + yOffset) * scaleY).toInt().coerceAtLeast(u + 1)
                val (bg, light) = sampleBg(bmp, box.left, box.top + yOffset, box.right, box.bottom + yOffset)
                out.add(OcrBlock(t, l, u, r, d, bg, 1, light))
            }
        }
    }

    private fun sampleBg(bmp: Bitmap, l: Int, u: Int, r: Int, d: Int): Pair<Int, Float> {
        // Sample a ring just outside the text box and take the most common colour (not the average,
        // which turns white + a nearby pink sound effect into a pink box). Near-white snaps to white.
        val m = max(3, (d - u) / 5)
        val x0 = (l - m).coerceIn(0, bmp.width - 1)
        val x1 = (r + m).coerceIn(0, bmp.width - 1)
        val y0 = (u - m).coerceIn(0, bmp.height - 1)
        val y1 = (d + m).coerceIn(0, bmp.height - 1)
        val pts = ArrayList<Int>(48)
        val n = 12
        for (k in 0..n) {
            val x = x0 + (x1 - x0) * k / n
            val y = y0 + (y1 - y0) * k / n
            pts += bmp.getPixel(x, y0); pts += bmp.getPixel(x, y1)
            pts += bmp.getPixel(x0, y); pts += bmp.getPixel(x1, y)
        }
        val buckets = HashMap<Int, MutableList<Int>>()
        for (c in pts) buckets.getOrPut(((Color.red(c) shr 5) shl 6) or ((Color.green(c) shr 5) shl 3) or (Color.blue(c) shr 5)) { mutableListOf() }.add(c)
        val best = buckets.values.maxByOrNull { it.size } ?: pts
        val rr = best.sumOf { Color.red(it) } / best.size
        val gg = best.sumOf { Color.green(it) } / best.size
        val bb = best.sumOf { Color.blue(it) } / best.size
        val lum = (0.299f * rr + 0.587f * gg + 0.114f * bb) / 255f
        val sat = (maxOf(rr, gg, bb) - minOf(rr, gg, bb)) / 255f
        val bg = when {
            lum > 0.82f && sat < 0.18f -> Color.WHITE
            lum < 0.12f -> Color.BLACK
            else -> Color.rgb(rr, gg, bb)
        }
        return bg to lum
    }

    private fun dedupLines(list: List<OcrBlock>): List<OcrBlock> {
        val out = mutableListOf<OcrBlock>()
        for (b in list) {
            val dup = out.indexOfFirst { o -> iou(b, o) > 0.45f }
            if (dup < 0) {
                out.add(b)
            } else {
                val prev = out[dup]
                if (b.text.length > prev.text.length) out[dup] = b
            }
        }
        return out
    }

    private fun iou(a: OcrBlock, b: OcrBlock): Float {
        val ix0 = max(a.left, b.left)
        val iy0 = max(a.top, b.top)
        val ix1 = min(a.right, b.right)
        val iy1 = min(a.bottom, b.bottom)
        if (ix1 <= ix0 || iy1 <= iy0) return 0f
        val inter = (ix1 - ix0).toFloat() * (iy1 - iy0)
        val areaA = (a.right - a.left).toFloat() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toFloat() * (b.bottom - b.top)
        val minArea = min(areaA, areaB).coerceAtLeast(1f)
        return inter / minArea
    }

    companion object {
        private const val PUNCT = ".,?!…~-–—'\"‘’“”:;()♡♥❤*･・"
        private val KANA_OR_JP = Regex("[\u3040-\u30FF\u31F0-\u31FF\uFF66-\uFF9F]")

        fun mergeBlocks(res: OcrResult): List<OcrBlock> {
            val list = res.blocks
            if (list.isEmpty()) return emptyList()
            val parent = IntArray(list.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) {
                    parent[r] = parent[parent[r]]
                    r = parent[r]
                }
                return r
            }
            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[rb] = ra
            }

            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val ha = max(1, a.bottom - a.top)
                    val hb = max(1, b.bottom - b.top)
                    val lineH = max(ha, hb)
                    val vGap = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
                    val hOverlap = min(a.right, b.right) - max(a.left, b.left)
                    val minW = min(max(1, a.right - a.left), max(1, b.right - b.left))
                    // Only join lines of the same bubble: close below each other, similar height, overlapping horizontally.
                    val similarH = max(ha, hb) <= min(ha, hb) * 1.8f
                    val closeVert = similarH && vGap <= lineH * 0.9f && (hOverlap >= minW * 0.3f || abs((a.left + a.right) - (b.left + b.right)) <= lineH)
                    val sameRow = similarH && abs((a.top + a.bottom) - (b.top + b.bottom)) <= lineH * 0.5f && max(0, max(a.left, b.left) - min(a.right, b.right)) <= lineH * 0.8f
                    if (closeVert || sameRow) {
                        union(i, j)
                    }
                }
            }

            val groups = linkedMapOf<Int, MutableList<OcrBlock>>()
            for (i in list.indices) {
                groups.getOrPut(find(i)) { mutableListOf() }.add(list[i])
            }

            val out = mutableListOf<OcrBlock>()
            for (g in groups.values) {
                val sorted = g.sortedWith(compareBy({ it.top }, { it.left }))
                val joined = buildString {
                    for ((idx, b) in sorted.withIndex()) {
                        val t = b.text.trim()
                        if (t.isEmpty()) continue
                        if (idx > 0) {
                            if (endsWith("-")) deleteCharAt(length - 1)
                            else append(' ')
                        }
                        append(t)
                    }
                }.replace(Regex("\\s+"), " ").trim()
                val lang = detectLang(joined) ?: continue
                val clean = cleanOcrText(joined, lang)
                if (detectLang(clean) == null) continue
                // Korean sound effects (두근, 주춤...): short, one line, drawn into the art. Leave them alone.
                if (lang == "ko" && sorted.size == 1 && clean.count { isHangul(it) } in 1..3 && clean.count { it.isLetter() } <= 3) continue
                out.add(
                    OcrBlock(
                        text = clean,
                        left = sorted.minOf { it.left },
                        top = sorted.minOf { it.top },
                        right = sorted.maxOf { it.right },
                        bottom = sorted.maxOf { it.bottom },
                        bg = sorted.first().bg,
                        lines = sorted.size,
                        light = sorted.map { it.light }.average().toFloat(),
                        parts = sorted.map { intArrayOf(it.left, it.top, it.right, it.bottom) },
                    )
                )
            }
            return out.sortedWith(compareBy({ it.top }, { it.left }))
        }

        fun cleanOcrText(text: String, lang: String): String {
            var s = text.replace(Regex("\\s+"), " ").trim()
            s = s.replace(Regex("…+|\\.{2,}|。{2,}"), "...")
            s = s.replace(Regex("\\s+([?!.,])"), "$1")
            s = s.replace(Regex("([?!]){3,}"), "$1$1")
            if (lang == "en") {
                val letters = s.filter { it.isLetter() }
                if (letters.length >= 2 && letters.count { it.isUpperCase() } * 10 >= letters.length * 7) {
                    val sb = StringBuilder(s.lowercase(Locale.ROOT))
                    var cap = true
                    for (i in sb.indices) {
                        val c = sb[i]
                        if (c.isLetter()) {
                            if (cap) sb[i] = c.uppercaseChar()
                            cap = false
                        } else if (c in ".?!") {
                            cap = true
                        }
                    }
                    s = sb.toString().replace(Regex("\\bi\\b"), "I")
                }
            } else if (lang == "ko") {
                s = s.filter { isHangul(it) || it.isLetterOrDigit() || it.isWhitespace() || it in ".,?!~'\"-()" }
            }
            return s.replace(Regex("\\s+"), " ").trim()
        }

        fun isHangul(c: Char): Boolean =
            c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'

        fun detectLang(t: String): String? {
            val cjk = t.count { Converter.isCjk(it) }
            val kana = t.count { it in '\u3040'..'\u30FF' || it in '\u31F0'..'\u31FF' || it in '\uFF66'..'\uFF9F' }
            if (kana > 0 && kana * 3 >= cjk) return null
            val hangul = t.count { isHangul(it) }
            if (hangul > 0 && hangul >= cjk) return "ko"
            if (cjk > 0) return "zh"
            if (KANA_OR_JP.containsMatchIn(t)) return null
            val latin = t.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (latin < 2) return null
            if (!Regex("[A-Za-z]{2,}").containsMatchIn(t)) return null
            // Punctuation does not count against the text: "OH...?" / "ME...?" are real lines.
            val core = t.count { !it.isWhitespace() && it !in PUNCT }
            return if (latin * 2 >= core) "en" else null
        }

        fun referrerPolicy(policy: String, chapterUrl: String): String? {
            return when (policy.lowercase(Locale.ROOT)) {
                "no-referrer" -> null
                "origin" -> Regex("^(https?://[^/]+)").find(chapterUrl)?.value?.plus("/")
                else -> chapterUrl.ifEmpty { null }
            }
        }

        private fun sha1(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
