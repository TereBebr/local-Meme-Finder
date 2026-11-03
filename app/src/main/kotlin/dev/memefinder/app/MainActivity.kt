package dev.memefinder.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.memefinder.core.Hit
import dev.memefinder.core.SearchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Purple = Color(0xFF5B4BC4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple)) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

private enum class Screen { SEARCH, INDEX }

private fun hasPhotoPermission(ctx: Context): Boolean {
    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var hasPerm by remember { mutableStateOf(hasPhotoPermission(ctx)) }
    var missing by remember { mutableStateOf(ModelFiles.missing(ctx)) }
    var screen by remember { mutableStateOf(Screen.SEARCH) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Library.load(MemeApp.instance.db) } }
    BackHandler(enabled = screen == Screen.INDEX) { screen = Screen.SEARCH }

    if (!hasPerm || missing.isNotEmpty()) {
        SetupScreen(
            hasPerm = hasPerm,
            missing = missing,
            onPermission = { hasPerm = hasPhotoPermission(ctx) },
            onModelsChanged = { missing = ModelFiles.missing(ctx) },
        )
    } else {
        when (screen) {
            Screen.SEARCH -> SearchScreen(onOpenIndex = { screen = Screen.INDEX })
            Screen.INDEX -> IndexScreen(onBack = { screen = Screen.SEARCH }, onModelsChanged = { missing = ModelFiles.missing(ctx) })
        }
    }
}

// ------------------------------------------------------------------------------------------ setup

@Composable
private fun ModelPickerButton(label: String, onModelsChanged: () -> Unit, onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                onStatus("Копирую модели…")
                val copied = withContext(Dispatchers.IO) { ModelFiles.importFrom(ctx, uri) { onStatus(it) } }
                Engines.releaseAll()
                onStatus(if (copied.isEmpty()) "В этой папке не нашёл файлов моделей" else "Скопировано файлов: ${copied.size}")
                onModelsChanged()
            }
        }
    }
    Button(onClick = { picker.launch(null) }) { Text(label) }
}

@Composable
private fun SetupScreen(hasPerm: Boolean, missing: List<String>, onPermission: () -> Unit, onModelsChanged: () -> Unit) {
    var status by remember { mutableStateOf("") }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermission() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Мем-Искатель", style = MaterialTheme.typography.headlineMedium)
            Text("Ищет картинки в галерее по смыслу и по тексту на них. Всё считается на телефоне, у приложения нет доступа в интернет.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Доступ к фото " + if (hasPerm) "✓" else "", style = MaterialTheme.typography.titleMedium)
                    if (!hasPerm) {
                        Text("Нужен, чтобы читать картинки из галереи. Ничего не копируется и не отправляется.")
                        Button(onClick = {
                            val perms = if (Build.VERSION.SDK_INT >= 33) {
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            permLauncher.launch(perms)
                        }) { Text("Разрешить") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Модели поиска " + if (missing.isEmpty()) "✓" else "", style = MaterialTheme.typography.titleMedium)
                    if (missing.isNotEmpty()) {
                        Text("Скопируйте с компьютера папку phone_models на телефон (например, в Download) и выберите её здесь. Это нужно один раз.")
                        Text("Не хватает: " + missing.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        ModelPickerButton("Выбрать папку с моделями", onModelsChanged) { status = it }
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ search

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(id: Long, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, id) { value = Thumbs.get(ctx, id) }
    Box(
        Modifier.aspectRatio(1f).padding(1.dp).background(Color(0xFFEEEEEE))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x885B4BC4))) {
                Text("✓", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
}

@Composable
private fun Viewer(id: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, id) { value = withContext(Dispatchers.IO) { Media.loadBitmap(ctx, id, 2048) } }
    val text = remember(id) { Library.ocrText(id) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        CircularProgressIndicator()
                    }
                }
                if (text.isNotBlank()) {
                    Text(
                        text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp).heightIn(max = 110.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { Share.send(ctx, listOf(id)) }) { Text("Поделиться") }
                    TextButton(onClick = onClose) { Text("Закрыть", color = Color.White) }
                }
            }
        }
    }
}

private fun modeLabel(m: SearchMode) = when (m) {
    SearchMode.ALL -> "Всё"
    SearchMode.TEXT -> "Текст"
    SearchMode.SEMANTIC -> "Смысл"
}

