package com.vps.manager.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.vps.manager.data.Server
import com.vps.manager.data.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * חיבור SSH חי לשרת אחד.
 * דואג לשמור על החיבור: keep-alive, זיהוי נפילה, חיבור מחדש אוטומטי
 * והחזרת התיקייה שבה היינו — גם כשמחליפים רשת (Wi-Fi ↔ סלולרי).
 */
class SshSession(
    @Volatile var server: Server,
    private val store: ServerStore?
) {
    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _status = MutableStateFlow("לא מחובר")
    val status: StateFlow<String> = _status

    private val _blocks = MutableStateFlow<List<CommandBlock>>(emptyList())
    val blocks: StateFlow<List<CommandBlock>> = _blocks

    private val _cwd = MutableStateFlow("~")
    val cwd: StateFlow<String> = _cwd

    @Volatile private var conn: Connection? = null
    @Volatile private var shell: Session? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var wanted = false
    private val busy = AtomicBoolean(false)
    private val idGen = AtomicLong(1)
    private var reconnectAttempt = 0

    private val parser = BlockParser()
    private val pending = ArrayDeque<CommandBlock>()

    // ---------------------------------------------------------------- חיבור

    fun connect() {
        wanted = true
        if (_state.value == ConnState.Connecting || _state.value == ConnState.Connected) return
        startConnectThread(false)
    }

    fun disconnect() {
        wanted = false
        teardown()
        _state.value = ConnState.Disconnected
        _status.value = "החיבור נסגר"
    }

    /** נקרא כשהרשת של הטלפון התחלפה או חזרה. */
    fun onNetworkChanged(available: Boolean) {
        if (!wanted) return
        if (!available) {
            _status.value = "אין רשת — מחכה לחיבור"
            return
        }
        when (_state.value) {
            ConnState.Connected -> Thread {
                if (!ping()) scheduleReconnect("הרשת התחלפה")
            }.apply { isDaemon = true }.start()
            ConnState.Failed, ConnState.Disconnected -> {
                reconnectAttempt = 0
                scheduleReconnect("הרשת חזרה")
            }
            ConnState.Reconnecting -> { reconnectAttempt = 0 }
            else -> {}
        }
    }

    private fun ping(): Boolean = try {
        conn?.sendIgnorePacket(); true
    } catch (t: Throwable) { false }

    private fun startConnectThread(isRetry: Boolean) {
        _state.value = if (isRetry) ConnState.Reconnecting else ConnState.Connecting
        _status.value = if (isRetry) "מתחבר מחדש…" else "מתחבר ל-${server.host}…"
        Thread {
            try {
                openConnection()
                reconnectAttempt = 0
                _state.value = ConnState.Connected
                _status.value = "מחובר · ${server.user}@${server.host}"
                if (isRetry) notice("החיבור חזר — ממשיכים מאותו מקום")
                queuedCommand?.let { pendingCmd ->
                    queuedCommand = null
                    run(pendingCmd)
                }
            } catch (t: Throwable) {
                teardown()
                if (!wanted) return@Thread
                val msg = t.message ?: t.javaClass.simpleName
                if (isAuthProblem(t)) {
                    _state.value = ConnState.Failed
                    _status.value = "ההתחברות נדחתה: $msg"
                    notice("ההתחברות נדחתה: $msg")
                } else {
                    scheduleReconnect(msg)
                }
            }
        }.apply { isDaemon = true; name = "ssh-${server.host}" }.start()
    }

    private fun isAuthProblem(t: Throwable): Boolean =
        (t.message ?: "").contains("authentication", true) ||
        (t.message ?: "").contains("אימות")

    private fun scheduleReconnect(reason: String) {
        if (!wanted) return
        teardown()
        _state.value = ConnState.Reconnecting
        reconnectAttempt++
        val delay = minOf(30_000L, 1_000L * (1L shl minOf(reconnectAttempt, 5)))
        _status.value = "החיבור נפל ($reason) — מתחבר מחדש בעוד ${delay / 1000} שנ׳"
        Thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
            if (wanted && _state.value != ConnState.Connected) startConnectThread(true)
        }.apply { isDaemon = true }.start()
    }

    private fun openConnection() {
        val s = server
        val c = Connection(s.host, s.port)
        c.setCompression(true)
        c.connect(object : ServerHostKeyVerifier {
            override fun verifyServerHostKey(
                hostname: String?, port: Int, algo: String?, key: ByteArray?
            ): Boolean {
                val fp = Base64.getEncoder().encodeToString(key ?: ByteArray(0))
                val known = s.hostKey
                if (known.isBlank()) {
                    val updated = s.copy(hostKey = fp)
                    server = updated
                    store?.save(updated)
                    return true
                }
                if (known != fp) {
                    notice("אזהרה: מפתח השרת השתנה! ייתכן שמישהו מתחזה לשרת. מחקו ושמרו את השרת מחדש אם השינוי מוכר לכם.")
                    return false
                }
                return true
            }
        }, 15_000, 20_000)
        c.addConnectionMonitor { cause ->
            if (wanted && conn === c) scheduleReconnect(cause?.message ?: "החיבור נסגר")
        }

        val ok = if (s.usesKey)
            c.authenticateWithPublicKey(s.user, s.privateKey.toCharArray(), s.keyPassphrase.ifBlank { null })
        else
            c.authenticateWithPassword(s.user, s.password)
        if (!ok) throw IllegalStateException("authentication failed — שם משתמש או סיסמה שגויים")

        val sh = c.openSession()
        sh.requestPTY("xterm-256color", 200, 60, 0, 0, null)
        sh.startShell()

        conn = c
        shell = sh
        stdin = sh.stdin
        parser.reset()

        writeLine("stty -echo 2>/dev/null; export TERM=xterm-256color LANG=\${LANG:-C.UTF-8}; unset PROMPT_COMMAND; PS1=''")
        val restore = _cwd.value
        if (restore.isNotBlank() && restore != "~") writeLine("cd ${shellQuote(restore)} 2>/dev/null")
        if (server.startupCommand.isNotBlank()) run(server.startupCommand)

        startReader(sh)
        startKeepAlive(c)
    }

    private fun teardown() {
        closeWorkConnections()
        runCatching { shell?.close() }
        runCatching { conn?.close() }
        shell = null; conn = null; stdin = null
        closeRunningBlock(null)
    }

    // ---------------------------------------------------------------- קריאה

    private fun startReader(sh: Session) {
        val out = com.trilead.ssh2.StreamGobbler(sh.stdout)
        val err = com.trilead.ssh2.StreamGobbler(sh.stderr)
        Thread {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = out.read(buf)
                    if (n < 0) break
                    feed(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Throwable) {
            }
            if (wanted && shell === sh) scheduleReconnect("השרת סגר את החיבור")
        }.apply { isDaemon = true; name = "ssh-out" }.start()

        Thread {
            val buf = ByteArray(8 * 1024)
            try {
                while (true) {
                    val n = err.read(buf)
                    if (n < 0) break
                    feed(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "ssh-err" }.start()
    }

    private fun startKeepAlive(c: Connection) {
        Thread {
            var failures = 0
            while (wanted && conn === c) {
                try { Thread.sleep(20_000) } catch (_: InterruptedException) { return@Thread }
                if (conn !== c) return@Thread
                try {
                    c.sendIgnorePacket()
                    failures = 0
                } catch (_: Throwable) {
                    failures++
                    if (failures >= 2) {
                        if (wanted && conn === c) scheduleReconnect("אין תגובה מהשרת")
                        return@Thread
                    }
                }
            }
        }.apply { isDaemon = true; name = "ssh-keepalive" }.start()
    }

    // ---------------------------------------------------------------- פקודות

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val id = idGen.getAndIncrement()
        val block = CommandBlock(id = id, command = cmd)
        pending.addLast(block)
        _blocks.value = _blocks.value + block
        val begin = "printf '\\001VPSB_%s\\n' $id"
        val end = "printf '\\002VPSE_%s_%s_%s\\n' $id \"\$__VRC\" \"\$PWD\""
        val sent = sendRaw("$begin; $cmd; __VRC=\$?; $end\n")
        if (!sent) {
            // החיבור נפל לפני שהפקודה יצאה — שומרים אותה ושולחים שוב אחרי החיבור
            queuedCommand = cmd
            _blocks.value = _blocks.value.map {
                if (it.id == id) it.copy(
                    running = false,
                    output = "החיבור נפל לפני שהפקודה נשלחה. היא תרוץ שוב אוטומטית ברגע שהחיבור יחזור.",
                    finishedAt = System.currentTimeMillis()
                ) else it
            }
        }
    }

    @Volatile private var queuedCommand: String? = null

    /** שליחת טקסט גולמי — למשל סיסמה לשאלה של sudo, או Ctrl+C. */
    fun sendRaw(text: String): Boolean = try {
        val out = stdin ?: throw IllegalStateException("אין ערוץ פתוח")
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        true
    } catch (_: Throwable) {
        scheduleReconnect("לא ניתן לשלוח לשרת")
        false
    }

    fun sendCtrlC() = sendRaw("\u0003")

    private fun writeLine(line: String) = sendRaw(line + "\n")

    /** החיבור הפתוח — לשימוש פעולות קבצים (SFTP/exec) על אותו ערוץ מאובטח. */
    fun connection(): Connection? = conn

    // חיבורי עבודה נפרדים לקבצים ולמדיה.
    // חשוב: אף פעולת קבצים לא רצה על חיבור המסוף — סגירת ערוץ באמצע שטף פלט
    // (למשל find שעדיין רץ) מפילה את כל החיבור, ואיתו את המסוף.
    private val workConns = arrayOfNulls<Connection>(WORK_CONNECTIONS)

    @Synchronized
    private fun workConnection(index: Int): Connection? {
        workConns[index]?.let { existing ->
            if (runCatching { existing.sendIgnorePacket(); true }.getOrDefault(false)) return existing
            SftpPool.release(existing)
            runCatching { existing.close() }
            workConns[index] = null
        }
        val s = server
        return runCatching {
            val c = Connection(s.host, s.port)
            c.connect({ _, _, _, _ -> true }, 15_000, 20_000)
            val ok = if (s.usesKey)
                c.authenticateWithPublicKey(s.user, s.privateKey.toCharArray(), s.keyPassphrase.ifBlank { null })
            else
                c.authenticateWithPassword(s.user, s.password)
            if (!ok) { c.close(); null } else { workConns[index] = c; c }
        }.getOrNull()
    }

    /** חיבור לפעולות קבצים קצרות (סריקה, תמונות ממוזערות, מחיקה). */
    fun fileConnection(): Connection? = workConnection(0)

    /** כל חיבורי העבודה — להזרמת וידאו בכמה ערוצים במקביל. */
    fun transferConnections(): List<Connection> =
        (0 until WORK_CONNECTIONS).mapNotNull { workConnection(it) }

    @Synchronized
    private fun closeWorkConnections() {
        workConns.indices.forEach { i ->
            workConns[i]?.let { SftpPool.release(it) }
            runCatching { workConns[i]?.close() }
            workConns[i] = null
        }
    }

    fun clearBlocks() { _blocks.value = emptyList() }

    fun removeBlock(id: Long) { _blocks.value = _blocks.value.filterNot { it.id == id } }

    private fun notice(text: String) {
        _blocks.value = _blocks.value + CommandBlock(
            id = idGen.getAndIncrement(), command = "", output = text,
            running = false, exitCode = 0, isNotice = true,
            finishedAt = System.currentTimeMillis()
        )
    }

    // ---------------------------------------------------------------- פענוח

    private fun feed(raw: String) = synchronized(parser) {
        parser.feed(raw, object : BlockParser.Sink {
            override fun onBanner(text: String) {
                val clean = Ansi.clean(text)
                if (clean.isBlank()) return
                val list = _blocks.value
                val last = list.lastOrNull()
                if (last != null && last.isNotice && last.running) {
                    _blocks.value = list.dropLast(1) + last.copy(output = last.output + clean)
                } else {
                    _blocks.value = list + CommandBlock(
                        id = idGen.getAndIncrement(), command = "", output = clean,
                        running = true, isNotice = true
                    )
                }
            }

            override fun onCommandStart(id: Long) {
                closeNoticeStream()
                busy.set(true)
            }

            override fun onOutput(id: Long, text: String) {
                val clean = Ansi.clean(text)
                if (clean.isEmpty()) return
                _blocks.value = _blocks.value.map {
                    if (it.id == id) it.copy(output = it.output + clean) else it
                }
            }

            override fun onCommandEnd(id: Long, rc: Int, pwd: String) {
                if (pwd.isNotBlank()) _cwd.value = pwd
                busy.set(false)
                pending.removeAll { it.id == id }
                _blocks.value = _blocks.value.map {
                    if (it.id == id) it.copy(
                        running = false, exitCode = rc,
                        output = it.output.trimEnd('\n'),
                        finishedAt = System.currentTimeMillis()
                    ) else it
                }
            }
        })
    }

    private fun closeNoticeStream() {
        val list = _blocks.value
        val last = list.lastOrNull() ?: return
        if (last.isNotice && last.running) {
            _blocks.value = list.dropLast(1) +
                last.copy(running = false, output = last.output.trim(), finishedAt = System.currentTimeMillis())
        }
    }

    private fun closeRunningBlock(rc: Int?) {
        _blocks.value = _blocks.value.map {
            if (it.running) it.copy(running = false, exitCode = rc, finishedAt = System.currentTimeMillis()) else it
        }
    }

    companion object {
        /** חיבורי עבודה ייעודיים לקבצים ולמדיה, בנפרד מהמסוף. */
        const val WORK_CONNECTIONS = 3

        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
