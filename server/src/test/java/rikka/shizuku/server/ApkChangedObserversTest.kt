package rikka.shizuku.server

import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApkChangedObserversTest {

    private fun listener() = object : ApkChangedListener {
        override fun onApkChanged() = Unit
    }

    @Test
    fun `stop removes the observer once its last listener is gone`() {
        val a = listener()
        val b = listener()
        ApkChangedObservers.start("/data/app/test-one/base.apk", a)
        ApkChangedObservers.start("/data/app/test-one/base.apk", b)
        ApkChangedObservers.stop(a)
        assertEquals(1, ApkChangedObservers.observerCountForTest())
        ApkChangedObservers.stop(b)
        assertEquals(0, ApkChangedObservers.observerCountForTest())
    }

    @Test
    fun `concurrent start and stop neither throw nor leak observers`() {
        // stop() used to iterate the shared map without its lock while binder threads called
        // start(), which can throw ConcurrentModificationException inside the server.
        val threads = 8
        val rounds = 2000
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(threads) { t ->
            pool.execute {
                ready.countDown()
                go.await()
                try {
                    repeat(rounds) { i ->
                        val l = listener()
                        ApkChangedObservers.start("/data/app/test-$t-${i % 16}/base.apk", l)
                        ApkChangedObservers.stop(l)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish")
        assertTrue(errors.isEmpty(), "start/stop threw: ${errors.firstOrNull()}")
        assertEquals(0, ApkChangedObservers.observerCountForTest())
    }
}
