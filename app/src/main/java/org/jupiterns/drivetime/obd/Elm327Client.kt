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
        val dtcs: List<String> = emptyList(),
    )

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val initLog = mutableListOf<String>()

    /** mode-01 PIDs this car answers (2-hex, e.g. "5E"), from the 0100/20/40/60 support
     *  bitmasks. Empty = discovery failed → [supports] treats everything as supported. */
    private val supported = linkedSetOf<String>()
    private var vin: String? = null
    // slow-mover cadence: fuel level & control-module voltage barely change, so we poll
    // them every SLOW_EVERY samples and reuse the cached reading between.
    private var slowTick = 0
    private var lastFuelLevel: Double? = null
    private var lastCtrlV: Double? = null

    fun isConnected() = socket?.isConnected == true
    fun supportedPids(): Set<String> = supported
    fun vin(): String? = vin

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val s = openSocket(device)
        socket = s
        input = s.inputStream
        output = s.outputStream
        // init: reset, echo off, linefeeds off, spaces off
        initLog.clear()
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0")) {
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
     * Read the four mode-01 support bitmasks (0100/0120/0140/0160) and record which PIDs
     * this car actually answers, so [readSample] skips the rest instead of paying a ~2 s
     * "NO DATA" timeout per unsupported PID. Each mask is 4 bytes = 32 bits, MSB-first,
     * bit i (from the high bit) = PID base+i+1. A range whose marker is absent (returns
     * <4 bytes) means "no more PIDs" — stop probing.
     */
    private fun discoverCapabilities() {
        supported.clear()
        for ((pid, base) in listOf("00" to 0, "20" to 0x20, "40" to 0x40, "60" to 0x60)) {
            val bytes = pidBytes(pid)
            if (bytes.size < 4) break
            var bits = 0L
            for (b in bytes.take(4)) bits = (bits shl 8) or b.toLong()   // b is already 0..255
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
     *  are read every call; slow movers (fuel level PID 2F, control-module voltage PID 42)
     *  only every [SLOW_EVERY] calls and are cached between. Missing PIDs come back null. */
    fun readSample(): ObdSample {
        val rpmB = if (supports("0C")) pidBytes("0C") else emptyList()
        val spdB = if (supports("0D")) pidBytes("0D") else emptyList()
        val loadB = if (supports("04")) pidBytes("04") else emptyList()
        val tempB = if (supports("05")) pidBytes("05") else emptyList()
        val thrB = if (supports("11")) pidBytes("11") else emptyList()
        val mafB = if (supports("10")) pidBytes("10") else emptyList()
        val fuelB = if (supports("5E")) pidBytes("5E") else emptyList()
        if (slowTick % SLOW_EVERY == 0) {
            if (supports("2F"))
                pidBytes("2F").getOrNull(0)?.let { lastFuelLevel = it * 100.0 / 255.0 }
            if (supports("42"))
                pidBytes("42").let { if (it.size >= 2) lastCtrlV = (it[0] * 256 + it[1]) / 1000.0 }
        }
        slowTick++
        return ObdSample(
            rpm = if (rpmB.size >= 2) (rpmB[0] * 256 + rpmB[1]) / 4 else null,
            obdKph = spdB.getOrNull(0),
            engineLoad = loadB.getOrNull(0)?.let { it * 100.0 / 255.0 },
            coolantC = tempB.getOrNull(0)?.let { it - 40 },
            throttle = thrB.getOrNull(0)?.let { it * 100.0 / 255.0 },
            maf = if (mafB.size >= 2) (mafB[0] * 256 + mafB[1]) / 100.0 else null,
            fuelLph = if (fuelB.size >= 2) (fuelB[0] * 256 + fuelB[1]) / 20.0 else null,
            fuelLevel = lastFuelLevel,
            voltage = Regex("([0-9]+\\.[0-9]+)").find(send("ATRV"))?.value?.toDoubleOrNull(),
            ctrlVoltage = lastCtrlV,
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

    private fun send(cmd: String): String {
        val out = output ?: return ""
        out.write((cmd + "\r").toByteArray()); out.flush()
        return readUntilPrompt()
    }

    private fun readUntilPrompt(): String {
        val inp = input ?: return ""
        val sb = StringBuilder()
        val buf = ByteArray(128)
        val deadline = System.currentTimeMillis() + 2000
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

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Poll the slow-moving PIDs (fuel level, control-module voltage) every Nth
         *  sample. At the ~1.5 s OBD loop, 20 ≈ every 30 s — plenty for a tank gauge. */
        private const val SLOW_EVERY = 20

        /** MAC → last-known-good openSocket strategy index, so a reconnect skips the
         *  secure-SPP timeout that fails on every connect to a clone dongle. In-process
         *  (resets on process death); good enough since reconnects within a drive are the
         *  hot path. */
        private val strategyCache = ConcurrentHashMap<String, Int>()
    }
}
