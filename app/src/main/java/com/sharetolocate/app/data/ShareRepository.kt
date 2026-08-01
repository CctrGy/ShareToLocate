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

class ShareRepository private constructor(private val context: Context) : ToxEngine.Listener {
    private val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppState(
        peers = loadPeers(),
        displayName = prefs.getString("display_name", "Mi ubicación") ?: "Mi ubicación",
        onboardingComplete = prefs.getBoolean("onboarding", false),
        settings = loadSettings()
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val engine = ToxEngine(context, this)

    init { engine.start(_state.value.displayName) }

    fun completeOnboarding(name: String) {
        val clean = name.trim().ifBlank { "Mi ubicación" }
        prefs.edit().putBoolean("onboarding", true).putString("display_name", clean).apply()
        _state.value = _state.value.copy(onboardingComplete = true, displayName = clean)
    }
    fun setOwnLocation(point: GeoPoint) {
        _state.value = _state.value.copy(ownLocation = point)
        if (_state.value.sharing) _state.value.peers.filter { it.verified && it.online }.forEach { engine.send(it.friendNumber, "STL1|LOC|${point.latitude}|${point.longitude}|${point.accuracy}|${point.timestamp}") }
    }
    fun setSharing(enabled: Boolean) {
        prefs.edit().putBoolean("sharing", enabled).apply()
        _state.value = _state.value.copy(sharing = enabled)
        _state.value.peers.filter { it.verified && it.online }.forEach { engine.send(it.friendNumber, "STL1|SHARE|${if(enabled) 1 else 0}") }
        TileService.requestListeningState(context, ComponentName(context, LocationQuickTileService::class.java))
    }
    fun follow(target: FollowTarget, peerId: String? = null) { _state.value = _state.value.copy(followTarget = target, followedPeerId = peerId) }
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val previous = _state.value.settings
        _state.value = _state.value.copy(settings = transform(previous)); saveSettings()
        if (previous.allowRing != _state.value.settings.allowRing) broadcastRingPermissions()
    }
    fun setPeerRingAllowed(id: String, allowed: Boolean) {
        val peer = _state.value.peers.firstOrNull { it.id == id } ?: return
        val updated = peer.copy(allowRing = allowed)
        updatePeer(updated)
        if (updated.online && updated.verified) sendRingPermission(updated)
    }
    fun ringPeer(id: String): Boolean {
        val peer = _state.value.peers.firstOrNull { it.id == id } ?: return false
        if (!peer.verified || !peer.online || !peer.sharing || !peer.remoteAllowsRing) return false
        return engine.send(peer.friendNumber, "STL1|RING|${System.currentTimeMillis()}")
    }

    fun createMigrationOffer(): MigrationManager.Offer {
        val (passphrase, savedata) = engine.exportIdentity()
        val bundle = JSONObject()
            .put("version", 1)
            .put("passphrase", passphrase)
            .put("savedata", Base64.encodeToString(savedata, Base64.NO_WRAP))
            .put("displayName", _state.value.displayName)
            .put("peers", prefs.getString("peers", "[]"))
            .put("settings", JSONObject().put("theme", _state.value.settings.themeMode.name).put("map", _state.value.settings.mapStyle.name))
        return MigrationManager.offer(bundle.toString().toByteArray())
    }

    suspend fun importMigration(payload: String) {
        val bundle = JSONObject(String(MigrationManager.receive(payload)))
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
            .commit()
    }

