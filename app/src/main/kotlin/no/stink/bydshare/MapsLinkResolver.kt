package no.stink.bydshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Turns a shared Google Maps payload into a place with coordinates.
 *
 * Google's "Share" almost never hands over raw coordinates. It gives a short link
 * (maps.app.goo.gl / goo.gl/maps), sometimes with a place name as a title line. We
 * follow the redirect to the real maps URL and pull the coordinates out of that,
 * falling back to scanning the page body when the URL itself carries none.
 *
 * The parsing helpers are pure so they can be unit-tested without a network.
 */
object MapsLinkResolver {

    data class Resolved(
        val name: String?,
        val lat: Double?,
        val lng: Double?,
        val resolvedUrl: String?,
        val rawText: String,
        val note: String?,
    ) {
        val hasCoords: Boolean get() = lat != null && lng != null
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val URL_RE = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)

    // The place marker, most precise: ...!3d<lat>!4d<lng>...
    private val MARKER_RE = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")

    // Explicit query params: ?q=<lat>,<lng> / query= / ll= / daddr= / destination= / center=
    private val PARAM_RE = Regex(
        """[?&](?:q|query|ll|sll|saddr|daddr|destination|center|viewpoint)=""" +
            """(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""",
        RegexOption.IGNORE_CASE,
    )

    // Map centre in the path: /@<lat>,<lng>,<zoom>z  (also matches a bare "@lat,lng")
    private val AT_RE = Regex("""[/@](-?\d+\.\d+),(-?\d+\.\d+)""")

    // Bare "lat,lng" typed straight into the share text
    private val BARE_RE = Regex("""(-?\d{1,3}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})""")

    private val PLACE_NAME_RE = Regex("""/maps/place/([^/@]+)""")
    private val GEO_RE = Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""")

    fun extractFirstUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')')

    /** Pull coordinates out of a URL string, trying most-precise sources first. */
    fun parseCoords(url: String): Pair<Double, Double>? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        for (re in listOf(MARKER_RE, PARAM_RE, AT_RE)) {
            val m = re.find(url) ?: re.find(decoded)
            if (m != null) {
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                    return lat to lng
                }
            }
        }
        return null
    }

    /** A human name from a /maps/place/<Name>/ URL, if present. */
    fun parseName(url: String): String? {
        val m = PLACE_NAME_RE.find(url) ?: return null
        val raw = m.groupValues[1]
        return runCatching { URLDecoder.decode(raw, "UTF-8") }
            .getOrDefault(raw)
            .replace('+', ' ')
            .trim()
            .ifEmpty { null }
    }

    /** Best-effort name hint from the shared text: the first non-URL, non-coordinate line. */
    private fun nameHintFromText(text: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("http") && !BARE_RE.matches(it) }

    suspend fun resolve(rawText: String, subject: String?): Resolved = withContext(Dispatchers.IO) {
        val text = rawText.trim()

        // 0. A geo: URI (from the VIEW intent) — coordinates are right there.
        GEO_RE.find(text)?.let { m ->
            return@withContext Resolved(
                name = subject?.trim()?.ifEmpty { null } ?: nameHintFromText(text),
                lat = m.groupValues[1].toDouble(),
                lng = m.groupValues[2].toDouble(),
                resolvedUrl = text,
                rawText = rawText,
                note = null,
            )
        }

        val nameHint = subject?.trim()?.ifEmpty { null } ?: nameHintFromText(text)
        val url = extractFirstUrl(text)

        // 1. No URL at all — maybe the user shared bare coordinates.
        if (url == null) {
            val bare = BARE_RE.find(text)
            return@withContext if (bare != null) {
                Resolved(nameHint, bare.groupValues[1].toDouble(), bare.groupValues[2].toDouble(),
                    null, rawText, null)
            } else {
                Resolved(nameHint, null, null, null, rawText,
                    "No link or coordinates found in the shared text.")
            }
        }

        // 2. Follow redirects to the real maps URL.
        val finalUrl = runCatching { follow(url) }.getOrNull() ?: url

        // 3. Coordinates from the resolved URL.
        parseCoords(finalUrl)?.let { (lat, lng) ->
            return@withContext Resolved(
                name = parseName(finalUrl) ?: nameHint,
                lat = lat, lng = lng, resolvedUrl = finalUrl, rawText = rawText, note = null,
            )
        }

        // 4. Last resort: scan the page body for the marker.
        val body = runCatching { fetchBody(finalUrl) }.getOrNull()
        if (body != null) {
            (MARKER_RE.find(body) ?: AT_RE.find(body))?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null) {
                    return@withContext Resolved(
                        name = parseName(finalUrl) ?: nameHint,
                        lat = lat, lng = lng, resolvedUrl = finalUrl, rawText = rawText, note = null,
                    )
                }
            }
        }

        Resolved(parseName(finalUrl) ?: nameHint, null, null, finalUrl, rawText,
            "Resolved the link but could not extract coordinates from it.")
    }

    /** Follow up to 6 redirects manually and return the final URL. */
    private fun follow(startUrl: String): String {
        var current = startUrl
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return current
                    current = URL(URL(current), loc).toString()
                } else {
                    return current
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private fun fetchBody(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (total < 400_000) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** The payload OverDrive will later POST into Telenav's favourites (Place model). */
    fun toCarJson(r: Resolved): String {
        val name = r.name ?: "Shared location"
        return buildString {
            append("{\n")
            append("""  "favoriteType": "Normal",""").append('\n')
            append("""  "placeName": "${jsonEscape(name)}",""").append('\n')
            append("""  "geoLatitude": ${r.lat},""").append('\n')
            append("""  "geoLongitude": ${r.lng},""").append('\n')
            append("""  "navLatitude": ${r.lat},""").append('\n')
            append("""  "navLongitude": ${r.lng}""").append('\n')
            append("}")
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
