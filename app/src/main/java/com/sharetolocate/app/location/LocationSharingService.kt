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
            result.lastLocation?.let { ShareRepository.get(this@LocationSharingService).setOwnLocation(GeoPoint(it.latitude, it.longitude, it.accuracy, it.time)) }
        }
    }

    override fun onCreate() {
        super.onCreate(); fused = LocationServices.getFusedLocationProviderClient(this); createChannel()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).setMinUpdateIntervalMillis(2_000).setMinUpdateDistanceMeters(3f).build()
            fused.requestLocationUpdates(request, callback, mainLooper)
            ShareRepository.get(this).setSharing(true)
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
    companion object { const val ACTION_STOP = "com.sharetolocate.STOP"; private const val CHANNEL = "location_sharing"; private const val NOTIFICATION_ID = 72 }
}
