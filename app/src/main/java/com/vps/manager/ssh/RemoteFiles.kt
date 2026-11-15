package com.vps.manager.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.StreamGobbler
import java.io.File
import java.io.FileOutputStream

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

    fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    /** מריץ פקודה קצרה ומחזיר את הפלט המלא. */
    fun exec(conn: Connection, command: String, timeoutMs: Long = 120_000): String {
        val session = conn.openSession()
        try {
            session.execCommand(command)
            val out = StreamGobbler(session.stdout)
            val sb = StringBuilder()
            val buf = ByteArray(16 * 1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val n = out.read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            return sb.toString()
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * סורק את השרת ומחזיר קבצים מהסוג המבוקש, הגדולים ביותר קודם.
     * מוגבל למערכת הקבצים הראשית כדי לא להיתקע על תיקיות מערכת.
     */
    fun scan(
        conn: Connection,
        kind: FileKind,
        root: String = "/",
        minSizeKb: Long = if (kind == FileKind.Largest) 10_240 else 4,
        limit: Int = 500
    ): List<RemoteFile> {
        val nameFilter = if (kind.patterns.isEmpty()) "" else
            kind.patterns.joinToString(" -o ") { "-iname '*.$it'" }.let { "\\( $it \\) " }
        val base = "find ${quote(root)} -xdev -type f $nameFilter-size +${minSizeKb}k"
        val gnu = "$base -printf '%s\\t%T@\\t%p\\n' 2>/dev/null | sort -t'\\t' -k1,1nr | head -n $limit"
        var raw = exec(conn, gnu)
        if (raw.isBlank()) {
            val busybox = "$base -print0 2>/dev/null | xargs -0 -r stat -c '%s\\t%Y\\t%n' 2>/dev/null | " +
                "sort -t'\\t' -k1,1nr | head -n $limit"
            raw = exec(conn, busybox)
        }
        return parse(raw)
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

    private fun parse(raw: String): List<RemoteFile> = raw.lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val parts = line.split("\t")
        if (parts.size < 3) return@mapNotNull null
        val size = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
        val mtime = parts[1].trim().substringBefore('.').toLongOrNull() ?: 0L
        val path = parts.subList(2, parts.size).joinToString("\t").trim()
        if (path.isBlank()) null else RemoteFile(path, size, mtime * 1000L)
    }

    fun delete(conn: Connection, paths: List<String>): String =
        exec(conn, "rm -f -- " + paths.joinToString(" ") { quote(it) } + " && echo OK")

    /** הורדת קובץ מהשרת עם התקדמות, בשביל צפייה בתוך האפליקציה. */
    fun download(
        conn: Connection,
        remote: RemoteFile,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val sftp = SFTPv3Client(conn)
        try {
            val handle = sftp.openFileRO(remote.path)
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { fos ->
                val buf = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    if (isCancelled()) return false
                    val n = sftp.read(handle, offset, buf, 0, buf.size)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    offset += n
                    onProgress(offset, remote.size)
                }
            }
            runCatching { sftp.closeFile(handle) }
            return true
        } finally {
            runCatching { sftp.close() }
        }
    }
}

fun Long.humanSize(): String = when {
    this >= 1_073_741_824 -> String.format("%.2f GB", this / 1_073_741_824.0)
    this >= 1_048_576 -> String.format("%.1f MB", this / 1_048_576.0)
    this >= 1024 -> String.format("%.0f KB", this / 1024.0)
    else -> "$this B"
}
