package rikka.shizuku.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
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

    @Test
    fun `a lock path that is a symlink is not followed and its target keeps its mode`() {
        // 6.1a: shell can plant the lock path as a symlink to a root-only file; a root start must
        // neither open the target nor open it up to everyone.
        val target = File(dir, "root-only").apply { writeText("secret") }
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        val link = File(dir, SingleInstanceLock.LOCK_NAME)
        Files.createSymbolicLink(link.toPath(), target.toPath())

        assertTrue(SingleInstanceLock.acquire(link), "a refused lock path fails open")
        assertFalse(SingleInstanceLock.isHeld(), "the lock must not be taken through a symlink")
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())),
            "the symlink target's mode changed",
        )
        assertTrue(Files.isSymbolicLink(link.toPath()), "the planted link is left as it was")
        RandomAccessFile(target, "rw").use { raf ->
            val taken = raf.channel.tryLock()
            assertNotNull(taken, "the symlink target was locked")
            taken.release()
        }
    }

    @Test
    fun `a new lock file grants nothing to other users`() {
        // 6.1a: no world bits (the #419 policy for shizuku.json).
        val file = File(dir, SingleInstanceLock.LOCK_NAME)
        assertTrue(SingleInstanceLock.acquire(file))
        val mode = Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        val others = setOf(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        assertTrue(mode.none { it in others }, "lock file is open to others: ${PosixFilePermissions.toString(mode)}")
    }

    /**
     * A JVM that holds [file]'s lock until killed (or, with [exitAfterMs], exits that long after it
     * took it), or null if one cannot be started here.
     */
    private fun startHolder(
        file: File,
        exitAfterMs: Long? = null,
    ): Process? {
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
                    (exitAfterMs ?: -1L).toString(),
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

/**
 * Main class of the holder JVM: takes the lock on `args[0]` and sleeps until killed, or exits
 * `args[1]` ms after taking it when that is not negative.
 */
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
            val exitAfterMs = args.getOrNull(1)?.toLongOrNull() ?: -1L
            if (exitAfterMs >= 0) {
                Thread.sleep(exitAfterMs)
                Runtime.getRuntime().halt(0)
            }
            while (true) Thread.sleep(1_000)
        }
    }
}
