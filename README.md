# ComEd Price Widgets

Two homescreen widgets showing the current ComEd hourly electricity price:

- **Current Price** — a small 1×1 widget showing just the price and last-
  updated time, e.g. `⚡2.3¢`.
- **Price Time Series** — a larger, freely resizable widget (from a small
  multi-cell size up to full screen width/height) showing the current
  price plus a short-term time series chart, with a configuration screen
  for chart style (line or bar) and how many hours the time series
  covers. Unlike Current Price, its background does not change with the
  price band — it stays a plain, theme-adaptive card (matching the look
  of standard Android widgets like Gmail, Calendar, and News), and the
  price is conveyed instead by the chart's line/bar coloring and the
  price text.

## What they display

The price shown is an exponentially-weighted average of 5-minute price
points from ComEd's API
(`https://hourlypricing.comed.com/api?type=5minutefeed`), anchored on the
most recent data point. Recent prices count more than older ones, fading
out smoothly by age rather than a hard cutoff — see `ComedApiClient.kt`
for the tunable half-life. Both widgets are driven by the same fetch each
tick; the Price Time Series widget additionally keeps the raw points from
that fetch to draw its chart.

If the network request fails, or ComEd's feed itself hasn't published a
new point recently enough to trust, both widgets show `⚡ — ¢` rather than
a stale-looking value — except when a fetch fails in a way that looks
like "no network was available at all" while the device is in Doze idle
mode, in which case the last known-good price (and, for Price Time
Series, its chart) is redisplayed instead. See the comments on
`WidgetUpdateUtil.refreshAllWidgets` for the exact reasoning.

Tapping either widget's background triggers an immediate refresh, rather
than waiting for the next scheduled 5-minute update. On the Price Time
Series widget, tapping the small "ComEd Live Prices" link instead opens
ComEd's live-prices page in the browser.

There is no screen-off check anywhere in the refresh path — the 5-minute
background refresh runs unconditionally on every alarm tick, regardless
of whether the screen is on. AlarmManager's own Doze-aware throttling
(`setExactAndAllowWhileIdle` / `setAndAllowWhileIdle`) is what limits how
often updates actually happen during long idle stretches; there's no
separate app-level screen-state gate layered on top of it.

## Opening the project

1. Open Android Studio.
2. **File > Open**, and select the `ComEdPriceWidgets` folder (the one
   containing this README).
3. Let Gradle sync. Android Studio will regenerate the Gradle wrapper jar
   automatically on first sync — you do not need internet access to any
   host other than the standard Google/Maven/Gradle repositories during
   this step.
4. **Build > Build Bundle(s) / APK(s) > Build APK(s)** to produce an APK,
   or **Run** to install it on a connected device/emulator directly.

## Adding a widget

Long-press the homescreen → **Widgets** → **ComEd Price Widgets** →
choose **Current Price** or **Price Time Series** → drag it onto the
screen.

- **Current Price** occupies a single 1×1 cell.
- **Price Time Series** is placed at a 3×2 default size and can be
  freely resized afterward in both directions, up to the full screen.
  Placing it opens a one-time configuration screen (also reachable later
  by removing and re-adding it, or via the launcher's widget-reconfigure
  gesture where supported) to choose the chart style and time series
  window.

## One-time setup for reliable 5-minute refreshes (recommended)

Android does not natively allow a widget to refresh faster than every 30
minutes through its standard update mechanism, so this app schedules its
own alarms (`AlarmManager.setExactAndAllowWhileIdle`) to hit the 5-minute
cadence, backed by a 15-minute WorkManager job as a fallback in case the
alarm chain gets interrupted. This one chain drives both widgets — it
starts as soon as either widget type has at least one instance placed,
and only stops once neither does. Two OS-level settings affect how
reliably it runs in the background:

1. **Alarms & reminders** (Android 12+ / API 31+ only): open
   **Settings → Apps → ComEd Price Widgets → Alarms & reminders** and
   enable it. Without this, refreshes fall back to an inexact alarm that
   the OS may delay, especially in Doze mode.
