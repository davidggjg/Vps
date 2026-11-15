package com.vps.manager.ssh

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * קריאה מקבילה של קובץ מהשרת דרך כמה ערוצי SFTP במקביל.
 *
 * ערוץ SSH בודד מוגבל לחלון של 30KB, כלומר כל קריאה ממתינה להלוך-חזור לשרת.
 * לכן מספר עובדים מושכים מקטעים שונים בו-זמנית וצוברים קדימה בזיכרון,
 * וכך הניגון מתחיל מיד וגם דילוג בתוך הסרט מהיר.
 */
@UnstableApi
class ParallelSftpSource(
    private val conn: Connection,
    private val workers: Int = 8,
    private val chunkSize: Int = 512 * 1024,
    private val maxAheadBytes: Long = 48L * 1024 * 1024
) : BaseDataSource(true) {

    private val chunks = ConcurrentHashMap<Long, ByteArray>()
    private val nextToFetch = AtomicLong(0)
    private val stopFlag = AtomicBoolean(false)
    private val threads = ArrayList<Thread>()

    private var path: String = ""
    private var uri: Uri? = null
    private var fileSize = 0L
    private var position = 0L
    private var remaining = 0L
    private var firstChunk = 0L
    @Volatile private var failure: Throwable? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        path = dataSpec.uri.path ?: throw java.io.IOException("נתיב לא תקין")

        val probe = SFTPv3Client(conn)
        fileSize = try {
            probe.stat(path).size ?: 0L
        } finally {
            runCatching { probe.close() }
        }

        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong())
            (fileSize - position).coerceAtLeast(0)
        else dataSpec.length

        firstChunk = position / chunkSize
        nextToFetch.set(firstChunk)
        startWorkers()

        opened = true
        transferStarted(dataSpec)
        return if (remaining <= 0) C.LENGTH_UNSET.toLong() else remaining
    }

    private fun startWorkers() {
        repeat(workers) { index ->
            val t = Thread {
                var client: SFTPv3Client? = null
                try {
                    while (!stopFlag.get()) {
                        val wanted = position / chunkSize
                        val chunkIndex = nextToFetch.getAndIncrement()
                        if (chunkIndex * chunkSize >= fileSize) return@Thread

                        // לא רצים רחוק מדי קדימה מהנגן
                        while (!stopFlag.get() &&
                            (chunkIndex - position / chunkSize) * chunkSize.toLong() > maxAheadBytes
                        ) {
                            Thread.sleep(25)
                        }
                        if (stopFlag.get()) return@Thread
                        if (chunkIndex < wanted - 2) continue

                        val c = client ?: SFTPv3Client(conn).also { client = it }
                        val handle = c.openFileRO(path)
                        try {
                            val start = chunkIndex * chunkSize.toLong()
                            val size = minOf(chunkSize.toLong(), fileSize - start).toInt()
                            if (size <= 0) return@Thread
                            val data = ByteArray(size)
                            var got = 0
                            while (got < size && !stopFlag.get()) {
                                val n = c.read(handle, start + got, data, got, size - got)
                                if (n <= 0) break
                                got += n
                            }
                            if (got > 0) {
                                chunks[chunkIndex] = if (got == size) data else data.copyOf(got)
                            }
                        } finally {
                            runCatching { c.closeFile(handle) }
                        }
                    }
                } catch (t: Throwable) {
                    if (!stopFlag.get()) failure = t
                } finally {
                    runCatching { client?.close() }
                }
            }
            t.isDaemon = true
            t.name = "sftp-prefetch-$index"
            threads.add(t)
            t.start()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        if (position >= fileSize) return C.RESULT_END_OF_INPUT

        val index = position / chunkSize
        val inChunk = (position % chunkSize).toInt()

        var data = chunks[index]
        val deadline = System.currentTimeMillis() + 30_000
        while (data == null) {
            failure?.let { throw java.io.IOException(it) }
            if (stopFlag.get()) return C.RESULT_END_OF_INPUT
            if (System.currentTimeMillis() > deadline) throw java.io.IOException("השרת לא מגיב")
            Thread.sleep(8)
            data = chunks[index]
        }

        if (inChunk >= data.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length.toLong(), (data.size - inChunk).toLong(), remaining).toInt()
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(data, inChunk, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)

        // משחררים מקטעים שכבר נוגנו
        if (inChunk + n >= data.size) {
            chunks.remove(index)
            chunks.keys.filter { it < index }.forEach { chunks.remove(it) }
        }
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        stopFlag.set(true)
        threads.forEach { runCatching { it.interrupt() } }
        threads.clear()
        chunks.clear()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        fun uriFor(path: String): Uri = Uri.Builder().scheme("sftp").path(path).build()

        @UnstableApi
        fun factory(conn: Connection): DataSource.Factory = DataSource.Factory { ParallelSftpSource(conn) }
    }
}
