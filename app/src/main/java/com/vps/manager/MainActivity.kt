package com.vps.manager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.vps.manager.data.Server
import com.vps.manager.data.ServerStore
import com.vps.manager.service.SshForegroundService
import com.vps.manager.ssh.SshManager
import com.vps.manager.ui.ServersScreen
import com.vps.manager.ui.TerminalScreen
import com.vps.manager.ui.VpsTheme

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        val store = ServerStore.get(this)

        setContent {
            VpsTheme {
                val servers by store.servers.collectAsState()
                var openId by remember { mutableStateOf<String?>(null) }
                val context = LocalContext.current

                val open = servers.firstOrNull { it.id == openId }

                if (open == null) {
                    ServersScreen(
                        servers = servers,
                        onOpen = { server ->
                            SshForegroundService.start(context)
                            val session = SshManager.session(context, server)
                            session.connect()
                            openId = server.id
                        },
                        onSave = { store.save(it) },
                        onDelete = { server ->
                            SshManager.close(server.id)
                            store.delete(server.id)
                        }
                    )
                } else {
                    val session = remember(open.id) { SshManager.session(context, open) }
                    BackHandler { openId = null }
                    TerminalScreen(
                        server = open,
                        session = session,
                        onBack = { openId = null }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** נשמר לשימוש עתידי: פתיחת שרת ישירות מהתראה. */
fun Server.displayName(): String = name.ifBlank { host }
