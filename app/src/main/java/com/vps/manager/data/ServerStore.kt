package com.vps.manager.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
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

    private fun encryptedFile(): EncryptedFile {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context, file, key, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun load(): List<Server> {
        if (!file.exists()) return emptyList()
        val text = encryptedFile().openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { Server.fromJson(arr.getJSONObject(it)) }
    }

    private fun persist(list: List<Server>) {
        if (file.exists()) file.delete()
        encryptedFile().openFileOutput().use {
            it.write(list.toJsonArray().toString().toByteArray(Charsets.UTF_8))
        }
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
