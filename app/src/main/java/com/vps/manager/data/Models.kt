package com.vps.manager.data

import org.json.JSONArray
import org.json.JSONObject

data class Server(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val hostKey: String = "",
    val startupCommand: String = ""
) {
    val usesKey: Boolean get() = privateKey.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port)
        put("user", user); put("password", password); put("privateKey", privateKey)
        put("keyPassphrase", keyPassphrase); put("hostKey", hostKey)
        put("startupCommand", startupCommand)
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.optString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user", "root"),
            password = o.optString("password"),
            privateKey = o.optString("privateKey"),
            keyPassphrase = o.optString("keyPassphrase"),
            hostKey = o.optString("hostKey"),
            startupCommand = o.optString("startupCommand")
        )
    }
}

fun List<Server>.toJsonArray(): JSONArray {
    val a = JSONArray()
    forEach { a.put(it.toJson()) }
    return a
}
