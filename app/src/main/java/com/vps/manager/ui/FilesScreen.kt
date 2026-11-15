package com.vps.manager.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vps.manager.data.Prefs
import com.vps.manager.ssh.FileKind
import com.vps.manager.ssh.RemoteFile
import com.vps.manager.ssh.RemoteFiles
import com.vps.manager.ssh.SshSession
import com.vps.manager.ssh.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SortBy(val title: String) {
    Size("גודל"), Name("שם"), Date("תאריך")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    session: SshSession,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(FileKind.Images) }
    var root by remember { mutableStateOf(prefs.lastSearchRoot) }
    val filesFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<List<RemoteFile>>(emptyList()) }
    val files by filesFlow.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableIntStateOf(0) }

    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(SortBy.Size) }
    var descending by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var viewing by remember { mutableStateOf<RemoteFile?>(null) }
    var loadState by remember { mutableStateOf<LoadState?>(null) }
    var cancelDownload by remember { mutableStateOf(false) }
    var rootDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(loading) {
        elapsed = 0
        while (loading) {
            kotlinx.coroutines.delay(1000)
            elapsed++
        }
    }

    // סריקה זורמת: תוצאות מופיעות תוך כדי, ונעצרת מיד אם עוברים לשונית או יוצאים.
    LaunchedEffect(kind, root, refreshKey) {
        loading = true; error = null; selected = emptySet()
        filesFlow.value = emptyList()
        val conn = session.connection()
        if (conn == null) {
            error = "אין חיבור פעיל לשרת"
            loading = false
            return@LaunchedEffect
        }
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val acc = ArrayList<RemoteFile>(512)
                RemoteFiles.scanStream(
                    conn = conn,
                    kind = kind,
                    root = root,
                    isCancelled = { job?.isActive != true }
                ) { batch ->
                    acc.addAll(batch)
                    filesFlow.value = ArrayList(acc)
                }
            }
        }
        result.onFailure { error = it.message ?: "הסריקה נכשלה" }
        loading = false
    }

    val shown = remember(files, query, sortBy, descending) {
        val filtered = if (query.isBlank()) files
        else files.filter { it.name.contains(query, true) || it.path.contains(query, true) }
        val sorted = when (sortBy) {
            SortBy.Size -> filtered.sortedBy { it.size }
            SortBy.Name -> filtered.sortedBy { it.name.lowercase() }
            SortBy.Date -> filtered.sortedBy { it.modified }
        }
        if (descending) sorted.reversed() else sorted
    }

    fun startView(file: RemoteFile) {
        val conn = session.connection() ?: return
        viewing = file
        cancelDownload = false

        // סרטונים ושמע מנוגנים ישירות מהשרת, בלי הורדה
        if (kind == FileKind.Videos || kind == FileKind.Audio) {
            loadState = LoadState.Streaming
            return
        }
        loadState = LoadState.Downloading(0, file.size)
        scope.launch {
            val dest = File(context.cacheDir, "remote/" + file.path.hashCode().toString() + "_" + file.name)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (dest.exists() && dest.length() == file.size) true
                    else RemoteFiles.download(
                        conn, file, dest,
                        onProgress = { done, total ->
                            loadState = LoadState.Downloading(done, total)
                        },
                        isCancelled = { cancelDownload }
                    )
                }
            }
            loadState = ok.fold(
                onSuccess = { done ->
                    if (done) LoadState.Ready(dest) else null
                },
                onFailure = { LoadState.Failed(it.message ?: "ההורדה נכשלה") }
            )
            if (loadState == null) viewing = null
        }
    }

    fun doDelete(list: List<RemoteFile>) {
        val conn = session.connection() ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { RemoteFiles.delete(conn, list.map { it.path }) }
            }
            res.onSuccess {
                val gone = list.map { it.path }.toSet()
                filesFlow.value = filesFlow.value.filterNot { it.path in gone }
                selected = emptySet()
                snackbar.showSnackbar("נמחקו ${list.size} קבצים")
            }.onFailure {
                snackbar.showSnackbar("המחיקה נכשלה: ${it.message}")
            }
        }
    }

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // כותרת
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .glass(radius = 22.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowForward, "חזרה") }
                    Column(Modifier.weight(1f)) {
                        Text("קבצים ומדיה", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (loading) "סורק… נמצאו ${files.size} עד כה"
                            else "${shown.size} פריטים · ${if (root == "/") "כל השרת" else root}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { rootDialog = true }) {
                        Icon(Icons.Rounded.FolderOpen, "בחירת תיקייה")
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, "רענון")
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.title) },
                            leadingIcon = {
                                Icon(
                                    when (k) {
                                        FileKind.Images -> Icons.Rounded.Image
                                        FileKind.Videos -> Icons.Rounded.Movie
                                        FileKind.Audio -> Icons.Rounded.MusicNote
                                        FileKind.Documents -> Icons.Rounded.Description
                                        FileKind.Largest -> Icons.Rounded.Storage
                                    },
                                    null, modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("סינון לפי שם…", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    var sortMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Rounded.Sort, "מיון")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text("מיון לפי ${s.title}") },
                                trailingIcon = {
                                    if (sortBy == s) Icon(Icons.Rounded.Check, null, tint = Ios.blue)
                                },
                                onClick = { sortBy = s; sortMenu = false }
                            )
                        }
                    }
                    IconButton(onClick = { descending = !descending }) {
                        Icon(
                            if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                            if (descending) "מהגדול לקטן" else "מהקטן לגדול"
                        )
                    }
                }
            }

            if (selected.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .glass(radius = 18.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selected.size} נבחרו", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selected = emptySet() }) { Text("ביטול בחירה") }
                    TextButton(
                        onClick = { confirmDelete = shown.filter { it.path in selected } },
                        colors = ButtonDefaults.textButtonColors(contentColor = Ios.red)
                    ) {
                        Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("מחיקה")
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    loading && files.isEmpty() -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Ios.blue)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "מחפש ${kind.title} בשרת…  ${elapsed} שנ׳",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "סריקה ראשונה של כל השרת לוקחת כמה שניות.\nאפשר לצמצם לתיקייה דרך אייקון התיקייה למעלה.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    error != null -> Text(
                        error!!,
                        Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = Ios.red
                    )
                    shown.isEmpty() -> Text(
                        "לא נמצאו קבצים מסוג ${kind.title}",
                        Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.path }) { file ->
                            FileRow(
                                file = file,
                                kind = kind,
                                session = session,
                                selected = file.path in selected,
                                selectionMode = selected.isNotEmpty(),
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (file.path in selected) selected - file.path
                                        else selected + file.path
                                    } else startView(file)
                                },
                                onLongClick = {
                                    selected = if (file.path in selected) selected - file.path
                                    else selected + file.path
                                },
                                onDelete = { confirmDelete = listOf(file) }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (confirmDelete.isNotEmpty()) {
        val list = confirmDelete
        AlertDialog(
            onDismissRequest = { confirmDelete = emptyList() },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = Ios.red) },
            title = { Text(if (list.size == 1) "למחוק את הקובץ?" else "למחוק ${list.size} קבצים?") },
            text = {
                Text(
                    if (list.size == 1)
                        "${list[0].name}\n(${list[0].size.humanSize()})\n\nהמחיקה היא מהשרת עצמו ואי אפשר לבטל אותה."
                    else "הקבצים יימחקו מהשרת עצמו ואי אפשר לבטל את הפעולה."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = emptyList(); doDelete(list) }) {
                    Text("אישור מחיקה", color = Ios.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = emptyList() }) { Text("ביטול") }
            }
        )
    }

    if (rootDialog) {
        var temp by remember { mutableStateOf(root) }
        AlertDialog(
            onDismissRequest = { rootDialog = false },
            title = { Text("איפה לחפש?") },
            text = {
                Column {
                    Text(
                        "ברירת המחדל היא כל השרת (/) — כל התיקיות וכל הקבצים.\nאפשר לצמצם לנתיב מסוים כדי לחפש רק שם.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = temp,
                        onValueChange = { temp = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("/", "/home", "/var/www", "/root", "/opt").forEach {
                            AssistChip(onClick = { temp = it }, label = { Text(it) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    root = temp.ifBlank { "/" }
                    prefs.lastSearchRoot = root
                    rootDialog = false
                }) { Text("סריקה") }
            },
            dismissButton = { TextButton(onClick = { rootDialog = false }) { Text("ביטול") } }
        )
    }

    val current = viewing
    val ls = loadState
    if (current != null && ls != null) {
        MediaViewerDialog(
            remote = current,
            kind = kind,
            state = ls,
            connection = session.connection(),
            onCancel = {
                cancelDownload = true
                viewing = null
                loadState = null
            },
            onDelete = {
                confirmDelete = listOf(current)
                cancelDownload = true
                viewing = null
                loadState = null
            },
            onRetry = { startView(current) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: RemoteFile,
    kind: FileKind,
    session: SshSession,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(radius = 18.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Ios.blue.copy(alpha = 0.18f) else Color.Transparent)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(file, kind, session)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    file.folder,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${file.size.humanSize()}  ·  ${dateText(file.modified)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selectionMode) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = if (selected) Ios.blue else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "מחיקה", tint = Ios.red, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** מגביל כמה תמונות נמשכות במקביל, כדי שהגלילה תישאר חלקה. */
private val thumbLimiter = Semaphore(3)

private fun dateText(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("dd/MM/yyyy", Locale("he")).format(Date(ms))

/** תצוגה מקדימה לתמונות קטנות — נטענת רק כשהשורה נראית על המסך. */
@Composable
private fun Thumbnail(file: RemoteFile, kind: FileKind, session: SshSession) {
    val context = LocalContext.current
    var bitmap by remember(file.path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val eligible = kind == FileKind.Images && file.size in 1..(2L * 1024 * 1024)

    LaunchedEffect(file.path) {
        if (!eligible) return@LaunchedEffect
        val conn = session.connection() ?: return@LaunchedEffect
        val dest = File(context.cacheDir, "thumbs/" + file.path.hashCode() + ".img")
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                if (!dest.exists() || dest.length() != file.size) {
                    thumbLimiter.withPermit {
                        RemoteFiles.download(conn, file, dest, { _, _ -> }) { job?.isActive != true }
                    }
                }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(dest.path, opts)
                val max = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                val sample = generateSequence(1) { it * 2 }.first { max / it <= 180 }
                BitmapFactory.decodeFile(dest.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        bitmap = bmp?.asImageBitmap()
    }

    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                when (kind) {
                    FileKind.Images -> Icons.Rounded.Image
                    FileKind.Videos -> Icons.Rounded.PlayCircle
                    FileKind.Audio -> Icons.Rounded.MusicNote
                    FileKind.Documents -> Icons.Rounded.Description
                    FileKind.Largest -> Icons.Rounded.InsertDriveFile
                },
                null,
                tint = Ios.blue,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
