package com.vps.manager.ssh

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * קריאת קובץ מהשרת בכמה ערוצים ובכמה חיבורים במקביל.
 *
 * ערוץ SSH בודד מוגבל לחלון של 30KB וכל קריאת SFTP מוגבלת ל-32KB,
 * ולכן ערוץ אחד תמיד יהיה איטי. כאן רצים כמה עובדים במקביל, כל אחד
 * מושך מקטע שלם ומאמת שהתקבל במלואו, כדי שהזרם שמגיע לנגן יהיה רציף.
 */
@UnstableApi
class ParallelSftpSource(
    private val connections: List<Connection>,
    private val workersPerConnection: Int = 4,
    private val chunkSize: Int = 256 * 1024,
    private val maxAheadBytes: Long = 64L * 1024 * 1024
) : BaseDataSource(true) {

    private val chunks = ConcurrentHashMap<Long, ByteArray>()
    private val nextToFetch = AtomicLong(0)
    private val stopFlag = AtomicBoolean(false)
    private val threads = ArrayList<Thread>()

    private var path = ""
    private var uri: Uri? = null
    private var fileSize = 0L
    @Volatile private var position = 0L
    private var remaining = 0L
    @Volatile private var failure: Throwable? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        path = dataSpec.uri.path ?: throw IOException("נתיב לא תקין")
        if (connections.isEmpty()) throw IOException("אין חיבור לשרת")

        val probe = SFTPv3Client(connections[0])
        fileSize = try {
            probe.stat(path).size ?: 0L
        } finally {
            runCatching { probe.close() }
        }
        if (fileSize <= 0) throw IOException("הקובץ ריק או לא נגיש")

        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong())
            (fileSize - position).coerceAtLeast(0)
        else dataSpec.length

        nextToFetch.set(position / chunkSize)
        startWorkers()

        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun startWorkers() {
        connections.forEachIndexed { connIndex, conn ->
            repeat(workersPerConnection) { workerIndex ->
                val t = Thread { workerLoop(conn) }
                t.isDaemon = true
                t.name = "sftp-$connIndex-$workerIndex"
                threads.add(t)
                t.start()
            }
        }
    }

    private fun workerLoop(conn: Connection) {
        var client: SFTPv3Client? = null
        try {
            while (!stopFlag.get()) {
                val chunkIndex = nextToFetch.getAndIncrement()
                val start = chunkIndex * chunkSize
                if (start >= fileSize) return

                // לא רצים רחוק מדי לפני הנגן, כדי לא למלא את הזיכרון
                while (!stopFlag.get() && start - position > maxAheadBytes) {
                    Thread.sleep(20)
                }
                if (stopFlag.get()) return

                val size = minOf(chunkSize.toLong(), fileSize - start).toInt()
                var stored = false
                var attempt = 0
                while (!stored && attempt < 4 && !stopFlag.get()) {
                    attempt++
                    try {
                        val c = client ?: SFTPv3Client(conn).also { client = it }
                        val handle = c.openFileRO(path)
                        try {
                            val data = ByteArray(size)
                            var got = 0
                            while (got < size && !stopFlag.get()) {
                                val want = minOf(RemoteFiles.MAX_SFTP_READ, size - got)
                                val n = c.read(handle, start + got, data, got, want)
                                if (n < 0) break
                                got += n
                            }
                            // מקטע חלקי לעולם לא נשמר — אחרת הזרם לנגן יישבר
                            if (got == size) {
                                chunks[chunkIndex] = data
                                stored = true
                            }
                        } finally {
                            runCatching { c.closeFile(handle) }
                        }
                    } catch (t: Throwable) {
                        runCatching { client?.close() }
                        client = null
                        if (attempt >= 4) failure = t else Thread.sleep(120L * attempt)
                    }
                }
                if (!stored && !stopFlag.get() && failure == null) {
                    failure = IOException("קריאת מקטע מהשרת נכשלה")
                }
            }
        } catch (t: Throwable) {
            if (!stopFlag.get()) failure = t
        } finally {
            runCatching { client?.close() }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0 || position >= fileSize) return C.RESULT_END_OF_INPUT

        val index = position / chunkSize
        val inChunk = (position % chunkSize).toInt()

        var data = chunks[index]
        val deadline = System.currentTimeMillis() + 45_000
        while (data == null) {
            failure?.let { throw IOException(it) }
            if (stopFlag.get()) return C.RESULT_END_OF_INPUT
            if (System.currentTimeMillis() > deadline) throw IOException("השרת לא מגיב")
            Thread.sleep(6)
            data = chunks[index]
        }
        if (inChunk >= data.size) throw IOException("מקטע לא תקין מהשרת")

        val n = minOf(length.toLong(), (data.size - inChunk).toLong(), remaining).toInt()
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(data, inChunk, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)

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
        fun factory(connections: List<Connection>): DataSource.Factory =
            DataSource.Factory { ParallelSftpSource(connections) }
    }
}
