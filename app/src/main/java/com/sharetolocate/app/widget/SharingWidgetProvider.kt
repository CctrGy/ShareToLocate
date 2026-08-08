package com.sharetolocate.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sharetolocate.app.R
import com.sharetolocate.app.data.ShareRepository
import com.sharetolocate.app.location.LocationSharingService

class SharingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { update(context, manager, it) }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_CYCLE -> if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                val current = prefs.getInt("duration_$widgetId", 60)
                val next = DURATIONS[(DURATIONS.indexOf(current).takeIf { it >= 0 } ?: 1).let { (it + 1) % DURATIONS.size }]
                prefs.edit().putInt("duration_$widgetId", next).apply()
            }
            ACTION_PAUSE -> {
                val minutes = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE).getInt("duration_$widgetId", 60)
                val repository = ShareRepository.get(context)
                repository.setSharing(true)
                repository.pauseSharing(minutes)
                ensureService(context)
                scheduleResume(context, System.currentTimeMillis() + minutes * 60_000L)
            }
            ACTION_RESUME -> { ShareRepository.get(context).resumeSharing(); ensureService(context) }
        }
        if (intent.action in setOf(ACTION_CYCLE, ACTION_PAUSE, ACTION_RESUME)) updateAll(context)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val appPrefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        val until = appPrefs.getLong("sharing_paused_until", 0L)
        val paused = until > System.currentTimeMillis()
        val minutes = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE).getInt("duration_$id", 60)
        val views = RemoteViews(context.packageName, R.layout.widget_sharing)
        views.setTextViewText(R.id.widget_status, if (paused) "Ubicación pausada" else "Ubicación compartida")
        views.setViewVisibility(R.id.widget_countdown, if (paused) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_duration, if (paused) View.GONE else View.VISIBLE)
        if (paused) views.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime() + (until - System.currentTimeMillis()), "Tiempo restante: %s", true)
        views.setTextViewText(R.id.widget_duration, durationLabel(minutes))
        views.setTextViewText(R.id.widget_action, if (paused) "Reanudar" else "Pausar")
        views.setOnClickPendingIntent(R.id.widget_duration, action(context, ACTION_CYCLE, id))
        views.setOnClickPendingIntent(R.id.widget_action, action(context, if (paused) ACTION_RESUME else ACTION_PAUSE, id))
        manager.updateAppWidget(id, views)
    }

    private fun action(context: Context, action: String, id: Int) = PendingIntent.getBroadcast(
        context, id * 10 + action.hashCode().and(7), Intent(context, SharingWidgetProvider::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleResume(context: Context, at: Long) {
        val pending = PendingIntent.getBroadcast(context, 7701, Intent(context, SharingWidgetProvider::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun ensureService(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(context, Intent(context, LocationSharingService::class.java))
        }
    }

    companion object {
        private const val WIDGET_PREFS = "sharing_widget"
        private const val ACTION_CYCLE = "com.sharetolocate.widget.CYCLE"
        private const val ACTION_PAUSE = "com.sharetolocate.widget.PAUSE"
        private const val ACTION_RESUME = "com.sharetolocate.widget.RESUME"
        private val DURATIONS = listOf(15, 60, 180, 360, 720, 1440)
        private fun durationLabel(minutes: Int) = when (minutes) { 15 -> "15 minutos"; 60 -> "1 hora"; else -> "${minutes / 60} horas" }
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, SharingWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { SharingWidgetProvider().update(context, manager, it) }
        }
    }
}
