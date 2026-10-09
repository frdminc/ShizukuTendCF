package rikka.shizuku.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The single-server lock (#26): one process may serve, the lock is really held while it serves,
 * another process holding it makes [SingleInstanceLock.acquire] stand down, and an unusable lock
 * path fails open.
 */
class SingleInstanceLockTest {
    private val dir: File = createTempDirectory("single-instance").toFile()

    @AfterEach
    fun tearDown() {
        SingleInstanceLock.release()
        dir.deleteRecursively()
    }

    @Test
    fun `the first server takes the lock and keeps it`() {
        val file = File(dir, SingleInstanceLock.LOCK_NAME)
        assertTrue(SingleInstanceLock.acquire(file))
        assertTrue(file.exists())
        assertTrue(SingleInstanceLock.isHeld())

        // Held for real: an independent channel in this JVM cannot take it (same-process attempts
        // throw rather than return null).
        RandomAccessFile(file, "rw").use { raf ->
            try {
                val other = raf.channel.tryLock()
                other?.release()
                throw AssertionError("the lock was not held: a second channel took it")
            } catch (_: OverlappingFileLockException) {
            }
        }

        // A second acquire by the same process is not a conflict.
        assertTrue(SingleInstanceLock.acquire(file))
    }

    @Test
    fun `releasing hands the lock back`() {
        val file = File(dir, SingleInstanceLock.LOCK_NAME)
        assertTrue(SingleInstanceLock.acquire(file))
        SingleInstanceLock.release()
        assertFalse(SingleInstanceLock.isHeld())
        RandomAccessFile(file, "rw").use { raf ->
            val taken = raf.channel.tryLock()
            assertNotNull(taken, "the lock was still held after release")
            taken.release()
        }
    }

    @Test
    fun `an unusable lock path fails open`() {
        // A directory cannot be opened as a file: the lock cannot be attempted, so the server runs.
        val file = File(dir, SingleInstanceLock.LOCK_NAME).apply { mkdirs() }
        assertTrue(SingleInstanceLock.acquire(file))
        assertFalse(SingleInstanceLock.isHeld())
    }

    @Test
    fun `a second process finds the lock held and stands down`() {
        val file = File(dir, SingleInstanceLock.LOCK_NAME)
        val holder = startHolder(file)
        assumeTrue(holder != null, "could not start a holder JVM")
        try {
            assertFalse(SingleInstanceLock.acquire(file), "the loser must stand down")
            assertFalse(SingleInstanceLock.isHeld())
        } finally {
            holder!!.destroyForcibly()
            holder.waitFor(10, TimeUnit.SECONDS)
        }
        // With the holder dead the kernel has dropped its lock: no cleanup path to get wrong.
        RandomAccessFile(file, "rw").use { raf ->
            val taken = raf.channel.tryLock()
            assertNotNull(taken, "the dead holder's lock lingered")
            taken.release()
        }
    }

    /** A JVM that holds [file]'s lock until killed, or null if one cannot be started here. */
    private fun startHolder(file: File): Process? {
        val java = File(System.getProperty("java.home"), "bin/java")
        if (!java.canExecute()) return null
        val process =
            runCatching {
                ProcessBuilder(
                    java.absolutePath,
                    "-cp",
                    System.getProperty("java.class.path"),
                    LockHolder::class.java.name,
                    file.absolutePath,
                ).redirectErrorStream(true).start()
            }.getOrNull() ?: return null
        // Waits for the holder to say it has the lock.
        val line = process.inputStream.bufferedReader().readLine()
        if (line != LockHolder.HELD) {
            process.destroyForcibly()
            return null
        }
        return process
    }
}

/** Main class of the holder JVM: takes the lock on `args[0]` and sleeps until killed. */
object LockHolder {
    const val HELD = "HELD"

    @JvmStatic
    fun main(args: Array<String>) {
        RandomAccessFile(File(args[0]), "rw").use { raf ->
            val lock = raf.channel.tryLock()
            if (lock == null) {
                println("NOT_HELD")
                return
            }
            println(HELD)
            System.out.flush()
            while (true) Thread.sleep(1_000)
        }
    }
}
