package no.stink.bydshare

import java.net.HttpURLConnection

/**
 * Edge authentication for whatever fronts the car's HTTP API. Pluggable so the
 * app can ship publicly: "None" for an unprotected host/LAN, "Cloudflare service
 * token" for a Cloudflare Access tunnel. Add more implementations as needed.
 */
interface EdgeAuth {
    fun apply(conn: HttpURLConnection)

    companion object {
        fun fromSettings(): EdgeAuth = when (Settings.edgeAuth) {
            Settings.EdgeAuthType.CLOUDFLARE ->
                CloudflareServiceTokenAuth(Settings.cfClientId, Settings.cfClientSecret)
            Settings.EdgeAuthType.NONE -> NoEdgeAuth
        }
    }
}

object NoEdgeAuth : EdgeAuth {
    override fun apply(conn: HttpURLConnection) {}
}

/** Cloudflare Access service token — non-interactive, long-lived, no browser. */
class CloudflareServiceTokenAuth(
    private val clientId: String,
    private val clientSecret: String,
) : EdgeAuth {
    override fun apply(conn: HttpURLConnection) {
        conn.setRequestProperty("CF-Access-Client-Id", clientId)
        conn.setRequestProperty("CF-Access-Client-Secret", clientSecret)
    }
}
