package no.stink.bydshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-parser tests — no network. Covers the URL shapes Google Maps actually produces. */
class MapsLinkResolverTest {

    @Test
    fun markerCoords() {
        val url = "https://www.google.com/maps/place/Oslo/@59.9139,10.7522,15z/" +
            "data=!3m1!4b1!4m6!3d59.9138688!4d10.7522454"
        val c = MapsLinkResolver.parseCoords(url)
        assertNotNull(c)
        assertEquals(59.9138688, c!!.first, 1e-6)
        assertEquals(10.7522454, c.second, 1e-6)
    }

    @Test
    fun atCenterCoords() {
        val url = "https://www.google.com/maps/@63.4305,10.3951,17z"
        val c = MapsLinkResolver.parseCoords(url)
        assertNotNull(c)
        assertEquals(63.4305, c!!.first, 1e-6)
        assertEquals(10.3951, c.second, 1e-6)
    }

    @Test
    fun queryParamCoords() {
        val url = "https://maps.google.com/?q=60.39299,5.32415"
        val c = MapsLinkResolver.parseCoords(url)
        assertNotNull(c)
        assertEquals(60.39299, c!!.first, 1e-6)
        assertEquals(5.32415, c.second, 1e-6)
    }

    @Test
    fun encodedQueryCoords() {
        val url = "https://www.google.com/maps/search/?api=1&query=59.911%2C10.750"
        val c = MapsLinkResolver.parseCoords(url)
        assertNotNull(c)
        assertEquals(59.911, c!!.first, 1e-6)
        assertEquals(10.750, c.second, 1e-6)
    }

    @Test
    fun rejectsOutOfRange() {
        // A version-looking "1.2,3.4" style false positive should not slip through @-matching.
        assertNull(MapsLinkResolver.parseCoords("https://example.com/path/v1.2.3"))
    }

    @Test
    fun placeName() {
        val url = "https://www.google.com/maps/place/Fj%C3%B8rukroa+Kafeteria/@62.0,9.0,15z"
        assertEquals("Fjørukroa Kafeteria", MapsLinkResolver.parseName(url))
    }

    @Test
    fun extractsUrlFromSharedText() {
        val text = "Oslo Opera House\nhttps://maps.app.goo.gl/abc123XYZ"
        assertEquals("https://maps.app.goo.gl/abc123XYZ", MapsLinkResolver.extractFirstUrl(text))
    }

    @Test
    fun trimsTrailingPunctuation() {
        val text = "Check this out (https://maps.app.goo.gl/abc123)."
        assertEquals("https://maps.app.goo.gl/abc123", MapsLinkResolver.extractFirstUrl(text))
    }

    @Test
    fun geocodeQueriesSplitsLabel() {
        val q = MapsLinkResolver.geocodeQueries("Ringerikshallen, Tyrimyrveien 1, 3515 Hønefoss")
        // Full string first, then the name alone (which is what actually resolves), then the address.
        assertEquals("Ringerikshallen, Tyrimyrveien 1, 3515 Hønefoss", q[0])
        assertEquals("Ringerikshallen", q[1])
        assertEquals("Tyrimyrveien 1, 3515 Hønefoss", q[2])
    }

    @Test
    fun droppedPinNameIsNotACoordinate() {
        // Google shares a dropped pin as /maps/place/<lat,lng>/... — that must not be treated as a name.
        val url = "https://www.google.com/maps/place/60.185931,10.261529/data=!3d60.18!4d10.26"
        assertNull(MapsLinkResolver.parseName(url))
        assertTrue(MapsLinkResolver.looksLikeCoords("60.185931,10.261529"))
        assertFalse(MapsLinkResolver.looksLikeCoords("Ringerikshallen"))
    }

    @Test
    fun geocodeQueriesSingleLabel() {
        assertEquals(listOf("Ringerikshallen"), MapsLinkResolver.geocodeQueries("Ringerikshallen"))
    }
}
