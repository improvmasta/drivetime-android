package org.jupiterns.drivetime.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin custom ELM327 layer over a Bluetooth SPP stream (no third-party lib).
 *
 * ELM327 is line-based ASCII: write an OBD-II mode+PID (e.g. "010C"), read until
 * the ">" prompt, parse the returned hex bytes. We connect, run the standard init
 * (ATZ/ATE0/ATL0/ATSP0), then poll a small set of PIDs.
 */
class Elm327Client {

    data class ObdSample(
        val rpm: Int? = null,
        val obdKph: Int? = null,
        val engineLoad: Double? = null,
        val coolantC: Int? = null,
        val throttle: Double? = null,
        val maf: Double? = null,
        val fuelLph: Double? = null,      // direct engine fuel rate, PID 5E
        val fuelLevel: Double? = null,    // fuel tank level %, PID 2F (slow-cadence)
        val voltage: Double? = null,      // dongle-pin voltage, ATRV
        val ctrlVoltage: Double? = null,  // control-module voltage, PID 42 (slow-cadence)
        val pids: Map<String, Double> = emptyMap(),  // EVERY supported PID we read, raw — "show all"
        val dtcs: List<String> = emptyList(),
    )

    /** The adapter accepted a Bluetooth socket but never answered the reset/wake handshake —
     *  asleep or wedged (typically a clone that sat powered on an already-running car, e.g.
     *  after a remote start). Thrown so the OBD loop can fail fast and escalate to a cold
     *  reset instead of treating it as an ordinary transient error. */
    class ObdUnresponsiveException(message: String) : IOException(message)

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val initLog = mutableListOf<String>()

    /** mode-01 PIDs this car answers (2-hex, e.g. "5E"), from the 0100/20/40/60 support
     *  bitmasks. Empty = discovery failed → [supports] treats everything as supported. */
    private val supported = linkedSetOf<String>()
    private var vin: String? = null
    // slow-mover cadence: temps/voltages/levels barely change, so we poll the PIDs
    // flagged slow every SLOW_EVERY samples and reuse the cached reading between.
    private var slowTick = 0
    private val slowCache = mutableMapOf<String, Double>()

