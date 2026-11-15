package com.vps.manager.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3FileHandle
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * קורא קובץ מהשרת בכמה ערוצי SFTP שנשארים פתוחים לכל אורך הצפייה.
 *
 * למה דווקא כך — שלוש מגבלות אמיתיות של SSH שנמדדו:
 *  1. חלון הערוץ הוא 30KB, ולכן ערוץ בודד תמיד איטי. הפתרון: כמה ערוצים וכמה חיבורים.
 *  2. כל קריאת SFTP מוגבלת ל-32KB, ולכן כל מקטע נקרא במנות.
 *  3. פתיחה וסגירה מהירה של ערוצים הורגת את כל החיבור, כי תשובה מגיעה
 *     לערוץ שכבר נסגר. לכן הערוצים נפתחים פעם אחת ונשארים פתוחים,
 *     ודילוג בסרט רק מאפס את החלון במקום לסגור ערוצים.
 */
class SftpStreamReader(
    private val connections: List<Connection>,
    private val path: String,
    private val workersPerConnection: Int = 4,
    private val chunkSize: Int = 256 * 1024,
    private val maxAheadBytes: Long = 48L * 1024 * 1024
) {
    val fileSize: Long

    private val chunks = ConcurrentHashMap<Long, ByteArray>()
    private val nextToFetch = AtomicLong(0)
    private val generation = AtomicInteger(0)
    private val threads = ArrayList<Thread>()

    @Volatile private var running = true
    @Volatile private var position = 0L
    @Volatile private var lastError: Throwable? = null

    init {
        require(connections.isNotEmpty()) { "אין חיבור לשרת" }
        val probe = SFTPv3Client(connections[0])
        fileSize = try {
            probe.stat(path).size ?: 0L
        } finally {
            runCatching { probe.close() }
        }
        if (fileSize <= 0) throw IOException("הקובץ ריק או לא נגיש")

        connections.forEachIndexed { ci, conn ->
            repeat(workersPerConnection) { wi ->
                Thread { workerLoop(conn) }.apply {
                    isDaemon = true
                    name = "sftp-reader-$ci-$wi"
                    threads.add(this)
                    start()
                }
            }
        }
    }

    /** מעביר את נקודת הקריאה — בלי לגעת בערוצים הפתוחים. */
    @Synchronized
    fun seekTo(pos: Long) {
        generation.incrementAndGet()
        chunks.clear()
        position = pos.coerceIn(0, fileSize)
        nextToFetch.set(position / chunkSize)
        lastError = null
    }

    private fun workerLoop(conn: Connection) {
        var client: SFTPv3Client? = null
        var handle: SFTPv3FileHandle? = null
        try {
            while (running) {
                val gen = generation.get()
                val index = nextToFetch.getAndIncrement()
                val start = index * chunkSize
                if (start >= fileSize) {
                    sleep(15)
                    continue
                }
                // לא רצים רחוק מדי לפני הנגן
                while (running && gen == generation.get() && start - position > maxAheadBytes) sleep(15)
                if (!running) break
                if (gen != generation.get()) continue

                val size = minOf(chunkSize.toLong(), fileSize - start).toInt()
                try {
                    if (client == null) {
                        val c = SFTPv3Client(conn)
                        client = c
                        handle = c.openFileRO(path)
                    }
                    val c = client!!
                    val h = handle!!
                    val data = ByteArray(size)
                    var got = 0
                    while (got < size && running) {
                        val want = minOf(RemoteFiles.MAX_SFTP_READ, size - got)
                        val n = c.read(h, start + got, data, got, want)
                        if (n < 0) break
                        got += n
                    }
                    // מקטע חלקי לעולם לא נמסר לנגן
                    if (got == size && gen == generation.get()) chunks[index] = data
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { client?.close() }
                    client = null
                    handle = null
                    sleep(200)
                }
            }
        } finally {
            val c = client
            val h = handle
            if (c != null && h != null) runCatching { c.closeFile(h) }
            runCatching { c?.close() }
        }
    }

    /** קריאה רציפה מהמיקום הנוכחי. מחזיר -1 בסוף הקובץ. */
    fun read(buffer: ByteArray, offset: Int, length: Int, remaining: Long): Int {
        if (length == 0) return 0
        if (remaining <= 0 || position >= fileSize) return -1
        if (!running) return -1

        val index = position / chunkSize
        val inChunk = (position % chunkSize).toInt()

        var data = chunks[index]
        val deadline = System.currentTimeMillis() + 45_000
        while (data == null) {
            if (!running) return -1
            if (System.currentTimeMillis() > deadline) {
                throw IOException(lastError?.message ?: "השרת לא מגיב")
            }
            sleep(4)
            data = chunks[index]
        }
        if (inChunk >= data.size) throw IOException("מקטע לא תקין מהשרת")

        val n = minOf(length.toLong(), (data.size - inChunk).toLong(), remaining).toInt()
        if (n <= 0) return -1
        System.arraycopy(data, inChunk, buffer, offset, n)
        position += n

        if (inChunk + n >= data.size) {
            chunks.remove(index)
            chunks.keys.filter { it < index }.forEach { chunks.remove(it) }
        }
        return n
    }

    /** סגירה מסודרת: העובדים מסיימים את המקטע הנוכחי ורק אז הערוצים נסגרים. */
    fun shutdown() {
        running = false
        threads.forEach { runCatching { it.join(2500) } }
        threads.clear()
        chunks.clear()
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }
}
