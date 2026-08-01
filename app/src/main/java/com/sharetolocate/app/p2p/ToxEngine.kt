package com.sharetolocate.app.p2p

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zoffcc.applications.trifa.MainActivity as NativeTox
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.io.File

class ToxEngine(private val context: Context, private val listener: Listener) : NativeTox.Events {
    interface Listener {
        fun onIdentity(id: String)
        fun onNetwork(connected: Boolean)
        fun onRequest(publicKey: String, message: String)
        fun onPeerConnection(friendNumber: Long, publicKey: String, connected: Boolean)
        fun onMessage(friendNumber: Long, publicKey: String, message: String)
    }
    private val native = NativeTox()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    private val passphrase by lazy { securePassphrase() }

    fun start(name: String) {
        if (started) return
        started = true; NativeTox.events = this
        scope.launch {
            try {
                val dir = java.io.File(context.filesDir, "tox").apply { mkdirs() }
                native.init(dir.absolutePath, 1, 1, 0, "127.0.0.1", 9050, sha256(passphrase), 1, 0, 90, 51, 12_000, 48_000, 1)
                NativeTox.init_tox_callbacks()
                NativeTox.tox_self_set_name(name)
                NativeTox.update_savedata_file(sha256(passphrase))
                bootstrap()
                listener.onIdentity(NativeTox.get_my_toxid())
                var lastSave = System.currentTimeMillis()
                while (isActive) {
                    NativeTox.tox_iterate()
                    if (System.currentTimeMillis() - lastSave >= 30_000) { NativeTox.update_savedata_file(sha256(passphrase)); lastSave = System.currentTimeMillis() }
                    delay(NativeTox.tox_iteration_interval().coerceIn(20, 1_000))
                }
            } catch (t: Throwable) { started = false; listener.onNetwork(false) }
        }
    }
    fun stop() { scope.cancel(); runCatching { NativeTox.update_savedata_file(sha256(passphrase)); NativeTox.tox_kill() }; NativeTox.events = null }
    fun sendRequest(toxId: String, name: String, nonce: String): Long = NativeTox.tox_friend_add(toxId, "STL1|REQ|$nonce|${encode(name)}")
    fun accept(publicKey: String): Long = NativeTox.tox_friend_add_norequest(publicKey)
    fun remove(friendNumber: Long) = NativeTox.tox_friend_delete(friendNumber)
    fun send(friendNumber: Long, message: String): Boolean = NativeTox.tox_friend_send_message(friendNumber, 0, message) >= 0
    fun friends(): LongArray = NativeTox.tox_self_get_friend_list() ?: longArrayOf()
    fun publicKey(friendNumber: Long): String = runCatching { NativeTox.tox_friend_get_public_key(friendNumber) }.getOrDefault("")
    fun exportIdentity(): Pair<String, ByteArray> {
        runCatching { NativeTox.update_savedata_file(sha256(passphrase)) }
        return passphrase to File(context.filesDir, "tox/savedata.tox").readBytes()
    }

    override fun onSelfConnection(status: Int) = listener.onNetwork(status > 0)
    override fun onFriendRequest(publicKey: String, message: String) = listener.onRequest(publicKey, message)
    override fun onFriendConnection(friendNumber: Long, status: Int) = listener.onPeerConnection(friendNumber, publicKey(friendNumber), status > 0)
    override fun onFriendMessage(friendNumber: Long, message: String) = listener.onMessage(friendNumber, publicKey(friendNumber), message)
    override fun onLosslessPacket(friendNumber: Long, data: ByteArray) {}

    private fun bootstrap() {
        val nodes = arrayOf(
            arrayOf("144.217.167.73","33445","7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C"),
            arrayOf("tox.abilinski.com","33445","10C00EB250C3233E343E2AEBA07115A5C28920E9C8D29492F6D00B29049EDC7E"),
            arrayOf("tox1.mf-net.eu","33445","B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506"),
            arrayOf("3.0.24.15","33445","E20ABCF38CDBFFD7D04B29C956B33F7B27A3BB7AF0618101617B036E4AEA402D")
        )
        nodes.forEach { runCatching { NativeTox.bootstrap_single(it[0], it[2], it[1].toLong()); NativeTox.add_tcp_relay_single(it[0], it[2], it[1].toLong()) } }
    }
    private fun securePassphrase(): String {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(context, "identity_secrets", master, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        return prefs.getString("tox_passphrase", null) ?: ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP) }.also { prefs.edit().putString("tox_passphrase", it).apply() }
    }
    companion object {
        private fun secretPrefs(context: Context) = EncryptedSharedPreferences.create(
            context,
            "identity_secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        fun importIdentity(context: Context, passphrase: String, savedata: ByteArray) {
            secretPrefs(context).edit().putString("tox_passphrase", passphrase).commit()
            File(context.filesDir, "tox").apply { mkdirs() }.resolve("savedata.tox").writeBytes(savedata)
        }
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun encode(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        fun decode(value: String) = String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))
        fun nonce() = ByteArray(16).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP) }
    }
}
