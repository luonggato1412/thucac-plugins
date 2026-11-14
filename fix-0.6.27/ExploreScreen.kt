package vn.thucac.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.thucac.engine.BookItem
import vn.thucac.engine.NavItem
import vn.thucac.reader.App

/** Khám phá tab: browses the last used source; the title opens a source picker. */
@Composable
fun ExploreScreen(nav: NavHostController, modifier: Modifier) {
    val app = App.instance
    val list = app.sources.sourcePlugins
    if (list.isEmpty()) {
        EmptyBox("Chưa có plugin nào. Vào tab Plugin để cài.", modifier.fillMaxSize())
        return
    }
    val pid = app.settings.exploreSource.takeIf { id -> list.any { it.id == id } } ?: list.first().id
    SourceBrowser(nav, pid, modifier, showBack = false) { app.settings.updateExploreSource(it) }
}

@Composable
fun SourceScreen(nav: NavHostController, pid: String) {
    var current by rememberSaveable { mutableStateOf(pid) }
    SourceBrowser(nav, current, Modifier, showBack = true) { current = it }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceBrowser(nav: NavHostController, pid: String, modifier: Modifier, showBack: Boolean, onPick: (String) -> Unit) {
    val app = App.instance
    val plugin = app.sources.get(pid)
    var state by remember { mutableStateOf<Load<Pair<List<NavItem>, List<NavItem>>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable(pid) { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(pid, reload) {
        state = Load.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { val api = app.sources.api(pid); api.home() to runCatching { api.genres() }.getOrDefault(emptyList()) }
                .fold({ Load.Ok(it) }, { Load.Err(errorText(it)) })
        }
    }
    Scaffold(modifier, topBar = {
        TopAppBar(colors = appBarColors(), 
            title = {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { picker = true }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PluginIcon(plugin?.iconFile, Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(plugin?.name ?: "Nguồn", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.KeyboardArrowDown, "Chọn nguồn")
                }
            },
            navigationIcon = { if (showBack) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.search(if (plugin?.hasScript("search") == true) pid else "")) }) { Icon(Icons.Default.Search, "Tìm") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Thêm") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Tìm trong tất cả nguồn") }, onClick = { menu = false; nav.navigate(Routes.search()) })
                        DropdownMenuItem(text = { Text("Tải lại") }, onClick = { menu = false; reload++ })
                        plugin?.source?.let { src -> DropdownMenuItem(text = { Text("Mở trang web") }, onClick = { menu = false; nav.navigate(Routes.web(src)) }) }
                    }
                }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                is Load.Loading -> Loading()
                is Load.Err -> ErrorBox(s.message, onRetry = { reload++ }, onOpenWeb = plugin?.source?.let { { nav.navigate(Routes.web(it)) } })
                is Load.Ok -> {
                    val (home, genres) = s.value
                    val tabs = home.map { app.translator.text(it.title) } + if (genres.isNotEmpty()) listOf("Thể loại") else emptyList()
                    if (tabs.isEmpty()) { EmptyBox("Nguồn này không có mục khám phá. Hãy dùng nút tìm kiếm."); return@Box }
                    if (tab >= tabs.size) tab = 0
                    Column(Modifier.fillMaxSize()) {
                        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, divider = {}) {
                            tabs.forEachIndexed { i, t ->
                                Tab(tab == i, { tab = i }, text = {
                                    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
                                })
                            }
                        }
                        if (tab < home.size) {
                            val item = home[tab]
                            BookListPane(nav, pid, item.script, item.input, Modifier.fillMaxSize())
                        } else GenreGrid(nav, pid, genres)
                    }
                }
            }
        }
    }
    if (picker) SourcePicker(pid, onDismiss = { picker = false }) { picker = false; onPick(it) }
}

