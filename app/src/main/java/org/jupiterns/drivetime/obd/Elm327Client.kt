package org.jupiterns.drivetime.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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

    fun isConnected() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
        // init: reset, echo off, linefeeds off, spaces off, auto protocol
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")) {
            send(cmd); Thread.sleep(120)
        }
    }

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

    /** Hex data bytes following the "41<pid>" response header. */
    private fun pidBytes(pid: String): List<Int> {
        val resp = send("01$pid")
        val toks = resp.uppercase()
            .replace(Regex("[^0-9A-F ]"), " ")
            .split(Regex("\\s+")).filter { it.length == 2 }
        val joined = toks.joinToString("")
        val idx = joined.indexOf("41$pid")
        if (idx < 0) return emptyList()
        return joined.substring(idx + 4).chunked(2).mapNotNull { it.toIntOrNull(16) }
    }

    private fun parseDtcs(resp: String): List<String> {
        val toks = resp.uppercase().replace(Regex("[^0-9A-F ]"), " ")
            .split(Regex("\\s+")).filter { it.length == 2 }
        val joined = toks.joinToString("")
        val idx = joined.indexOf("43")
        if (idx < 0) return emptyList()
        val data = joined.substring(idx + 2).chunked(2).mapNotNull { it.toIntOrNull(16) }
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
