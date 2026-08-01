package com.sharetolocate.app.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecurityTest {
    private val alice = "A".repeat(64)
    private val bob = "B".repeat(64)

    @Test fun `both phones derive the same code`() {
        val first = PairingSecurity.verificationCode(alice, bob, "nonce-alice", "nonce-bob")
        val second = PairingSecurity.verificationCode(bob, alice, "nonce-bob", "nonce-alice")
        assertEquals(first, second)
    }

    @Test fun `code has human readable eight digit format`() {
        assertTrue(PairingSecurity.verificationCode(alice, bob, "one", "two").matches(Regex("\\d{4}-\\d{4}")))
    }

    @Test fun `changing a nonce changes the verification code`() {
        assertNotEquals(
            PairingSecurity.verificationCode(alice, bob, "one", "two"),
            PairingSecurity.verificationCode(alice, bob, "one", "tampered")
        )
    }

    @Test fun `empty identity data is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingSecurity.verificationCode("", bob, "one", "two")
        }
    }
}
