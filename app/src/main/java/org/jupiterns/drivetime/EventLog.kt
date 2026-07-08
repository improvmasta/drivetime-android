package org.jupiterns.drivetime

import android.content.Context
import java.io.File

/**
 * Tiny persistent activity log so the app can answer "what happened / why did it
 * stop". A bounded, newest-last buffer kept both in memory (fast read for the Log
 * screen) and on disk (survives process death / OS kill), written by the service,
 * uploader and workers.
 *
 * Deliberately *coarse* — lifecycle, tier changes, connection health, errors —
 * never per-fix, so it stays readable and the whole-file rewrite on each append
 * stays cheap.
 */
object EventLog {
    enum class Level { INFO, WARN, ERROR }
    data class Entry(val ts: Long, val level: Level, val msg: String)

    private const val FILE = "events.log"
    private const val MAX = 400
    private val LOCK = Any()
    private val buf = ArrayDeque<Entry>()
    @Volatile private var file: File? = null
    private var loaded = false

    /** Capture the app context once and load any persisted history; safe to repeat. */
    fun init(context: Context) {
        synchronized(LOCK) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, FILE)
            ensureLoadedLocked()
        }
    }

    fun info(msg: String) = add(Level.INFO, msg)
    fun warn(msg: String) = add(Level.WARN, msg)

    fun add(level: Level, msg: String) {
        synchronized(LOCK) {
            ensureLoadedLocked()
            buf.addLast(Entry(System.currentTimeMillis(), level, msg))
            while (buf.size > MAX) buf.removeFirst()
            persistLocked()
        }
    }

    /** Newest-first snapshot for display. */
    fun recent(): List<Entry> = synchronized(LOCK) { ensureLoadedLocked(); buf.toList().asReversed() }

    fun clear() = synchronized(LOCK) { buf.clear(); file?.let { runCatching { it.delete() } } }

    // --- persistence (one line per entry: ts|LEVEL|msg) ---
    private fun ensureLoadedLocked() {
        if (loaded) return
        val f = file ?: return
        loaded = true
        if (!f.exists()) return
        runCatching {
            f.readLines().forEach { line ->
                val p = line.split("|", limit = 3)
                if (p.size == 3) {
                    val ts = p[0].toLongOrNull() ?: return@forEach
                    val lvl = runCatching { Level.valueOf(p[1]) }.getOrDefault(Level.INFO)
                    buf.addLast(Entry(ts, lvl, p[2]))
                }
            }
            while (buf.size > MAX) buf.removeFirst()
        }
    }

    private fun persistLocked() {
        val f = file ?: return
        runCatching {
            f.writeText(buf.joinToString("\n") { "${it.ts}|${it.level}|${it.msg.replace("\n", " ")}" })
        }
    }
}
