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

    /**
     * Total attempts made for a single fetch before giving up and
     * returning null for this tick. Deliberately small: this call runs
     * inside a WorkManager Worker holding a wake lock for its duration
     * (see RefreshWorker), so retries here extend how long the device
     * stays awake. A tick that exhausts all attempts isn't the end of
     * the line — the next alarm fires in 5 minutes regardless (see
     * WidgetUpdateUtil.onChainTick), so this only needs to smooth over
     * brief, transient failures, not guarantee eventual success.
     */
    private const val MAX_ATTEMPTS = 3

    /** Base delay before the first retry; doubles on each subsequent attempt. */
    private const val BASE_RETRY_DELAY_MILLIS = 1_000L

    /** Upper bound on any single retry delay, including one derived from a Retry-After header. */
    private const val MAX_RETRY_DELAY_MILLIS = 10_000L

    /**
     * HTTP response codes worth retrying: 429 (rate limited) and the 5xx
     * range (server-side/upstream trouble, often transient). Other 4xx
     * codes indicate a problem with the request itself that a retry
     * won't fix, so those fail immediately instead.
     */
    private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)

    private data class PricePoint(val millisUtc: Long, val price: Double)

    private sealed class FetchOutcome {
        data class Success(val points: List<PricePoint>) : FetchOutcome()
        data class Retryable(val reason: String, val retryAfterMillis: Long? = null) : FetchOutcome()
        data class Failed(val reason: String) : FetchOutcome()
    }

    /**
     * Returns the exponentially-weighted average price (in cents/kWh)
     * anchored on the most recent data point returned by the API, or null
     * if the data could not be fetched or parsed after retries.
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

    /**
     * Attempts the fetch up to MAX_ATTEMPTS times, retrying only on
     * transient outcomes (network exceptions, 429, or 5xx) with a
     * short exponential backoff between attempts. A non-retryable
     * outcome (e.g. a 4xx other than 429, or a successful-but-unparseable
     * body) or exhausting all attempts both result in null.
     */
    private fun fetchPricePoints(): List<PricePoint>? {
        var attempt = 1
        while (true) {
            when (val outcome = attemptFetch()) {
                is FetchOutcome.Success -> {
                    Log.d(TAG, "Fetched ${outcome.points.size} price points (attempt $attempt)")
                    return outcome.points
                }
                is FetchOutcome.Failed -> {
                    Log.w(TAG, "Fetch failed, not retrying: ${outcome.reason}")
                    return null
                }
                is FetchOutcome.Retryable -> {
                    if (attempt >= MAX_ATTEMPTS) {
                        Log.w(TAG, "Fetch failed after $attempt attempts: ${outcome.reason}")
                        return null
                    }
                    val delayMillis = backoffDelayMillis(attempt, outcome.retryAfterMillis)
                    Log.w(
                        TAG,
                        "Fetch attempt $attempt failed (${outcome.reason}), retrying in ${delayMillis}ms"
                    )
                    Thread.sleep(delayMillis)
                    attempt++
                }
            }
        }
    }

    private fun backoffDelayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        if (retryAfterMillis != null) {
            return retryAfterMillis.coerceIn(0L, MAX_RETRY_DELAY_MILLIS)
        }
        val exponential = BASE_RETRY_DELAY_MILLIS * (1L shl (attempt - 1))
        return exponential.coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    /** Performs a single connection attempt and classifies the result. */
    private fun attemptFetch(): FetchOutcome {
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
                when {
                    code == HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        FetchOutcome.Success(parsePoints(body))
                    }
                    code in RETRYABLE_HTTP_CODES -> {
                        val retryAfterMillis = parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
                        FetchOutcome.Retryable("HTTP $code", retryAfterMillis)
                    }
                    else -> FetchOutcome.Failed("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Timeouts, connection resets, DNS hiccups, etc. Treated as
            // transient — worth a retry within this tick.
            FetchOutcome.Retryable("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Parses a Retry-After header given in delay-seconds form (e.g. "2").
     * The HTTP-date form is not handled, since ComEd's feed has no
     * documented use of Retry-After at all; this is a defensive best
     * effort for if that ever changes, not a relied-upon behavior.
     */
    private fun parseRetryAfterMillis(headerValue: String?): Long? {
        val seconds = headerValue?.toLongOrNull() ?: return null
        if (seconds < 0) return null
        return seconds * 1000L
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
