package org.jupiterns.drivetime.obd

/**
 * Phase B — thin custom ELM327 layer over a Bluetooth SPP stream.
 *
 * ELM327 is line-based ASCII: write an OBD-II mode+PID (e.g. "010C" for RPM),
 * read until the ">" prompt, parse the hex bytes. This stub defines the shape;
 * the Bluetooth socket wiring + PID parsing lands in Phase B.
 *
 * Planned core PIDs:
 *   010C RPM · 010D speed · 0104 engine load · 0105 coolant temp
 *   0110 MAF (→ MPG) · 015E fuel rate · 0111 throttle · ATRV battery voltage
 *   03   read DTCs (fault codes)
 */
class Elm327Client {

    data class ObdSample(
        val epochSec: Long,
        val rpm: Int? = null,
        val speedKph: Int? = null,
        val engineLoadPct: Int? = null,
        val coolantC: Int? = null,
        val throttlePct: Int? = null,
        val fuelRateLph: Double? = null,
        val voltage: Double? = null,
        val dtcs: List<String> = emptyList()
    )

    // TODO(Phase B): connect(device), init ("ATZ","ATE0","ATSP0"), query(pid), close().
    fun isConnected(): Boolean = false
}
