package com.vps.manager.ssh

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.trilead.ssh2.Connection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * מפעל מקורות נתונים לנגן: מחזיק קורא קבוע לכל קובץ (הסרט וגם קובצי הכתוביות),
 * כך שהערוצים נפתחים פעם אחת ונשארים פתוחים לכל אורך הצפייה.
 */
@UnstableApi
class SftpSourceFactory(
    private val connections: List<Connection>,
    private val mainPath: String
) : DataSource.Factory {

    private val readers = ConcurrentHashMap<String, SftpStreamReader>()

    fun readerFor(path: String): SftpStreamReader =
        readers.computeIfAbsent(path) {
            SftpStreamReader(
                connections = connections,
                path = it,
                // הסרט מקבל את כל הערוצים; קובץ כתוביות קטן ומסתפק בערוץ אחד
                workersPerConnection = if (it == mainPath) 4 else 1
            )
        }

    override fun createDataSource(): DataSource = ParallelSftpSource(this)

    fun release() {
        readers.values.forEach { runCatching { it.shutdown() } }
        readers.clear()
    }
}

/** עטיפה דקה: הנגן פותח וסוגר אותה שוב ושוב, הערוצים נשארים חיים ברקע. */
@UnstableApi
class ParallelSftpSource(private val factory: SftpSourceFactory) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var reader: SftpStreamReader? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("נתיב לא תקין")
        uri = dataSpec.uri
        val r = factory.readerFor(path)
        reader = r
        r.seekTo(dataSpec.position)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong())
            (r.fileSize - dataSpec.position).coerceAtLeast(0)
        else dataSpec.length
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val n = reader?.read(buffer, offset, length, remaining) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        fun uriFor(path: String): Uri = Uri.Builder().scheme("sftp").path(path).build()
    }
}
