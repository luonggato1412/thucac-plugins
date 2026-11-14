package vn.thucac.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.thucac.engine.ChapterRef
import vn.thucac.engine.ComicPage
import vn.thucac.reader.App
import vn.thucac.reader.data.BookEntry
import vn.thucac.reader.data.OcrBlock
import vn.thucac.reader.data.OcrResult
import vn.thucac.reader.data.TransMode

/** Translated speech bubbles of one page, in the page's own pixel coordinates. */
private data class PageText(val res: OcrResult, val texts: List<String>)

/** The small status line shown on each page while translating. */
private data class PageStatus(val text: String, val busy: Boolean = false, val error: Boolean = false)

/** Chapters kept ready before and after the one being read. */
private const val KEEP = 3

private val ocrModes = listOf("auto" to "Tự động", "manual" to "Thủ công")

private val comicEngines = listOf("auto" to "Tự động", "convert" to "Từ điển", "google" to "Google", "ai" to "AI")

@Volatile private var comicLoader: ImageLoader? = null

private fun comicImageLoader(ctx: android.content.Context): ImageLoader =
    comicLoader ?: synchronized(ComicPage::class) {
        comicLoader ?: ImageLoader.Builder(ctx.applicationContext)
            .okHttpClient(App.instance.http)
            .crossfade(true)
            .build().also { comicLoader = it }
    }

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ComicReaderScreen(nav: NavHostController, pid: String, bookUrl: String, startIndex: Int) {
    val app = App.instance
    val tr = app.translator
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val loader = remember { comicImageLoader(ctx) }
    val prefs = remember { ctx.getSharedPreferences("comic_reader", android.content.Context.MODE_PRIVATE) }
    val bookKey = app.library.keyOf(pid, bookUrl)

    val initial = remember { app.library.get(pid, bookUrl) }
    var index by remember { mutableIntStateOf(if (startIndex >= 0) startIndex else initial?.chapterIndex ?: 0) }
    var restoreScroll by remember { mutableStateOf(if (startIndex < 0 || startIndex == initial?.chapterIndex) initial?.scrollIndex ?: 0 else 0) }
    var toc by remember { mutableStateOf<Load<List<ChapterRef>>>(Load.Loading) }
    var pages by remember { mutableStateOf<Load<List<ComicPage>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    var translateOn by remember { mutableStateOf(prefs.getBoolean("on_$bookKey", false)) }
    var engine by remember {
        mutableStateOf(prefs.getString("engine_$bookKey", null) ?: "auto")
    }
    // "auto": every page is read by itself. "manual": the reader circles the parts to translate.
    var ocrMode by remember { mutableStateOf(prefs.getString("ocrmode_$bookKey", null) ?: "auto") }
    var selecting by remember { mutableStateOf(false) }
    // Preloading nearby chapters can trip a site's rate limit (Kuaikan: HTTP 429). It can be turned off,
    // runs slowly, and pauses for a while after the site says "too many requests".
    var prefetch by remember { mutableStateOf(prefs.getBoolean("prefetch", true)) }
    // Regions translated by hand (key: chapter url|page index), kept while the reader is open.
    val manualCache = remember { java.util.concurrent.ConcurrentHashMap<String, PageText>() }
    val texts = remember { mutableStateMapOf<Int, PageText>() }
    val status = remember { mutableStateMapOf<Int, PageStatus>() }
    val pageCache = remember { java.util.concurrent.ConcurrentHashMap<String, List<ComicPage>>() }
    // Translated pages of the chapters around the one being read (key: chapter url|engine|version).
    val transCache = remember { java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Int, Pair<PageText, PageStatus>>>() }
    var ocrProgress by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val noRipple = remember { MutableInteractionSource() }

    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) app.stats.addSeconds(30)
        }
    }
    LaunchedEffect(index) { app.stats.addChapter() }
    LaunchedEffect(Unit) {
        app.library.clearNewBadge(pid, bookUrl)
        toc = withContext(Dispatchers.IO) { runCatching { app.sources.loadToc(pid, bookUrl, false) }.fold({ Load.Ok(it) }, { Load.Err(errorText(it)) }) }
    }
    val chapters = (toc as? Load.Ok)?.value ?: emptyList()
    val chapter = chapters.getOrNull(index)

    LaunchedEffect(chapter?.url, reload) {
        val c = chapter ?: return@LaunchedEffect
        pages = Load.Loading
        texts.clear()
        status.clear()
        val cachedPages = if (reload == 0) pageCache[c.url] else null
        pages = cachedPages?.let { Load.Ok(it) } ?: withContext(Dispatchers.IO) {
            runCatching { app.sources.comicPages(pid, c.url) }.fold({ pageCache[c.url] = it; Load.Ok(it) }, { Load.Err(errorText(it)) })
        }
        // Keep the chapters around this one ready (KEEP before and after): page lists and images.
        // Chapters further away are dropped from memory.
        val window = (index - KEEP..index + KEEP).mapNotNull { chapters.getOrNull(it)?.url }.toSet()
        pageCache.keys.retainAll(window)
        transCache.keys.removeAll { k -> k.substringBeforeLast('|').substringBeforeLast('|') !in window }
        val host = hostOf(c.url)
        if (prefetch) launch(Dispatchers.IO) {
            val slow = isSlow(host)
            if (slow) kotlinx.coroutines.delay(2000)
            val near = (1..KEEP).flatMap { listOf(index + it, index - it) }
            for (k in near) {
                if (!prefetchAllowed(host)) break
                val n = chapters.getOrNull(k) ?: continue
                val np = pageCache[n.url] ?: run {
                    if (slow) kotlinx.coroutines.delay(1500)
                    runCatching { app.sources.comicPages(pid, n.url) }.onFailure { noteLimit(host, it) }.getOrNull()?.also { pageCache[n.url] = it }
                } ?: continue
                for (pg in np) {
                    if (!prefetchAllowed(host)) break
                    val req = ImageRequest.Builder(ctx).data(pg.link).apply {
                        refererFor(pg, n.url)?.let { addHeader("Referer", it) }
                        addHeader("User-Agent", app.userAgent)
                    }.build()
                    if (!slow) { loader.enqueue(req); continue }
                    // Rate-limited sites: one image at a time, with a short pause, instead of whole chapters at once.
                    val r = loader.execute(req)
                    if (r is coil.request.ErrorResult) noteLimit(host, r.throwable)
                    kotlinx.coroutines.delay(250)
                }
            }
        }
        val p = (pages as? Load.Ok)?.value
        if (p != null && restoreScroll > 0) { listState.scrollToItem(restoreScroll.coerceAtMost(p.size)); restoreScroll = 0 }
    }

    // OCR + translate the pages one at a time, starting from the one on screen.
    LaunchedEffect(pages, translateOn, engine, tr.version, ocrMode) {
        val list = (pages as? Load.Ok)?.value ?: return@LaunchedEffect
        val c = chapter ?: return@LaunchedEffect
        texts.clear()
        status.clear()
        if (!translateOn) { ocrProgress = null; selecting = false; return@LaunchedEffect }
        if (ocrMode == "manual") {
            ocrProgress = null
            for (i in list.indices) manualCache["${c.url}|$i"]?.let { pt ->
                texts[i] = pt; status[i] = PageStatus("Ảnh ${i + 1} · ${pt.res.blocks.size} vùng đã dịch")
            }
            return@LaunchedEffect
        }
        selecting = false
        val start = (listState.firstVisibleItemIndex - 1).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val order = (start until list.size) + (0 until start)
        // Language of this chapter, voted by amount of text over the pages read so far. It only narrows
        // OCR after two pages agree, so a Latin watermark on the first page no longer hides Chinese text.
        var langHint: String? = null
        val votes = HashMap<String, Int>()
        var voted = 0
        fun vote(res: OcrResult) {
            var any = false
            for (b in res.blocks) vn.thucac.reader.data.ImageOcr.detectLang(b.text)?.let { votes[it] = (votes[it] ?: 0) + b.text.length; any = true }
            if (any) voted++
            langHint = if (voted >= 2) votes.maxByOrNull { it.value }?.key else null
        }
        var done = 0
        var failed = 0
        val eng = engine
        val ver = tr.version
        val cache = transCache.getOrPut("${c.url}|$eng|$ver") { java.util.concurrent.ConcurrentHashMap() }
        for (i in order) {
            val hit = cache[i]
            if (hit != null) {
                texts[i] = hit.first; status[i] = hit.second; done++
                vote(hit.first.res)
                continue
            }
            ocrProgress = "Đang dịch ảnh ${done + 1}/${list.size}" + if (failed > 0) " · $failed lỗi" else ""
            val page = list[i]
            val label = "Ảnh ${i + 1}"
            val step: (String) -> Unit = { t -> status[i] = PageStatus("$label · $t…", busy = true) }
            step("Chờ")
            val hint = langHint
            val outcome: Result<Pair<PageText, PageStatus>> = withContext(Dispatchers.IO) {
                runCatching {
                    val res = app.ocr.ocrSteps(page.link, app.http, emptyMap(), refererFor(page, c.url), hint, step)
                    if (res.blocks.isEmpty()) {
                        PageText(res, emptyList()) to PageStatus("$label · Không thấy chữ")
                    } else {
                        val tx = tr.translateComic(res.blocks.map { it.text }, eng, bookKey, step)
                        val info = buildString {
                            append(label).append(" · ").append(tx.langs.joinToString("+").ifEmpty { "?" })
                            append(" · ").append(res.blocks.size).append(" bong bóng · ").append(tx.used.joinToString("+"))
                            if (tx.warnings.isNotEmpty()) append(" · ⚠ ").append(tx.warnings.joinToString("; "))
                        }
                        PageText(res, tx.texts) to PageStatus(info, error = tx.warnings.isNotEmpty())
                    }
                }
            }
            outcome.fold({ (pt, st) ->
                texts[i] = pt
                status[i] = st
                cache[i] = pt to st
                vote(pt.res)
            }, { e ->
                failed++
                val where = (e as? vn.thucac.reader.data.ComicStepException)?.message ?: ("không rõ: " + (e.message ?: e.javaClass.simpleName))
                status[i] = PageStatus("$label · LỖI ở bước $where", error = true)
            })
            done++
        }
        ocrProgress = if (failed > 0) "Xong · $failed/${list.size} ảnh lỗi (chạm dòng đỏ để sao chép)" else null
        if (failed > 0) { kotlinx.coroutines.delay(5000); ocrProgress = null }
        // Translate the next KEEP chapters ahead, so they open already translated.
        val hint = langHint
        val host = hostOf(c.url)
        val slow = isSlow(host)
        if (prefetch) withContext(Dispatchers.IO) {
            for (k in 1..KEEP) {
                if (!prefetchAllowed(host)) break
                val next = chapters.getOrNull(index + k) ?: break
                val np = pageCache[next.url] ?: runCatching { app.sources.comicPages(pid, next.url) }.onFailure { noteLimit(host, it) }.getOrNull()?.also { pageCache[next.url] = it } ?: continue
                val nc = transCache.getOrPut("${next.url}|$eng|$ver") { java.util.concurrent.ConcurrentHashMap() }
                for ((j, pg) in np.withIndex()) {
                    if (nc.containsKey(j)) continue
                    if (!prefetchAllowed(host)) break
                    if (slow) kotlinx.coroutines.delay(400)
                    val label = "Ảnh ${j + 1}"
                    runCatching {
                        val res = app.ocr.ocrSteps(pg.link, app.http, emptyMap(), refererFor(pg, next.url), hint) {}
                        nc[j] = if (res.blocks.isEmpty()) PageText(res, emptyList()) to PageStatus("$label · Không thấy chữ")
                        else {
                            val tx = tr.translateComic(res.blocks.map { it.text }, eng, bookKey) {}
                            val info = "$label · " + tx.langs.joinToString("+").ifEmpty { "?" } + " · ${res.blocks.size} bong bóng · " + tx.used.joinToString("+") +
                                (if (tx.warnings.isNotEmpty()) " · ⚠ " + tx.warnings.joinToString("; ") else "")
                            PageText(res, tx.texts) to PageStatus(info, error = tx.warnings.isNotEmpty())
                        }
                    }.onFailure { noteLimit(host, it) }
                }
            }
        }
    }

    LaunchedEffect(chapters.size) {
        if (chapters.isEmpty()) return@LaunchedEffect
        snapshotFlow { index to listState.firstVisibleItemIndex }
            .debounce(600)
            .collect { (i, item) ->
                val c = chapters.getOrNull(i) ?: return@collect
                val e = app.library.get(pid, bookUrl) ?: BookEntry(pid, bookUrl, "", added = System.currentTimeMillis())
                app.library.upsert(e.copy(chapterIndex = i, chapterName = c.name, chapterCount = chapters.size, scrollIndex = item, scrollOffset = 0, lastRead = System.currentTimeMillis(), inHistory = true))
            }
    }

    fun go(i: Int) {
        if (i < 0 || i >= chapters.size) return
        index = i
        scope.launch { listState.scrollToItem(0) }
    }

    fun setTranslate(on: Boolean, e: String = engine) {
        translateOn = on
        engine = e
        prefs.edit().putBoolean("on_$bookKey", on).putString("engine_$bookKey", e).apply()
    }

    fun setOcrMode(m: String) {
        ocrMode = m
        selecting = m == "manual"
        prefs.edit().putString("ocrmode_$bookKey", m).apply()
    }

    // Manual mode: OCR + translate the circled part of page [i] and add it to that page's bubbles.
    fun translateRegion(i: Int, page: ComicPage, fx0: Float, fy0: Float, fx1: Float, fy1: Float) {
        val c = chapter ?: return
        val key = "${c.url}|$i"
        val label = "Ảnh ${i + 1}"
        val eng = engine
        status[i] = PageStatus("$label · Nhận dạng vùng…", busy = true)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val reg = app.ocr.ocrRegion(page.link, app.http, refererFor(page, c.url), fx0, fy0, fx1, fy1)
                    if (reg.blocks.isEmpty()) null
                    else reg to tr.translateComic(reg.blocks.map { it.text }, eng, bookKey) { t -> status[i] = PageStatus("$label · $t…", busy = true) }.texts
                }
            }
            if (chapter?.url != c.url) return@launch
            r.fold({ got ->
                val prev = manualCache[key]
                if (got == null) {
                    status[i] = PageStatus("$label · Không thấy chữ trong vùng vừa khoanh", error = true)
                } else {
                    val (reg, tx) = got
                    val pt = PageText(
                        OcrResult(reg.width, reg.height, (prev?.res?.blocks ?: emptyList()) + reg.blocks),
                        (prev?.texts ?: emptyList()) + tx,
                    )
                    manualCache[key] = pt
                    texts[i] = pt
                    status[i] = PageStatus("$label · ${pt.res.blocks.size} vùng đã dịch")
                }
            }, { e ->
                val where = (e as? vn.thucac.reader.data.ComicStepException)?.message ?: ("không rõ: " + (e.message ?: e.javaClass.simpleName))
                status[i] = PageStatus("$label · LỖI ở bước $where", error = true)
            })
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            toc is Load.Err -> ErrorBox((toc as Load.Err).message, onOpenWeb = { nav.navigate(Routes.web(bookUrl)) })
            toc is Load.Loading -> Loading()
            chapters.isEmpty() -> EmptyBox("Truyện chưa có chương nào.")
            pages is Load.Err -> ErrorBox((pages as Load.Err).message, onRetry = { reload++ }, onOpenWeb = { chapter?.let { nav.navigate(Routes.web(it.url)) } })
            pages is Load.Loading -> Loading()
            else -> {
                val list = (pages as Load.Ok).value
                if (list.isEmpty()) {
                    ErrorBox("Chương này không có ảnh.", onRetry = { reload++ }, onOpenWeb = { chapter?.let { nav.navigate(Routes.web(it.url)) } })
                } else LazyColumn(
                    Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { menu = !menu },
                    state = listState,
                ) {
                    item { Spacer(Modifier.statusBarsPadding()) }
                    itemsIndexed(list, key = { i, p -> "$i ${p.link}" }) { i, page ->
                        ComicPageView(
                            page, chapter?.url ?: bookUrl, loader, if (translateOn) texts[i] else null, if (translateOn) status[i] else null,
                            selecting = translateOn && ocrMode == "manual" && selecting,
                        ) { x0, y0, x1, y1 -> translateRegion(i, page, x0, y0, x1, y1) }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton(onClick = { go(index - 1) }, enabled = index > 0) { Text("Chương trước") }
                            Button(onClick = { go(index + 1) }, enabled = index < chapters.size - 1) { Text("Chương sau") }
                        }
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }

        ocrProgress?.let { p ->
            Surface(
                shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (menu) 140.dp else 16.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(p, color = Color.White, fontSize = 13.sp)
                }
            }
        }

        if (translateOn && ocrMode == "manual") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selecting) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .padding(end = 12.dp, bottom = if (menu) 140.dp else 16.dp)
                    .clickable { selecting = !selecting },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Crop, null, tint = if (selecting) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (selecting) "Kéo để khoanh · bấm để tắt" else "Khoanh vùng dịch",
                        color = if (selecting) MaterialTheme.colorScheme.onPrimary else Color.White, fontSize = 13.sp,
                    )
                }
            }
        }

        AnimatedVisibility(menu, enter = slideInVertically { -it }, exit = slideOutVertically { -it }, modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                    Column(Modifier.weight(1f)) {
                        Text(tr.bookTitle(initial?.name ?: app.library.get(pid, bookUrl)?.name ?: "", bookKey), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        val pageCount = (pages as? Load.Ok)?.value?.size ?: 0
                        Text("${index + 1}/${chapters.size} · " + tr.title(chapter?.name ?: "", bookKey) + if (pageCount > 0) " · $pageCount ảnh" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { chapter?.let { nav.navigate(Routes.web(it.url)) } }) { Icon(Icons.Default.Public, "Mở trang gốc") }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Tải lại chương") }
                }
            }
        }
        AnimatedVisibility(menu, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { go(index - 1) }, enabled = index > 0) { Icon(Icons.Default.ChevronLeft, "Chương trước") }
                    ComicMenuAction(Icons.AutoMirrored.Filled.List, "Mục lục", Modifier.weight(1f)) { showToc = true }
                    ComicMenuAction(Icons.Default.Translate, if (translateOn) "Dịch ảnh: bật" else "Dịch ảnh", Modifier.weight(1f)) { showTranslate = true }
                    IconButton(onClick = { go(index + 1) }, enabled = index < chapters.size - 1) { Icon(Icons.Default.ChevronRight, "Chương sau") }
                }
            }
        }
    }

    if (showToc) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showToc = false }, sheetState = sheet) {
            val tocState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth().height(520.dp), state = tocState) {
                itemsIndexed(chapters) { i, c ->
                    Text(
                        tr.title(c.name, bookKey), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { showToc = false; menu = false; go(i) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (showTranslate) {
        ModalBottomSheet(onDismissRequest = { showTranslate = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dịch chữ trong ảnh", style = MaterialTheme.typography.titleMedium)
                        Text("Nhận dạng chữ Trung, Hàn, Anh trên ảnh rồi dịch, đè lên bong bóng thoại. Ghi nhớ riêng cho truyện này.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(translateOn, { setTranslate(it) })
                }
                Spacer(Modifier.height(16.dp))
                Text("Nhận diện chữ")
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ocrModes.forEachIndexed { i, (id, name) ->
                        SegmentedButton(selected = ocrMode == id, onClick = { if (!translateOn) setTranslate(true); setOcrMode(id) }, shape = SegmentedButtonDefaults.itemShape(i, ocrModes.size), icon = {}) {
                            Text(name, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ocrMode == "manual") "Thủ công: bấm nút \"Khoanh vùng dịch\" rồi kéo ngón tay quanh bong bóng cần dịch. Bấm lại nút để cuộn trang."
                    else "Tự động: app tự tìm và dịch mọi bong bóng trên từng ảnh.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ocrMode == "manual") {
                    TextButton(onClick = {
                        val c = chapter ?: return@TextButton
                        manualCache.keys.removeAll { it.substringBeforeLast('|') == c.url }
                        texts.clear(); status.clear()
                    }) { Text("Xoá các vùng đã dịch ở chương này") }
                }
                Spacer(Modifier.height(16.dp))
                Text("Cách dịch")
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    comicEngines.forEachIndexed { i, (id, name) ->
                        SegmentedButton(selected = engine == id, onClick = { setTranslate(true, id) }, shape = SegmentedButtonDefaults.itemShape(i, comicEngines.size), icon = {}) {
                            Text(name, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tự động: chữ Trung dịch bằng Từ điển; chữ Anh, Hàn dịch bằng AI (" + tr.aiName() + (if (tr.aiConfigured()) "" else ", chưa có key nên dùng Google") + "). AI lỗi thì tự chuyển sang Google. " +
                        "Mỗi ảnh có một dòng nhỏ cho biết đang ở bước nào và dịch bằng gì; dòng đỏ là lỗi, chạm để sao chép gửi báo lỗi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tải trước chương lân cận")
                        Text(
                            "Tải sẵn ảnh và dịch trước 3 chương quanh chương đang đọc. Tắt nếu trang nguồn báo \"Too many requests\". " +
                                "Khi trang báo quá tải, app tự dừng tải trước 5 phút.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(prefetch, { prefetch = it; prefs.edit().putBoolean("prefetch", it).apply() })
                }
            }
        }
    }
}

/** Sites known to rate-limit preloading. Only these (and any site that answered HTTP 429) preload slowly. */
private val slowSites = listOf("kuaikanmanhua.com")

/** Per site: until when preloading stays paused after it answered "too many requests". */
private val limitedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("").removePrefix("www.").removePrefix("m.")

private fun isSlow(host: String): Boolean = slowSites.any { host.endsWith(it) } || limitedUntil.containsKey(host)

private fun prefetchAllowed(host: String): Boolean = System.currentTimeMillis() >= (limitedUntil[host] ?: 0L)

/** Pause preloading of [host] for 5 minutes when an error looks like a rate limit (HTTP 429 / "Too many requests"). */
private fun noteLimit(host: String, e: Throwable?) {
    var t = e
    while (t != null) {
        val m = (t.message ?: "") + " " + t.javaClass.simpleName
        if ("429" in m || m.contains("too many", ignoreCase = true)) { limitedUntil[host] = System.currentTimeMillis() + 5 * 60_000L; return }
        t = t.cause
    }
}

private fun refererFor(page: ComicPage, chapterUrl: String): String? =
    if (page.referrerPolicy.equals("no-referrer", ignoreCase = true)) null else chapterUrl

@Composable
private fun ComicPageView(
    page: ComicPage, chapterUrl: String, loader: ImageLoader, text: PageText?, status: PageStatus?,
    selecting: Boolean = false, onRegion: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
) {
    val ctx = LocalContext.current
    val urls = remember(page) { listOf(page.link) + page.fallback }
    var which by remember(page) { mutableIntStateOf(0) }
    var loaded by remember(page) { mutableStateOf(false) }
    var failed by remember(page) { mutableStateOf(false) }
    val url = urls[which.coerceAtMost(urls.lastIndex)]
    val request = remember(url) {
        ImageRequest.Builder(ctx).data(url).apply {
            refererFor(page, chapterUrl)?.let { addHeader("Referer", it) }
            addHeader("User-Agent", App.instance.userAgent)
        }.build()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = if (loaded) 0.dp else 320.dp)) {
        AsyncImage(
            model = request, imageLoader = loader, contentDescription = null,
            contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth(),
            onSuccess = { loaded = true; failed = false },
            onError = { if (which < urls.lastIndex) which++ else failed = true },
        )
        if (!loaded && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (failed) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Không tải được ảnh", color = Color.White)
            OutlinedButton(onClick = { failed = false; which = 0 }) { Text("Thử lại", color = Color.White) }
        }
        if (loaded && text != null && text.res.width > 0) {
            val density = LocalDensity.current
            val scale = constraints.maxWidth.toFloat() / text.res.width
            text.res.blocks.forEachIndexed { i, b -> Bubble(b, text.texts.getOrNull(i) ?: b.text, scale, density) }
        }
        if (loaded && selecting) {
            // Drag a box around the text to translate; the box is sent as fractions of the image.
            var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            var a by remember { mutableStateOf<Offset?>(null) }
            var b by remember { mutableStateOf<Offset?>(null) }
            val onRegionNow by androidx.compose.runtime.rememberUpdatedState(onRegion)
            Box(
                Modifier.matchParentSize().onSizeChanged { size = it }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { a = it; b = it },
                        onDrag = { ch, _ -> ch.consume(); b = ch.position },
                        onDragCancel = { a = null; b = null },
                        onDragEnd = {
                            val p = a; val q = b
                            a = null; b = null
                            if (p != null && q != null && size.width > 0 && size.height > 0 &&
                                kotlin.math.abs(p.x - q.x) > 16f && kotlin.math.abs(p.y - q.y) > 12f
                            ) {
                                onRegionNow(
                                    minOf(p.x, q.x) / size.width, minOf(p.y, q.y) / size.height,
                                    maxOf(p.x, q.x) / size.width, maxOf(p.y, q.y) / size.height,
                                )
                            }
                        },
                    )
                }
            ) {
                val p = a; val q = b
                if (p != null && q != null) with(LocalDensity.current) {
                    Box(
                        Modifier.offset { IntOffset(minOf(p.x, q.x).toInt(), minOf(p.y, q.y).toInt()) }
                            .size(kotlin.math.abs(p.x - q.x).toDp(), kotlin.math.abs(p.y - q.y).toDp())
                            .background(Color(0x332196F3))
                            .border(2.dp, Color(0xFF2196F3))
                    )
                }
            }
        }
        if (status != null) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(if (status.error) Color(0xCCB3261E) else Color(0x99000000), RoundedCornerShape(6.dp))
                    .clickable {
                        clip.setText(androidx.compose.ui.text.AnnotatedString(status.text))
                        android.widget.Toast.makeText(ctx, "Đã sao chép", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (status.busy) { CircularProgressIndicator(Modifier.size(9.dp), color = Color.White, strokeWidth = 1.dp); Spacer(Modifier.size(4.dp)) }
                Text(status.text, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Bubble(b: OcrBlock, value: String, scale: Float, density: androidx.compose.ui.unit.Density) {
    var showOriginal by remember(b) { mutableStateOf(false) }
    val shown = if (showOriginal) b.text else value
    val bg = Color(b.bg)
    val fg = if (b.light > 0.5f) Color.Black else Color.White
    val parts = b.parts.ifEmpty { listOf(intArrayOf(b.left, b.top, b.right, b.bottom)) }
    val unionArea = ((b.right - b.left) * (b.bottom - b.top)).toFloat().coerceAtLeast(1f)
    val partArea = parts.sumOf { (it[2] - it[0]) * (it[3] - it[1]) }.toFloat()
    // Lines packed tightly (a normal bubble): cover the whole box. Loose groups: cover only the old lines, keep the art.
    val fillUnion = partArea / unionArea >= 0.55f
    val lineH = ((b.bottom - b.top).toFloat() / b.lines.coerceAtLeast(1)).coerceAtLeast(8f)
    // Pad by the line height so slanted/stylised letters (the "A" of "A WOMAN", hearts) are covered too.
    val padX = (lineH * 0.3f).coerceIn(3f, 18f)
    val padY = (lineH * 0.18f).coerceIn(3f, 12f)
    with(density) {
        if (fillUnion) {
            Box(Modifier.offset { IntOffset(((b.left - padX) * scale).toInt(), ((b.top - padY) * scale).toInt()) }
                .size(((b.right - b.left + 2 * padX) * scale).toDp(), ((b.bottom - b.top + 2 * padY) * scale).toDp())
                .background(bg, RoundedCornerShape(6.dp)))
        } else parts.forEach { r ->
            val ph = (r[3] - r[1]).toFloat()
            val px = (ph * 0.3f).coerceIn(3f, 18f)
            val py = (ph * 0.18f).coerceIn(3f, 12f)
            Box(Modifier.offset { IntOffset(((r[0] - px) * scale).toInt(), ((r[1] - py) * scale).toInt()) }
                .size(((r[2] - r[0] + 2 * px) * scale).toDp(), ((r[3] - r[1] + 2 * py) * scale).toDp())
                .background(bg, RoundedCornerShape(3.dp)))
        }
        // Text area = the covered box, so text never spills past it; the font shrinks until it fits.
        val wPx = ((b.right - b.left + 2 * padX) * scale).coerceAtLeast(24f)
        val hPx = ((b.bottom - b.top + 2 * padY) * scale).coerceAtLeast(14f)
        val cx = (b.left + b.right) / 2f * scale
        val cy = (b.top + b.bottom) / 2f * scale
        val maxPx = (lineH * scale * 0.8f).coerceIn(9.sp.toPx(), 18.sp.toPx())
        var fontPx by remember(shown, scale) { mutableStateOf(maxPx) }
        Box(
            Modifier.offset { IntOffset((cx - wPx / 2).toInt(), (cy - hPx / 2).toInt()) }
                .size(wPx.toDp(), hPx.toDp())
                .clickable { showOriginal = !showOriginal },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                shown, color = fg, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium,
                fontSize = fontPx.toSp(), lineHeight = (fontPx * 1.12f).toSp(),
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(bg, blurRadius = 6f)),
                onTextLayout = { l ->
                    // Shrink when the text overflows or a word had to be split across lines.
                    val split = (0 until l.lineCount - 1).any { k ->
                        val e = l.getLineEnd(k)
                        e in 1 until shown.length && shown[e - 1].isLetterOrDigit() && shown[e].isLetterOrDigit()
                    }
                    if ((l.hasVisualOverflow || split) && fontPx > 7.sp.toPx()) fontPx *= 0.9f
                },
            )
        }
    }
}

@Composable
private fun ComicMenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
