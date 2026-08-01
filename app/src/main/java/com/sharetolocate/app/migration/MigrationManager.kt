package com.sharetolocate.app.migration

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MigrationManager {
    data class Offer(val payload: String, val close: () -> Unit)

    fun offer(data: ByteArray): Offer {
        val token = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP) }
        val encrypted = encrypt(data, token)
        val server = ServerSocket(0).apply { soTimeout = 180_000 }
        Thread {
            runCatching {
                server.use { socket ->
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        if (input.readLine() == token) client.getOutputStream().apply { write(encrypted); flush() }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val host = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: throw IllegalStateException("Conecta ambos móviles a la misma red Wi-Fi")
        return Offer("sharetolocate://migrate?v=1&host=$host&port=${server.localPort}&token=${Uri.encode(token)}") { runCatching { server.close() } }
    }

    suspend fun receive(payload: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(payload)
        require(uri.scheme == "sharetolocate" && uri.host == "migrate")
        val host = requireNotNull(uri.getQueryParameter("host"))
        val port = requireNotNull(uri.getQueryParameter("port")).toInt()
        val token = requireNotNull(uri.getQueryParameter("token"))
        val encrypted = Socket(host, port).use { socket ->
            socket.soTimeout = 30_000
            socket.getOutputStream().apply { write((token + "\n").toByteArray()); flush() }
            socket.getInputStream().readBytes()
        }
        decrypt(encrypted, token)
    }

    private fun key(token: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()), "AES")
    private fun encrypt(data: ByteArray, token: String): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(token), GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(data)
    }
    private fun decrypt(data: ByteArray, token: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key(token), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        doFinal(data.copyOfRange(12, data.size))
    }
}
