package no.stink.bydshare

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to OverDrive's HTTP API on the car through whatever edge auth is configured.
 *
 * Two auth layers:
 *  - Edge (Cloudflare Access service token, or none) — applied to every request.
 *  - OverDrive app: a device token minted into a short-lived JWT via /auth/token.
 *    The JWT is cached and silently re-minted on a 401, so the user never re-auths
 *    by hand after entering the device token once.
 *
 * The host's WAF 403s the default UA, so we send a browser User-Agent.
 */
object CarClient {

    data class Result(val ok: Boolean, val message: String)

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile private var cachedJwt: String? = null

    /** POST a shared place as a Save-to-Favourites. */
    suspend fun addFavorite(name: String, lat: Double, lng: Double, favoriteType: String): Result =
        action("/api/telenav/addFavorite", name, lat, lng, favoriteType)

    /** POST a shared place as a Navigate. */
    suspend fun navigate(name: String, lat: Double, lng: Double): Result =
        action("/api/telenav/navigate", name, lat, lng, null)

    private suspend fun action(
        path: String, name: String, lat: Double, lng: Double, favoriteType: String?,
    ): Result = withContext(Dispatchers.IO) {
        if (!Settings.isConfigured) {
            return@withContext Result(false, "Not configured — open Settings and enter the car connection details.")
        }
        val body = JSONObject().apply {
            put("name", name)
            put("lat", lat)
            put("lng", lng)
            put("formattedAddress", name)
            if (favoriteType != null) put("favoriteType", favoriteType)
        }.toString()

        try {
            var resp = post(path, body, jwt = ensureJwt())
            if (resp.code == 401) {                 // JWT expired/absent — re-mint once and retry.
                cachedJwt = null
                resp = post(path, body, jwt = ensureJwt())
            }
            interpret(resp)
        } catch (e: EdgeException) {
            Result(false, e.message ?: "Edge auth failed")
        } catch (e: Exception) {
            Result(false, "Network error: ${e.message}")
        }
    }

    private data class Resp(val code: Int, val body: String)
    private class EdgeException(msg: String) : Exception(msg)

    /** Fetch a JWT if we don't have one cached. */
    private fun ensureJwt(): String {
        cachedJwt?.let { return it }

        // Mirror OverDrive's own login: read the deviceId (unauthenticated) and combine
        // it with the 8-char access code to form the full token.
        val status = get("/auth/status")
        if (looksLikeEdgeBlock(status)) throw EdgeException(edgeHint())
        val deviceId = runCatching { JSONObject(status.body).optString("deviceId", "") }.getOrDefault("")
        if (deviceId.isEmpty() || deviceId == "unknown") {
            throw Exception("Couldn't read the car's device id (HTTP ${status.code}).")
        }
        val fullToken = deviceId + "-" + Settings.accessCode.trim().lowercase()

        val resp = post("/auth/token", JSONObject().put("token", fullToken).toString(), jwt = null)
        // Log status only — never the body (it carries the JWT on success).
        Log.i("CarClient", "auth /auth/token code=${resp.code} edge=${Settings.edgeAuth} " +
            "hasJwt=${resp.body.contains("\"jwt\"")}")
        if (resp.code == 200) {
            val jwt = runCatching { JSONObject(resp.body).optString("jwt", "") }.getOrDefault("")
            if (jwt.isNotEmpty()) {
                cachedJwt = jwt
                return jwt
            }
        }
        if (looksLikeEdgeBlock(resp)) throw EdgeException(edgeHint())
        throw Exception("Auth failed (${resp.code}) — check the access code.")
    }

    private fun get(path: String): Resp {
        val conn = (URL(Settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            EdgeAuth.fromSettings().apply(this)
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Resp(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: String, jwt: String?): Resp {
        val conn = (URL(Settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 25000
            instanceFollowRedirects = false      // a 302 to the IdP means edge auth failed
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            EdgeAuth.fromSettings().apply(this)
            if (jwt != null) setRequestProperty("Authorization", "Bearer $jwt")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun interpret(resp: Resp): Result {
        if (looksLikeEdgeBlock(resp)) return Result(false, edgeHint())
        return when (resp.code) {
            in 200..299 -> {
                val ok = runCatching { JSONObject(resp.body).optBoolean("success", true) }.getOrDefault(true)
                if (ok) Result(true, "Sent to car")
                else Result(false, runCatching { JSONObject(resp.body).optString("error", "Car rejected the request") }
                    .getOrDefault("Car rejected the request"))
            }
            401 -> Result(false, "Auth failed — check the access code.")
            else -> Result(false, "Car returned HTTP ${resp.code}")
        }
    }

    // A CF Access rejection is a 302 to the IdP (or a Cloudflare HTML page), not JSON.
    private fun looksLikeEdgeBlock(resp: Resp): Boolean =
        resp.code in 300..399 ||
            (resp.code == 403 && resp.body.contains("Cloudflare", ignoreCase = true)) ||
            (Settings.edgeAuth == Settings.EdgeAuthType.CLOUDFLARE &&
                resp.body.contains("<html", ignoreCase = true))

    private fun edgeHint(): String =
        if (Settings.edgeAuth == Settings.EdgeAuthType.CLOUDFLARE)
            "Cloudflare Access blocked the request. Check the service-token id/secret, and that the Access app has a Service Auth policy."
        else
            "Blocked before reaching the car (a login page came back). If the host is behind Cloudflare Access, set that up in Settings."
}
