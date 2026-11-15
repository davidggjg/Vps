package com.vps.manager.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * מאגר ערוצי SFTP לשימוש חוזר.
 *
 * נמדד בבדיקה: פתיחה וסגירה מהירה של ערוצים גורמת לתשובה שמגיעה לערוץ שכבר נסגר,
 * וספריית ה-SSH מפילה בעקבות זה את כל החיבור. לכן ערוץ שנפתח נשמר ומשמש שוב.
 */
object SftpPool {

    private const val PER_CONNECTION = 4
    private val pools = ConcurrentHashMap<Connection, ArrayBlockingQueue<SFTPv3Client>>()

    fun <T> withClient(conn: Connection, block: (SFTPv3Client) -> T): T {
        val queue = pools.computeIfAbsent(conn) { ArrayBlockingQueue(PER_CONNECTION) }
        val client = queue.poll() ?: SFTPv3Client(conn)
        return try {
            val result = block(client)
            if (!queue.offer(client)) runCatching { client.close() }
            result
        } catch (t: Throwable) {
            runCatching { client.close() }
            throw t
        }
    }

    fun release(conn: Connection) {
        pools.remove(conn)?.let { queue ->
            while (true) {
                val c = queue.poll() ?: break
                runCatching { c.close() }
            }
        }
    }

    fun releaseAll() {
        pools.keys.toList().forEach { release(it) }
    }
}
