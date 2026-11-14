package vn.thucac.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.thucac.engine.Plugin
import vn.thucac.reader.App
import vn.thucac.reader.data.RepoPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(nav: NavHostController, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var repoList by remember { mutableStateOf<Pair<String, List<RepoPlugin>>?>(null) }
    var removing by remember { mutableStateOf<Plugin?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    fun runTask(block: suspend () -> String) {
        scope.launch {
            busy = true
            val msg = runCatching { block() }.getOrElse { "Lỗi: " + errorText(it) }
            busy = false
            snack.showSnackbar(msg)
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask {
            val p = withContext(Dispatchers.IO) { app.sources.install(app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
            "Đã cài plugin ${p.name}"
        }
    }

    fun openUrl(url: String) = runTask {
        val (p, repo) = withContext(Dispatchers.IO) { app.sources.installFromUrl(url) }
        if (repo != null) { app.settings.addRepo(android.net.Uri.parse(url).host ?: "Kho plugin", url); repoList = url to repo; "Kho có ${repo.size} plugin" } else "Đã cài plugin ${p!!.name}"
    }

    Scaffold(
        modifier,
        topBar = {
            TopAppBar(colors = appBarColors(), title = { Text("Tiện ích mở rộng") }, navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } }, actions = {
                TextButton(onClick = { nav.navigate("repos") }) { Text("Quản lý nguồn") }
                IconButton(onClick = { showLogs = true }) { Icon(Icons.Default.Description, "Nhật ký plugin") }
            })
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Thêm plugin") })
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Từ file .zip trên máy") }, onClick = { addMenu = false; pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
                    DropdownMenuItem(text = { Text("Từ link (.zip hoặc kho .json)") }, onClick = { addMenu = false; urlDialog = true })
                    DropdownMenuItem(text = { Text("Từ kho plugin (Quản lý nguồn)") }, onClick = { addMenu = false; nav.navigate("repos") })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
            androidx.compose.material3.TabRow(selectedTabIndex = tab) {
                androidx.compose.material3.Tab(tab == 0, { tab = 0 }, text = { Text("Nguồn truyện") })
                androidx.compose.material3.Tab(tab == 1, { tab = 1 }, text = { Text("Tiện ích mở rộng") })
            }
            val isExt = tab == 1
            val allList = if (isExt) app.sources.extensions else app.sources.sourcePlugins
            // Search + "Lọc" (same dialog as the source picker in Khám phá), remembered between visits.
            val fprefs = remember { app.getSharedPreferences("explore", android.content.Context.MODE_PRIVATE) }
            var query by remember { mutableStateOf("") }
            var pfilter by remember { mutableStateOf(vn.thucac.reader.data.PluginFilter.parse(fprefs.getString("plugins_filter", "") ?: "")) }
            var showFilter by remember { mutableStateOf(false) }
            val facts = remember(allList) { allList.associate { it.id to app.sources.facts(it) } }
            val q = query.trim().lowercase()
            val list = allList.filter { p ->
                (q.isEmpty() || p.name.lowercase().contains(q) || p.source.lowercase().contains(q) || p.author.lowercase().contains(q)) &&
                    facts[p.id]?.let { pfilter.matches(it) } != false
            }
            if (allList.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Tìm plugin") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Xoá") } }) else null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { showFilter = true }) {
                    Icon(Icons.Default.FilterList, "Lọc", tint = if (pfilter.isActive()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showFilter) SourceFilterDialog(pfilter, facts.values.toList(), onDismiss = { showFilter = false }) { f ->
                pfilter = f
                fprefs.edit().putString("plugins_filter", f.serialize()).apply()
                showFilter = false
            }
            val upd = app.updater
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (upd.pluginUpdates.isEmpty() && !upd.checkingPlugins && System.currentTimeMillis() - app.settings.lastPluginCheck > 6 * 3600_000L) withContext(Dispatchers.IO) { runCatching { upd.checkPlugins() } }
            }
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            upd.checkingPlugins -> "Đang kiểm tra bản mới..."
                            upd.pluginUpdates.isEmpty() -> "Chưa có plugin cần cập nhật"
                            else -> "${upd.pluginUpdates.size} plugin có bản mới"
                        },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    if (upd.pluginUpdates.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                        runTask { val n = withContext(Dispatchers.IO) { upd.updateAllPlugins() }; "Đã cập nhật $n plugin" }
                    }) { Text("Cập nhật tất cả") }
                    else TextButton(enabled = !upd.checkingPlugins, onClick = {
                        runTask { val l = withContext(Dispatchers.IO) { upd.checkPlugins() }; if (l.isEmpty()) "Không có bản mới" else "${l.size} plugin có bản mới" }
                    }) { Text("Kiểm tra") }
                }
            }
            if (allList.isNotEmpty() && list.isEmpty()) EmptyBox("Không có plugin nào khớp tìm kiếm hoặc bộ lọc.")
            else if (list.isEmpty()) EmptyBox(if (isExt) "Chưa có tiện ích nào.\nVào \"Quản lý nguồn\", mở một kho và cài tiện ích dịch (loại translate), ví dụ OpenAI-compatible API, DeepSeek Translate."
                else "Chưa cài plugin nào.\nBấm \"Thêm plugin\" để cài từ file .zip, từ link hoặc từ một kho plugin.")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(if (isExt) Routes.ext(p.id) else Routes.source(p.id)) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PluginIcon(p.iconFile)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${p.name}  v${p.version}", style = MaterialTheme.typography.titleMedium)
                            Text(p.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (p.description.isNotEmpty()) Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            if (isExt) {
                                val active = app.settings.activeExtension == p.id
                                Text(extStatus(p) + if (active) " · đang dùng để dịch" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                val caps = listOf("home" to "khám phá", "search" to "tìm kiếm", "detail" to "chi tiết", "toc" to "mục lục", "chap" to "đọc").filter { p.hasScript(it.first) }.joinToString(", ") { it.second }
                                Text("Hỗ trợ: $caps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (!isExt) IconButton(onClick = { nav.navigate(Routes.pluginTest(p.id)) }) { Icon(Icons.Default.BugReport, "Thử plugin") }
                        IconButton(onClick = { removing = p }) { Icon(Icons.Default.Delete, "Gỡ") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (urlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("Cài từ link") },
            text = {
                Column {
                    Text("Dán link file plugin .zip, hoặc link kho plugin (file .json có danh sách \"data\").", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("https://...") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { urlDialog = false; if (url.isNotBlank()) openUrl(url.trim()) }) { Text("Cài") } },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Hủy") } },
        )
    }

    repoList?.let { (repoUrl, items) ->
        AlertDialog(
            onDismissRequest = { repoList = null },
            title = { Text("Kho plugin") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(items, key = { it.url }) { r ->
                        val installed = app.sources.plugins.firstOrNull { it.name == r.name }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.name}  v${r.version}", style = MaterialTheme.typography.titleSmall)
                                if (r.description.isNotEmpty()) Text(r.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            TextButton(onClick = {
                                runTask { val p = withContext(Dispatchers.IO) { app.sources.install(app.sources.download(r.url)) }; "Đã cài ${p.name}" }
                            }) { Text(if (installed == null) "Cài" else if (installed.version < r.version) "Cập nhật" else "Cài lại") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { repoList = null }) { Text("Đóng") } },
            dismissButton = { TextButton(onClick = { app.settings.removeRepo(repoUrl); repoList = null }) { Text("Xóa kho") } },
        )
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Gỡ ${p.name}?") },
            text = { Text("Truyện trong kệ từ nguồn này vẫn còn nhưng sẽ không đọc tiếp được cho tới khi cài lại.") },
            confirmButton = { TextButton(onClick = { app.sources.uninstall(p); removing = null }) { Text("Gỡ") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Hủy") } },
        )
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Nhật ký plugin") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    val logs = app.sources.logs.toList().reversed()
                    if (logs.isEmpty()) item { Text("Chưa có gì.") }
                    items(logs) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { showLogs = false }) { Text("Đóng") } },
            dismissButton = { TextButton(onClick = { app.sources.logs.clear() }) { Text("Xóa") } },
        )
    }
}

internal fun extStatus(p: Plugin): String = when {
    p.meta.optBoolean("encrypt") -> "${p.type.uppercase()} · bị mã hoá, Thư Các không chạy được"
    p.type != "translate" -> "${p.type.uppercase()} · chưa hỗ trợ loại này"
    else -> "TRANSLATE · " + p.author
}
