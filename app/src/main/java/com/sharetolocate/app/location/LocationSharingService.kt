package com.sharetolocate.app.location

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.sharetolocate.app.MainActivity
import com.sharetolocate.app.data.ShareRepository
import com.sharetolocate.app.model.GeoPoint

class LocationSharingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.filter { it.hasAccuracy() && it.accuracy <= 100f }
                .minWithOrNull(compareBy<android.location.Location> { it.accuracy }.thenByDescending { it.time })
                ?.let { ShareRepository.get(this@LocationSharingService).setOwnLocation(GeoPoint(it.latitude, it.longitude, it.accuracy, it.time)) }
        }
    }

    override fun onCreate() {
        super.onCreate(); fused = LocationServices.getFusedLocationProviderClient(this); createChannel()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.removeLocationUpdates(callback)
            val repository = ShareRepository.get(this)
            val settings = repository.state.value.settings
            val interval = settings.locationIntervalMinutes * 60_000L
            val priority = if (settings.preciseMode) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val request = LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis((interval / 3).coerceAtLeast(15_000L))
                .setMaxUpdateDelayMillis(interval)
                .setMinUpdateDistanceMeters(if (settings.preciseMode) 2f else 10f)
                .setWaitForAccurateLocation(settings.preciseMode)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .build()
            fused.requestLocationUpdates(request, callback, mainLooper)
            if (intent?.action != ACTION_REFRESH_INTERVAL) repository.setSharing(true)
            fused.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(priority).setMaxUpdateAgeMillis(30_000L).setGranularity(Granularity.GRANULARITY_FINE).build(), null)
                .addOnSuccessListener { location -> location?.let { repository.setOwnLocation(GeoPoint(it.latitude, it.longitude, it.accuracy, it.time)) } }
        }
        return START_STICKY
    }
    override fun onDestroy() { fused.removeLocationUpdates(callback); ShareRepository.get(this).setSharing(false); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Ubicación compartida", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Compartiendo tu ubicación").setContentText("Cifrada y visible solo para tus contactos").setOngoing(true).setContentIntent(open).addAction(0, "Detener", stop).build()
    }
    companion object { const val ACTION_STOP = "com.sharetolocate.STOP"; const val ACTION_REFRESH_INTERVAL = "com.sharetolocate.REFRESH_INTERVAL"; const val CHANNEL = "location_sharing"; private const val NOTIFICATION_ID = 72 }
}
