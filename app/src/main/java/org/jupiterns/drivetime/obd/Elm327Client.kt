package org.jupiterns.drivetime.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

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
        val voltage: Double? = null,
        val dtcs: List<String> = emptyList(),
    )

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val initLog = mutableListOf<String>()

    fun isConnected() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val s = openSocket(device)
        socket = s
        input = s.inputStream
        output = s.outputStream
        // init: reset, echo off, linefeeds off, spaces off, auto protocol
        initLog.clear()
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")) {
            val r = send(cmd); Thread.sleep(120)
            initLog.add("$cmd→${clean(r)}")
        }
    }

    /**
     * Open an RFCOMM stream to the dongle, working around the cheap-ELM327 quirk where
     * the secure SPP socket is silently refused. Try, in order: secure SPP → insecure
     * SPP → reflection on channel 1 (for clones that publish no SPP service record).
     * The first socket that connects wins; failed attempts are closed.
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
        var last: Exception? = null
        for (open in strategies) {
            val sock = runCatching { open() }.getOrElse { last = it.toException(); null } ?: continue
            try {
                sock.connect()
                return sock
            } catch (e: Exception) {
                last = e
                runCatching { sock.close() }
            }
        }
        throw last ?: IOException("OBD: no RFCOMM socket strategy connected")
    }

    private fun Throwable.toException() = this as? Exception ?: IOException(message, this)

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** Read the current core PIDs + battery voltage. Missing PIDs come back null. */
    fun readSample(): ObdSample {
        val rpmB = pidBytes("0C")
        val spdB = pidBytes("0D")
        val loadB = pidBytes("04")
        val tempB = pidBytes("05")
        val thrB = pidBytes("11")
        val mafB = pidBytes("10")
        return ObdSample(
            rpm = if (rpmB.size >= 2) (rpmB[0] * 256 + rpmB[1]) / 4 else null,
            obdKph = spdB.getOrNull(0),
            engineLoad = loadB.getOrNull(0)?.let { it * 100.0 / 255.0 },
            coolantC = tempB.getOrNull(0)?.let { it - 40 },
            throttle = thrB.getOrNull(0)?.let { it * 100.0 / 255.0 },
            maf = if (mafB.size >= 2) (mafB[0] * 256 + mafB[1]) / 100.0 else null,
            voltage = Regex("([0-9]+\\.[0-9]+)").find(send("ATRV"))?.value?.toDoubleOrNull(),
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
        val probe = buildString {
            append("probe:")
            for (pid in listOf("0C", "0D", "04", "05", "11", "10")) {
                append("  01$pid→").append(clean(send("01$pid")))
            }
            append("  ATRV→").append(clean(send("ATRV")))
        }
        return listOf(init, probe)
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
    }
}