@Composable
private fun SearchScreen(onOpenIndex: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.ALL) }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Long?>(null) }
    val selected = remember { mutableStateListOf<Long>() }
    val embCache = remember { HashMap<String, FloatArray>() }

    val version by Library.version.collectAsState()
    val running by IndexState.running.collectAsState()
    val done by IndexState.done.collectAsState()
    val total by IndexState.total.collectAsState()

    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    LaunchedEffect(query, mode, version) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = emptyList()
            status = ""
            return@LaunchedEffect
        }
        delay(300)
        if (mode != SearchMode.TEXT && !Engines.isTextLoaded() && !embCache.containsKey(q)) status = "Загружаю модель поиска (несколько секунд)…"
        val result = withContext(Dispatchers.Default) {
            var emb: FloatArray? = null
            if (mode != SearchMode.TEXT) {
                emb = embCache[q] ?: runCatching { Engines.text(ctx).encode(q) }.getOrNull()?.also { embCache[q] = it }
            }
            Library.search(q, emb, mode)
        }
        hits = result
        status = if (result.isEmpty()) "Ничего не нашлось" else "Найдено: ${result.size}"
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("котик, хомяк, текст с картинки…") },
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onOpenIndex) { Text("Индекс") }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in SearchMode.values()) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(modeLabel(m)) })
            if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Очистить") }
        }
        if (running) {
            Text("Индексация: $done из $total", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Выбрано: ${selected.size}")
                Row {
                    Button(onClick = { Share.send(ctx, selected.toList()) }) { Text("Поделиться") }
                    TextButton(onClick = { selected.clear() }) { Text("Отмена") }
                }
            }
        } else if (status.isNotEmpty()) {
            Text(status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hits.isEmpty()) {
                val size = Library.size()
                Text(
                    when {
                        query.isNotBlank() -> ""
                        size == 0 -> "Пока ничего не проиндексировано. Нажмите «Индекс» и запустите индексацию."
                        else -> "В индексе картинок: $size. Введите запрос."
                    },
                    Modifier.padding(16.dp),
                )
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
                    items(hits, key = { it.id }) { hit ->
                        Cell(
                            id = hit.id,
                            selected = hit.id in selected,
                            onClick = { if (selected.isNotEmpty()) toggle(selected, hit.id) else viewing = hit.id },
                            onLongClick = { toggle(selected, hit.id) },
                        )
                    }
                }
            }
        }
    }
    viewing?.let { Viewer(it) { viewing = null } }
}

private fun toggle(list: MutableList<Long>, id: Long) {
    if (!list.remove(id)) list.add(id)
}

// ------------------------------------------------------------------------------------------ indexing

@Composable
private fun IndexScreen(onBack: () -> Unit, onModelsChanged: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>?>(Settings.buckets(ctx)) }
    var mode by remember { mutableStateOf(Settings.mode(ctx)) }
    var modelStatus by remember { mutableStateOf("") }

    val running by IndexState.running.collectAsState()
    val done by IndexState.done.collectAsState()
    val total by IndexState.total.collectAsState()
    val eta by IndexState.etaSeconds.collectAsState()
    val message by IndexState.message.collectAsState()
    val version by Library.version.collectAsState()

    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { Media.listImages(ctx) } }
    val buckets = remember(items) { Media.buckets(items) }
    fun isChosen(b: Bucket) = chosen == null || b.id.toString() in chosen!!

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад") }
                Text("Индексация", style = MaterialTheme.typography.titleLarge)
            }
            val libSize = remember(version) { Library.size() }
            Text("В галерее картинок: ${items.size}. В индексе: $libSize")
        }
        item { Text("Что искать", style = MaterialTheme.typography.titleMedium) }
        items(IndexMode.values().toList()) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == m, onClick = { mode = m; Settings.setMode(ctx, m) })
                Column {
                    Text(m.title)
                    Text(m.hint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Папки", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { chosen = null; Settings.setBuckets(ctx, null) }) { Text("Все") }
                TextButton(onClick = { chosen = emptySet(); Settings.setBuckets(ctx, emptySet()) }) { Text("Ничего") }
            }
        }
        items(buckets, key = { it.id }) { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isChosen(b), onCheckedChange = { on ->
                    val current = chosen ?: buckets.map { it.id.toString() }.toSet()
                    val next = if (on) current + b.id.toString() else current - b.id.toString()
                    chosen = next
                    Settings.setBuckets(ctx, next)
                })
                Text("${b.name} (${b.count})")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            if (running) {
                Button(onClick = { IndexService.stop(ctx) }) { Text("Остановить") }
            } else {
                Button(onClick = { IndexService.start(ctx) }) { Text("Начать индексацию") }
            }
            if (running || message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(message)
                if (running && total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Text("$done из $total, осталось ${formatEta(eta)}")
                }
            }
            Text(
                "Индексацию можно не ждать: она идёт в фоне, приложение можно закрыть. Остановить её можно в шторке уведомлений. " +
                    "Уже обработанные картинки не пересчитываются.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("Модели", style = MaterialTheme.typography.titleMedium)
            Text(if (ModelFiles.missing(ctx).isEmpty()) "Все файлы моделей на месте." else "Не хватает: " + ModelFiles.missing(ctx).joinToString(", "))
            ModelPickerButton("Заменить файлы моделей", onModelsChanged) { modelStatus = it }
            if (modelStatus.isNotEmpty()) Text(modelStatus, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }
}
