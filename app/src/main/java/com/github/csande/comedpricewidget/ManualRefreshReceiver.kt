package com.github.csande.comedpricewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired when the user taps the widget. Hands off to WorkManager rather
 * than doing network I/O here, since BroadcastReceiver.onReceive must not
 * block.
 */
class ManualRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdateUtil.showLoadingState(context)
        WidgetUpdateUtil.enqueueImmediateRefresh(context)
    }
}
