package com.vps.manager.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.StreamGobbler
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** קובץ שנמצא על השרת. */
data class RemoteFile(
    val path: String,
    val size: Long,
    val modified: Long
) {
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val folder: String get() = path.substringBeforeLast('/', "/")
}

enum class FileKind(val title: String) {
    Images("תמונות"),
    Videos("סרטונים"),
    Audio("שמע"),
    Documents("מסמכים"),
    Largest("הקבצים הגדולים");

    val patterns: List<String>
        get() = when (this) {
            Images -> listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "tiff")
            Videos -> listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "mpg", "mpeg")
            Audio -> listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma")
            Documents -> listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "zip", "tar", "gz", "rar", "7z")
            Largest -> emptyList()
        }
}

/** פעולות קבצים על השרת: סריקה, מחיקה והורדה — בערוץ נפרד, בלי להפריע למסוף. */
object RemoteFiles {

    /** ספריית ה-SFTP מגבילה כל קריאה ל-32KB — לכן כל קריאה מתבצעת במנות. */
    const val MAX_SFTP_READ = 32 * 1024

    fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    /** מריץ פקודה ומחזיר את הפלט המלא. קורא גם stderr כדי שהפקודה לא תיתקע. */
    fun exec(conn: Connection, command: String, timeoutMs: Long = 180_000): String {
        val session = conn.openSession()
        try {
            session.execCommand(command)
            val out = StreamGobbler(session.stdout)
            val err = StreamGobbler(session.stderr)
            val drain = Thread {
                val b = ByteArray(8192)
                try { while (err.read(b) >= 0) { /* נזרק */ } } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }

            val sb = StringBuilder()
            val buf = ByteArray(64 * 1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val n = out.read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (System.currentTimeMillis() > deadline) break
            }
            runCatching { drain.interrupt() }
            return sb.toString()
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * רק מערכות קבצים וירטואליות של הליבה — אין בהן קבצים אמיתיים,
     * והן מה שגורם לסריקה להיתקע. כל שאר השרת נסרק במלואו.
     */
    private val PRUNED = listOf("/proc", "/sys", "/dev", "/run", "/var/lib/lxcfs")

    private fun pruneExpr(root: String): String {
        val inside = PRUNED.filter { root == "/" || it.startsWith(root) }
        if (inside.isEmpty()) return ""
        return inside.joinToString(" -o ") { "-path ${quote(it)}" }.let { "\\( $it \\) -prune -o " }
    }

    private fun findCommand(kind: FileKind, root: String, minSizeKb: Long, limit: Int): String {
        val nameFilter = if (kind.patterns.isEmpty()) "" else
            kind.patterns.joinToString(" -o ") { "-iname '*.$it'" }.let { "\\( $it \\) " }
        return "find ${quote(root)} ${pruneExpr(root)}-type f $nameFilter-size +${minSizeKb}k " +
            "-printf '%s\\t%T@\\t%p\\n' 2>/dev/null | head -n $limit"
    }

    /**
     * סריקה זורמת: מחזירה קבצים תוך כדי החיפוש, כך שרואים תוצאות מיד
     * ולא מחכים שהשרת יסיים לעבור על הכול.
     */
    fun scanStream(
        conn: Connection,
        kind: FileKind,
        root: String = "/",
        minSizeKb: Long = if (kind == FileKind.Largest) 20_480 else 8,
        limit: Int = 20_000,
        isCancelled: () -> Boolean,
        onBatch: (List<RemoteFile>) -> Unit
    ) {
        val session = conn.openSession()
        try {
            session.execCommand(findCommand(kind, root, minSizeKb, limit))
            val out = StreamGobbler(session.stdout)
            val err = StreamGobbler(session.stderr)
            Thread {
                val b = ByteArray(8192)
                try { while (err.read(b) >= 0) { /* נזרק */ } } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }

            val buf = ByteArray(64 * 1024)
            val pending = StringBuilder()
            val batch = ArrayList<RemoteFile>(64)
            var lastFlush = System.currentTimeMillis()

            while (!isCancelled()) {
                val n = out.read(buf)
                if (n < 0) break
                pending.append(String(buf, 0, n, Charsets.UTF_8))
                var nl = pending.indexOf("\n")
                while (nl >= 0) {
                    parseLine(pending.substring(0, nl))?.let { batch.add(it) }
                    pending.delete(0, nl + 1)
                    nl = pending.indexOf("\n")
                }
                val now = System.currentTimeMillis()
                if (batch.isNotEmpty() && (batch.size >= 120 || now - lastFlush > 400)) {
                    onBatch(ArrayList(batch)); batch.clear(); lastFlush = now
                }
            }
            parseLine(pending.toString())?.let { batch.add(it) }
            if (batch.isNotEmpty()) onBatch(batch)
        } finally {
            runCatching { session.close() }
        }
    }

    /** סריקה רגילה (לא זורמת) — מחזירה הכול בבת אחת. */
    fun scan(
        conn: Connection,
        kind: FileKind,
        root: String = "/",
        minSizeKb: Long = if (kind == FileKind.Largest) 20_480 else 8,
        limit: Int = 20_000
    ): List<RemoteFile> {
        var raw = exec(conn, findCommand(kind, root, minSizeKb, limit))
        if (raw.isBlank()) {
            val nameFilter = if (kind.patterns.isEmpty()) "" else
                kind.patterns.joinToString(" -o ") { "-iname '*.$it'" }.let { "\\( $it \\) " }
            raw = exec(
                conn,
                "find ${quote(root)} ${pruneExpr(root)}-type f $nameFilter-size +${minSizeKb}k " +
                    "-print0 2>/dev/null | xargs -0 -r stat -c '%s\\t%Y\\t%n' 2>/dev/null | head -n $limit"
            )
        }
        return parse(raw).sortedByDescending { it.size }
    }

    /** קבצים בתיקייה אחת (דפדפן הקבצים). */
    fun listFolder(conn: Connection, folder: String): Pair<List<String>, List<RemoteFile>> {
        val dirs = exec(conn, "find ${quote(folder)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | head -n 400")
            .lines().filter { it.isNotBlank() }
        val files = parse(
            exec(
                conn,
                "find ${quote(folder)} -mindepth 1 -maxdepth 1 -type f -printf '%s\\t%T@\\t%p\\n' 2>/dev/null | head -n 600"
            )
        )
        return dirs to files
    }

    private fun parse(raw: String): List<RemoteFile> = raw.lines().mapNotNull { parseLine(it) }

    private fun parseLine(line: String): RemoteFile? {
        if (line.isBlank()) return null
        val parts = line.split("\t")
        if (parts.size < 3) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        val mtime = parts[1].trim().substringBefore('.').toLongOrNull() ?: 0L
        val path = parts.subList(2, parts.size).joinToString("\t").trim()
        return if (path.isBlank()) null else RemoteFile(path, size, mtime * 1000L)
    }

    fun delete(conn: Connection, paths: List<String>): String =
        exec(conn, "rm -f -- " + paths.joinToString(" ") { quote(it) } + " && echo OK")

    /** מריץ פקודה ומחזיר פלט בינארי (למשל תמונה שה-ffmpeg של השרת מייצר). */
    fun execBinary(conn: Connection, command: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        val session = conn.openSession()
        try {
            session.execCommand(command)
            val out = StreamGobbler(session.stdout)
            val err = StreamGobbler(session.stderr)
            Thread {
                val b = ByteArray(8192)
                try { while (err.read(b) >= 0) { /* נזרק */ } } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (bos.size() < maxBytes) {
                val n = out.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally {
            runCatching { session.close() }
        }
    }

    /** קובצי כתוביות שנמצאים ליד הסרט ונושאים את אותו שם. */
    fun siblingSubtitles(conn: Connection, remote: RemoteFile): List<String> {
        val base = remote.name.substringBeforeLast('.')
        val out = exec(
            conn,
            "find ${quote(remote.folder)} -maxdepth 1 -type f " +
                "\\( -iname '*.srt' -o -iname '*.vtt' -o -iname '*.ass' -o -iname '*.ssa' \\) 2>/dev/null | head -n 20",
            20_000
        )
        val all = out.lines().filter { it.isNotBlank() }
        val same = all.filter { it.substringAfterLast('/').startsWith(base, ignoreCase = true) }
        return if (same.isNotEmpty()) same else all
    }

    private val ffmpegCache = ConcurrentHashMap<Connection, Boolean>()

    /** בודק פעם אחת אם יש ffmpeg בשרת — כך אפשר ליצור תמונות ממוזערות לסרטונים. */
    fun hasFfmpeg(conn: Connection): Boolean = ffmpegCache.getOrPut(conn) {
        exec(conn, "command -v ffmpeg >/dev/null 2>&1 && echo yes || echo no", 15_000).contains("yes")
    }

    /** תמונה ממוזערת לתמונה גדולה — נוצרת בשרת, כך שעוברים בייתים בודדים. */
    fun imageThumbnail(conn: Connection, remote: RemoteFile, width: Int = 240): ByteArray? {
        if (!hasFfmpeg(conn)) return null
        val cmd = "ffmpeg -nostdin -loglevel quiet -i ${quote(remote.path)} " +
            "-vf scale=$width:-2 -frames:v 1 -f mjpeg - 2>/dev/null"
        val bytes = execBinary(conn, cmd, 512 * 1024)
        return if (bytes.size > 512) bytes else null
    }

    /** תמונה ממוזערת לסרטון — נוצרת בשרת ומגיעה כ-JPEG קטן, בלי להוריד את הסרט. */
    fun videoThumbnail(conn: Connection, remote: RemoteFile): ByteArray? {
        if (!hasFfmpeg(conn)) return null
        val cmd = "ffmpeg -nostdin -loglevel quiet -ss 20 -i ${quote(remote.path)} " +
            "-frames:v 1 -vf scale=320:-2 -f mjpeg - 2>/dev/null"
        val bytes = execBinary(conn, cmd, 1024 * 1024)
        return if (bytes.size > 512) bytes else null
    }

    /**
     * קריאת קובץ שלם לזיכרון בלבד — בלי לשמור שום דבר בטלפון.
     * נקרא במקביל בכמה ערוצים כדי שתמונה תיפתח מיד.
     */
    fun readToMemory(
        conn: Connection,
        remote: RemoteFile,
        maxBytes: Long = 40L * 1024 * 1024,
        parallel: Int = 3,
        isCancelled: () -> Boolean = { false }
    ): ByteArray? {
        val size = remote.size.coerceAtMost(maxBytes).toInt()
        if (size <= 0) return null
        val out = ByteArray(size)
        val slice = (size + parallel - 1) / parallel
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val threads = (0 until parallel).map { i ->
            Thread {
                val from = i * slice
                val to = minOf(size, from + slice)
                if (from >= to) return@Thread
                try {
                    SftpPool.withClient(conn) { c ->
                        val handle = c.openFileRO(remote.path)
                        try {
                            var pos = from
                            while (pos < to && !isCancelled()) {
                                val want = minOf(MAX_SFTP_READ, to - pos)
                                val n = c.read(handle, pos.toLong(), out, pos, want)
                                if (n < 0) break
                                pos += n
                            }
                        } finally {
                            runCatching { c.closeFile(handle) }
                        }
                    }
                } catch (t: Throwable) {
                    errors.set(t)
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { runCatching { it.join() } }
        errors.get()?.let { throw it }
        return if (isCancelled()) null else out
    }

    /** הורדת קובץ מהשרת עם התקדמות, בשביל צפייה בתוך האפליקציה. */
    fun download(
        conn: Connection,
        remote: RemoteFile,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        return SftpPool.withClient(conn) { sftp ->
            val handle = sftp.openFileRO(remote.path)
            try {
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { fos ->
                    val buf = ByteArray(MAX_SFTP_READ)
                    var offset = 0L
                    while (true) {
                        if (isCancelled()) return@withClient false
                        val n = sftp.read(handle, offset, buf, 0, buf.size)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        offset += n
                        onProgress(offset, remote.size)
                    }
                }
                true
            } finally {
                runCatching { sftp.closeFile(handle) }
            }
        }
    }
}

fun Long.humanSize(): String = when {
    this >= 1_073_741_824 -> String.format("%.2f GB", this / 1_073_741_824.0)
    this >= 1_048_576 -> String.format("%.1f MB", this / 1_048_576.0)
    this >= 1024 -> String.format("%.0f KB", this / 1024.0)
    else -> "$this B"
}
