package com.yugahashimoto.andcode.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.yugahashimoto.andcode.R

class QuickInputWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_SEND = "com.yugahashimoto.andcode.widget.SEND"
        const val ACTION_MIC = "com.yugahashimoto.andcode.widget.MIC"
        const val EXTRA_TEXT = "widget_text"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_input)

            val sendIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_SEND
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val sendPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    sendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            val micIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_MIC
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val micPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 1000,
                    micIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            views.setOnClickPendingIntent(R.id.widget_send_button, sendPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_mic_button, micPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_input, sendPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
