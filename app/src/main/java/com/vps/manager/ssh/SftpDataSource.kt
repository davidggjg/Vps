package com.vps.manager.ssh

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3FileHandle

/**
 * מקור נתונים לנגן שקורא את הקובץ ישירות מהשרת דרך SFTP,
 * בקריאות לפי מיקום — כך אפשר לצפות ולדלג בסרטון בלי להוריד אותו קודם.
 */
@UnstableApi
class SftpDataSource(private val conn: Connection) : BaseDataSource(true) {

    private var client: SFTPv3Client? = null
    private var handle: SFTPv3FileHandle? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val path = dataSpec.uri.path ?: throw java.io.IOException("נתיב לא תקין")

        val c = SFTPv3Client(conn)
        val h = c.openFileRO(path)
        client = c
        handle = h

        val total = c.fstat(h).size ?: 0L
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong())
            (total - dataSpec.position).coerceAtLeast(0L)
        else dataSpec.length

        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val c = client ?: return C.RESULT_END_OF_INPUT
        val h = handle ?: return C.RESULT_END_OF_INPUT

        val want = if (remaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), remaining).toInt()

        val read = c.read(h, position, buffer, offset, want)
        if (read <= 0) return C.RESULT_END_OF_INPUT

        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            handle?.let { h -> runCatching { client?.closeFile(h) } }
            runCatching { client?.close() }
        } finally {
            handle = null
            client = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        fun uriFor(path: String): Uri = Uri.Builder().scheme("sftp").path(path).build()

        @UnstableApi
        fun factory(conn: Connection): DataSource.Factory = DataSource.Factory { SftpDataSource(conn) }
    }
}
