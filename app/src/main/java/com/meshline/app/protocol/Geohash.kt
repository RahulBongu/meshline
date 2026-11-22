package com.meshline.app.protocol

/**
 * Standard base32 geohash encode/decode (used by GEO_PING, §6.10).
 *
 * Pure JVM — no Android, no secrets. The encode follows the canonical
 * geohash algorithm: alternate lat/lon bits, lon/lat bounds alternating
 * per character (first char splits latitude).
 *
 * Notes on units and privacy:
 *  - A geohash of length 6 spans about 0.1 km (a few hundred meters) in
 *    latitude and ~1 km in longitude; length 5 is coarser. Geo pings
 *    therefore disclose only a coarse area, never a precise coordinate.
 *  - [encode] uses `>=` comparisons for the "east/north" side of each
 *    midpoint, matching the reference implementation.
 */
object Geohash {

    /** Standard geohash base32 alphabet (RFC: 0-9, b-v minus a, e, i, l, o, u, y). */
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    const val MAX_LENGTH: Int = 12

    fun isValid(s: String): Boolean =
        s.isNotEmpty() && s.length <= MAX_LENGTH && s.all { BASE32.indexOf(it) >= 0 }

    /**
     * Encode (lat, lon) at [precision] chars. Returns a string of exactly
     * [precision] chars; the returned geohash's cell contains the point.
     */
    fun encode(lat: Double, lon: Double, precision: Int = 6): String {
        if (precision !in 1..MAX_LENGTH) throw IllegalArgumentException("precision must be 1..$MAX_LENGTH")
        if (lat < -90.0 || lat > 90.0) throw IllegalArgumentException("latitude out of range: $lat")
        if (lon < -180.0 || lon > 180.0) throw IllegalArgumentException("longitude out of range: $lon")
        val sb = StringBuilder(precision)
        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0
        var even = true
        while (sb.length < precision) {
            var c = 0
            for (bit in 4 downTo 0) {
                c = c shl 1
                if (even) {
                    val mid = (latLow + latHigh) / 2
                    if (lat >= mid) {
                        latLow = mid
                        c += 1
                    } else {
                        latHigh = mid
                    }
                } else {
                    val mid = (lonLow + lonHigh) / 2
                    if (lon >= mid) {
                        lonLow = mid
                        c += 1
                    } else {
                        lonHigh = mid
                    }
                }
                even = !even
            }
            sb.append(BASE32[c])
        }
        return sb.toString()
    }

    /**
     * Decode a geohash to the **center** of its cell (lat, lon).
     * @throws IllegalArgumentException if the geohash is invalid.
     */
    fun decode(geohash: String): Pair<Double, Double> {
        if (!isValid(geohash)) throw IllegalArgumentException("invalid geohash: \"$geohash\"")
        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0
        var even = true
        for (ch in geohash) {
            val c = BASE32.indexOf(ch)
            for (bit in 4 downTo 0) {
                val b = (c shr bit) and 1
                if (even) {
                    val mid = (latLow + latHigh) / 2
                    if (b == 1) latLow = mid else latHigh = mid
                } else {
                    val mid = (lonLow + lonHigh) / 2
                    if (b == 1) lonLow = mid else lonHigh = mid
                }
                even = !even
            }
        }
        return ((latLow + latHigh) / 2) to ((lonLow + lonHigh) / 2)
    }
}