2. **Battery optimization**: open **Settings → Apps → ComEd Price
   Widgets → Battery**, and choose **Unrestricted** (wording varies by
   Android version/OEM: "Don't optimize" / "No restrictions"). This
   matters most on Samsung, Xiaomi, and other OEM skins with aggressive
   background-kill policies.

Neither step is required for the app to function — without them the
widgets still refresh, just potentially less punctually while the
device is idle.

## Project structure

```
app/src/main/java/com/github/csande/comedpricewidgets/
  ComedApiClient.kt                          Fetches the ComEd feed, computes the
                                              weighted average, and returns the
                                              raw points used by the time series chart
  WidgetUpdateUtil.kt                        Shared logic: renders both widgets'
                                              views, schedules the shared alarm
                                              chain and WorkManager fallback, and
                                              manages per-time-series-widget config
  PriceChartRenderer.kt                      Draws the time series chart (line or
                                              bar) onto a Bitmap for the time series
                                              widget
  RefreshWorker.kt                           WorkManager Worker that performs the
                                              refresh for both widgets
  PriceWidgetProvider.kt                     AppWidgetProvider: Current Price
                                              widget lifecycle
  PriceTimeSeriesWidgetProvider.kt           AppWidgetProvider: Price Time Series
                                              widget lifecycle, resize, and
                                              per-instance cleanup
  PriceTimeSeriesWidgetConfigureActivity.kt  Chart style / time-series-length
                                              config screen shown when a Price
                                              Time Series widget is placed
  PriceAlarmReceiver.kt                      Fires every 5 minutes, hands off to
                                              WorkManager
  ManualRefreshReceiver.kt                   Handles tap-to-refresh for both
                                              widgets
  BootReceiver.kt                            Re-arms scheduling after a device
                                              reboot
  WatchdogWorker.kt                          Periodic fallback that restarts the
                                              chain if it's gone stale

app/src/main/res/
  layout/price_widget.xml                             Current Price widget layout
  layout/price_time_series_widget.xml                 Price Time Series widget layout
  layout/activity_price_time_series_widget_configure.xml
                                                        Price Time Series config screen layout
  xml/price_widget_info.xml                           Current Price widget metadata
  xml/price_time_series_widget_info.xml               Price Time Series widget metadata
                                                        (resizable, has a configure
                                                        activity)
  drawable/widget_background_{green,orange,red,unknown}.xml
                                                        Rounded, price-band-colored
                                                        backgrounds used only by
                                                        Current Price
  drawable/widget_background_neutral.xml              Fixed, theme-adaptive card
                                                        background used by Price
                                                        Time Series (see above)
  values/colors.xml                                   Text color + one color per
                                                        price band
  values/strings.xml                                   App name, widget picker
                                                        labels, and widget/config
                                                        screen text
  mipmap-anydpi-v26/                                  Adaptive launcher icon
```

## Attribution

The launcher icon's lightning-bolt artwork is Google's Noto Emoji "high
voltage sign" (U+26A1) glyph, from
[googlefonts/noto-emoji](https://github.com/googlefonts/noto-emoji),
licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

The Price Time Series widget's chart layout and behavior are ported from
a companion KDE Plasma widget (line/bar time series chart with
band-colored segments, nice-number axis ticks, and a configurable time
series window), adapted here to Android's RemoteViews/Bitmap-based
widget model.

## Notes / possible future improvements

- The app has no launcher activity by design (it's widget-only, aside
  from the Price Time Series configuration screen); to reach its system
  settings page, go through **Settings → Apps → See all apps → ComEd
  Price Widgets** rather than the app drawer.
- The API is queried via its default "last 24 hours" endpoint rather
  than the custom time-range endpoint, to avoid ambiguity about which
  timezone the custom endpoint's `datestart`/`dateend` parameters expect.
  This means each refresh downloads slightly more data than strictly
  necessary (roughly 288 small JSON records), which is a reasonable
  trade-off for correctness but could be tightened later.
- The Price Time Series widget's chart Bitmap is sized from an
  approximation of the widget's laid-out pixel dimensions (RemoteViews
  has no way to measure the actual ImageView before rendering into it)
  — see the comment on `WidgetUpdateUtil.computeChartPixelSize` for the
  constants involved if the chart ever looks noticeably cropped or
  under-filled at unusual widget sizes.
