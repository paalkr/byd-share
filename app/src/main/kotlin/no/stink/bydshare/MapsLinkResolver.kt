package no.stink.bydshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Turns a shared Google Maps payload into a place with coordinates.
 *
 * Two cases have to work:
 *  1. The share already carries coordinates (a dropped pin, "share my location", a plus code,
 *     many link forms) — we read them straight out of the resolved URL.
 *  2. The share is a business/place with only a feature-id and no coordinates in the URL or
 *     page — we geocode the name/address (OpenStreetMap / Nominatim), because Telenav's
 *     add-to-favourites path does NOT geocode: it stores whatever coordinates the Place carries.
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
        val source: String? = null,
    ) {
        val hasCoords: Boolean get() = lat != null && lng != null
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    // Nominatim's usage policy requires an identifying User-Agent.
    private const val GEOCODE_USER_AGENT = "byd-share (github.com/paalkr/byd-share; personal project)"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val NOMINATIM_REVERSE = "https://nominatim.openstreetmap.org/reverse"

    private val URL_RE = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val MARKER_RE = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val PARAM_RE = Regex(
        """[?&](?:q|query|ll|sll|saddr|daddr|destination|center|viewpoint)=""" +
            """(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val AT_RE = Regex("""[/@](-?\d+\.\d+),(-?\d+\.\d+)""")
    private val BARE_RE = Regex("""(-?\d{1,3}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})""")
    private val PLACE_NAME_RE = Regex("""/maps/place/([^/@]+)""")
    private val GEO_RE = Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""")

    // Nominatim JSON is small; pull the first result's lat/lon without a JSON dependency.
    private val NOMINATIM_LAT_RE = Regex(""""lat"\s*:\s*"(-?[0-9.]+)"""")
    private val NOMINATIM_LON_RE = Regex(""""lon"\s*:\s*"(-?[0-9.]+)"""")

    fun extractFirstUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')')

    /** True when a string is really just a "lat,lng" pair, not a human name. */
    fun looksLikeCoords(s: String): Boolean = BARE_RE.matches(s.trim())

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
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }
            .getOrDefault(raw)
            .replace('+', ' ')
            .trim()
        // A dropped pin's URL is /maps/place/<lat,lng>/... — that's not a name.
        return decoded.ifEmpty { null }?.takeUnless { looksLikeCoords(it) }
    }

    /** Best-effort name hint from the shared text: the first non-URL, non-coordinate line. */
    private fun nameHintFromText(text: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.isNotEmpty() && !it.startsWith("http") && !it.startsWith("geo:") &&
                    !BARE_RE.matches(it)
            }

    /**
     * Progressively simpler geocode queries for a place label. Nominatim struggles with a
     * "Name, street, postcode city" concatenation, so we also try the name alone and the
     * address part alone. Order matters — first hit wins.
     */
    fun geocodeQueries(name: String): List<String> {
        val full = name.trim()
        val parts = full.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = mutableListOf(full)
        if (parts.size > 1) {
            candidates += parts.first()                       // the POI / name
            candidates += parts.drop(1).joinToString(", ")    // the address part
        }
        return candidates.distinct().filter { it.isNotEmpty() }
    }

    suspend fun resolve(rawText: String, subject: String?): Resolved = withContext(Dispatchers.IO) {
        val text = rawText.trim()

        // 0. A geo: URI (from the VIEW intent) — coordinates are right there.
        GEO_RE.find(text)?.let { m ->
            return@withContext coordsResolved(
                realName = null,
                hint = subject?.trim()?.ifEmpty { null },
                lat = m.groupValues[1].toDouble(),
                lng = m.groupValues[2].toDouble(),
                resolvedUrl = text, rawText = rawText, note = null, source = "geo: link",
            )
        }

        val nameHint = subject?.trim()?.ifEmpty { null } ?: nameHintFromText(text)
        val url = extractFirstUrl(text)

        // 1. No URL at all — maybe the user shared bare coordinates.
        if (url == null) {
            val bare = BARE_RE.find(text)
            if (bare != null) {
                return@withContext coordsResolved(null, nameHint, bare.groupValues[1].toDouble(),
                    bare.groupValues[2].toDouble(), null, rawText, null, "coordinates in shared text")
            }
            // Nothing but a name? Try geocoding it.
            return@withContext geocodeInto(nameHint, null, rawText)
        }

        // 2. Follow redirects to the real maps URL.
        val finalUrl = runCatching { follow(url) }.getOrNull() ?: url
        val name = parseName(finalUrl) ?: nameHint

        // 3. Coordinates from the resolved URL.
        parseCoords(finalUrl)?.let { (lat, lng) ->
            return@withContext coordsResolved(parseName(finalUrl), nameHint, lat, lng, finalUrl,
                rawText, null, "Google Maps link")
        }

        // 4. Scan the page body for the marker.
        val body = runCatching { fetchBody(finalUrl) }.getOrNull()
        if (body != null) {
            (MARKER_RE.find(body) ?: AT_RE.find(body))?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null) {
                    return@withContext coordsResolved(parseName(finalUrl), nameHint, lat, lng, finalUrl,
                        rawText, null, "Google Maps page")
                }
            }
        }

        // 5. A place/address share with no coordinates anywhere — geocode the name.
        geocodeInto(name, finalUrl, rawText)
    }

    private fun geocodeInto(name: String?, resolvedUrl: String?, rawText: String): Resolved {
        // A "name" that is really a lat,lng pair (Google sometimes shares the coordinates
        // as the title) must NOT be forward-geocoded and kept as the label. Treat it as
        // coordinates and reverse-geocode a proper name instead.
        if (name != null && looksLikeCoords(name)) {
            val m = BARE_RE.find(name)
            if (m != null) {
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null) {
                    return coordsResolved(null, null, lat, lng, resolvedUrl, rawText, null,
                        "coordinates in shared text")
                }
            }
        }
        if (name.isNullOrBlank()) {
            return Resolved(name, null, null, resolvedUrl, rawText,
                "No link or coordinates found, and no name to look up.", null)
        }
        val hit = runCatching { geocode(name) }.getOrNull()
        return if (hit != null) {
            Resolved(name, hit.first, hit.second, resolvedUrl, rawText,
                "Coordinates looked up from the address — check they're right.",
                "address lookup (Nominatim / OpenStreetMap)")
        } else {
            Resolved(name, null, null, resolvedUrl, rawText,
                "Could not find coordinates for this address.", null)
        }
    }

    // Google's generic placeholder labels for a dropped pin, per locale. These are not real
    // names, so we reverse-geocode instead of keeping them. Extend as more locales show up.
    private val GENERIC_PIN_NAMES = setOf(
        "dropped pin", "pinned location", "selected location", "pin",
        "festet knappenål", "festet knappenal", "valgt sted", // nb
        "markerad plats", "släppt nål",                        // sv
        "fastgjort nål", "valgt placering",                    // da
        "markierter ort", "stecknadel",                        // de
        "épingle déposée", "lieu sélectionné",                 // fr
    )

    private fun isGenericPinName(s: String): Boolean =
        GENERIC_PIN_NAMES.contains(s.trim().lowercase())

    /**
     * Wrap a coordinate result. Priority for the favourite's name:
     *   1. a real name carried by the link (a place / address),
     *   2. else a reverse-geocoded area name (a dropped pin has none),
     *   3. else a weak hint (share subject / text line) — unless it's coordinates or Google's
     *      generic "dropped pin" placeholder.
     * A favourite list of bare coordinates or "Dropped pin" is useless.
     */
    private fun coordsResolved(
        realName: String?, hint: String?, lat: Double, lng: Double,
        resolvedUrl: String?, rawText: String, note: String?, source: String?,
    ): Resolved {
        val real = realName?.trim()?.ifEmpty { null }?.takeUnless { looksLikeCoords(it) }
        if (real != null) return Resolved(real, lat, lng, resolvedUrl, rawText, note, source)

        val looked = runCatching { reverseGeocode(lat, lng) }.getOrNull()
        if (looked != null) {
            return Resolved(looked, lat, lng, resolvedUrl, rawText,
                note ?: "Name looked up from the coordinates.", source)
        }

        val fallback = hint?.trim()?.ifEmpty { null }
            ?.takeUnless { looksLikeCoords(it) || isGenericPinName(it) }
        return Resolved(fallback, lat, lng, resolvedUrl, rawText, note, source)
    }

    // Area fields from most to least specific. A dropped pin gets the finest natural area name
    // (neighbourhood/suburb/…), not the coarse town — zoom 15 is where these appear.
    private val AREA_KEYS = listOf(
        "neighbourhood", "quarter", "suburb", "city_district",
        "hamlet", "village", "residential", "locality",
    )
    private val TOWN_KEYS = listOf("town", "city", "municipality")

    /** Reverse-geocode a point to a short, natural area label ("Tyrimyra, Hønefoss"). */
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        val lang = java.util.Locale.getDefault().toLanguageTag()
        val url = "$NOMINATIM_REVERSE?format=jsonv2&zoom=15&addressdetails=1&namedetails=1&lat=$lat&lon=$lng"
        val body = runCatching { httpGet(url, GEOCODE_USER_AGENT, "$lang,en;q=0.8") }.getOrNull()
            ?: return null
        return runCatching {
            val obj = org.json.JSONObject(body)
            val addr = obj.optJSONObject("address")
            val area = addr?.let { a -> AREA_KEYS.firstNotNullOfOrNull { a.optString(it, "").ifEmpty { null } } }
            val town = addr?.let { a -> TOWN_KEYS.firstNotNullOfOrNull { a.optString(it, "").ifEmpty { null } } }
            val primary = area ?: obj.optString("name", "").ifEmpty { null }
            when {
                primary != null && town != null && !primary.equals(town, ignoreCase = true) -> "$primary, $town"
                primary != null -> primary
                town != null -> town
                else -> obj.optString("display_name", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .take(2).joinToString(", ").ifEmpty { null }
            }
        }.getOrNull()
    }

    /** Geocode a place label via Nominatim, trying progressively simpler queries. */
    private fun geocode(name: String): Pair<Double, Double>? {
        for ((i, q) in geocodeQueries(name).withIndex()) {
            if (i > 0) Thread.sleep(1100) // Nominatim asks for <= 1 request/second
            val body = runCatching { nominatim(q) }.getOrNull() ?: continue
            val lat = NOMINATIM_LAT_RE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val lng = NOMINATIM_LON_RE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat != null && lng != null) return lat to lng
        }
        return null
    }

    private fun nominatim(query: String): String {
        val lang = java.util.Locale.getDefault().toLanguageTag()
        return httpGet(
            "$NOMINATIM?format=json&limit=1&q=" + URLEncoder.encode(query, "UTF-8"),
            GEOCODE_USER_AGENT, "$lang,en;q=0.8",
        )
    }

    private fun httpGet(url: String, userAgent: String, acceptLanguage: String = "en-US,en;q=0.9"): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", acceptLanguage)
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
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
