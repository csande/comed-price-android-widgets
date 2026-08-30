package com.example.comedpricewidget

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches 5-minute price data from ComEd's Hourly Pricing API
 * (https://hourlypricing.comed.com/hp-api/) and computes an
 * exponentially-weighted average price, most heavily weighted toward the
 * most recent data point.
 *
 * This performs blocking network I/O and must only be called from a
 * background thread (e.g. from within a WorkManager Worker).
 */
object ComedApiClient {

    private const val TAG = "ComedApiClient"
    private const val FEED_URL = "https://hourlypricing.comed.com/api?type=5minutefeed"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * A price this many minutes older than the most recent data point
     * counts for half as much as the most recent point; a price twice
     * that old counts for a quarter as much, and so on. This is the
     * single knob to tune the displayed price's responsiveness: lower
     * values track recent prices more tightly, higher values smooth out
     * more short-term noise.
     *
     * 10 minutes was chosen empirically: this widget is checked manually
     * at moments when no low-price notification has fired, specifically
     * to confirm the current price is acceptable before running an
     * appliance. Backtesting against a real single-tick price spike in
     * ComEd's feed showed the displayed value takes roughly 3x the
     * half-life to fall back into the correct color band after such a
     * spike resolves — so a shorter half-life recovers to an accurate
     * reading faster, which matters more here than protecting against
     * the rare case of checking at the exact instant of a spike (which
     * is both uncommon and self-correcting via the manual refresh).
     */
    private const val HALF_LIFE_MINUTES = 10.0

    /**
     * Points older than this contribute a genuinely negligible amount
     * (well under 0.1% at the half-life above) and are dropped before
     * computing the weighted sum, purely to avoid summing hundreds of
     * near-zero terms unnecessarily.
     */
    private const val MAX_AGE_MINUTES = 120.0

    private data class PricePoint(val millisUtc: Long, val price: Double)

    /**
     * Returns the exponentially-weighted average price (in cents/kWh)
     * anchored on the most recent data point returned by the API, or null
     * if the data could not be fetched or parsed.
     */
    fun fetchAveragePrice(): Double? {
        val points = fetchPricePoints() ?: return null
        if (points.isEmpty()) return null

        // The feed is documented to return points most-recent-first, but
        // that ordering isn't relied on here; the true max is used instead.
        val mostRecentMillis = points.maxOf { it.millisUtc }

        var weightedSum = 0.0
        var weightTotal = 0.0
        for (point in points) {
            val ageMinutes = (mostRecentMillis - point.millisUtc) / 60_000.0
            if (ageMinutes > MAX_AGE_MINUTES) continue
            val weight = Math.pow(0.5, ageMinutes / HALF_LIFE_MINUTES)
            weightedSum += weight * point.price
            weightTotal += weight
        }
        if (weightTotal == 0.0) return null

        return weightedSum / weightTotal
    }

    private fun fetchPricePoints(): List<PricePoint>? {
        return try {
            val connection = (URL(FEED_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Some CDNs/WAFs reject requests with no User-Agent or the
                // default "Java/x.x.x" one HttpURLConnection sends.
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ComEdPriceWidget/1.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Unexpected response code: $code")
                    return null
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val points = parsePoints(body)
                Log.d(TAG, "Fetched ${points.size} price points")
                points
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun parsePoints(json: String): List<PricePoint> {
        val array = JSONArray(json)
        val result = ArrayList<PricePoint>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val millis = obj.getString("millisUTC").toLongOrNull() ?: continue
            val price = obj.getString("price").toDoubleOrNull() ?: continue
            result.add(PricePoint(millis, price))
        }
        return result
    }
}