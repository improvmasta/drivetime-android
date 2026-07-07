package org.jupiterns.drivetime

import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses a pairing payload (AUTH.md) — scanned from the server dashboard's QR, or pasted
 * by hand — into a server URL + device token. Accepts, most-specific first:
 *   * `drivetime://pair?url=<enc>&token=<enc>` — the QR the web Settings renders
 *   * a JSON object `{"url": "...", "token": "..."}`
 *   * a bare token string (no scheme) — pairs against the URL the user already typed
 *
 * Either field may be null: a bare token keeps the existing server URL; a payload missing
 * a token is treated as "no token found". Pure Kotlin (+ org.json) so it's unit-testable.
 */
object Pairing {
    data class Result(val url: String?, val token: String?) {
        val hasToken: Boolean get() = !token.isNullOrBlank()
    }

    fun parse(raw: String?): Result {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return Result(null, null)

        if (s.startsWith("drivetime://")) {
            val params = s.substringAfter('?', "")
                .split('&')
                .mapNotNull { kv ->
                    val i = kv.indexOf('=')
                    if (i < 0) null else decode(kv.substring(0, i)) to decode(kv.substring(i + 1))
                }
                .toMap()
            return Result(cleanUrl(params["url"]), params["token"]?.ifBlank { null })
        }

        if (s.startsWith("{")) {
            return runCatching {
                val o = JSONObject(s)
                Result(cleanUrl(o.optString("url")), o.optString("token").ifBlank { null })
            }.getOrDefault(Result(null, null))
        }

        // Bare token — no scheme, no JSON. Keep whatever URL the user already entered.
        return Result(null, s)
    }

    private fun cleanUrl(u: String?): String? = u?.trim()?.trimEnd('/')?.ifBlank { null }

    private fun decode(x: String): String = runCatching { URLDecoder.decode(x, "UTF-8") }.getOrDefault(x)
}
