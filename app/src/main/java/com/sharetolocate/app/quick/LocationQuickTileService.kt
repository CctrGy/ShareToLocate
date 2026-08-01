package com.sharetolocate.app.quick

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.sharetolocate.app.MainActivity
import com.sharetolocate.app.location.LocationSharingService

class LocationQuickTileService : TileService() {
    override fun onTileAdded() = refresh()
    override fun onStartListening() = refresh()

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val active = getSharedPreferences("app_state", MODE_PRIVATE).getBoolean("sharing", false)
        if (active) {
            startService(Intent(this, LocationSharingService::class.java).setAction(LocationSharingService.ACTION_STOP))
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(this, Intent(this, LocationSharingService::class.java))
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 41, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
        Handler(Looper.getMainLooper()).postDelayed(::refresh, 500)
    }

    private fun refresh() {
        val active = getSharedPreferences("app_state", MODE_PRIVATE).getBoolean("sharing", false)
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Ubicación compartida"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (active) "ShareToLocate activo" else "ShareToLocate detenido"
            updateTile()
        }
    }
}