    fun isConnected() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val s = openSocket(device)
        socket = s
        input = s.inputStream
        output = s.outputStream
        initLog.clear()
        // Fast handshake + wake before the real init. A healthy ELM327 answers ATZ in ~1s.
        // When the car was started well before we reach the dongle (e.g. a remote start), a
        // cheap clone often sits powered-but-idle until it locks up — it still accepts the
        // Bluetooth socket but won't answer commands. [handshake] nudges it (reset/warm-start)
        // on a SHORT timeout and bails immediately if it stays mute, so the loop can retry or
        // cold-reset instead of grinding through ~20s of per-command timeouts every attempt.
        if (!handshake()) throw ObdUnresponsiveException("no ELM327 response — dongle asleep or wedged")
        // echo off, linefeeds off, spaces off
        for (cmd in listOf("ATE0", "ATL0", "ATS0")) {
            val r = send(cmd); Thread.sleep(120)
            initLog.add("$cmd→${clean(r)}")
        }
        // Protocol: pin ISO 15765-4 CAN 11-bit/500k (ATSP6 — ~95% of post-2008 cars) so the
        // first OBD query skips the multi-second "SEARCHING..." auto-probe. If that car isn't
        // protocol 6, fall back to auto (ATSP0) which searches once. A connected car answers
        // 0100 with a "4100" header; "NO DATA"/"UNABLE TO CONNECT"/"SEARCHING" don't.
        if (!tryProtocol("6")) tryProtocol("0")
        discoverCapabilities()
        vin = runCatching { readVin() }.getOrNull()
    }

    /**
     * Quick reset/wake handshake on a short timeout. Sends ATZ (full reset), a warm-start
     * nudge, then ATZ again, returning true as soon as the adapter identifies itself
     * ("ELM…"). A dongle that never answers here is asleep or wedged (the classic
     * remote-start "powered but idle" lockup) — we fail fast rather than hang, so the caller
     * retries far more often and can force a cold reset. A healthy adapter answers the first
     * ATZ and returns immediately, so this adds no cost to a normal connect.
     */
    private fun handshake(): Boolean {
        for ((i, cmd) in listOf("ATZ", "ATWS", "ATZ").withIndex()) {
            val r = send(cmd, HANDSHAKE_TIMEOUT_MS)
            initLog.add("$cmd→${clean(r)}")
            if (r.uppercase().contains("ELM")) return true
            if (i < 2) Thread.sleep(200)
        }
        return false
    }

    /**
     * Read the four mode-01 support bitmasks (0100/0120/0140/0160) and record which PIDs
     * this car actually answers, so [readSample] skips the rest instead of paying a ~2 s
     * "NO DATA" timeout per unsupported PID. Each mask is 4 bytes = 32 bits, MSB-first,
     * bit i (from the high bit) = PID base+i+1. A range whose marker is absent (returns
     * <4 bytes) means "no more PIDs" — stop probing.
     */
    private fun discoverCapabilities() {
        supported.clear()
        for ((pid, base) in listOf("00" to 0, "20" to 0x20, "40" to 0x40, "60" to 0x60)) {
            val hex = send("01$pid").uppercase().replace(Regex("[^0-9A-F]"), "")
            val marker = "41$pid"
            // Several ECUs (engine, transmission, …) can each answer one support query
            // with their OWN mask. OR every response's 4-byte mask together — taking just
            // the first reply risks parsing the wrong ECU and missing real PIDs.
            var bits = 0L
            var any = false
            var at = hex.indexOf(marker)
            while (at >= 0) {
                hex.substring(at + marker.length).take(8).takeIf { it.length == 8 }
                    ?.toLongOrNull(16)?.let { bits = bits or it; any = true }
                at = hex.indexOf(marker, at + marker.length)
            }
            if (!any) break   // range unsupported (no ECU answered) → no higher PIDs
            for (i in 0 until 32) {
                if ((bits ushr (31 - i)) and 1L == 1L) supported.add("%02X".format(base + i + 1))
            }
        }
    }

    /**
     * Mode 09 PID 02 — VIN. The reply is multi-frame (ISO-TP): the ELM327 emits numbered
     * lines ("0:4902...", "1:..."), which we de-frame, then decode the 17 ASCII bytes after
     * the "490201" header. Best-effort: null if absent or not a clean 17-char VIN.
     */
    private fun readVin(): String? {
        val raw = send("0902")
        val hex = raw.split('\r', '\n').joinToString("") { line ->
            val t = line.trim()
            // drop a leading single-hex-digit frame counter like "0:" / "1:"
            val body = if (t.length > 2 && t[1] == ':') t.substring(2) else t
            body.uppercase().replace(Regex("[^0-9A-F]"), "")
        }
        val idx = hex.indexOf("4902")
        if (idx < 0) return null
        var rest = hex.substring(idx + 4)
        if (rest.startsWith("01")) rest = rest.substring(2)   // optional message-count byte
        val vin = rest.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .filter { it in 0x20..0x7E }.map { it.toChar() }
            .joinToString("").filter { it.isLetterOrDigit() }
        return vin.takeIf { it.length == 17 }
    }

    private fun supports(pid: String) = supported.isEmpty() || pid in supported

    /** Set protocol [p], then probe with 0100; true when the adapter returns a valid
     *  response header (4100), i.e. the protocol talks to this car. */
    private fun tryProtocol(p: String): Boolean {
        val sp = send("ATSP$p"); Thread.sleep(80)
        val probe = send("0100")
        val ok = probe.uppercase().replace(Regex("[^0-9A-F]"), "").contains("4100")
        initLog.add("ATSP$p→${clean(sp)}  0100→${clean(probe)}${if (ok) " ok" else " miss"}")
        return ok
    }

    /**
     * Open an RFCOMM stream to the dongle, working around the cheap-ELM327 quirk where
     * the secure SPP socket is silently refused. Try, in order: secure SPP → insecure
     * SPP → reflection on channel 1 (for clones that publish no SPP service record).
     * The first socket that connects wins; failed attempts are closed.
     *
     * The winning strategy is cached per-MAC (in-process) and tried first next time, so a
     * reconnect doesn't pay the multi-second secure-SPP connect *timeout* that fails on every
     * connect to a clone dongle — the single biggest cold-connect cost.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val strategies = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            },
        )
        val mac = device.address
        // Try the last-known-good strategy first; preserve original order for the rest.
        val order = strategyCache[mac]?.let { c ->
            (0 until strategies.size).sortedByDescending { it == c }
        } ?: strategies.indices.toList()
        var last: Exception? = null
        for (idx in order) {
            val sock = runCatching { strategies[idx]() }.getOrElse { last = it.toException(); null } ?: continue
            try {
                sock.connect()
                strategyCache[mac] = idx
                return sock
            } catch (e: Exception) {
                last = e
                runCatching { sock.close() }
            }
        }
        strategyCache.remove(mac)   // none worked — don't pin a stale hint
        throw last ?: IOException("OBD: no RFCOMM socket strategy connected")
    }

    private fun Throwable.toException() = this as? Exception ?: IOException(message, this)

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** Read the current PIDs (gated by [supported]) + battery voltage. Fast-moving PIDs
     *  are read every call; slow movers (temps, levels, voltages) only every [SLOW_EVERY]
     *  calls and are cached between. The common typed fields are pulled back out of the
     *  map; the whole map rides along for "show everything" display + storage. */
    fun readSample(): ObdSample {
        val vals = LinkedHashMap<String, Double>()
        val refreshSlow = slowTick % SLOW_EVERY == 0
        for (d in PID_TABLE) {
            if (!supports(d.pid)) continue
            if (d.slow) {
                if (refreshSlow) d.decode(pidBytes(d.pid))?.let { slowCache[d.pid] = it }
                slowCache[d.pid]?.let { vals[d.pid] = it }
            } else {
                d.decode(pidBytes(d.pid))?.let { vals[d.pid] = it }
            }
        }
        slowTick++
        // Battery voltage is the dongle's own ATRV reading (works key-off), not a PID.
        val rv = Regex("([0-9]+\\.[0-9]+)").find(send("ATRV"))?.value?.toDoubleOrNull()
        if (rv != null) vals["RV"] = rv
        return ObdSample(
            rpm = vals["0C"]?.toInt(),
            obdKph = vals["0D"]?.toInt(),
            engineLoad = vals["04"],
            coolantC = vals["05"]?.toInt(),
            throttle = vals["11"],
            maf = vals["10"],
            fuelLph = vals["5E"],
            fuelLevel = vals["2F"],
            voltage = rv,
            ctrlVoltage = vals["42"],
            pids = vals,
        )
    }

    /** Mode 03 — stored diagnostic trouble codes (check-engine). */
    fun readDtcs(): List<String> = parseDtcs(send("03"))

    /** One-shot raw probe for the activity log: the init transcript plus the exact
     *  text the adapter returns for each polled PID. Makes a parse/protocol fault
     *  visible (e.g. "NO DATA", "SEARCHING...", "UNABLE TO CONNECT") instead of a
     *  silent null. Two compact lines so the Log stays readable. */
    fun diagnostic(): List<String> {
        val init = "init: " + initLog.joinToString("  ")
        val caps = "supports: " +
            (if (supported.isEmpty()) "(discovery failed)" else supported.sorted().joinToString(" ")) +
            "  vin: " + (vin ?: "n/a")
        val probe = buildString {
            append("probe:")
            for (pid in listOf("0C", "0D", "04", "05", "11", "10")) {
                append("  01$pid→").append(clean(send("01$pid")))
            }
            append("  ATRV→").append(clean(send("ATRV")))
        }
        return listOf(init, caps, probe)
    }

    private fun clean(s: String) =
        s.replace(Regex("[\\r\\n>]"), " ").replace(Regex("\\s+"), " ").trim()

    // --- internals ---

    private fun send(cmd: String, timeoutMs: Long = READ_TIMEOUT_MS): String {
        val out = output ?: return ""
        out.write((cmd + "\r").toByteArray()); out.flush()
        return readUntilPrompt(timeoutMs)
    }

    private fun readUntilPrompt(timeoutMs: Long = READ_TIMEOUT_MS): String {
        val inp = input ?: return ""
        val sb = StringBuilder()
        val buf = ByteArray(128)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val n = inp.read(buf)
                if (n > 0) sb.append(String(buf, 0, n))
                if (sb.contains(">")) break
            } else Thread.sleep(15)
        }
        return sb.toString()
    }

    /** Hex data bytes following the "41<pid>" response header. Anchors on the marker
     *  so it works whether the ELM327 returns spaced ("41 0C 1A F0") or packed
     *  ("410C1AF0") bytes. The init sends ATS0 (spaces off), and the old space-tokenised
     *  parse then dropped every byte — leaving only the separately-read voltage. The
     *  marker search also skips leading "SEARCHING..."/echo noise. */
    private fun pidBytes(pid: String): List<Int> {
        val hex = send("01$pid").uppercase().replace(Regex("[^0-9A-F]"), "")
        val marker = "41$pid"
        val idx = hex.indexOf(marker)
        if (idx < 0) return emptyList()
        return hex.substring(idx + marker.length).chunked(2)
            .filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
    }

    private fun parseDtcs(resp: String): List<String> {
        val hex = resp.uppercase().replace(Regex("[^0-9A-F]"), "")
        val idx = hex.indexOf("43")
        if (idx < 0) return emptyList()
        val data = hex.substring(idx + 2).chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        val out = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val a = data[i]; val b = data[i + 1]; i += 2
            if (a == 0 && b == 0) continue
            val letter = charArrayOf('P', 'C', 'B', 'U')[(a shr 6) and 0x03]
            // e.g. a=0x01,b=0x33 -> "P0133"
            out.add("%c%d%X%02X".format(letter, (a shr 4) and 0x03, a and 0x0F, b))
        }
        return out
    }

    /** One mode-01 PID we know how to decode: [decode] turns the data bytes (A=[0],
     *  B=[1], …) into a scalar; [slow]=true polls it on the coarse cadence. */
    private class PidDef(
        val pid: String, val label: String, val unit: String, val slow: Boolean,
        val decode: (List<Int>) -> Double?,
    )

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Normal per-command read timeout — a slow or absent answer times out here. */
        private const val READ_TIMEOUT_MS = 2000L
        /** Short timeout for the reset/wake handshake, so a mute dongle is caught in ~1.5s
         *  (not the full command timeout) and the loop can retry / cold-reset quickly. */
        private const val HANDSHAKE_TIMEOUT_MS = 1500L

        /** Poll PIDs flagged `slow` every Nth sample. At the ~1.5 s OBD loop, 20 ≈ every
         *  30 s — plenty for temps, tank level, voltages. */
        private const val SLOW_EVERY = 20

        // 1-byte helper (A) and 2-byte helper (A*256+B), null if too few bytes.
        private fun a(b: List<Int>) = b.getOrNull(0)?.toDouble()
        private fun ab(b: List<Int>) = if (b.size >= 2) (b[0] * 256 + b[1]).toDouble() else null

        /**
         * The scalar mode-01 PIDs we decode. Intentionally broad — we read everything the
         * car supports and show it all, then prune later. Bitfield/struct PIDs (monitor
         * status, O2-sensor arrays, support masks) are deliberately omitted.
         */
        private val PID_TABLE = listOf(
            PidDef("04", "Engine load", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("05", "Coolant", "°C", true) { a(it)?.minus(40) },
            PidDef("06", "Short fuel trim B1", "%", true) { a(it)?.let { v -> (v - 128) * 100 / 128 } },
            PidDef("07", "Long fuel trim B1", "%", true) { a(it)?.let { v -> (v - 128) * 100 / 128 } },
            PidDef("0B", "Intake MAP", "kPa", false) { a(it) },
            PidDef("0C", "RPM", "rpm", false) { ab(it)?.div(4) },
            PidDef("0D", "Speed", "km/h", false) { a(it) },
            PidDef("0E", "Timing advance", "°", false) { a(it)?.div(2)?.minus(64) },
            PidDef("0F", "Intake air temp", "°C", true) { a(it)?.minus(40) },
            PidDef("10", "MAF", "g/s", false) { ab(it)?.div(100) },
            PidDef("11", "Throttle", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("1F", "Run time", "s", true) { ab(it) },
            PidDef("21", "Dist w/ MIL on", "km", true) { ab(it) },
            PidDef("2F", "Fuel level", "%", true) { a(it)?.times(100.0 / 255) },
            PidDef("30", "Warm-ups since clr", "", true) { a(it) },
            PidDef("31", "Dist since clr", "km", true) { ab(it) },
            PidDef("33", "Barometric", "kPa", true) { a(it) },
            PidDef("42", "Module voltage", "V", true) { ab(it)?.div(1000) },
            PidDef("43", "Absolute load", "%", false) { ab(it)?.times(100.0 / 255) },
            PidDef("44", "Equiv ratio λ", "", false) { ab(it)?.div(32768) },
            PidDef("45", "Rel throttle", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("46", "Ambient temp", "°C", true) { a(it)?.minus(40) },
            PidDef("47", "Abs throttle B", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("49", "Accel pedal D", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("4A", "Accel pedal E", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("4C", "Cmd throttle", "%", false) { a(it)?.times(100.0 / 255) },
            PidDef("4D", "Time w/ MIL on", "min", true) { ab(it) },
            PidDef("4E", "Time since clr", "min", true) { ab(it) },
            PidDef("59", "Fuel rail P", "kPa", true) { ab(it)?.times(10) },
            PidDef("5C", "Oil temp", "°C", true) { a(it)?.minus(40) },
            PidDef("5E", "Fuel rate", "L/h", false) { ab(it)?.div(20) },
        )

        /** MAC → last-known-good openSocket strategy index, so a reconnect skips the
         *  secure-SPP timeout that fails on every connect to a clone dongle. In-process
         *  (resets on process death); good enough since reconnects within a drive are the
         *  hot path. */
        private val strategyCache = ConcurrentHashMap<String, Int>()

        /** Forget the cached socket strategy for [mac] so the next connect re-probes
         *  secure → insecure → reflection from scratch. Called after repeated wedged
         *  connects, in case the pinned strategy is the one now half-failing. */
        fun clearStrategy(mac: String) { strategyCache.remove(mac) }
    }
}
