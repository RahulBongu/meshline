package com.meshline.app.protocol

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the standard base32 geohash (used by GEO_PING, §6.10). */
class GeohashTest {

    @Test
    fun `encode returns exactly the requested precision`() {
        assertEquals(1, Geohash.encode(37.7749, -122.4194, 1).length)
        assertEquals(5, Geohash.encode(37.7749, -122.4194, 5).length)
        assertEquals(6, Geohash.encode(37.7749, -122.4194, 6).length)
        assertEquals(12, Geohash.encode(37.7749, -122.4194, 12).length)
    }

    @Test
    fun `encode is deterministic`() {
        assertEquals(Geohash.encode(1.5, 2.5, 6), Geohash.encode(1.5, 2.5, 6))
        assertEquals(Geohash.encode(0.0, 0.0, 12), Geohash.encode(0.0, 0.0, 12))
    }

    @Test
    fun `encode of distinct areas is distinct`() {
        // Far-apart points must never produce the same cell.
        assertFalse(Geohash.encode(37.7749, -122.4194, 6) == Geohash.encode(48.8566, 2.3522, 6))
    }

    @Test
    fun `encode rejects invalid inputs`() {
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(91.0, 0.0, 6) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(-90.1, 0.0, 6) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(0.0, 181.0, 6) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(0.0, -180.1, 6) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(0.0, 0.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(0.0, 0.0, -1) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(0.0, 0.0, 13) }
    }

    @Test
    fun `encode+decode round trip stays within the cell (property sweep)`() {
        // At precision 6 the lat interval is 180/2^15 and the lon interval 360/2^15
        // (chars alternate between 3 and 2 lat bits); decode returns the cell
        // centre, so the deviation from the point is at most the interval.
        val latTol = 180.0 / 2.0.pow(15.0)
        val lonTol = 360.0 / 2.0.pow(15.0)
        val points = listOf(
            0.0 to 0.0,
            37.7749 to -122.4194,
            -33.8568 to 151.2093,
            12.9716 to 77.5946,
            -7.4044 to -60.0317,
            33.4484 to 70.2671,
            41.8788 to 12.4831,
            48.8566 to 2.3522,
        )
        for ((lat, lon) in points) {
            val (lat2, lon2) = Geohash.decode(Geohash.encode(lat, lon, 6))
            assertTrue("lat deviation for ($lat, $lon): |$lat2 - $lat|", Math.abs(lat2 - lat) <= latTol)
            assertTrue("lon deviation for ($lat, $lon): |$lon2 - $lon|", Math.abs(lon2 - lon) <= lonTol)
        }
    }

    @Test
    fun `isValid accepts real geohashes`() {
        assertTrue(Geohash.isValid(Geohash.encode(0.0, 0.0, 6)))
        assertTrue(Geohash.isValid(Geohash.encode(37.7749, -122.4194, 12)))
        assertTrue(Geohash.isValid("zzzzzzzzzzzz")) // 12 chars, all in the base32 alphabet
    }

    @Test
    fun `isValid rejects malformed strings`() {
        assertFalse(Geohash.isValid(""))
        assertFalse(Geohash.isValid("zzzzzzzzzzzzz")) // 13 chars > MAX_LENGTH
        assertFalse(Geohash.isValid("a!b")) // '!' is not in the base32 alphabet
        assertFalse(Geohash.isValid("ai")) // 'a' and 'i' are not in the base32 alphabet
    }

    @Test
    fun `decode rejects malformed geohashes`() {
        assertThrows(IllegalArgumentException::class.java) { Geohash.decode("") }
        assertThrows(IllegalArgumentException::class.java) { Geohash.decode("zzzzzzzzzzzzz") }
        assertThrows(IllegalArgumentException::class.java) { Geohash.decode("a!b") }
        assertThrows(IllegalArgumentException::class.java) { Geohash.decode("ai") }
    }
}
