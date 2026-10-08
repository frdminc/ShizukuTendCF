package rikka.shizuku.server

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a persisted grant still belongs to the app that earned it. The case a phone cannot be
 * asked to produce on demand is a same-named app, signed by someone else, taking over the UID
 * after an uninstall and a reboot.
 */
class GrantSignersTest {

    private val ours = "aa".repeat(32)
    private val theirs = "bb".repeat(32)

    @Test
    fun `a same-named app signed by another key does not inherit the grant`() {
        assertTrue(GrantSigners.revoked(listOf(ours), listOf(theirs)))
    }

    @Test
    fun `the same signer keeps the grant`() {
        assertFalse(GrantSigners.revoked(listOf(ours), listOf(ours)))
    }

    @Test
    fun `a rotated key keeps the grant while its lineage still holds the recorded certificate`() {
        assertFalse(GrantSigners.revoked(listOf(ours), listOf(theirs, ours)))
    }

    @Test
    fun `a shared uid keeps the grant while one recorded signer is still present`() {
        assertFalse(GrantSigners.revoked(listOf(ours, theirs), listOf(theirs)))
    }

    @Test
    fun `signers that could not be read keep the grant`() {
        assertFalse(GrantSigners.revoked(listOf(ours), null))
        assertFalse(GrantSigners.revoked(listOf(ours), emptyList()))
    }

    @Test
    fun `an entry written before digests were recorded keeps the grant and is backfilled`() {
        assertFalse(GrantSigners.revoked(null, listOf(theirs)))
        assertFalse(GrantSigners.revoked(emptyList(), listOf(theirs)))
    }
}
