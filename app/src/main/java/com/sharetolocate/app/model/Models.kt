package com.sharetolocate.app.model

data class GeoPoint(val latitude: Double, val longitude: Double, val accuracy: Float = 0f, val timestamp: Long = System.currentTimeMillis())

data class Peer(
    val id: String,
    val name: String,
    val friendNumber: Long = -1,
    val online: Boolean = false,
    val sharing: Boolean = false,
    val location: GeoPoint? = null,
    val lastSeen: Long? = null,
    val accent: Long = 0xFF67E8B6,
    val verified: Boolean = false,
    val verificationCode: String? = null,
    val localConfirmed: Boolean = false,
    val remoteConfirmed: Boolean = false,
    val localNonce: String = "",
    val remoteNonce: String = "",
    val allowRing: Boolean = false,
    val remoteAllowsRing: Boolean = false
)

data class ContactRequest(val publicKey: String, val name: String, val nonce: String, val receivedAt: Long = System.currentTimeMillis())

enum class FollowTarget { NONE, ME, FRIEND }
enum class ThemeMode { LIGHT, DARK }
enum class MapStyle { STREETS, SATELLITE }
enum class AccentColor(val argb: Long) { MINT(0xFF67E8B6), BLUE(0xFF64B5F6), PURPLE(0xFFB39DDB), ORANGE(0xFFFFB15C), PINK(0xFFF48FB1), RED(0xFFFF8A80), TEAL(0xFF4DD0C8) }
enum class RingBehavior { NOTIFICATION_ONLY, NOTIFICATION_WITH_SOUND, SOUND_ONLY }

data class AppSettings(
    val smoothMine: Boolean = true,
    val smoothFriends: Boolean = true,
    val preventScreenshots: Boolean = true,
    val keepScreenOn: Boolean = false,
    val preciseMode: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val mapStyle: MapStyle = MapStyle.STREETS,
    val accentColor: AccentColor = AccentColor.MINT,
    val allowRing: Boolean = false,
    val ringBehavior: RingBehavior = RingBehavior.NOTIFICATION_ONLY
)

data class AppState(
    val ownId: String = "Generando identidad…",
    val displayName: String = "Mi ubicación",
    val ownLocation: GeoPoint? = null,
    val peers: List<Peer> = emptyList(),
    val sharing: Boolean = false,
    val followTarget: FollowTarget = FollowTarget.NONE,
    val followedPeerId: String? = null,
    val settings: AppSettings = AppSettings(),
    val transportStatus: String = "Iniciando red P2P…",
    val pendingRequests: List<ContactRequest> = emptyList(),
    val onboardingComplete: Boolean = false,
    val identityReady: Boolean = false,
    val networkConnected: Boolean = false
)
