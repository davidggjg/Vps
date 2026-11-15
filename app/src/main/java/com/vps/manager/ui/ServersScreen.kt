package com.vps.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vps.manager.data.Server
import com.vps.manager.data.ServerStore
import com.vps.manager.ssh.ConnState
import com.vps.manager.ssh.SshManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    servers: List<Server>,
    onOpen: (Server) -> Unit,
    onSave: (Server) -> Unit,
    onDelete: (Server) -> Unit
) {
    var editing by remember { mutableStateOf<Server?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    GlassBackground {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 12.dp)) {
                Text(
                    "השרתים שלי",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "חיבור יציב שנשמר גם בהחלפת רשת",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (servers.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.Dns, null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "עוד לא הוספתם שרת.\nלחצו על הכפתור למטה כדי להוסיף את הראשון.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            onOpen = { onOpen(server) },
                            onEdit = { editing = server; showEditor = true },
                            onDelete = { onDelete(server) }
                        )
                    }
                }
            }
        }

        Button(
            onClick = { editing = null; showEditor = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .height(54.dp)
                .fillMaxWidth(0.72f),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ios.blue)
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("הוספת שרת", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showEditor) {
        ServerEditorSheet(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = {
                onSave(it)
                showEditor = false
            }
        )
    }
}

@Composable
private fun ServerCard(
    server: Server,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val session = SshManager.existing(server.id)
    val state = session?.state?.collectAsState()?.value ?: ConnState.Disconnected
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .clickable { onOpen() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    server.name.ifBlank { server.host },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        "${server.user}@${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(state)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpen) {
                Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("פתיחת מסוף")
            }
            TextButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("עריכה")
            }
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Ios.red)
            ) {
                Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("מחיקה")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק את השרת?") },
            text = { Text("השרת \"${server.name.ifBlank { server.host }}\" יימחק מהמכשיר, כולל פרטי ההתחברות.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("מחיקה", color = Ios.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("ביטול") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorSheet(
    initial: Server?,
    onDismiss: () -> Unit,
    onSave: (Server) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "root") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var key by remember { mutableStateOf(initial?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(initial?.keyPassphrase ?: "") }
    var startup by remember { mutableStateOf(initial?.startupCommand ?: "") }
    var useKey by remember { mutableStateOf(initial?.usesKey ?: false) }
    var showPassword by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (initial == null) "שרת חדש" else "עריכת שרת",
                style = MaterialTheme.typography.headlineSmall
            )

            Field(name, { name = it }, "שם לתצוגה", "לדוגמה: שרת ראשי")
            Field(host, { host = it }, "כתובת / IP", "example.com או 1.2.3.4")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Field(port, { port = it.filter { c -> c.isDigit() } }, "פורט", "22", KeyboardType.Number)
                }
                Box(Modifier.weight(2f)) {
                    Field(user, { user = it }, "שם משתמש", "root")
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !useKey,
                    onClick = { useKey = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("סיסמה") }
                SegmentedButton(
                    selected = useKey,
                    onClick = { useKey = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("מפתח SSH") }
            }

            if (useKey) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("מפתח פרטי (PEM)") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                Field(passphrase, { passphrase = it }, "סיסמת המפתח (אם יש)", "")
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("סיסמה") },
                    singleLine = true,
                    visualTransformation = if (showPassword)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "הצגת סיסמה"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }

            Field(startup, { startup = it }, "פקודת פתיחה (לא חובה)", "לדוגמה: cd /var/www")

            Text(
                "הפרטים נשמרים מוצפנים על המכשיר בלבד ולא נשלחים לשום מקום.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    onSave(
                        Server(
                            id = initial?.id ?: ServerStore.newId(),
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 22,
                            user = user.trim().ifBlank { "root" },
                            password = if (useKey) "" else password,
                            privateKey = if (useKey) key.trim() else "",
                            keyPassphrase = if (useKey) passphrase else "",
                            hostKey = if (initial?.host == host.trim()) initial?.hostKey.orEmpty() else "",
                            startupCommand = startup.trim()
                        )
                    )
                },
                enabled = host.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ios.blue)
            ) { Text("שמירה", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder, color = Color.Gray) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal)
    )
}
