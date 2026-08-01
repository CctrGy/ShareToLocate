package com.sharetolocate.app.p2p

import java.security.MessageDigest

object PairingSecurity {
    fun verificationCode(ownPublicKey: String, peerPublicKey: String, ownNonce: String, peerNonce: String): String {
        require(ownPublicKey.isNotBlank() && peerPublicKey.isNotBlank())
        require(ownNonce.isNotBlank() && peerNonce.isNotBlank())
        val material = listOf(ownPublicKey, peerPublicKey).sorted().joinToString("") +
            listOf(ownNonce, peerNonce).sorted().joinToString("")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        val number = ((digest.take(8).fold(0L) { acc, byte ->
            (acc shl 8) xor (byte.toLong() and 0xff)
        }) and Long.MAX_VALUE) % 100_000_000
        return "%08d".format(number).let { it.take(4) + "-" + it.drop(4) }
    }
}
