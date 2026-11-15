package com.vps.manager.ssh

/** ניקוי קודי צבע ובקרה כדי שהפלט יהיה נקי להצגה, לחיפוש ולהעתקה. */
object Ansi {
    private val csi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val osc = Regex("\u001B][^\u0007\u001B]*(\u0007|\u001B\\\\)")
    private val other = Regex("\u001B[()#][0-9A-Za-z]|\u001B[=><ncDEHMZ78]")
    private val junk = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    fun clean(raw: String): String {
        var s = raw
        s = csi.replace(s, "")
        s = osc.replace(s, "")
        s = other.replace(s, "")
        s = s.replace("\r\n", "\n").replace("\r", "\n")
        s = junk.replace(s, "")
        return s
    }
}

enum class ConnState { Disconnected, Connecting, Connected, Reconnecting, Failed }

data class CommandBlock(
    val id: Long,
    val command: String,
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = true,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val isNotice: Boolean = false
) {
    val duration: Long? get() = finishedAt?.minus(startedAt)
    val succeeded: Boolean get() = exitCode == 0
}
