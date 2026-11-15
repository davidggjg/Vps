package com.vps.manager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** שומר את רשימת השרתים בקובץ מוצפן על המכשיר. */
class ServerStore(private val context: Context) {

    private val file = File(context.filesDir, "servers.enc")
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers

    init {
        _servers.value = runCatching { load() }.getOrElse { emptyList() }
    }

    private fun load(): List<Server> {
        if (!file.exists()) return emptyList()
        val text = Crypto.decrypt(file.readBytes()).toString(Charsets.UTF_8)
        val arr = JSONArray(text)
        return (0 until arr.length()).map { Server.fromJson(arr.getJSONObject(it)) }
    }

    private fun persist(list: List<Server>) {
        val json = list.toJsonArray().toString().toByteArray(Charsets.UTF_8)
        file.writeBytes(Crypto.encrypt(json))
    }

    fun save(server: Server) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        _servers.value = list
        runCatching { persist(list) }
    }

    fun delete(id: String) {
        val list = _servers.value.filterNot { it.id == id }
        _servers.value = list
        runCatching { persist(list) }
    }

    fun byId(id: String): Server? = _servers.value.firstOrNull { it.id == id }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        @Volatile private var instance: ServerStore? = null
        fun get(context: Context): ServerStore =
            instance ?: synchronized(this) {
                instance ?: ServerStore(context.applicationContext).also { instance = it }
            }
    }
}
