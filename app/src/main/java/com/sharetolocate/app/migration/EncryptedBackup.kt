package com.sharetolocate.app.migration

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptedBackup {
    private const val ITERATIONS = 210_000
    private val magic = "STLBK1".toByteArray()

    fun encrypt(clear: ByteArray, password: String): ByteArray {
        require(password.length >= 6) { "La contraseña debe tener al menos 6 caracteres" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return ByteBuffer.allocate(magic.size + salt.size + iv.size + clear.size + 16)
            .put(magic).put(salt).put(iv).put(cipher.doFinal(clear)).array()
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size > magic.size + 28 && data.copyOfRange(0, magic.size).contentEquals(magic)) { "Archivo de copia no válido" }
        val salt = data.copyOfRange(magic.size, magic.size + 16)
        val iv = data.copyOfRange(magic.size + 16, magic.size + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return runCatching { cipher.doFinal(data.copyOfRange(magic.size + 28, data.size)) }
            .getOrElse { throw IllegalArgumentException("Contraseña incorrecta o copia dañada") }
    }

    private fun key(password: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded, "AES"
    )
}