    fun addPeer(toxId: String, alias: String, pairingNonce: String = ToxEngine.nonce()): Boolean {
        val id = toxId.trim().uppercase().removePrefix("TOX:").substringBefore('?')
        if (id.length != 76 || id.any { it !in "0123456789ABCDEF" }) return false
        val pk = id.take(64); if (_state.value.peers.any { it.id == pk }) return false
        val number = runCatching { engine.sendRequest(id, _state.value.displayName, pairingNonce) }.getOrDefault(-1)
        if (number < 0) return false
        updatePeer(Peer(pk, alias.ifBlank { "Nuevo contacto" }, number, localNonce = pairingNonce))
        return true
    }
    fun acceptRequest(request: ContactRequest) {
        val number = runCatching { engine.accept(request.publicKey) }.getOrDefault(-1)
        if (number < 0) return
        val peer = Peer(request.publicKey, request.name, number, localNonce = ToxEngine.nonce(), remoteNonce = request.nonce)
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
        if (updated.verified && updated.online) sendRingPermission(updated)
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
        val existing = _state.value.peers.firstOrNull { it.id == publicKey } ?: Peer(publicKey, "Contacto", friendNumber, localNonce = ToxEngine.nonce())
        val updated = existing.copy(friendNumber = friendNumber, online = connected, lastSeen = System.currentTimeMillis())
        updatePeer(updated); if (connected) { sendHello(updated); sendRingPermission(updated) }
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
                if (updated.verified && updated.online) sendRingPermission(updated)
            }
            "SHARE" -> if (p.size >= 3) updatePeer(peer.copy(sharing = p[2] == "1"))
            "LOC" -> if (p.size >= 6 && peer.verified) runCatching { GeoPoint(p[2].toDouble(), p[3].toDouble(), p[4].toFloat(), p[5].toLong()) }.getOrNull()?.let { updatePeer(peer.copy(location = it, sharing = true, lastSeen = System.currentTimeMillis())) }
            "RINGPERM" -> if (p.size >= 3 && peer.verified) updatePeer(peer.copy(remoteAllowsRing = p[2] == "1"))
            "RING" -> if (peer.verified) handleRing(peer)
        }
    }

    private fun sendHello(peer: Peer) { if (peer.localNonce.isNotEmpty()) engine.send(peer.friendNumber, "STL1|HELLO|${peer.localNonce}|${ToxEngine.encode(_state.value.displayName)}") }
    private fun sendRingPermission(peer: Peer) {
        val allowed = _state.value.settings.allowRing && peer.allowRing
        engine.send(peer.friendNumber, "STL1|RINGPERM|${if (allowed) 1 else 0}")
    }
    private fun broadcastRingPermissions() = _state.value.peers.filter { it.verified && it.online }.forEach(::sendRingPermission)
    private fun handleRing(peer: Peer) {
        val current = _state.value
        if (!current.sharing || !current.settings.allowRing || !peer.allowRing) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val dndOff = manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        val behavior = current.settings.ringBehavior
        val showNotification = behavior != RingBehavior.SOUND_ONLY
        val playSound = behavior != RingBehavior.NOTIFICATION_ONLY && dndOff &&
            context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL
        if (showNotification) showRingNotification(peer, playSound)
        else if (playSound) RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
    }
    private fun showRingNotification(peer: Peer, withSound: Boolean) {
        val channelId = if (withSound) "friend_ring_sound" else "friend_ring_silent"
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, if (withSound) "Hacer sonar" else "Avisos de contactos", if (withSound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)
        if (!withSound) channel.setSound(null, null)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 90, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${peer.name} te ha dado un toque")
            .setContentText("Ha usado Hacer sonar en ShareToLocate")
            .setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify((peer.id.hashCode() and 0x7fffffff), notification)
        }
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
        val arr = JSONArray(); _state.value.peers.forEach { arr.put(JSONObject().put("id",it.id).put("name",it.name).put("number",it.friendNumber).put("verified",it.verified).put("localNonce",it.localNonce).put("remoteNonce",it.remoteNonce).put("allowRing",it.allowRing)) }
        prefs.edit().putString("peers", arr.toString()).apply()
    }
    private fun saveSettings() { _state.value.settings.let { prefs.edit().putBoolean("smooth_mine",it.smoothMine).putBoolean("smooth_friends",it.smoothFriends).putBoolean("secure",it.preventScreenshots).putBoolean("screen_on",it.keepScreenOn).putString("theme",it.themeMode.name).putString("map_style",it.mapStyle.name).putString("accent",it.accentColor.name).putBoolean("allow_ring",it.allowRing).putString("ring_behavior",it.ringBehavior.name).apply() } }
    private fun loadSettings() = AppSettings(smoothMine=prefs.getBoolean("smooth_mine",true), smoothFriends=prefs.getBoolean("smooth_friends",true), preventScreenshots=prefs.getBoolean("secure",true), keepScreenOn=prefs.getBoolean("screen_on",false), themeMode=runCatching { ThemeMode.valueOf(prefs.getString("theme","DARK")!!) }.getOrDefault(ThemeMode.DARK), mapStyle=runCatching { MapStyle.valueOf(prefs.getString("map_style","STREETS")!!) }.getOrDefault(MapStyle.STREETS), accentColor=runCatching { AccentColor.valueOf(prefs.getString("accent","MINT")!!) }.getOrDefault(AccentColor.MINT), allowRing=prefs.getBoolean("allow_ring",false), ringBehavior=runCatching { RingBehavior.valueOf(prefs.getString("ring_behavior","NOTIFICATION_ONLY")!!) }.getOrDefault(RingBehavior.NOTIFICATION_ONLY))
    private fun loadPeers(): List<Peer> = runCatching {
        val arr = JSONArray(prefs.getString("peers", "[]")); (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Peer(it.getString("id"), it.getString("name"), it.optLong("number",-1), verified=it.optBoolean("verified"), localNonce=it.optString("localNonce"), remoteNonce=it.optString("remoteNonce"), allowRing=it.optBoolean("allowRing")) } }
    }.getOrDefault(emptyList())

    @SuppressLint("StaticFieldLeak")
    companion object { @Volatile private var instance: ShareRepository? = null; fun get(context: Context) = instance ?: synchronized(this) { instance ?: ShareRepository(context.applicationContext).also { instance = it } } }
}
