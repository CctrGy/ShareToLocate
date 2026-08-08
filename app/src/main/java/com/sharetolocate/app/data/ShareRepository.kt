package com.sharetolocate.app.data

import android.content.Context
import android.content.ComponentName
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import com.sharetolocate.app.model.*
import com.sharetolocate.app.p2p.ToxEngine
import com.sharetolocate.app.p2p.PairingSecurity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import com.sharetolocate.app.migration.MigrationManager
import com.sharetolocate.app.migration.EncryptedBackup
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sharetolocate.app.MainActivity
import com.sharetolocate.app.quick.LocationQuickTileService
import android.service.quicksettings.TileService
import android.os.SystemClock
import android.location.Location
import com.sharetolocate.app.location.LocationSharingService
import kotlinx.coroutines.*
import com.sharetolocate.app.widget.SharingWidgetProvider

class ShareRepository private constructor(private val context: Context) : ToxEngine.Listener {
    private val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppState(
        peers = loadPeers(),
        sharing = prefs.getBoolean("sharing", true),
        displayName = prefs.getString("display_name", "Mi ubicación") ?: "Mi ubicación",
        onboardingComplete = prefs.getBoolean("onboarding", false),
        sharingPausedUntil = prefs.getLong("sharing_paused_until", 0L).takeIf { it > System.currentTimeMillis() },
        settings = loadSettings(),
        ownLocation = loadOwnLocation(),
        deliveryStats = loadDeliveryStats()
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val engine = ToxEngine(context, this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastRingAt = mutableMapOf<String, Long>()
    private val lastSentLocations = mutableMapOf<String, GeoPoint>()
    private var lastRefreshRequestAt = 0L

    init {
        engine.start(_state.value.displayName)
        _state.value.sharingPausedUntil?.let { until -> scope.launch { delay((until - System.currentTimeMillis()).coerceAtLeast(0)); resumeSharing() } }
    }

    fun completeOnboarding(name: String) {
        val clean = name.trim().ifBlank { "Mi ubicación" }
        prefs.edit().putBoolean("onboarding", true).putString("display_name", clean).apply()
        _state.value = _state.value.copy(onboardingComplete = true, displayName = clean)
    }
    fun setOwnLocation(point: GeoPoint) {
        if (!isValidLocation(point)) return
        prefs.edit().putString("own_location", point.toJson().toString()).apply()
        _state.value = _state.value.copy(ownLocation = point)
        updateStats { it.copy(locationsCaptured = it.locationsCaptured + 1) }
        if (isActivelySharing()) _state.value.peers.filter { it.verified && it.online }.forEach { peer ->
            if (shouldSendLocation(peer.id, point)) sendLocation(peer, point) else updateStats { it.copy(locationsSuppressed = it.locationsSuppressed + 1) }
        }
    }
    fun setSharing(enabled: Boolean) {
        prefs.edit().putBoolean("sharing", enabled).also { if (!enabled) it.remove("sharing_paused_until") }.apply()
        _state.value = _state.value.copy(sharing = enabled, sharingPausedUntil = if (enabled) _state.value.sharingPausedUntil else null)
        _state.value.peers.filter { it.verified && it.online }.forEach { peer ->
            sendSharingState(peer)
            sendRingPermission(peer)
            if (enabled) sendCurrentLocation(peer)
        }
        TileService.requestListeningState(context, ComponentName(context, LocationQuickTileService::class.java))
        SharingWidgetProvider.updateAll(context)
    }
    fun pauseSharing(minutes: Int) {
        if (!_state.value.sharing) return
        val until = System.currentTimeMillis() + minutes.coerceIn(1, 1440) * 60_000L
        prefs.edit().putLong("sharing_paused_until", until).apply()
        _state.value = _state.value.copy(sharingPausedUntil = until)
        _state.value.peers.filter { it.verified && it.online }.forEach { sendSharingState(it); sendRingPermission(it) }
        SharingWidgetProvider.updateAll(context)
        scope.launch { delay((until - System.currentTimeMillis()).coerceAtLeast(0)); resumeSharing() }
    }
    fun resumeSharing() {
        if (!_state.value.sharing) return
        prefs.edit().remove("sharing_paused_until").apply()
        _state.value = _state.value.copy(sharingPausedUntil = null)
        _state.value.peers.filter { it.verified && it.online }.forEach { sendSharingState(it); sendRingPermission(it); sendCurrentLocation(it) }
        SharingWidgetProvider.updateAll(context)
    }
    private fun isActivelySharing() = _state.value.sharing && (_state.value.sharingPausedUntil ?: 0L) <= System.currentTimeMillis()
    fun follow(target: FollowTarget, peerId: String? = null) { _state.value = _state.value.copy(followTarget = target, followedPeerId = peerId) }
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val previous = _state.value.settings
        _state.value = _state.value.copy(settings = transform(previous)); saveSettings()
        if (previous.allowRing != _state.value.settings.allowRing || previous.allowRingWithoutLocation != _state.value.settings.allowRingWithoutLocation) broadcastRingPermissions()
    }
    fun setPeerRingAllowed(id: String, allowed: Boolean) {
        val peer = _state.value.peers.firstOrNull { it.id == id } ?: return
        val updated = peer.copy(allowRing = allowed)
        updatePeer(updated)
        if (updated.online && updated.verified) sendRingPermission(updated)
    }
    fun setPeerNickname(id: String, nickname: String) {
        _state.value.peers.firstOrNull { it.id == id }?.let { updatePeer(it.copy(nickname = nickname.trim().ifBlank { null })) }
    }
    fun setPeerFollowMenuVisible(id: String, visible: Boolean) {
        _state.value.peers.firstOrNull { it.id == id }?.let { updatePeer(it.copy(showInFollowMenu = visible)) }
        if (!visible && _state.value.followedPeerId == id) follow(FollowTarget.NONE)
    }
    fun setPeerLinkedContact(id: String, name: String?, phone: String?) {
        _state.value.peers.firstOrNull { it.id == id }?.let {
            updatePeer(it.copy(linkedContactName = name?.trim()?.ifBlank { null }, linkedContactPhone = phone?.trim()?.ifBlank { null }))
        }
    }
    fun setPeerAccent(id: String, color: Long) {
        _state.value.peers.firstOrNull { it.id == id }?.let { updatePeer(it.copy(accent = color)) }
    }
    fun setLocationInterval(minutes: Int) {
        val value = minutes.takeIf { it in listOf(3,6,12,15,20,30,60) } ?: 15
        updateSettings { it.copy(locationIntervalMinutes = value) }
        if (_state.value.sharing) context.startService(Intent(context, LocationSharingService::class.java).setAction(LocationSharingService.ACTION_REFRESH_INTERVAL))
    }
    fun setDefaultMapCenter(point: GeoPoint?) {
        updateSettings { it.copy(defaultMapCenter = point?.takeIf(::isValidLocation)) }
    }
    fun ringPeer(id: String, message: String = ""): Boolean {
        val peer = _state.value.peers.firstOrNull { it.id == id } ?: return false
        if (!peer.verified || !peer.online || !peer.remoteAllowsRing) return false
        val requestAt = System.currentTimeMillis()
        val safeMessage = message.trim().take(140)
        val sent = engine.send(peer.friendNumber, "STL1|RING|$requestAt|${ToxEngine.encode(safeMessage)}")
        if (sent) updatePeer(peer.copy(ringRequestedAt = requestAt, ringAcknowledgedAt = null))
        return sent
    }

    fun requestPeerLocations() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshRequestAt < 10_000L) return
        lastRefreshRequestAt = now
        val requestId = System.currentTimeMillis().toString()
        var sent = 0L
        _state.value.peers.filter { it.verified && it.online }.forEach { if (engine.send(it.friendNumber, "STL1|LOCREQ|$requestId")) sent++ }
        if (sent > 0) updateStats { it.copy(refreshRequestsSent = it.refreshRequestsSent + sent) }
    }

    fun createMigrationOffer(): MigrationManager.Offer {
        return MigrationManager.offer(createTransferBundle().toString().toByteArray())
    }
    fun exportEncryptedBackup(password: String): ByteArray = EncryptedBackup.encrypt(createTransferBundle().toString().toByteArray(), password)
    fun importEncryptedBackup(bytes: ByteArray, password: String) = applyTransferBundle(JSONObject(String(EncryptedBackup.decrypt(bytes, password))))

    private fun createTransferBundle(): JSONObject {
        val (passphrase, savedata) = engine.exportIdentity()
        return JSONObject()
            .put("version", 1)
            .put("passphrase", passphrase)
            .put("savedata", Base64.encodeToString(savedata, Base64.NO_WRAP))
            .put("displayName", _state.value.displayName)
            .put("peers", prefs.getString("peers", "[]"))
            .put("settings", with(_state.value.settings) { JSONObject().put("theme",themeMode.name).put("map",mapStyle.name).put("accent",accentColor.name).put("smoothMine",smoothMine).put("smoothFriends",smoothFriends).put("secure",preventScreenshots).put("screenOn",keepScreenOn).put("allowRing",allowRing).put("allowRingWithoutLocation",allowRingWithoutLocation).put("ringBehavior",ringBehavior.name).put("locationInterval",locationIntervalMinutes) })
    }

    suspend fun importMigration(payload: String) {
        applyTransferBundle(JSONObject(String(MigrationManager.receive(payload))))
    }
    private fun applyTransferBundle(bundle: JSONObject) {
        require(bundle.getInt("version") == 1)
        val savedata = Base64.decode(bundle.getString("savedata"), Base64.NO_WRAP)
        engine.stop()
        ToxEngine.importIdentity(context, bundle.getString("passphrase"), savedata)
        val settings = bundle.optJSONObject("settings")
        prefs.edit().putBoolean("onboarding", true)
            .putString("display_name", bundle.optString("displayName", "Mi ubicación"))
            .putString("peers", bundle.optString("peers", "[]"))
            .putString("theme", settings?.optString("theme", "DARK"))
            .putString("map_style", settings?.optString("map", "STREETS"))
            .putString("accent", settings?.optString("accent", "MINT"))
            .putBoolean("smooth_mine", settings?.optBoolean("smoothMine", true) ?: true)
            .putBoolean("smooth_friends", settings?.optBoolean("smoothFriends", true) ?: true)
            .putBoolean("secure", settings?.optBoolean("secure", true) ?: true)
            .putBoolean("screen_on", settings?.optBoolean("screenOn", false) ?: false)
            .putBoolean("allow_ring", settings?.optBoolean("allowRing", false) ?: false)
            .putBoolean("allow_ring_without_location", settings?.optBoolean("allowRingWithoutLocation", false) ?: false)
            .putString("ring_behavior", settings?.optString("ringBehavior", "NOTIFICATION_ONLY"))
            .putInt("location_interval", settings?.optInt("locationInterval", 15) ?: 15)
            .commit()
    }

    fun addPeer(toxId: String, alias: String, pairingNonce: String = ToxEngine.nonce()): Boolean {
        val id = toxId.trim().uppercase().removePrefix("TOX:").substringBefore('?')
        if (id.length != 76 || id.any { it !in "0123456789ABCDEF" }) return false
        val pk = id.take(64); if (_state.value.peers.any { it.id == pk }) return false
        val number = runCatching { engine.sendRequest(id, _state.value.displayName, pairingNonce) }.getOrDefault(-1)
        if (number < 0) return false
        updatePeer(Peer(id = pk, name = "Contacto", nickname = alias.ifBlank { null }, friendNumber = number, localNonce = pairingNonce))
        return true
    }
    fun acceptRequest(request: ContactRequest) {
        val number = runCatching { engine.accept(request.publicKey) }.getOrDefault(-1)
        if (number < 0) return
        val peer = Peer(id = request.publicKey, name = request.name, friendNumber = number, localNonce = ToxEngine.nonce(), remoteNonce = request.nonce)
        _state.value = _state.value.copy(pendingRequests = _state.value.pendingRequests.filterNot { it.publicKey == request.publicKey })
        updatePeer(peer); sendHello(peer)
    }
    fun rejectRequest(request: ContactRequest) { _state.value = _state.value.copy(pendingRequests = _state.value.pendingRequests.filterNot { it.publicKey == request.publicKey }) }
    fun confirmVerification(id: String) {
        val peer = _state.value.peers.firstOrNull { it.id == id } ?: return
        val code = peer.verificationCode ?: return
        engine.send(peer.friendNumber, "STL1|VERIFY|${code.replace("-", "")}")
        val updated = peer.copy(localConfirmed = true, verified = peer.remoteConfirmed)
        updatePeer(updated)
        if (updated.verified && updated.online) { sendRingPermission(updated); sendSharingState(updated); sendCurrentLocation(updated) }
    }
    fun removePeer(id: String) {
        _state.value.peers.firstOrNull { it.id == id }?.let { runCatching { engine.remove(it.friendNumber) } }
        _state.value = _state.value.copy(peers = _state.value.peers.filterNot { it.id == id }); savePeers()
    }

    override fun onIdentity(id: String) { _state.value = _state.value.copy(ownId = id, identityReady = true, transportStatus = "Identidad P2P preparada") }
    override fun onNetwork(connected: Boolean) { _state.value = _state.value.copy(networkConnected = connected, transportStatus = if (connected) "Red P2P conectada" else "Conectando a la red P2P…") }
    override fun onRequest(publicKey: String, message: String) {
        val p = message.split('|'); if (p.size < 4 || p[0] != "STL1" || p[1] != "REQ") return
        val req = ContactRequest(publicKey.take(64).uppercase(), runCatching { ToxEngine.decode(p[3]) }.getOrDefault("Nuevo contacto"), p[2])
        if (_state.value.pendingRequests.none { it.publicKey == req.publicKey }) _state.value = _state.value.copy(pendingRequests = _state.value.pendingRequests + req)
    }
    override fun onPeerConnection(friendNumber: Long, publicKey: String, connected: Boolean) {
        val existing = _state.value.peers.firstOrNull { it.id == publicKey } ?: Peer(id = publicKey, name = "Contacto", friendNumber = friendNumber, localNonce = ToxEngine.nonce())
        val updated = existing.copy(friendNumber = friendNumber, online = connected, lastSeen = System.currentTimeMillis())
        updatePeer(updated); if (connected) {
            sendHello(updated)
            sendRingPermission(updated)
            if (updated.verified) { sendSharingState(updated); sendCurrentLocation(updated) }
        }
    }
    override fun onMessage(friendNumber: Long, publicKey: String, message: String) {
        val peer = _state.value.peers.firstOrNull { it.id == publicKey } ?: return
        val p = message.split('|'); if (p.size < 2 || p[0] != "STL1") return
        when (p[1]) {
            "HELLO" -> if (p.size >= 4) {
                val name = runCatching { ToxEngine.decode(p[3]) }.getOrDefault(peer.name)
                val next = peer.copy(name = name, remoteNonce = p[2])
                updatePeer(next.copy(verificationCode = verificationCode(next)))
            }
            "VERIFY" -> if (p.size >= 3 && peer.verificationCode?.replace("-", "") == p[2]) {
                val updated = peer.copy(remoteConfirmed = true, verified = peer.localConfirmed)
                updatePeer(updated)
                if (updated.verified && updated.online) { sendRingPermission(updated); sendSharingState(updated); sendCurrentLocation(updated) }
            }
            "SHARE" -> if (p.size >= 3) updatePeer(peer.copy(sharing = p[2] == "1"))
            "LOC" -> if (p.size >= 6 && peer.verified) receiveLocation(peer, p, 2)
            "LOCREQ" -> if (p.size >= 3 && peer.verified) sendCurrentLocation(peer, force = true, requestId = p[2])
            "LOCRES" -> if (p.size >= 7 && peer.verified) receiveLocation(peer, p, 3)
            "RINGPERM" -> if (p.size >= 3 && peer.verified) updatePeer(peer.copy(remoteAllowsRing = p[2] == "1"))
            "RING" -> if (peer.verified) { handleRing(peer, p.getOrNull(3)?.let { runCatching { ToxEngine.decode(it).take(140) }.getOrDefault("") }.orEmpty()); p.getOrNull(2)?.let { engine.send(peer.friendNumber, "STL1|RINGACK|$it|${System.currentTimeMillis()}") } }
            "RINGACK" -> if (p.size >= 4 && peer.verified && peer.ringRequestedAt?.toString() == p[2]) updatePeer(peer.copy(ringAcknowledgedAt = p[3].toLongOrNull() ?: System.currentTimeMillis()))
        }
    }

    private fun sendHello(peer: Peer) { if (peer.localNonce.isNotEmpty()) engine.send(peer.friendNumber, "STL1|HELLO|${peer.localNonce}|${ToxEngine.encode(_state.value.displayName)}") }
    private fun sendSharingState(peer: Peer) = engine.send(peer.friendNumber, "STL1|SHARE|${if (isActivelySharing()) 1 else 0}")
    private fun sendCurrentLocation(peer: Peer, force: Boolean = true, requestId: String? = null) {
        if (!isActivelySharing()) return
        _state.value.ownLocation?.let { point ->
            if (force || shouldSendLocation(peer.id, point)) sendLocation(peer, point, requestId)
        }
    }
    private fun sendRingPermission(peer: Peer) {
        val allowed = _state.value.settings.allowRing && peer.allowRing && (isActivelySharing() || _state.value.settings.allowRingWithoutLocation)
        engine.send(peer.friendNumber, "STL1|RINGPERM|${if (allowed) 1 else 0}")
    }
    private fun broadcastRingPermissions() = _state.value.peers.filter { it.verified && it.online }.forEach(::sendRingPermission)
    private fun handleRing(peer: Peer, message: String) {
        val current = _state.value
        if ((!current.sharing && !current.settings.allowRingWithoutLocation) || !current.settings.allowRing || !peer.allowRing) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lastRingAt) { val previous = lastRingAt[peer.id]; if (previous != null && now - previous < 60_000L) return; lastRingAt[peer.id] = now }
        val manager = context.getSystemService(NotificationManager::class.java)
        val dndOff = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        val behavior = current.settings.ringBehavior
        val showNotification = behavior != RingBehavior.SOUND_ONLY
        val playSound = behavior != RingBehavior.NOTIFICATION_ONLY && dndOff &&
            context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL
        if (showNotification) showRingNotification(peer, playSound, message)
        else if (playSound) RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
    }
    private fun showRingNotification(peer: Peer, withSound: Boolean, message: String) {
        val channelId = if (withSound) "friend_ring_sound" else "friend_ring_silent"
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, if (withSound) "Hacer sonar" else "Avisos de contactos", if (withSound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)
        if (!withSound) channel.setSound(null, null)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 90, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${peer.displayName} te ha dado un toque")
            .setContentText(message.ifBlank { "Ha usado Hacer sonar en ShareToLocate" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.ifBlank { "Ha usado Hacer sonar en ShareToLocate" }))
            .setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH)
        peer.linkedContactPhone?.let { phone ->
            val call = PendingIntent.getActivity(context, peer.id.hashCode(), Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${android.net.Uri.encode(phone)}")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_menu_call, "Llamar", call)
            val whatsapp = PendingIntent.getActivity(context, peer.id.hashCode() xor 0x51A7, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${phone.filter(Char::isDigit)}")).setPackage("com.whatsapp"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.sym_action_chat, "WhatsApp", whatsapp)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify((peer.id.hashCode() and 0x7fffffff), notification)
        }
    }
    private fun sendLocation(peer: Peer, point: GeoPoint, requestId: String? = null): Boolean {
        val type = if (requestId == null) "LOC" else "LOCRES|$requestId"
        val sent = engine.send(peer.friendNumber, "STL1|$type|${point.latitude}|${point.longitude}|${point.accuracy}|${point.timestamp}")
        if (sent) {
            lastSentLocations[peer.id] = point
            updateStats { it.copy(locationsSent = it.locationsSent + 1, lastSentAt = System.currentTimeMillis()) }
        }
        return sent
    }
    private fun shouldSendLocation(peerId: String, point: GeoPoint): Boolean {
        val previous = lastSentLocations[peerId] ?: return true
        val distance = FloatArray(1).also { Location.distanceBetween(previous.latitude, previous.longitude, point.latitude, point.longitude, it) }[0]
        val interval = _state.value.settings.locationIntervalMinutes * 60_000L
        val accuracyImproved = previous.accuracy <= 0f || (point.accuracy > 0f && point.accuracy <= previous.accuracy * 0.7f)
        val meaningfulDistance = distance >= maxOf(5f, point.accuracy.coerceAtLeast(1f) * 0.6f)
        return meaningfulDistance || accuracyImproved || point.timestamp - previous.timestamp >= interval
    }
    private fun receiveLocation(peer: Peer, parts: List<String>, offset: Int) {
        val point = runCatching { GeoPoint(parts[offset].toDouble(), parts[offset + 1].toDouble(), parts[offset + 2].toFloat(), parts[offset + 3].toLong()) }.getOrNull() ?: return
        if (!isValidLocation(point) || (peer.location?.timestamp ?: Long.MIN_VALUE) > point.timestamp) return
        updatePeer(peer.copy(location = point, sharing = true, lastSeen = System.currentTimeMillis()))
        updateStats { it.copy(locationsReceived = it.locationsReceived + 1, lastReceivedAt = System.currentTimeMillis()) }
    }
    private fun isValidLocation(point: GeoPoint) = point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 && point.accuracy.isFinite() && point.accuracy >= 0f
    private fun GeoPoint.toJson() = JSONObject().put("latitude", latitude).put("longitude", longitude).put("accuracy", accuracy.toDouble()).put("timestamp", timestamp)
    private fun updateStats(transform: (DeliveryStats) -> DeliveryStats) {
        val stats = transform(_state.value.deliveryStats)
        _state.value = _state.value.copy(deliveryStats = stats)
        prefs.edit().putString("delivery_stats", JSONObject().put("captured",stats.locationsCaptured).put("sent",stats.locationsSent).put("suppressed",stats.locationsSuppressed).put("received",stats.locationsReceived).put("requests",stats.refreshRequestsSent).put("lastSent",stats.lastSentAt ?: 0).put("lastReceived",stats.lastReceivedAt ?: 0).toString()).apply()
    }
    private fun verificationCode(peer: Peer): String? {
        if (peer.localNonce.isEmpty() || peer.remoteNonce.isEmpty() || !_state.value.identityReady) return null
        return PairingSecurity.verificationCode(_state.value.ownId.take(64), peer.id, peer.localNonce, peer.remoteNonce)
    }
    private fun updatePeer(peer: Peer) {
        val list = _state.value.peers.toMutableList(); val i = list.indexOfFirst { it.id == peer.id }; if (i >= 0) list[i] = peer else list += peer
        _state.value = _state.value.copy(peers = list); savePeers()
    }
    private fun savePeers() {
        val arr = JSONArray(); _state.value.peers.forEach { peer ->
            val item = JSONObject().put("id",peer.id).put("name",peer.name).put("nickname",peer.nickname).put("number",peer.friendNumber).put("verified",peer.verified).put("localNonce",peer.localNonce).put("remoteNonce",peer.remoteNonce).put("allowRing",peer.allowRing).put("showInFollowMenu",peer.showInFollowMenu).put("accent",peer.accent).put("linkedContactName",peer.linkedContactName).put("linkedContactPhone",peer.linkedContactPhone).put("sharing",peer.sharing).put("lastSeen",peer.lastSeen)
            peer.location?.let { item.put("latitude",it.latitude).put("longitude",it.longitude).put("accuracy",it.accuracy.toDouble()).put("locationTime",it.timestamp) }
            arr.put(item)
        }
        prefs.edit().putString("peers", arr.toString()).apply()
    }
    private fun saveSettings() { _state.value.settings.let { settings -> prefs.edit().putBoolean("smooth_mine",settings.smoothMine).putBoolean("smooth_friends",settings.smoothFriends).putBoolean("secure",settings.preventScreenshots).putBoolean("screen_on",settings.keepScreenOn).putString("theme",settings.themeMode.name).putString("map_style",settings.mapStyle.name).putString("accent",settings.accentColor.name).putBoolean("allow_ring",settings.allowRing).putBoolean("allow_ring_without_location",settings.allowRingWithoutLocation).putString("ring_behavior",settings.ringBehavior.name).putInt("location_interval",settings.locationIntervalMinutes).putBoolean("start_locate_on_boot",settings.startLocateOnBoot).apply { settings.defaultMapCenter?.let { point -> putString("default_map_center", point.toJson().toString()) } ?: remove("default_map_center") }.apply() } }
    private fun loadSettings(): AppSettings {
        val center = runCatching { JSONObject(prefs.getString("default_map_center", null)!!).let { GeoPoint(it.getDouble("latitude"), it.getDouble("longitude"), it.optDouble("accuracy").toFloat(), it.optLong("timestamp", 0L)) } }.getOrNull()?.takeIf(::isValidLocation)
        return AppSettings(smoothMine=prefs.getBoolean("smooth_mine",true), smoothFriends=prefs.getBoolean("smooth_friends",true), preventScreenshots=prefs.getBoolean("secure",true), keepScreenOn=prefs.getBoolean("screen_on",false), themeMode=runCatching { ThemeMode.valueOf(prefs.getString("theme","LIGHT")!!) }.getOrDefault(ThemeMode.LIGHT), mapStyle=runCatching { MapStyle.valueOf(prefs.getString("map_style","STREETS")!!) }.getOrDefault(MapStyle.STREETS), accentColor=runCatching { AccentColor.valueOf(prefs.getString("accent","MINT")!!) }.getOrDefault(AccentColor.MINT), allowRing=prefs.getBoolean("allow_ring",false), allowRingWithoutLocation=prefs.getBoolean("allow_ring_without_location",false), ringBehavior=runCatching { RingBehavior.valueOf(prefs.getString("ring_behavior","NOTIFICATION_WITH_SOUND")!!) }.getOrDefault(RingBehavior.NOTIFICATION_WITH_SOUND), locationIntervalMinutes=prefs.getInt("location_interval",15), startLocateOnBoot=prefs.getBoolean("start_locate_on_boot",true), defaultMapCenter=center)
    }
    private fun loadOwnLocation(): GeoPoint? = runCatching {
        JSONObject(prefs.getString("own_location", null)!!).let { GeoPoint(it.getDouble("latitude"), it.getDouble("longitude"), it.optDouble("accuracy").toFloat(), it.getLong("timestamp")) }
    }.getOrNull()?.takeIf(::isValidLocation)
    private fun loadDeliveryStats(): DeliveryStats = runCatching {
        JSONObject(prefs.getString("delivery_stats", null)!!).let { DeliveryStats(it.optLong("captured"), it.optLong("sent"), it.optLong("suppressed"), it.optLong("received"), it.optLong("requests"), it.optLong("lastSent").takeIf { value -> value > 0 }, it.optLong("lastReceived").takeIf { value -> value > 0 }) }
    }.getOrDefault(DeliveryStats())
    private fun loadPeers(): List<Peer> = runCatching {
        val arr = JSONArray(prefs.getString("peers", "[]")); (0 until arr.length()).map { i -> arr.getJSONObject(i).let { item ->
            val location = if (item.has("latitude") && item.has("longitude")) GeoPoint(item.getDouble("latitude"), item.getDouble("longitude"), item.optDouble("accuracy",0.0).toFloat(), item.optLong("locationTime",System.currentTimeMillis())) else null
            Peer(id=item.getString("id"), name=item.getString("name"), nickname=item.optString("nickname").takeIf { it.isNotBlank() && it != "null" }, friendNumber=item.optLong("number",-1), sharing=item.optBoolean("sharing"), location=location, lastSeen=item.optLong("lastSeen").takeIf { value -> value > 0 }, accent=item.optLong("accent", 0xFF67E8B6), verified=item.optBoolean("verified"), localNonce=item.optString("localNonce"), remoteNonce=item.optString("remoteNonce"), allowRing=item.optBoolean("allowRing"), showInFollowMenu=item.optBoolean("showInFollowMenu", true), linkedContactName=item.optString("linkedContactName").takeIf { it.isNotBlank() && it != "null" }, linkedContactPhone=item.optString("linkedContactPhone").takeIf { it.isNotBlank() && it != "null" })
        } }
    }.getOrDefault(emptyList())

    @SuppressLint("StaticFieldLeak")
    companion object { @Volatile private var instance: ShareRepository? = null; fun get(context: Context) = instance ?: synchronized(this) { instance ?: ShareRepository(context.applicationContext).also { instance = it } } }
}
