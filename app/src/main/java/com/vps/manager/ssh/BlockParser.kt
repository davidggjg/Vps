package com.vps.manager.ssh

/**
 * מפריד את הזרם מהשרת לבלוקים: לכל פקודה יש התחלה, פלט, קוד יציאה ותיקייה.
 * כך אפשר להעתיק בדיוק את הפלט של פקודה אחת, בלחיצה אחת.
 */
class BlockParser {

    interface Sink {
        fun onBanner(text: String)
        fun onCommandStart(id: Long)
        fun onOutput(id: Long, text: String)
        fun onCommandEnd(id: Long, rc: Int, pwd: String)
    }

    private val buf = StringBuilder()
    private var current: Long? = null

    fun reset() {
        buf.setLength(0)
        current = null
    }

    fun feed(chunk: String, sink: Sink) {
        buf.append(chunk)
        while (true) {
            val id = current
            if (id == null) {
                val i = buf.indexOf(BEGIN)
                if (i < 0) {
                    val keep = KEEP.coerceAtMost(buf.length)
                    val emit = buf.length - keep
                    if (emit > 0) {
                        sink.onBanner(buf.substring(0, emit))
                        buf.delete(0, emit)
                    }
                    return
                }
                if (i > 0) {
                    sink.onBanner(buf.substring(0, i))
                    buf.delete(0, i)
                }
                val nl = buf.indexOf("\n")
                if (nl < 0) return
                val parsed = buf.substring(BEGIN.length, nl).trim().toLongOrNull()
                buf.delete(0, nl + 1)
                if (parsed == null) continue
                current = parsed
                sink.onCommandStart(parsed)
            } else {
                val i = buf.indexOf(END)
                if (i < 0) {
                    val keep = KEEP.coerceAtMost(buf.length)
                    val emit = buf.length - keep
                    if (emit > 0) {
                        sink.onOutput(id, buf.substring(0, emit))
                        buf.delete(0, emit)
                    }
                    return
                }
                if (i > 0) {
                    sink.onOutput(id, buf.substring(0, i))
                    buf.delete(0, i)
                }
                val nl = buf.indexOf("\n")
                if (nl < 0) return
                val body = buf.substring(END.length, nl)
                buf.delete(0, nl + 1)
                val parts = body.split("_", limit = 3)
                val endId = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: id
                val rc = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
                val pwd = parts.getOrNull(2)?.trim().orEmpty()
                current = null
                sink.onCommandEnd(endId, rc, pwd)
            }
        }
    }

    companion object {
        const val BEGIN = "\u0001VPSB_"
        const val END = "\u0002VPSE_"
        /** שומרים זנב קטן כדי לא לחתוך סימן באמצע. */
        private const val KEEP = 64
    }
}
