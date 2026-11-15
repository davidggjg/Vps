package com.vps.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vps.manager.data.Server
import com.vps.manager.ssh.CommandBlock
import com.vps.manager.ssh.ConnState
import com.vps.manager.ssh.SshSession
import kotlinx.coroutines.launch

private const val COLLAPSE_LINES = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    server: Server,
    session: SshSession,
    onBack: () -> Unit
) {
    val state = session.state.collectAsState().value
    val status = session.status.collectAsState().value
    val blocks = session.blocks.collectAsState().value
    val cwd = session.cwd.collectAsState().value

    var input by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var blockSearch by remember { mutableStateOf<CommandBlock?>(null) }
    var sheetBlock by remember { mutableStateOf<CommandBlock?>(null) }
    var historyIndex by remember { mutableStateOf(-1) }

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun copy(text: String, what: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar("$what הועתק") }
    }

    val history = remember(blocks) { blocks.filter { it.command.isNotBlank() }.map { it.command } }

    val shown = remember(blocks, query, searchOpen) {
        if (!searchOpen || query.isBlank()) blocks
        else blocks.filter {
            it.output.contains(query, true) || it.command.contains(query, true)
        }
    }
    val matchCount = remember(shown, query) {
        if (query.isBlank()) 0 else shown.sumOf { countMatches(it.output, query) + countMatches(it.command, query) }
    }

    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty() && !searchOpen) listState.animateScrollToItem(shown.lastIndex.coerceAtLeast(0))
    }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 8.dp)
                        .glass(radius = 22.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowForward, contentDescription = "חזרה")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                server.name.ifBlank { server.host },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        StatusPill(state)
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(
                                if (searchOpen) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                contentDescription = "חיפוש"
                            )
                        }
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "עוד")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("העתקת כל המסוף") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = {
                                    menu = false
                                    copy(blocks.joinToString("\n\n") { it.asText() }, "כל המסוף")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("ניקוי המסך") },
                                leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
                                onClick = { menu = false; session.clearBlocks() }
                            )
                            DropdownMenuItem(
                                text = { Text("חיבור מחדש") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = { menu = false; session.onNetworkChanged(true); session.connect() }
                            )
                            DropdownMenuItem(
                                text = { Text("סגירת החיבור") },
                                leadingIcon = { Icon(Icons.Rounded.LinkOff, null) },
                                onClick = { menu = false; session.disconnect() }
                            )
                        }
                    }

                    AnimatedVisibility(visible = searchOpen) {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("חיפוש מילה בכל הפלט…") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Rounded.Close, "ניקוי")
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            )
                            if (query.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "נמצאו $matchCount תוצאות ב-${shown.size} פקודות",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            "${server.user}@${server.host}  ·  $cwd",
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            },
            bottomBar = {
                CommandBar(
                    input = input,
                    onInput = { input = it; historyIndex = -1 },
                    enabled = state == ConnState.Connected,
                    onSend = {
                        val cmd = input.trim()
                        if (cmd.isNotEmpty()) {
                            session.run(cmd)
                            input = ""
                            historyIndex = -1
                            searchOpen = false
                        }
                    },
                    onCtrlC = { session.sendCtrlC() },
                    onHistory = {
                        if (history.isNotEmpty()) {
                            historyIndex = (historyIndex + 1).coerceAtMost(history.lastIndex)
                            input = history[history.lastIndex - historyIndex]
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                    start = 12.dp, end = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { block ->
                    BlockCard(
                        block = block,
                        highlight = if (searchOpen) query else "",
                        onCopyOutput = { copy(block.output, "הפלט") },
                        onCopyCommand = { copy(block.command, "הפקודה") },
                        onCopyAll = { copy(block.asText(), "הפקודה והפלט") },
                        onSearchInside = { blockSearch = block },
                        onRerun = { session.run(block.command) },
                        onMore = { sheetBlock = block }
                    )
                }
                if (shown.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (query.isNotBlank()) "לא נמצאו תוצאות עבור \"$query\""
                                else "כתבו פקודה למטה כדי להתחיל",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    blockSearch?.let { b ->
        BlockSearchSheet(
            block = b,
            onDismiss = { blockSearch = null },
            onCopyLine = { copy(it, "השורה") },
            onCopyAllMatches = { copy(it, "כל השורות שנמצאו") }
        )
    }

    sheetBlock?.let { b ->
        BlockActionsSheet(
            block = b,
            onDismiss = { sheetBlock = null },
            onCopyOutput = { copy(b.output, "הפלט"); sheetBlock = null },
            onCopyCommand = { copy(b.command, "הפקודה"); sheetBlock = null },
            onCopyAll = { copy(b.asText(), "הפקודה והפלט"); sheetBlock = null },
            onCopyLast = { copy(b.output.lines().takeLast(20).joinToString("\n"), "20 השורות האחרונות"); sheetBlock = null },
            onSearch = { blockSearch = b; sheetBlock = null },
            onRerun = { session.run(b.command); sheetBlock = null },
            onDelete = { session.removeBlock(b.id); sheetBlock = null }
        )
    }
}

private fun CommandBlock.asText(): String =
    if (command.isBlank()) output else "$ $command\n$output"

private fun countMatches(text: String, q: String): Int {
    if (q.isBlank()) return 0
    var i = 0; var n = 0
    while (true) {
        val idx = text.indexOf(q, i, true)
        if (idx < 0) return n
        n++; i = idx + q.length
    }
}

@Composable
fun highlighted(text: String, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (true) {
        val idx = text.indexOf(query, i, true)
        if (idx < 0) { append(text.substring(i)); break }
        append(text.substring(i, idx))
        withStyle(SpanStyle(background = Ios.orange.copy(alpha = 0.45f), fontWeight = FontWeight.Bold)) {
            append(text.substring(idx, idx + query.length))
        }
        i = idx + query.length
    }
}

@Composable
private fun BlockCard(
    block: CommandBlock,
    highlight: String,
    onCopyOutput: () -> Unit,
    onCopyCommand: () -> Unit,
    onCopyAll: () -> Unit,
    onSearchInside: () -> Unit,
    onRerun: () -> Unit,
    onMore: () -> Unit
) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    val lines = remember(block.output) { block.output.lines() }
    val tooLong = lines.size > COLLAPSE_LINES
    val visibleText = remember(block.output, expanded) {
        if (tooLong && !expanded) lines.takeLast(COLLAPSE_LINES).joinToString("\n") else block.output
    }

    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .clickable { onMore() }
            .padding(14.dp)
    ) {
        if (block.command.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        highlighted("$ ${block.command}", highlight),
                        style = MonoStyle.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    block.running -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Ios.orange
                    )
                    block.exitCode == 0 -> Icon(
                        Icons.Rounded.CheckCircle, "הצליח",
                        tint = Ios.green, modifier = Modifier.size(18.dp)
                    )
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, "שגיאה", tint = Ios.red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("קוד ${block.exitCode}", style = MaterialTheme.typography.labelMedium, color = Ios.red)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (block.output.isNotBlank()) {
            if (tooLong && !expanded) {
                TextButton(onClick = { expanded = true }) {
                    Icon(Icons.Rounded.UnfoldMore, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("הצגת כל ${lines.size} השורות")
                }
            }
            SelectionContainer {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        highlighted(visibleText, highlight),
                        style = MonoStyle,
                        color = if (block.isNotice) Ios.orange else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else if (block.running) {
            Text(
                "מריץ…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!block.isNotice) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionChip(Icons.Rounded.ContentCopy, "העתקת הפלט", onCopyOutput)
                ActionChip(Icons.Rounded.Keyboard, "העתקת הפקודה", onCopyCommand)
                ActionChip(Icons.Rounded.SelectAll, "העתקת הכול", onCopyAll)
                ActionChip(Icons.Rounded.ManageSearch, "חיפוש בפלט", onSearchInside)
                ActionChip(Icons.Rounded.Replay, "הרצה מחדש", onRerun)
                Spacer(Modifier.weight(1f))
                block.duration?.let {
                    Text(
                        "${it / 1000}.${(it % 1000) / 100} שנ׳",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(17.dp), tint = Ios.blue)
    }
}

@Composable
private fun CommandBar(
    input: String,
    onInput: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onCtrlC: () -> Unit,
    onHistory: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 28.dp)
            .glass(radius = 26.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onHistory) {
            Icon(Icons.Rounded.History, "פקודה קודמת", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BasicTextFieldRow(input, onInput, enabled, onSend)
        }
        IconButton(onClick = onCtrlC) {
            Icon(Icons.Rounded.Stop, "עצירה (Ctrl+C)", tint = Ios.red)
        }
        IconButton(
            onClick = onSend,
            enabled = enabled && input.isNotBlank(),
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(if (enabled && input.isNotBlank()) Ios.blue else Ios.gray.copy(alpha = 0.3f))
        ) {
            Icon(Icons.Rounded.ArrowUpward, "שליחה", tint = Color.White)
        }
    }
}

@Composable
private fun RowScope.BasicTextFieldRow(
    input: String,
    onInput: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    TextField(
        value = input,
        onValueChange = onInput,
        enabled = enabled,
        placeholder = { Text(if (enabled) "פקודה…" else "מחכה לחיבור…", style = MonoStyle) },
        textStyle = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = false,
        maxLines = 4,
        modifier = Modifier.weight(1f),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() })
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockActionsSheet(
    block: CommandBlock,
    onDismiss: () -> Unit,
    onCopyOutput: () -> Unit,
    onCopyCommand: () -> Unit,
    onCopyAll: () -> Unit,
    onCopyLast: () -> Unit,
    onSearch: () -> Unit,
    onRerun: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 28.dp)) {
            Text(
                block.command.ifBlank { "הודעת מערכת" },
                style = MonoStyle.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
            SheetRow(Icons.Rounded.ContentCopy, "העתקת כל הפלט של הפקודה", onCopyOutput)
            SheetRow(Icons.Rounded.SelectAll, "העתקת הפקודה + הפלט", onCopyAll)
            SheetRow(Icons.Rounded.Keyboard, "העתקת הפקודה בלבד", onCopyCommand)
            SheetRow(Icons.Rounded.VerticalAlignBottom, "העתקת 20 השורות האחרונות", onCopyLast)
            SheetRow(Icons.Rounded.ManageSearch, "חיפוש מילה בתוך הפלט", onSearch)
            if (block.command.isNotBlank()) SheetRow(Icons.Rounded.Replay, "הרצת הפקודה מחדש", onRerun)
            SheetRow(Icons.Rounded.Delete, "מחיקת הבלוק מהמסך", onDelete, Ios.red)
        }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    tint: Color = Ios.blue
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockSearchSheet(
    block: CommandBlock,
    onDismiss: () -> Unit,
    onCopyLine: (String) -> Unit,
    onCopyAllMatches: (String) -> Unit
) {
    var q by remember { mutableStateOf("") }
    val lines = remember(block.output) { block.output.lines() }
    val matches = remember(q, lines) {
        if (q.isBlank()) emptyList()
        else lines.withIndex().filter { it.value.contains(q, true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("חיפוש בתוך הפלט", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("איזו מילה לחפש?") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (q.isBlank()) "${lines.size} שורות בפלט"
                    else "${matches.size} שורות תואמות מתוך ${lines.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (matches.isNotEmpty()) {
                    TextButton(onClick = { onCopyAllMatches(matches.joinToString("\n") { it.value }) }) {
                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("העתקת כל התוצאות")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                matches.forEach { (index, line) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onCopyLine(line) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(38.dp)
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                highlighted(line, q),
                                style = MonoStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Icon(
                            Icons.Rounded.ContentCopy, "העתקת השורה",
                            tint = Ios.blue, modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
