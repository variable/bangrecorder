package com.example.bangrecorder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BangEvent(
    val file: String,
    val timeMillis: Long,
    val peakDb: Float,
    val offsetSec: Float,   // where in the clip the bang is
    val hits: Int           // separate bangs captured in this clip
)

/**
 * Clips live in Android/data/com.example.bangrecorder/files/bangs/ alongside events.csv,
 * a plain log you can open in a spreadsheet (one row per saved clip).
 */
object EventStore {
    private const val LOG_NAME = "events.csv"
    private const val HEADER = "file,epoch_ms,local_time,peak_db,bang_at_seconds,bang_count"

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "bangs").also { it.mkdirs() }

    fun logFile(ctx: Context): File = File(dir(ctx), LOG_NAME)

    @Synchronized
    fun append(ctx: Context, e: BangEvent) {
        val f = logFile(ctx)
        if (!f.exists()) f.writeText(HEADER + "\n")
        f.appendText(toLine(e) + "\n")
    }

    @Synchronized
    fun load(ctx: Context): List<BangEvent> {
        val f = logFile(ctx)
        if (!f.exists()) return emptyList()
        val d = dir(ctx)
        return f.readLines().drop(1)
            .mapNotNull { parse(it) }
            .filter { File(d, it.file).exists() }
            .sortedByDescending { it.timeMillis }
    }

    @Synchronized
    fun delete(ctx: Context, e: BangEvent) {
        File(dir(ctx), e.file).delete()
        val f = logFile(ctx)
        if (!f.exists()) return
        val keep = f.readLines().drop(1).filter { it.isNotBlank() && parse(it)?.file != e.file }
        f.writeText((listOf(HEADER) + keep).joinToString("\n", postfix = "\n"))
    }

    private fun toLine(e: BangEvent): String {
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(e.timeMillis))
        return String.format(
            Locale.US, "%s,%d,%s,%.1f,%.2f,%d",
            e.file, e.timeMillis, local, e.peakDb, e.offsetSec, e.hits
        )
    }

    private fun parse(line: String): BangEvent? {
        val p = line.split(",")
        if (p.size < 6) return null
        return try {
            BangEvent(p[0], p[1].toLong(), p[3].toFloat(), p[4].toFloat(), p[5].toInt())
        } catch (_: Exception) {
            null
        }
    }
}
