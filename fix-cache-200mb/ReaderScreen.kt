package vn.thucac.reader.ui

import androidx.compose.ui.text.withStyle
import vn.thucac.reader.data.DictKind

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import vn.thucac.reader.data.joinBrokenLines
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.thucac.engine.ChapterRef
import vn.thucac.reader.App
import vn.thucac.reader.data.BookEntry
import vn.thucac.reader.data.TransMode

@Composable
fun ReaderScreen(nav: NavHostController, pid: String, bookUrl: String, startIndex: Int, startPara: Int = -1) {
    val plugin = remember(pid) { App.instance.sources.get(pid) }
    if (plugin?.isComic == true) ComicReaderScreen(nav, pid, bookUrl, startIndex)
    else if (plugin?.isVideo == true) VideoScreen(nav, pid, bookUrl, startIndex)
    else NovelReaderScreen(nav, pid, bookUrl, startIndex, startPara)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
private fun NovelReaderScreen(nav: NavHostController, pid: String, bookUrl: String, startIndex: Int, startPara: Int) {
    val app = App.instance
    val tr = app.translator
    val s = app.settings
    val bookKey = app.library.keyOf(pid, bookUrl)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    val initial = remember { app.library.get(pid, bookUrl) }
    var index by remember { mutableIntStateOf(if (startIndex >= 0) startIndex else initial?.chapterIndex ?: 0) }
    var restoreScroll by remember { mutableStateOf(if (startPara >= 0) startPara else if (startIndex < 0 || startIndex == initial?.chapterIndex) initial?.scrollIndex ?: 0 else 0) }
    val prefs = app.prefs
    var srcParas by remember { mutableStateOf<List<String>>(emptyList()) }
    var paraAction by remember { mutableStateOf<Int?>(null) }
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var showBookmarks by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    var toc by remember { mutableStateOf<Load<List<ChapterRef>>>(Load.Loading) }
    var raw by remember { mutableStateOf<Load<List<String>>>(Load.Loading) }
    var shown by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var wordPara by remember { mutableStateOf<Int?>(null) }
    var showTts by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var showAudioDl by remember { mutableStateOf(false) }
    var autoTts by remember { mutableStateOf(false) }
    val tts = app.tts
    val ttsHere = tts.active
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var mode by remember { mutableStateOf(initial?.mode ?: s.transMode) }
    val listState = rememberLazyListState()
    // Coming back from the in-app browser (e.g. after passing Cloudflare): retry a failed load.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var paused = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || ev == androidx.lifecycle.Lifecycle.Event.ON_STOP) paused = true
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME && paused) { paused = false; if (raw is Load.Err) reload++ }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Reading time for the home screen stats: count 30 s ticks while the reader is in the foreground.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || app.tts.playing) app.stats.addSeconds(30)
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
        raw = Load.Loading
        raw = withContext(Dispatchers.IO) { runCatching { app.sources.chapter(pid, c.url, reload > 0) }.fold({ Load.Ok(it) }, { Load.Err(errorText(it)) }) }
        if (raw is Load.Ok) {
            // Download the next two chapters in the background.
            launch(Dispatchers.IO) { for (k in 1..2) chapters.getOrNull(index + k)?.let { n -> runCatching { app.sources.chapter(pid, n.url) } } }
        }
    }
    LaunchedEffect(raw, mode, tr.version, s.namesFirst, s.bookNamesFirst, s.longestFirst, s.luatNhanMode, s.maxPhrase, s.splitSentences, s.autoSimplified, s.applyNumberRules, prefs.joinBrokenLines) {
        val r = raw as? Load.Ok ?: return@LaunchedEffect
        val c = chapter ?: return@LaunchedEffect
        notice = null
        val input = if (prefs.joinBrokenLines) joinBrokenLines(r.value) else r.value
        srcParas = input
        val (out, err) = withContext(Dispatchers.Default) {
            runCatching { tr.chapter(input, mode, bookKey, c.url) }.fold({ it to tr.takeNotice() }, { input to errorText(it) })
        }
        shown = out
        notice = err
        if (restoreScroll > 0) { listState.scrollToItem(restoreScroll.coerceAtMost(out.size)); restoreScroll = 0 }
        // Tải trước 2 chương kế (N+1, N+2) vào ChapterCache ở mọi chế độ; Convert/AI thì dịch trước luôn (có cache theo chương).
        for (n in listOfNotNull(chapters.getOrNull(index + 1), chapters.getOrNull(index + 2))) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val nr = app.sources.chapter(pid, n.url)
                    if (mode == TransMode.CONVERT || mode == TransMode.GEMINI) {
                        tr.chapter(nr, mode, bookKey, n.url)
                        tr.takeNotice()
                    }
                }
            }
        }
    }
    // Save progress.
    LaunchedEffect(chapters.size) {
        if (chapters.isEmpty()) return@LaunchedEffect
        snapshotFlow { Triple(index, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .debounce(600)
            .collect { (i, item, off) ->
                val c = chapters.getOrNull(i) ?: return@collect
                val e = app.library.get(pid, bookUrl) ?: BookEntry(pid, bookUrl, "", added = System.currentTimeMillis())
                app.library.upsert(e.copy(chapterIndex = i, chapterName = c.name, chapterCount = chapters.size, scrollIndex = item, scrollOffset = off, lastRead = System.currentTimeMillis(), inHistory = true))
            }
    }

    // Follow the reader-aloud: keep the spoken paragraph on screen.
    LaunchedEffect(ttsHere, tts.index, shown.size) {
        if (!ttsHere || shown.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (tts.index !in visible.dropLast(1)) listState.animateScrollToItem(tts.index)
    }

    fun go(i: Int) {
        if (i < 0 || i >= chapters.size) return
        index = i
        shown = emptyList()
        scope.launch { listState.scrollToItem(0) }
    }

    // Cuộn hết chương mà kéo tiếp thì sang chương sau; ở đầu chương kéo ngược thì về cuối chương trước.
    val pullPx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    val pullToTurn = remember(index, chapters.size, ttsHere) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pull = 0f
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y == 0f || shown.isEmpty() || ttsHere) { pull = 0f; return androidx.compose.ui.geometry.Offset.Zero }
                if (pull != 0f && kotlin.math.sign(pull) != kotlin.math.sign(available.y)) pull = 0f
                pull += available.y
                if (pull < -pullPx && index + 1 < chapters.size) { pull = 0f; go(index + 1) }
                else if (pull > pullPx && index > 0) { pull = 0f; go(index - 1); restoreScroll = Int.MAX_VALUE }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity { pull = 0f; return androidx.compose.ui.unit.Velocity.Zero }
        }
    }

    fun startTts(fromStart: Boolean = false) {
        val c = chapter ?: return
        if (shown.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        if (!fromStart) showPlayer = true
        val name = app.library.get(pid, bookUrl)?.name ?: initial?.name ?: ""
        val paras = listOf(tr.text(c.name, mode, bookKey)) + shown
        tts.speak(
            paras = paras,
            audioDir = app.prerender.lookup(bookKey, c.url, mode, paras),
            startIndex = if (fromStart) 0 else listState.firstVisibleItemIndex,
            bookTitle = name,
            chapterTitle = tr.text(c.name, mode, bookKey),
            onChapterEnd = {
                if (s.ttsAutoNextChapter && index + 1 < chapters.size) { autoTts = true; go(index + 1) } else tts.stop()
            },
        )
    }

    // Keep reading after moving to another chapter while the reader-aloud is on.
    LaunchedEffect(shown, autoTts) {
        if (!autoTts || shown.isEmpty()) return@LaunchedEffect
        autoTts = false
        if (tts.active) startTts(fromStart = true)
    }

    // "Tự tải chương sau": move on once the reader has scrolled to the very end.
    LaunchedEffect(shown, prefs.autoLoadNext) {
        if (!prefs.autoLoadNext || shown.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex > 0 && !listState.canScrollForward }.first { it }
        kotlinx.coroutines.delay(800)
        if (!listState.canScrollForward && !ttsHere && index + 1 < chapters.size) go(index + 1)
    }

    fun addBookmark(item: Int, note: String = "") {
        val c = chapter ?: return
        val name = app.library.get(pid, bookUrl)?.name ?: initial?.name ?: ""
        app.bookmarks.add(vn.thucac.reader.data.Bookmark(
            id = System.currentTimeMillis(), pluginId = pid, bookUrl = bookUrl, bookName = name,
            chapterIndex = index, chapterName = c.name, para = item,
            excerpt = shown.getOrNull((item - 1).coerceAtLeast(0))?.take(200) ?: "", note = note, time = System.currentTimeMillis(),
        ))
        android.widget.Toast.makeText(toastCtx, if (note.isEmpty()) "Đã thêm dấu trang" else "Đã lưu ghi chú", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun setMode(m: TransMode) {
        mode = m
        app.library.get(pid, bookUrl)?.let { app.library.upsert(it.copy(mode = m)) }
    }

    val (bg, fg) = readerThemes[s.theme.coerceIn(0, readerThemes.lastIndex)].second
    val family = if (s.readerFont == "serif") FontFamily.Serif else FontFamily.SansSerif
    val noRipple = remember { MutableInteractionSource() }
    val book = prefs.bookLayout

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            toc is Load.Err -> ErrorBox((toc as Load.Err).message, onOpenWeb = { nav.navigate(Routes.web(bookUrl)) })
            toc is Load.Loading -> Loading()
            chapters.isEmpty() -> EmptyBox("Truyện chưa có chương nào.")
            raw is Load.Err -> ErrorBox((raw as Load.Err).message + "\n\nNếu trang bị Cloudflare chặn: bấm Mở trang web, chờ trang qua bước kiểm tra (bấm xác nhận nếu được hỏi), rồi quay lại. App sẽ tự tải lại chương.", onRetry = { reload++ }, onOpenWeb = { chapter?.let { nav.navigate(Routes.web(it.url)) } })
            raw is Load.Loading || shown.isEmpty() -> Loading()
            else -> LazyColumn(
                Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { menu = !menu }
                    .nestedScroll(pullToTurn)
                    .then(if (book) Modifier.statusBarsPadding().navigationBarsPadding().padding(top = 22.dp, bottom = 22.dp) else Modifier),
                state = listState,
            ) {
                item {
                    Text(
                        tr.text(chapter?.name ?: "", mode, bookKey), color = fg, fontFamily = family, fontWeight = FontWeight.Bold,
                        fontSize = (s.fontSize + if (book) 10 else 4).sp, lineHeight = (s.fontSize + if (book) 10 else 4).sp * 1.25f,
                        modifier = (if (book) Modifier else Modifier.statusBarsPadding()).padding(start = 20.dp, end = 20.dp, top = if (book) 8.dp else 24.dp, bottom = if (book) 20.dp else 12.dp),
                    )
                }
                notice?.let { n -> item { Text(n, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) } }
                itemsIndexed(shown) { i, p ->
                    val speaking = ttsHere && tts.index == i + 1
                    Text(
                        if (s.italicDialogue) italicDialogue(p) else androidx.compose.ui.text.AnnotatedString(p), color = fg,
                        style = androidx.compose.ui.text.TextStyle(
                            background = if (speaking) fg.copy(alpha = 0.12f) else Color.Unspecified,
                            textIndent = if (book) androidx.compose.ui.text.style.TextIndent(firstLine = 2.em) else null,
                            textAlign = if (book) androidx.compose.ui.text.style.TextAlign.Justify else androidx.compose.ui.text.style.TextAlign.Unspecified,
                        ), fontFamily = family, fontSize = s.fontSize.sp, lineHeight = (s.lineHeight / 100f).em,
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(interactionSource = noRipple, indication = null, onClick = { menu = !menu }, onLongClick = { paraAction = i })
                            .padding(horizontal = 20.dp, vertical = if (book) (s.fontSize * 0.55f).dp else 6.dp),
                    )
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

        // Kiểu sách: tên chương nhỏ ở trên, chương x/y · trang x/y · % cả truyện ở dưới.
        if (book && !menu && raw is Load.Ok && shown.isNotEmpty()) {
            val small = androidx.compose.ui.text.TextStyle(color = fg.copy(alpha = 0.65f), fontSize = 12.sp)
            Text(
                tr.text(chapter?.name ?: "", mode, bookKey), style = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().background(bg).statusBarsPadding().padding(horizontal = 20.dp, vertical = 3.dp),
            )
            val pages by remember(shown) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val vis = info.visibleItemsInfo
                    val view = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
                    if (vis.isEmpty() || info.totalItemsCount == 0) 1 to 1 else {
                        val avg = vis.sumOf { it.size }.toFloat() / vis.size
                        val total = avg * info.totalItemsCount
                        val done = listState.firstVisibleItemIndex * avg + listState.firstVisibleItemScrollOffset
                        val n = kotlin.math.ceil(total / view).toInt().coerceAtLeast(1)
                        val p = (if (listState.canScrollForward) (done / view).toInt() + 1 else n).coerceIn(1, n)
                        p to n
                    }
                }
            }
            val pct = (index + pages.first.toFloat() / pages.second) * 100f / chapters.size.coerceAtLeast(1)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${index + 1}/${chapters.size}", style = small)
                Text("${pages.first}/${pages.second}     " + "%.2f".format(java.util.Locale.US, pct) + "%", style = small)
            }
        }

        AnimatedVisibility(menu, enter = slideInVertically { -it }, exit = slideOutVertically { -it }, modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                    Column(Modifier.weight(1f)) {
                        Text(tr.text(initial?.name ?: app.library.get(pid, bookUrl)?.name ?: "", mode, bookKey), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text("${index + 1}/${chapters.size} · " + tr.text(chapter?.name ?: "", mode, bookKey), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { addBookmark(listState.firstVisibleItemIndex) }) { Icon(Icons.Default.BookmarkAdd, "Thêm dấu trang") }
                    IconButton(onClick = { chapter?.let { nav.navigate(Routes.web(it.url)) } }) { Icon(Icons.Default.Public, "Mở trang gốc") }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Tải lại chương") }
                }
            }
        }
        AnimatedVisibility(menu, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TransMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(selected = mode == m, onClick = { setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, TransMode.entries.size), icon = {}) { Text(m.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { go(index - 1) }, enabled = index > 0) { Icon(Icons.Default.ChevronLeft, "Chương trước") }
                        MenuAction(Icons.AutoMirrored.Filled.List, "Mục lục", Modifier.weight(1f)) { showToc = true }
                        MenuAction(Icons.Default.TextFields, "Giao diện", Modifier.weight(1f)) { showStyle = true }
                        MenuAction(Icons.Default.Headphones, "Nghe", Modifier.weight(1f)) { showTts = true }
                        MenuAction(Icons.Default.Bookmarks, "Dấu trang", Modifier.weight(1f)) { showBookmarks = true }
                        IconButton(onClick = { go(index + 1) }, enabled = index < chapters.size - 1) { Icon(Icons.Default.ChevronRight, "Chương sau") }
                    }
                }
            }
        }
    }

    val ctxBg = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(tts.playing, s.bgMusicEnabled, s.bgMusicUri) { BgMusic.sync(ctxBg, tts.playing && ttsHere) }
    if (ttsHere && showPlayer) {
        val e = app.library.get(pid, bookUrl) ?: initial
        TtsPlayerScreen(
            title = tr.bookTitle(e?.name ?: "", bookKey),
            author = tr.title(e?.author ?: "", bookKey),
            cover = e?.cover ?: "",
            chapterTitle = tr.text(chapter?.name ?: "", mode, bookKey),
            onCollapse = { showPlayer = false },
            onPrevChapter = { if (index > 0) { autoTts = true; go(index - 1) } },
            onNextChapter = { if (index + 1 < chapters.size) { autoTts = true; go(index + 1) } },
            onToc = { showToc = true },
            onSettings = { showTts = true },
            onDownload = { showAudioDl = true },
        )
        if (showAudioDl) TtsDownloadDialog(pid, bookUrl, index, chapters, mode, onDismiss = { showAudioDl = false })
    } else if (ttsHere && !menu) {
        Box(Modifier.fillMaxSize()) {
            TtsControls(
                onExpand = { showPlayer = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                chapterTitle = tr.text(chapter?.name ?: "", mode, bookKey),
                onPrevChapter = { if (index > 0) { autoTts = true; go(index - 1) } },
                onNextChapter = { if (index + 1 < chapters.size) { autoTts = true; go(index + 1) } },
                onVoice = { showTts = true },
                onSettings = { showTts = true },
            )
        }
    }

    if (showTts) {
        ModalBottomSheet(onDismissRequest = { showTts = false }) {
            TtsPanel(
                active = ttsHere,
                onStart = { startTts(); showTts = false },
            )
        }
    }

    if (showToc) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showToc = false }, sheetState = sheet) {
            val tocState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth().height(520.dp), state = tocState) {
                itemsIndexed(chapters) { i, c ->
                    Text(
                        tr.text(c.name, mode, bookKey), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { showToc = false; menu = false; go(i) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (showStyle) {
        ModalBottomSheet(onDismissRequest = { showStyle = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text("Cỡ chữ: ${s.fontSize}")
                Slider(s.fontSize.toFloat(), { s.setFontSize(it.toInt()) }, valueRange = 13f..32f, steps = 18)
                Text("Giãn dòng: ${"%.1f".format(s.lineHeight / 100f)}")
                Slider(s.lineHeight / 100f, { s.setLineHeight((it * 100).toInt()) }, valueRange = 1.2f..2.4f, steps = 11)
                Text("Màu nền")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    readerThemes.forEachIndexed { i, (name, colors) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(44.dp).background(colors.first, CircleShape)
                                    .border(if (i == s.theme) 3.dp else 1.dp, if (i == s.theme) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                    .clickable { s.setTheme(i) },
                                contentAlignment = Alignment.Center,
                            ) { Text("A", color = colors.second) }
                            Text(name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chữ có chân (Serif)", Modifier.weight(1f))
                    Switch(s.readerFont == "serif", { s.setReaderFont(if (it) "serif" else "sans") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trình bày kiểu sách (thụt đầu dòng, căn đều)", Modifier.weight(1f))
                    Switch(prefs.bookLayout, { prefs.bookLayout = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tự tải chương sau khi cuộn hết", Modifier.weight(1f))
                    Switch(prefs.autoLoadNext, { prefs.autoLoadNext = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tắt xuống dòng (nối câu bị ngắt)", Modifier.weight(1f))
                    Switch(prefs.joinBrokenLines, { prefs.joinBrokenLines = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hiện mọi nghĩa VietPhrase", Modifier.weight(1f))
                    Switch(!s.firstMeaningOnly, { s.setFirstMeaningOnly(!it); tr.reloadAsync() })
                }
            }
        }
    }

    paraAction?.let { pi ->
        val text = shown.getOrNull(pi) ?: ""
        AlertDialog(
            onDismissRequest = { paraAction = null },
            title = { Text("Đoạn văn") },
            text = {
                Column {
                    Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (mode == TransMode.CONVERT) TextButton(onClick = { paraAction = null; wordPara = pi }, modifier = Modifier.fillMaxWidth()) { Text("Sửa từ / thêm tên riêng") }
                    TextButton(onClick = { paraAction = null; addBookmark(pi + 1) }, modifier = Modifier.fillMaxWidth()) { Text("Thêm dấu trang") }
                    TextButton(onClick = { paraAction = null; noteFor = pi }, modifier = Modifier.fillMaxWidth()) { Text("Ghi chú") }
                    TextButton(onClick = { paraAction = null; clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }, modifier = Modifier.fillMaxWidth()) { Text("Sao chép") }
                }
            },
            confirmButton = { TextButton(onClick = { paraAction = null }) { Text("Đóng") } },
        )
    }
    noteFor?.let { pi -> NoteDialog("", onDismiss = { noteFor = null }) { n -> noteFor = null; addBookmark(pi + 1, n) } }
    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            val marks = app.bookmarks.of(bookKey)
            if (marks.isEmpty()) Text("Chưa có dấu trang. Bấm giữ một đoạn văn để thêm dấu trang hoặc ghi chú.", Modifier.padding(20.dp))
            var editMark by remember { mutableStateOf<vn.thucac.reader.data.Bookmark?>(null) }
            LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                itemsIndexed(marks) { _, b ->
                    BookmarkRow(b, showBook = false, onOpen = {
                        showBookmarks = false; menu = false
                        if (b.chapterIndex == index) scope.launch { listState.scrollToItem(b.para.coerceAtMost(shown.size)) }
                        else { go(b.chapterIndex); restoreScroll = b.para }
                    }, onEdit = { editMark = b }, onDelete = { app.bookmarks.remove(b.id) })
                }
            }
            editMark?.let { b -> NoteDialog(b.note, onDismiss = { editMark = null }) { app.bookmarks.updateNote(b.id, it); editMark = null } }
        }
    }
    wordPara?.let { pi ->
        val original = srcParas.getOrNull(pi) ?: (raw as? Load.Ok)?.value?.getOrNull(pi)
        if (original == null) wordPara = null else WordDialog(original, bookKey, onDismiss = { wordPara = null })
    }
}

/** Shows how a paragraph was split, and lets the user fix a word or add a name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordDialog(original: String, bookKey: String, onDismiss: () -> Unit) {
    val tr = App.instance.translator
    tr.version
    val segs = remember(original, tr.version) { tr.segments(original, bookKey) }
    var zh by remember { mutableStateOf("") }
    var vi by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sửa từ / thêm tên") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(original, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Chạm một cụm để sửa:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    segs.forEach { (k, v) ->
                        AssistChip(onClick = { zh = k; vi = v; saved = "" }, label = { Text("$k\n${v.ifEmpty { "∅" }}", style = MaterialTheme.typography.labelSmall) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(zh, { zh = it; saved = "" }, label = { Text("Chữ Trung") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (zh.isNotEmpty()) Text("Hán Việt: " + tr.hanVietOf(zh), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(vi, { vi = it; saved = "" }, label = { Text("Nghĩa tiếng Việt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vi = tr.hanVietOf(zh).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } } }) { Text("Hán Việt viết hoa") }
                }
                if (saved.isNotEmpty()) Text(saved, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                val ok = zh.isNotBlank()
                Button(onClick = { tr.addBookName(bookKey, zh.trim(), vi.trim()); saved = "Đã lưu tên riêng cho truyện này" }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Lưu tên riêng (chỉ truyện này)") }
                OutlinedButton(onClick = { tr.addCustomEntry(DictKind.NAMES, zh.trim(), vi.trim()); saved = "Đã lưu vào Names chung" }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Lưu vào Names (mọi truyện)") }
                OutlinedButton(onClick = { tr.addCustomEntry(DictKind.VIETPHRASE, zh.trim(), vi.trim()); saved = "Đã lưu vào VietPhrase" }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Lưu vào VietPhrase (mọi truyện)") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
private fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Floating reader-aloud bar: chapter, paragraph position and progress, main and secondary controls, collapsible. */
@Composable
private fun TtsControls(
    onExpand: () -> Unit,
    modifier: Modifier,
    chapterTitle: String,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onVoice: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = App.instance
    val tts = app.tts
    val s = app.settings
    var sleepMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    // Paragraph 0 is the chapter title, so the story paragraphs are 1..total-1.
    val paraTotal = (tts.total - 1).coerceAtLeast(1)
    val paraNow = tts.index.coerceIn(0, paraTotal).coerceAtLeast(1)
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 6.dp).clickable(onClick = onExpand)) {
                    Text(chapterTitle.ifBlank { tts.chapterTitle }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("Đoạn $paraNow/$paraTotal · " + tts.engineLabel.ifBlank { vn.thucac.reader.data.AiVoiceCatalog.label(s.ttsAiVoice) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (collapsed) {
                    IconButton(onClick = { if (tts.playing) tts.pause() else tts.resume() }) {
                        Icon(if (tts.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (tts.playing) "Tạm dừng" else "Đọc tiếp")
                    }
                }
                IconButton(onClick = { collapsed = !collapsed }) {
                    Icon(if (collapsed) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (collapsed) "Mở rộng" else "Thu gọn")
                }
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { paraNow.toFloat() / paraTotal },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).height(3.dp),
            )
            tts.error?.takeIf { it.isNotEmpty() }?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (!collapsed) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Default.SkipPrevious, "Chương trước", 46.dp, onPrevChapter)
                    RoundButton(Icons.Default.FastRewind, "Đoạn trước", 46.dp) { tts.prev() }
                    RoundButton(if (tts.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (tts.playing) "Tạm dừng" else "Đọc tiếp", 64.dp) {
                        if (tts.playing) tts.pause() else tts.resume()
                    }
                    RoundButton(Icons.Default.FastForward, "Đoạn sau", 46.dp) { tts.next() }
                    RoundButton(Icons.Default.SkipNext, "Chương sau", 46.dp, onNextChapter)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val left = if (tts.sleepRemainingSec > 0) (tts.sleepRemainingSec + 59) / 60 else 0
                        TextButton(onClick = { sleepMenu = true }) {
                            Icon(Icons.Default.Timer, "Hẹn giờ tắt")
                            if (left > 0) Text(" ${left}p", style = MaterialTheme.typography.labelMedium)
                            else if (tts.stopAtChapterEnd) Text(" Hết chương", style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(sleepMenu, { sleepMenu = false }) {
                            listOf(0, 15, 30, 60, 90).forEach { m ->
                                DropdownMenuItem(text = { Text(if (m == 0) "Tắt hẹn giờ" else "$m phút") }, onClick = { tts.startSleepTimer(m); sleepMenu = false })
                            }
                            DropdownMenuItem(text = { Text("Hết chương này") }, onClick = { tts.sleepAtChapterEnd(); sleepMenu = false })
                        }
                    }
                    Box {
                        TextButton(onClick = { speedMenu = true }) { Text(speedLabel(s.ttsRate), style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(speedMenu, { speedMenu = false }) {
                            listOf(70, 80, 90, 100, 110, 120, 130, 150, 175, 200, 250).forEach { r ->
                                DropdownMenuItem(text = { Text(speedLabel(r)) }, onClick = { s.setTtsRate(r); tts.applyRatePitch(); speedMenu = false })
                            }
                        }
                    }
                    IconButton(onClick = onVoice) { Icon(Icons.Default.RecordVoiceOver, "Đổi giọng") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Cài đặt TTS") }
                    IconButton(onClick = { tts.stop() }) { Icon(Icons.Default.PowerSettingsNew, "Tắt đọc") }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val big = size > 50.dp
    Surface(
        onClick = onClick, shape = CircleShape, modifier = Modifier.size(size),
        color = if (big) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (big) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, desc, Modifier.size(if (big) 34.dp else 24.dp)) }
    }
}

/** Puts text inside quotes ("…", “…”, 「…」) in italics. */
internal fun italicDialogue(p: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val pairs = mapOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')
    var i = 0
    while (i < p.length) {
        val close = pairs[p[i]]
        val end = if (close != null) p.indexOf(close, i + 1) else -1
        if (close != null && end > i) {
            withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(p, i, end + 1) }
            i = end + 1
        } else {
            append(p[i]); i++
        }
    }
}
