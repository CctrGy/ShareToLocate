package com.sharetolocate.app.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class AppLockStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    fun hasPin() = prefs.contains("hash")
    fun setPin(pin: String) {
        require(pin.matches(Regex("\\d{4,8}")))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", hash(pin, salt)).commit()
    }
    fun verify(pin: String): Boolean {
        val salt = prefs.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString("hash", null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(pin, salt).toByteArray())
    }
    private fun hash(pin: String, salt: ByteArray): String {
        var value = salt + pin.toByteArray()
        repeat(20_000) { value = MessageDigest.getInstance("SHA-256").digest(value + salt) }
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }
}
