package rikka.shizuku.server

import af.shizuku.common.util.TrustedSigners
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedSignerTest {

    private fun signer(bytes: ByteArray): Signature =
        mockk<Signature> { every { toByteArray() } returns bytes }

    @Test
    fun `shipped digests are lowercase hex without colons`() {
        // apksigner prints this form; an uppercase or colon-separated entry would never match
        // sha256Hex() and would silently stop trusting the app.
        assertTrue(TrustedSigners.SHA256.isNotEmpty())
        for (digest in TrustedSigners.SHA256) {
            assertTrue(Regex("[0-9a-f]{64}").matches(digest), "bad digest format: $digest")
        }
    }

    @Test
    fun `sha256Hex matches the standard test vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TrustedSigners.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `containsTrusted matches only a listed certificate`() {
        val trustedCert = "trusted-cert".toByteArray()
        val trusted = setOf(TrustedSigners.sha256Hex(trustedCert)!!)

        assertTrue(TrustedSigners.containsTrusted(arrayOf(signer(trustedCert)), trusted))
        assertTrue(TrustedSigners.containsTrusted(arrayOf(signer("other".toByteArray()), signer(trustedCert)), trusted))
        assertFalse(TrustedSigners.containsTrusted(arrayOf(signer("other".toByteArray())), trusted))
    }

    @Test
    fun `containsTrusted fails closed`() {
        val cert = "cert".toByteArray()
        assertFalse(TrustedSigners.containsTrusted(null, setOf(TrustedSigners.sha256Hex(cert)!!)))
        assertFalse(TrustedSigners.containsTrusted(arrayOf(signer(cert)), emptySet()))
        assertFalse(TrustedSigners.containsTrusted(arrayOfNulls(1), setOf(TrustedSigners.sha256Hex(cert)!!)))
        assertFalse(TrustedSigners.isSignedByTrustedKey(null))
    }

    @Test
    fun `clearsAllowed is true only for permission writes without FLAG_ALLOWED`() {
        val mask = ConfigManager.MASK_PERMISSION
        assertTrue(ShizukuConfigManager.clearsAllowed(mask, 0), "manager revoke")
        assertTrue(ShizukuConfigManager.clearsAllowed(mask, ConfigManager.FLAG_DENIED), "dialog deny")
        assertFalse(ShizukuConfigManager.clearsAllowed(mask, ConfigManager.FLAG_ALLOWED), "grant")
        assertFalse(ShizukuConfigManager.clearsAllowed(mask, ConfigManager.FLAG_ALLOWED or ConfigManager.FLAG_DENIED), "grant with stray deny bit")
        assertFalse(ShizukuConfigManager.clearsAllowed(0, 0), "no permission bits in mask")
    }

    private fun packageUnder(
        uid: Int,
        installed: Boolean = true,
    ): PackageInfo =
        PackageInfo().apply {
            applicationInfo =
                ApplicationInfo().apply {
                    this.uid = uid
                    flags = if (installed) ApplicationInfo.FLAG_INSTALLED else 0
                }
        }

    @Test
    fun `a certificate counts only for a package installed under the uid being checked`() {
        val uid = 10_123
        assertTrue(ShizukuConfigManager.isInstalledUnderUid(packageUnder(uid), uid))

        assertFalse(ShizukuConfigManager.isInstalledUnderUid(null, uid), "package gone")
        assertFalse(ShizukuConfigManager.isInstalledUnderUid(PackageInfo(), uid), "no applicationInfo")
        assertFalse(ShizukuConfigManager.isInstalledUnderUid(packageUnder(10_124), uid), "same name reinstalled under another uid")
        assertFalse(ShizukuConfigManager.isInstalledUnderUid(packageUnder(1_010_123), uid), "same app id, other user")
        assertFalse(ShizukuConfigManager.isInstalledUnderUid(packageUnder(uid, installed = false), uid), "not installed for this user")
    }

    // versionCode, not longVersionCode: the JVM's android.jar stub reports SDK_INT 0, so the
    // fingerprint reads the int field here.
    @Suppress("DEPRECATION")
    private fun install(
        name: String,
        uid: Int,
        version: Int = 1,
        firstInstall: Long = 1_000L,
        lastUpdate: Long = 1_000L,
        installed: Boolean = true,
    ): PackageInfo =
        packageUnder(uid, installed).apply {
            packageName = name
            versionCode = version
            firstInstallTime = firstInstall
            lastUpdateTime = lastUpdate
        }

    @Test
    fun `package-set fingerprint changes with anything that could change a signer`() {
        val uid = 10_123
        val base = ShizukuConfigManager.packageSetFingerprint(uid, listOf(install("org.example.agent", uid)))!!

        assertEquals(base, ShizukuConfigManager.packageSetFingerprint(uid, listOf(install("org.example.agent", uid))))
        val changed =
            mapOf(
                "updated" to install("org.example.agent", uid, lastUpdate = 2_000L),
                "reinstalled" to install("org.example.agent", uid, firstInstall = 2_000L, lastUpdate = 2_000L),
                "new version" to install("org.example.agent", uid, version = 2),
                "other package under a reused uid" to install("org.example.other", uid),
            )
        for ((why, pi) in changed) {
            assertTrue(base != ShizukuConfigManager.packageSetFingerprint(uid, listOf(pi)), why)
        }
        assertTrue(
            base != ShizukuConfigManager.packageSetFingerprint(uid, listOf(install("org.example.agent", uid), install("org.example.sibling", uid))),
            "package added to a shared uid",
        )
    }

    @Test
    fun `package-set fingerprint is order independent and refuses an incomplete set`() {
        val uid = 10_123
        val a = install("a.example", uid)
        val b = install("b.example", uid)
        assertEquals(
            ShizukuConfigManager.packageSetFingerprint(uid, listOf(a, b)),
            ShizukuConfigManager.packageSetFingerprint(uid, listOf(b, a)),
        )
        assertEquals(null, ShizukuConfigManager.packageSetFingerprint(uid, emptyList()), "uid has no packages")
        assertEquals(null, ShizukuConfigManager.packageSetFingerprint(uid, listOf(a, null)), "a package could not be read")
        assertEquals(null, ShizukuConfigManager.packageSetFingerprint(uid, listOf(a, install("c.example", 10_124))), "package belongs to another uid")
        assertEquals(null, ShizukuConfigManager.packageSetFingerprint(uid, listOf(install("a.example", uid, installed = false))), "not installed")
    }

    @Test
    fun `a positive is believed only while the package set is unchanged`() {
        val cache = TrustedSignerCache()
        val uid = 10_123
        assertFalse(cache.hasPositive(uid))
        assertFalse(cache.confirmPositive(uid, "fp"), "nothing cached")

        cache.putPositive(uid, "fp")
        assertTrue(cache.confirmPositive(uid, "fp"))
        assertTrue(cache.confirmPositive(uid, "fp"), "still valid on a second hit")

        assertFalse(cache.confirmPositive(uid, "fp-after-update"))
        assertFalse(cache.hasPositive(uid), "a mismatch forgets the positive")
        assertFalse(cache.confirmPositive(uid, "fp"), "and the old fingerprint does not bring it back")

        cache.putPositive(uid, "fp")
        assertFalse(cache.confirmPositive(uid, null), "a failed re-read is not a match")
        assertFalse(cache.hasPositive(uid))

        cache.putPositive(uid, "fp")
        assertFalse(cache.confirmPositive(uid + 1, "fp"), "keyed by uid")
    }

    @Test
    fun `the positive cache is bounded`() {
        val cache = TrustedSignerCache()
        for (uid in 0..TrustedSignerCache.MAX_POSITIVE) {
            cache.putPositive(uid, "fp$uid")
        }
        assertFalse(cache.hasPositive(0), "eldest evicted")
        assertTrue(cache.hasPositive(TrustedSignerCache.MAX_POSITIVE))
    }

    @Test
    fun `a negative only rate-limits full lookups and expires`() {
        val cache = TrustedSignerCache()
        val uid = 10_123
        val t0 = 50_000L
        assertEquals(null, cache.recentNegative(uid, t0))

        cache.putNegative(uid, ShizukuConfigManager.Trust.LOOKUP_FAILED, t0)
        assertEquals(ShizukuConfigManager.Trust.LOOKUP_FAILED, cache.recentNegative(uid, t0 + TrustedSignerCache.NEGATIVE_INTERVAL_MS - 1))
        assertEquals(null, cache.recentNegative(uid, t0 + TrustedSignerCache.NEGATIVE_INTERVAL_MS), "next real lookup allowed after the interval")
        assertEquals(null, cache.recentNegative(uid, t0 + 1), "expired entry is gone")

        cache.putNegative(uid, ShizukuConfigManager.Trust.NOT_TRUSTED, t0)
        assertEquals(null, cache.recentNegative(uid, t0 - 1), "clock going backwards never extends it")
    }

    @Test
    fun `negatives and positives replace each other and a negative can never be TRUSTED`() {
        val cache = TrustedSignerCache()
        val uid = 10_123
        cache.putNegative(uid, ShizukuConfigManager.Trust.NOT_TRUSTED, 0L)
        cache.putPositive(uid, "fp")
        assertEquals(null, cache.recentNegative(uid, 1L), "a verified positive clears the rate limit")

        cache.putNegative(uid, ShizukuConfigManager.Trust.NOT_TRUSTED, 0L)
        assertFalse(cache.hasPositive(uid), "a negative full lookup clears the positive")

        assertFailsWith<IllegalArgumentException> { cache.putNegative(uid, ShizukuConfigManager.Trust.TRUSTED, 0L) }
    }

    @Test
    fun `a fresh negative is never evicted, however many other uids are looked up`() {
        val cache = TrustedSignerCache()
        val hammered = 10_123
        cache.putNegative(hammered, ShizukuConfigManager.Trust.NOT_TRUSTED, 0L)
        for (uid in 20_000 until 21_000) {
            cache.putNegative(uid, ShizukuConfigManager.Trust.NOT_TRUSTED, 1L)
        }
        assertEquals(ShizukuConfigManager.Trust.NOT_TRUSTED, cache.recentNegative(hammered, 2L))

        cache.putNegative(30_000, ShizukuConfigManager.Trust.NOT_TRUSTED, 1L + TrustedSignerCache.NEGATIVE_INTERVAL_MS)
        assertEquals(1, cache.negativeCount(), "expired negatives are purged on insert, so the set stays bounded")
    }

    private class FakeClock(
        var now: Long = 100_000L,
    ) : TrustedSignerCache.Clock {
        override fun now(): Long = now
    }

    private fun TrustedSignerCache.resolveWith(
        uid: Int,
        clock: FakeClock,
        budgeted: Boolean = true,
        positive: Boolean = false,
        lookup: () -> ShizukuConfigManager.Trust,
    ): ShizukuConfigManager.Trust = resolve(uid, budgeted, clock, { positive }, { lookup() })

    @Test
    fun `concurrent callers for one uid share a single lookup`() {
        val cache = TrustedSignerCache()
        val clock = FakeClock()
        val uid = 10_123
        val lookups = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner =
                pool.submit<ShizukuConfigManager.Trust> {
                    cache.resolveWith(uid, clock) {
                        lookups.incrementAndGet()
                        entered.countDown()
                        release.await()
                        ShizukuConfigManager.Trust.NOT_TRUSTED
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val joiner =
                pool.submit<ShizukuConfigManager.Trust> {
                    cache.resolveWith(uid, clock) {
                        lookups.incrementAndGet()
                        ShizukuConfigManager.Trust.NOT_TRUSTED
                    }
                }
            awaitWaiting(cache, uid)
            release.countDown()
            assertEquals(ShizukuConfigManager.Trust.NOT_TRUSTED, owner.get(5, TimeUnit.SECONDS))
            assertEquals(ShizukuConfigManager.Trust.NOT_TRUSTED, joiner.get(5, TimeUnit.SECONDS))
            assertEquals(1, lookups.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a shared positive is believed only after the joiner re-validates it`() {
        val cache = TrustedSignerCache()
        val clock = FakeClock()
        val uid = 10_123
        val lookups = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner =
                pool.submit<ShizukuConfigManager.Trust> {
                    cache.resolveWith(uid, clock) {
                        lookups.incrementAndGet()
                        entered.countDown()
                        release.await()
                        ShizukuConfigManager.Trust.TRUSTED
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // The uid's packages changed after the owner read them, so re-validation fails and
            // the joiner must look for itself.
            val joiner =
                pool.submit<ShizukuConfigManager.Trust> {
                    cache.resolveWith(uid, clock, positive = false) {
                        lookups.incrementAndGet()
                        ShizukuConfigManager.Trust.NOT_TRUSTED
                    }
                }
            awaitWaiting(cache, uid)
            release.countDown()
            assertEquals(ShizukuConfigManager.Trust.TRUSTED, owner.get(5, TimeUnit.SECONDS))
            assertEquals(ShizukuConfigManager.Trust.NOT_TRUSTED, joiner.get(5, TimeUnit.SECONDS))
            assertEquals(2, lookups.get())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitWaiting(
        cache: TrustedSignerCache,
        uid: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.waitingFor(uid) == 0) {
            check(System.nanoTime() < deadline) { "joiner never started waiting" }
            Thread.sleep(1)
        }
    }

    @Test
    fun `the cooldown runs from lookup completion, then a trusted app gets its real lookup`() {
        val cache = TrustedSignerCache()
        val clock = FakeClock(now = 0L)
        val uid = 10_123
        var lookups = 0
        val slowFailure = {
            lookups++
            clock.now = 5_000L
            ShizukuConfigManager.Trust.LOOKUP_FAILED
        }
        assertEquals(ShizukuConfigManager.Trust.LOOKUP_FAILED, cache.resolveWith(uid, clock, lookup = slowFailure))

        clock.now = 5_000L + TrustedSignerCache.NEGATIVE_INTERVAL_MS - 1
        assertEquals(ShizukuConfigManager.Trust.LOOKUP_FAILED, cache.resolveWith(uid, clock) { error("must not look up during the cooldown") })

        clock.now = 5_000L + TrustedSignerCache.NEGATIVE_INTERVAL_MS
        assertEquals(ShizukuConfigManager.Trust.TRUSTED, cache.resolveWith(uid, clock) { lookups++; ShizukuConfigManager.Trust.TRUSTED })
        assertEquals(2, lookups)
    }

    @Test
    fun `a spent budget answers UNCHECKED without a lookup and without starting a cooldown`() {
        val cache = TrustedSignerCache()
        val clock = FakeClock()
        repeat(TrustedSignerCache.MAX_BUDGETED_LOOKUPS) { i ->
            assertEquals(ShizukuConfigManager.Trust.NOT_TRUSTED, cache.resolveWith(20_000 + i, clock) { ShizukuConfigManager.Trust.NOT_TRUSTED })
        }
        val uid = 10_123
        assertEquals(ShizukuConfigManager.Trust.UNCHECKED, cache.resolveWith(uid, clock) { error("budget is spent") })
        assertEquals(null, cache.recentNegative(uid, clock.now), "UNCHECKED is not recorded")

        assertEquals(ShizukuConfigManager.Trust.TRUSTED, cache.resolveWith(uid, clock, positive = true) { error("positive needs no lookup") })
        assertEquals(
            ShizukuConfigManager.Trust.TRUSTED,
            cache.resolveWith(uid, clock, budgeted = false) { ShizukuConfigManager.Trust.TRUSTED },
            "manager queries are not charged",
        )

        clock.now += TrustedSignerCache.NEGATIVE_INTERVAL_MS
        assertEquals(ShizukuConfigManager.Trust.TRUSTED, cache.resolveWith(10_124, clock) { ShizukuConfigManager.Trust.TRUSTED }, "budget refills")
    }

    @Test
    fun `a lookup that throws or returns a non-lookup value fails closed`() {
        val cache = TrustedSignerCache()
        val clock = FakeClock()
        assertFailsWith<IllegalStateException> { cache.resolveWith(1, clock) { throw IllegalStateException("pm died") } }
        assertEquals(ShizukuConfigManager.Trust.LOOKUP_FAILED, cache.recentNegative(1, clock.now))
        assertEquals(0, cache.waitingFor(1), "nothing left in flight")

        listOf(ShizukuConfigManager.Trust.UNCHECKED, null).forEachIndexed { i, bogus ->
            assertEquals(ShizukuConfigManager.Trust.LOOKUP_FAILED, cache.resolve(2 + i, true, clock, { false }, { bogus }))
        }
    }

    @Test
    fun `only real lookup negatives can be recorded`() {
        val cache = TrustedSignerCache()
        for (t in ShizukuConfigManager.Trust.values()) {
            if (t == ShizukuConfigManager.Trust.NOT_TRUSTED || t == ShizukuConfigManager.Trust.LOOKUP_FAILED) {
                cache.putNegative(10_123, t, 0L)
            } else {
                assertFailsWith<IllegalArgumentException>(t.name) { cache.putNegative(10_123, t, 0L) }
            }
        }
    }

    @Test
    fun `only a confirmed trusted uid overrides a deny`() {
        assertEquals(TrustedSigners.CONFIRMATION_DENY_OVERRIDDEN, ShizukuConfigManager.denyOutcome(ShizukuConfigManager.Trust.TRUSTED))
        assertEquals(TrustedSigners.CONFIRMATION_DENIED, ShizukuConfigManager.denyOutcome(ShizukuConfigManager.Trust.NOT_TRUSTED))
        assertEquals(TrustedSigners.CONFIRMATION_DENIED_UNVERIFIED, ShizukuConfigManager.denyOutcome(ShizukuConfigManager.Trust.LOOKUP_FAILED))
        assertEquals(TrustedSigners.CONFIRMATION_DENIED_UNVERIFIED, ShizukuConfigManager.denyOutcome(ShizukuConfigManager.Trust.UNCHECKED))
    }

    @Test
    fun `confirmation outcomes are distinct and IGNORED is what an empty int reads as`() {
        val outcomes =
            listOf(
                TrustedSigners.CONFIRMATION_IGNORED,
                TrustedSigners.CONFIRMATION_ALLOWED,
                TrustedSigners.CONFIRMATION_DENIED,
                TrustedSigners.CONFIRMATION_DENIED_UNVERIFIED,
                TrustedSigners.CONFIRMATION_DENY_OVERRIDDEN,
            )
        assertEquals(outcomes.size, outcomes.toSet().size)
        assertEquals(0, TrustedSigners.CONFIRMATION_IGNORED)
        // AIDL "= 104" on the wire; must stay the existing method, not a new transaction code.
        assertEquals(1 + 104, TrustedSigners.CONFIRMATION_TRANSACTION)
    }

    @Test
    fun `effective entry grants only a stored allow or a confirmed trusted uid`() {
        val uid = 10_123
        val denied = ShizukuConfig.PackageEntry(uid, ConfigManager.FLAG_DENIED)
        val allowed = ShizukuConfig.PackageEntry(uid, ConfigManager.FLAG_ALLOWED)

        assertEquals(null, ShizukuConfigManager.effectiveEntry(uid, null, false))
        assertTrue(ShizukuConfigManager.effectiveEntry(uid, denied, false) === denied, "non-trusted deny is untouched")
        assertTrue(ShizukuConfigManager.effectiveEntry(uid, allowed, false) === allowed)
        assertTrue(ShizukuConfigManager.effectiveEntry(uid, allowed, true) === allowed)
        assertTrue(ShizukuConfigManager.effectiveEntry(uid, null, true)!!.isAllowed)
        assertTrue(ShizukuConfigManager.effectiveEntry(uid, denied, true)!!.isAllowed, "trusted overrides a stored deny")
        assertFalse(ShizukuConfigManager.effectiveEntry(uid, denied, true)!!.isDenied)
    }

    @Test
    fun `always-allowed capability bit is clear of the stored permission flags`() {
        // An overlap would make an older server's stored flags read as "always allowed" and lock
        // a revocable row.
        assertEquals(0, TrustedSigners.FLAG_ALWAYS_ALLOWED and ConfigManager.MASK_PERMISSION)
        assertFalse(ShizukuConfigManager.clearsAllowed(TrustedSigners.FLAG_ALWAYS_ALLOWED, 0), "capability-only mask is not a revoke")
    }
}