/** Bottom sheet with every installed source as a 2-column grid of cards, with search and a filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = App.instance
    val prefs = remember { app.getSharedPreferences("explore", android.content.Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(vn.thucac.reader.data.PluginFilter.parse(prefs.getString("source_filter", "") ?: "")) }
    var showFilter by remember { mutableStateOf(false) }
    val all = app.sources.sourcePlugins
    val facts = remember(all) { all.associate { it.id to app.sources.facts(it) } }
    val q = query.trim().lowercase()
    val shown = all.filter { p ->
        (q.isEmpty() || p.name.lowercase().contains(q) || p.source.lowercase().contains(q) || p.author.lowercase().contains(q)) &&
            facts[p.id]?.let { filter.matches(it) } != false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Chọn nguồn", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Tìm nguồn") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Xoá") } }) else null,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(onClick = { showFilter = true }) {
                    Icon(Icons.Default.FilterList, "Lọc", tint = if (filter.isActive()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (filter.isActive()) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
            }
        }
        if (shown.isEmpty()) {
            Text(
                "Không có nguồn nào khớp.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
            )
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxWidth().heightIn(max = 620.dp),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(shown, key = { it.id }) { p ->
                val selected = p.id == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface)
                        .clickable { onPick(p.id) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PluginIcon(p.iconFile, Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(domainOf(p.source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (showFilter) SourceFilterDialog(filter, facts.values.toList(), onDismiss = { showFilter = false }) { f ->
        filter = f
        prefs.edit().putString("source_filter", f.serialize()).apply()
        showFilter = false
    }
}

/** "Lọc" dialog: author, language, type and an NSFW switch, applied only on "Áp dụng". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceFilterDialog(
    start: vn.thucac.reader.data.PluginFilter,
    facts: List<vn.thucac.reader.data.PluginFacts>,
    onDismiss: () -> Unit,
    onApply: (vn.thucac.reader.data.PluginFilter) -> Unit,
) {
    var f by remember { mutableStateOf(start) }
    val authors = remember(facts) { facts.map { it.author.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() } }
    val langs = remember(facts) {
        vn.thucac.reader.data.PluginLang.values().filter { l -> l.code.isNotEmpty() && facts.any { it.lang == l } }
    }
    val types = remember(facts) { facts.map { it.type.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() } }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lọc", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (authors.isNotEmpty()) FilterChips("Tác giả", authors.map { it to it }, f.author) { f = f.copy(author = it) }
                if (langs.isNotEmpty()) FilterChips("Ngôn ngữ", langs.map { it.code to it.label }, f.lang) { f = f.copy(lang = it) }
                if (types.isNotEmpty()) FilterChips("Loại", types.map { it to it.uppercase() }, f.type) { f = f.copy(type = it) }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nội dung NSFW", Modifier.weight(1f))
                    androidx.compose.material3.Switch(f.adult != "hide", { f = f.copy(adult = if (it) "all" else "hide") })
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onApply(f) }) { Text("Áp dụng") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(title: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf("all" to "Tất cả") + options).forEach { (id, label) ->
            androidx.compose.material3.FilterChip(selected = value == id, onClick = { onChange(id) }, label = { Text(label) })
        }
    }
}

private fun domainOf(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.").trimEnd('/')

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreGrid(nav: NavHostController, pid: String, genres: List<NavItem>) {
    val tr = App.instance.translator
    FlowRow(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { g ->
            val title = tr.text(g.title)
            AssistChip(onClick = { nav.navigate(Routes.list(pid, title, g.script, g.input)) }, label = { Text(title) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(nav: NavHostController, pid: String, title: String, script: String, input: String) {
    Scaffold(topBar = {
        TopAppBar(colors = appBarColors(), title = { Text(title) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } })
    }) { pad -> BookListPane(nav, pid, script, input, Modifier.padding(pad).fillMaxSize()) }
}

/** An endless list of books from one plugin script. */
@Composable
fun BookListPane(nav: NavHostController, pid: String, script: String, input: String, modifier: Modifier) {
    val app = App.instance
    val tr = app.translator
    tr.version
    val items = remember(pid, script, input) { mutableStateListOf<BookItem>() }
    var next by remember(pid, script, input) { mutableStateOf<String?>(null) }
    var loading by remember(pid, script, input) { mutableStateOf(false) }
    var error by remember(pid, script, input) { mutableStateOf<String?>(null) }
    var request by remember(pid, script, input) { mutableIntStateOf(1) }
    var ended by remember(pid, script, input) { mutableStateOf(false) }
    val listState = rememberLazyGridState()

    LaunchedEffect(pid, script, input, request) {
        if (ended || loading) return@LaunchedEffect
        loading = true; error = null
        val r = withContext(Dispatchers.IO) { runCatching { app.sources.api(pid).list(script, input, next) } }
        r.onSuccess { page ->
            val known = items.map { it.url }.toHashSet()
            val fresh = page.items.filter { known.add(it.url) }
            items.addAll(fresh)
            next = page.next
            if (page.next == null || fresh.isEmpty()) ended = true
        }.onFailure { error = errorText(it) }
        loading = false
    }
    val nearEnd by remember { derivedStateOf { val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, loading) { if (nearEnd && !loading && !ended && error == null && items.isNotEmpty()) request++ }

    Box(modifier) {
        if (items.isEmpty()) {
            when {
                error != null -> ErrorBox(error!!, onRetry = { request++ }, onOpenWeb = app.sources.get(pid)?.source?.let { { nav.navigate(Routes.web(it)) } })
                loading -> Loading()
                else -> EmptyBox("Không có truyện nào.")
            }
        } else LazyVerticalGrid(
            GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItems(items, key = { it.url }) { b ->
                BookCard(tr.bookTitle(b.name), tr.text(authorOf(b.description)), b.cover) { nav.navigate(Routes.detail(pid, b.url)) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> Loading(Modifier)
                        error != null -> ErrorBox(error!!, onRetry = { request++ }, modifier = Modifier)
                        ended -> Text("Hết danh sách", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Cover on top, title (2 lines) and author under it, for the 3-column grid. */
@Composable
fun BookCard(name: String, sub: String, cover: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(cover, Modifier.fillMaxWidth().aspectRatio(0.7f))
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Plugins put "author | latest chapter" or "作者|name<br>intro" in the description; keep the author part. */
internal fun authorOf(desc: String): String {
    val first = desc.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    val noLabel = first.replace(Regex("^(作者|作\\s*者|Tác giả|Author)\\s*[|:：]?\\s*", RegexOption.IGNORE_CASE), "")
    return noLabel.split('|', '｜').first().trim()
}
