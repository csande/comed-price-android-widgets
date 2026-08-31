# ComEd Price Widget

A 1×1 homescreen widget that shows the current ComEd hourly electricity
price, e.g. `⚡2.3¢`.

## What it displays

The value shown is an exponentially-weighted average of 5-minute price
points from ComEd's API
(`https://hourlypricing.comed.com/api?type=5minutefeed`), anchored on the
most recent data point. Recent prices count more than older ones, fading
out smoothly by age rather than a hard cutoff — see `ComedApiClient.kt`
for the tunable half-life.

If the network request fails for any reason, the widget shows `⚡No data`
rather than a stale value.

Tapping the widget triggers an immediate refresh, rather than waiting for
the next scheduled 5-minute update.

The 5-minute background refresh is skipped whenever the screen is off
(checked via `PowerManager.isInteractive()`), since there's no point
spending a network fetch and battery on an update nobody can see. The
alarm chain itself keeps firing on schedule regardless — each firing
just checks the screen state and no-ops if it's off — so a refresh
happens promptly at whichever 5-minute tick the screen is next on for,
and a manual tap always refreshes immediately regardless of this check.

## Opening the project

1. Open Android Studio.
2. **File > Open**, and select the `ComEdPriceWidget` folder (the one
   containing this README).
3. Let Gradle sync. Android Studio will regenerate the Gradle wrapper jar
   automatically on first sync — you do not need internet access to any
   host other than the standard Google/Maven/Gradle repositories during
   this step.
4. **Build > Build Bundle(s) / APK(s) > Build APK(s)** to produce an APK,
   or **Run** to install it on a connected device/emulator directly.

## Adding the widget

Long-press the homescreen → **Widgets** → **ComEd Price Widget** → drag
it onto the screen. It occupies a single 1×1 cell.

## One-time setup for reliable 5-minute refreshes (recommended)

Android does not natively allow a widget to refresh faster than every 30
minutes through its standard update mechanism, so this app schedules its
own alarms (`AlarmManager.setExactAndAllowWhileIdle`) to hit the 5-minute
cadence, backed by a 15-minute WorkManager job as a fallback in case the
alarm chain gets interrupted. Two OS-level settings affect how reliably
this runs in the background:

1. **Alarms & reminders** (Android 12+ / API 31+ only): open
   **Settings → Apps → ComEd Price Widget → Alarms & reminders** and
   enable it. Without this, refreshes fall back to an inexact alarm that
   the OS may delay, especially in Doze mode.
2. **Battery optimization**: open **Settings → Apps → ComEd Price
   Widget → Battery**, and choose **Unrestricted** (wording varies by
   Android version/OEM: "Don't optimize" / "No restrictions"). This
   matters most on Samsung, Xiaomi, and other OEM skins with aggressive
   background-kill policies.

Neither step is required for the app to function — without them the
widget still refreshes, just potentially less punctually while the
device is idle.

## Project structure

```
app/src/main/java/com/example/comedpricewidget/
  ComedApiClient.kt         Fetches the ComEd feed and computes the average
  WidgetUpdateUtil.kt       Shared logic: renders widget views, schedules
                            the alarm chain and WorkManager fallback
  RefreshWorker.kt          WorkManager Worker that performs the refresh
                            (no-ops if the screen is currently off)
  PriceAlarmReceiver.kt     Fires every 5 minutes, reschedules itself
  ManualRefreshReceiver.kt  Handles the widget's tap-to-refresh
  BootReceiver.kt           Re-arms scheduling after a device reboot
  PriceWidgetProvider.kt    AppWidgetProvider: widget lifecycle

app/src/main/res/
  layout/price_widget.xml            Widget layout (price text + spinner)
  xml/price_widget_info.xml          Widget metadata (1x1 size, etc.)
  drawable/widget_background_*.xml   Rounded backgrounds, one per price band
  values/colors.xml                  Text color + one color per price band
  mipmap-anydpi-v26/                 Adaptive launcher icon
```

## Attribution

The launcher icon's lightning-bolt artwork is Google's Noto Emoji "high
voltage sign" (U+26A1) glyph, from
[googlefonts/noto-emoji](https://github.com/googlefonts/noto-emoji),
licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Notes / possible future improvements

- The app has no launcher activity by design (it's widget-only); to
  reach its system settings page, go through **Settings → Apps → See
  all apps → ComEd Price Widget** rather than the app drawer.
- The API is queried via its default "last 24 hours" endpoint rather
  than the custom time-range endpoint, to avoid ambiguity about which
  timezone the custom endpoint's `datestart`/`dateend` parameters expect.
  This means each refresh downloads slightly more data than strictly
  necessary (roughly 288 small JSON records), which is a reasonable
  trade-off for correctness but could be tightened later.
- 