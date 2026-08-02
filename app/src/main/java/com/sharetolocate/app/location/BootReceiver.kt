package com.sharetolocate.app.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val configured = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .getBoolean("onboarding", false)
        val startLocate = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .getBoolean("start_locate_on_boot", true)
        val locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (configured && startLocate && locationGranted) {
            ContextCompat.startForegroundService(context, Intent(context, LocationSharingService::class.java))
        }
    }
}
