package com.example.comedpricewidget

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches 5-minute price data from ComEd's Hourly Pricing API
 * (https://hourlypricing.comed.com/hp-api/) and computes the average price
 * over the 30-minute window ending at the most recent data point.
 *
 * This performs blocking network I/O and must only be called from a
 * background thread (e.g. from within a WorkManager Worker).
 */
object ComedApiClient {

    private const val TAG = "ComedApiClient"
    private const val FEED_URL = "https://hourlypricing.comed.com/api?type=5minutefeed"
    private const val WINDOW_MILLIS = 30L * 60L * 1000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private data class PricePoint(val millisUtc: Long, val price: Double)

    /**
     * Returns the average price (in cents/kWh) of all data points within
     * 30 minutes of the most recent data point returned by the API, or
     * null if the data could not be fetched or parsed.
     */
    fun fetchAveragePrice(): Double? {
        val points = fetchPricePoints() ?: return null
        if (points.isEmpty()) return null

        // The feed is documented to return points most-recent-first, but
        // that ordering isn't relied on here; the true max is used instead.
        val mostRecentMillis = points.maxOf { it.millisUtc }
        val windowStart = mostRecentMillis - WINDOW_MILLIS

        val inWindow = points.filter { it.millisUtc in windowStart..mostRecentMillis }
        if (inWindow.isEmpty()) return null

        return inWindow.sumOf { it.price } / inWindow.size
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
