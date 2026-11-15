package com.vps.manager.ssh

import android.content.Context
import com.vps.manager.data.Server
import com.vps.manager.data.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** מחזיק את כל החיבורים הפתוחים, גם כשהמסך סגור. */
object SshManager {

    private val sessions = ConcurrentHashMap<String, SshSession>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active

    fun session(context: Context, server: Server): SshSession {
        val existing = sessions[server.id]
        if (existing != null) {
            existing.server = server
            return existing
        }
        val created = SshSession(server, ServerStore.get(context))
        sessions[server.id] = created
        _active.value = sessions.keys.toSet()
        return created
    }

    fun existing(id: String): SshSession? = sessions[id]

    fun close(id: String) {
        sessions.remove(id)?.disconnect()
        _active.value = sessions.keys.toSet()
    }

    fun closeAll() {
        sessions.values.forEach { it.disconnect() }
        sessions.clear()
        _active.value = emptySet()
    }

    fun onNetworkChanged(available: Boolean) =
        sessions.values.forEach { it.onNetworkChanged(available) }

    fun connectedCount(): Int = sessions.values.count { it.state.value == ConnState.Connected }

    fun anyWanted(): Boolean = sessions.isNotEmpty()
}
