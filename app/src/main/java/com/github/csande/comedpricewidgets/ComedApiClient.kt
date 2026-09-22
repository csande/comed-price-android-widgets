package com.github.csande.comedpricewidgets

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
     * If the feed's own most recent data point is older than this many
     * minutes relative to the device's current time, the feed itself is
     * considered too stale to trust, and this is treated as an outright
     * "price unavailable" result rather than displaying a number that
     * may no longer reflect reality — this is a distinct condition from
     * a fetch failure: the request succeeded, but the data it returned
     * is out of date.
     *
     * 20 minutes accounts for two independent, compounding sources of
     * age rather than just ComEd's publish lag in isolation: the widget's
     * own 5-minute poll cadence isn't synchronized to ComEd's publish
     * schedule, so a poll can land just before a new point is published
     * and only catch the previous one. Under entirely normal operation
     * that can push the observed age to roughly (publish lag + one poll
     * interval) — e.g. a ~4 minute publish lag plus a poorly-timed poll
     * can routinely show up as 8-9 minutes old, not just as a rare
     * fluke. 20 minutes restores a meaningful margin (about one extra
     * widget tick) above that realistic worst-normal case, while still
     * flagging a genuine feed outage within a few ticks of it starting,
     * since an outage adds a full 5 minutes of age on every subsequent
     * tick with nothing to reset it.
     */
    private const val FEED_STALENESS_THRESHOLD_MINUTES = 20.0

    /**
     * Total attempts made for a single fetch before giving up and
     * returning FetchFailed for this tick. Deliberately small: this call
     * runs inside a WorkManager Worker holding a wake lock for its
     * duration (see RefreshWorker), so retries here extend how long the
     * device stays awake. A tick that exhausts all attempts isn't the
     * end of the line — the next alarm fires in 5 minutes regardless
     * (see WidgetUpdateUtil.onChainTick), so this only needs to smooth
     * over brief, transient failures, not guarantee eventual success.
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

    /**
     * A single 5-minute feed reading. Public (unlike previously, when
     * this was a private implementation detail) so callers that need
     * more than the single weighted average — currently just the trend
     * widget's chart, see PriceChartRenderer — can consume the raw
     * series returned alongside it in PriceResult.Available.
     */
    data class PricePoint(val millisUtc: Long, val price: Double)

    /**
     * The outcome of fetchPrice(). Deliberately more than a nullable
     * Double so the caller can tell apart three situations that all used
     * to collapse into "null" and get the same "price unavailable"
     * treatment:
     *  - Available: a real, fresh price, along with the feed's own
     *    timestamp for its most recent data point (feedTimestampMillis)
     *    and the full set of points this tick's fetch returned (points).
     *    The widget displays feedTimestampMillis rather than "when the
     *    device last polled", since it's the more useful answer to "how
     *    current is this number" — the two can differ by several minutes
     *    even in normal operation, since ComEd's own publishing has some
     *    lag. points is the raw, unfiltered series (oldest first is not
     *    guaranteed — see mostRecentMillis's own max-based comment
     *    below); the trend widget filters and sorts it as needed.
     *  - FeedStale: the request succeeded, but the feed's own most recent
     *    data point is too old to trust (see FEED_STALENESS_THRESHOLD_MINUTES).
     *    Always shown as unavailable — the data that came back isn't
     *    trustworthy, regardless of connectivity.
     *  - FetchFailed: no usable response was obtained after retries.
     *    likelyConnectivityIssue is true only if every attempt failed
     *    with a connection-level exception (DNS failure, connect/read
     *    timeout, connection reset, etc.) and none ever got as far as
     *    receiving an HTTP response — i.e. nothing suggests the request
     *    ever reached ComEd's servers at all, as opposed to reaching
     *    them and getting back an error or empty body. The caller
     *    combines this with device state (e.g. Doze) to decide whether
     *    this looks enough like "no network was available" to warrant
     *    showing a stale price instead of "unavailable".
     */
    sealed class PriceResult {
        data class Available(
            val average: Double,
            val feedTimestampMillis: Long,
            val points: List<PricePoint>
        ) : PriceResult()
        object FeedStale : PriceResult()
        data class FetchFailed(val likelyConnectivityIssue: Boolean) : PriceResult()
    }

    private sealed class FetchOutcome {
        data class Success(val points: List<PricePoint>) : FetchOutcome()
        data class Retryable(
            val reason: String,
            val sawHttpResponse: Boolean,
            val retryAfterMillis: Long? = null
        ) : FetchOutcome()
        data class Failed(val reason: String, val sawHttpResponse: Boolean) : FetchOutcome()
    }

    private sealed class PointsResult {
        data class Success(val points: List<PricePoint>) : PointsResult()
        data class Failure(val everSawHttpResponse: Boolean) : PointsResult()
    }

    /**
     * Fetches and evaluates the current price. See PriceResult for what
     * each outcome means.
     */
    fun fetchPrice(): PriceResult {
        when (val result = fetchPricePoints()) {
            is PointsResult.Failure -> {
                return PriceResult.FetchFailed(likelyConnectivityIssue = !result.everSawHttpResponse)
            }
            is PointsResult.Success -> {
                val points = result.points
                // fetchPricePoints() retries an empty-but-successful
                // response rather than returning it, so this shouldn't
                // occur in practice; treated defensively as a non-
                // connectivity failure rather than assumed impossible.
                if (points.isEmpty()) {
                    return PriceResult.FetchFailed(likelyConnectivityIssue = false)
                }

                // The feed is documented to return points most-recent-
                // first, but that ordering isn't relied on here; the
                // true max is used instead.
                val mostRecentMillis = points.maxOf { it.millisUtc }

                val feedAgeMinutes = (System.currentTimeMillis() - mostRecentMillis) / 60_000.0
                if (feedAgeMinutes > FEED_STALENESS_THRESHOLD_MINUTES) {
                    Log.w(
                        TAG,
                        "Most recent feed point is %.1f min old (threshold %.0f); treating as unavailable"
                            .format(feedAgeMinutes, FEED_STALENESS_THRESHOLD_MINUTES)
                    )
                    return PriceResult.FeedStale
                }

                var weightedSum = 0.0
                var weightTotal = 0.0
                for (point in points) {
                    val ageMinutes = (mostRecentMillis - point.millisUtc) / 60_000.0
                    if (ageMinutes > MAX_AGE_MINUTES) continue
                    val weight = Math.pow(0.5, ageMinutes / HALF_LIFE_MINUTES)
                    weightedSum += weight * point.price
                    weightTotal += weight
                }
                // Can't actually be 0 given points is non-empty (the most
                // recent point always has age 0, weight 1), but guarded
                // defensively rather than assumed.
                if (weightTotal == 0.0) {
                    return PriceResult.FetchFailed(likelyConnectivityIssue = false)
                }

                return PriceResult.Available(weightedSum / weightTotal, mostRecentMillis, points)
            }
        }
    }

    /**
     * Attempts the fetch up to MAX_ATTEMPTS times, retrying only on
     * transient outcomes (network exceptions, 429, 5xx, or a 200 response
     * with no usable price points) with a short exponential backoff
     * between attempts. Tracks, across all attempts, whether any of them
     * ever got as far as receiving an HTTP response at all — used by
     * fetchPrice() to distinguish "never reached the server" from
     * "reached it and got an error or empty body".
     */
    private fun fetchPricePoints(): PointsResult {
        var attempt = 1
        var everSawHttpResponse = false
        while (true) {
            when (val outcome = attemptFetch()) {
                is FetchOutcome.Success -> {
                    Log.d(TAG, "Fetched ${outcome.points.size} price points (attempt $attempt)")
                    return PointsResult.Success(outcome.points)
                }
                is FetchOutcome.Failed -> {
                    Log.w(TAG, "Fetch failed, not retrying: ${outcome.reason}")
                    everSawHttpResponse = everSawHttpResponse || outcome.sawHttpResponse
                    return PointsResult.Failure(everSawHttpResponse)
                }
                is FetchOutcome.Retryable -> {
                    everSawHttpResponse = everSawHttpResponse || outcome.sawHttpResponse
                    if (attempt >= MAX_ATTEMPTS) {
                        Log.w(TAG, "Fetch failed after $attempt attempts: ${outcome.reason}")
                        return PointsResult.Failure(everSawHttpResponse)
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
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ComEdPriceWidgets/1.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                // Reaching this line means an HTTP response was actually
                // received — connection-level failures throw before here.
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val points = parsePoints(body)
                        if (points.isEmpty()) {
                            // The request succeeded but returned nothing
                            // usable — worth a retry rather than treating
                            // this the same as a real, populated response.
                            FetchOutcome.Retryable(
                                "HTTP 200 with no usable price points",
                                sawHttpResponse = true
                            )
                        } else {
                            FetchOutcome.Success(points)
                        }
                    }
                    code in RETRYABLE_HTTP_CODES -> {
                        val retryAfterMillis = parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
                        FetchOutcome.Retryable("HTTP $code", sawHttpResponse = true, retryAfterMillis)
                    }
                    else -> FetchOutcome.Failed("HTTP $code", sawHttpResponse = true)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Timeouts, connection resets, DNS hiccups, etc. — no HTTP
            // response was ever received. Treated as transient and worth
            // a retry within this tick; also the signal fetchPrice() uses
            // to flag a failure as connectivity-like.
            FetchOutcome.Retryable("${e.javaClass.simpleName}: ${e.message}", sawHttpResponse = false)
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
