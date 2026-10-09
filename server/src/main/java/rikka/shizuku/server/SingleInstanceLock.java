package rikka.shizuku.server;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

import rikka.shizuku.server.util.Logger;

/**
 * The invariant that only one {@code shizuku_plus_server} may exist, enforced by the server itself
 * (frdminc/ShizukuTendCF#26; after ShiroiKuma0/shiroikuma-shizuku eedfd83f, Apache-2.0).
 *
 * <p><b>Why this cannot live in the starter alone.</b> {@code starter.cpp} kills any existing server
 * and then forks a new one, with nothing atomic in between, and it is run from several places (the
 * boot receiver, the watchdog, the tile, a start by hand, {@code adb shell}). Two starters running
 * close together each sweep, each find nothing to kill because neither server exists yet, and each
 * then forks one. Reordering cannot close that; only something atomic can, and the natural atomic
 * thing is a lock held by the process that must be unique.
 *
 * <p><b>Why a duplicate is not merely wasteful.</b> Each server loads the grant table once at
 * startup, every server pushes its binder to every client at every launch, and a client keeps the
 * first binder that arrives ({@code ShizukuProvider.handleSendBinder}). Which server a client ends
 * up bound to is then a coin toss, and a grant made after both started lives in exactly one of them.
 * Both also flush the same config file from their own in-memory copy, so the loser's flush can
 * silently erase the grant table.
 *
 * <p><b>Why a file lock rather than "the new server kills the old one".</b> The older server may be
 * holding live user-service bindings for apps that are working fine. The loser of the race is the
 * one that should stand down, and an advisory file lock picks the winner atomically however many
 * starters fire. It cannot go stale: the kernel drops it when the holder dies, SIGKILL included.
 *
 * <p><b>Failure is open, not closed.</b> If the lock file cannot be created or locked at all (a
 * read-only path, an SELinux denial, an OEM oddity) the server starts unlocked. A device where the
 * lock is unavailable must still get a working Shizuku; the duplicate is a rare race, and refusing
 * to start would turn it into an outage.
 */
public final class SingleInstanceLock {

    private static final Logger LOGGER = new Logger("SingleInstanceLock");

    /**
     * Named after the process the starter sweeps for, so the lock excludes exactly the servers the
     * starter would have killed (both flavors share the server binary and the process name) and
     * nothing else: stock Shizuku's server must not block ours, nor the other way round.
     */
    static final String LOCK_NAME = ServerConstants.SERVER_NAME + ".lock";

    /**
     * Held for the life of the process. All three references are kept because a {@link FileLock}
     * is released when its channel is closed <em>or</em> collected: dropping them would hand the
     * lock back at the next GC, and a second server would then start hours later with no trace of
     * why.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private static RandomAccessFile lockRaf;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileChannel lockChannel;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileLock lock;

    private SingleInstanceLock() {
    }

    /**
     * @return {@code true} if this process may serve: either it took the lock, or the lock could
     * not be attempted at all. {@code false} only when another live server demonstrably holds it,
     * which is the caller's cue to exit without publishing any binder.
     */
    public static boolean acquire() {
        return acquire(lockFile());
    }

    /** {@link #acquire()} on a given file (tests). */
    static synchronized boolean acquire(File file) {
        if (lock != null && lock.isValid()) {
            LOGGER.w("single-instance lock already held by this process");
            return true;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            // The server runs as shell (adb start) or root (root start), and whichever created the
            // file first owns it. A zero-byte lock file carries nothing worth protecting, so open
            // it to everyone rather than let a root-created file lock out the next adb start.
            try {
                //noinspection ResultOfMethodCallIgnored
                file.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                file.setWritable(true, false);
            } catch (Throwable ignored) {
            }

            FileChannel channel = raf.getChannel();
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                LOGGER.e("another %s already holds %s; standing down so the running one keeps its clients and its grant table",
                        ServerConstants.SERVER_NAME, file);
                closeQuietly(raf);
                return false;
            }

            lockRaf = raf;
            lockChannel = channel;
            lock = acquired;
            LOGGER.i("single-instance lock held on %s", file);
            return true;
        } catch (OverlappingFileLockException e) {
            // Only reachable if this JVM already holds it through another channel, which main()
            // cannot do twice.
            LOGGER.w("single-instance lock already held by this process");
            closeQuietly(raf);
            return true;
        } catch (Throwable tr) {
            LOGGER.w(tr, "cannot take the single-instance lock at %s; starting unlocked", file);
            closeQuietly(raf);
            return true;
        }
    }

    /** Whether this process holds the lock (tests). */
    static synchronized boolean isHeld() {
        return lock != null && lock.isValid();
    }

    /** Hands the lock back (tests only: a server holds it until it dies). */
    static synchronized void release() {
        try {
            if (lock != null) lock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (lockChannel != null) lockChannel.close();
        } catch (Throwable ignored) {
        }
        closeQuietly(lockRaf);
        lock = null;
        lockChannel = null;
        lockRaf = null;
    }

    /**
     * Where the lock lives, following {@code ShizukuConfigManager.getConfigFile()} exactly: the
     * server may be running as shell and may start before the user has unlocked, so it cannot use
     * the manager's data dir. {@code com.android.shell}'s DE storage is writable by the uid we
     * normally run as; {@code /data/local/tmp} is the fallback when it is not.
     *
     * <p>The {@code exists()} check comes first so that once either path has been used, every later
     * server agrees on it: two servers resolving to <em>different</em> files would not exclude each
     * other, which is the one way this could quietly do nothing.
     */
    static File lockFile() {
        File shellFile = new File("/data/user_de/0/com.android.shell/" + LOCK_NAME);
        if (shellFile.exists()) {
            return shellFile;
        }
        try {
            File parent = shellFile.getParentFile();
            if (parent != null && parent.exists() && parent.canWrite()) {
                return shellFile;
            }
        } catch (Throwable ignored) {
        }
        return new File("/data/local/tmp/" + LOCK_NAME);
    }

    private static void closeQuietly(RandomAccessFile raf) {
        if (raf == null) return;
        try {
            raf.close();
        } catch (Throwable ignored) {
        }
    }
}
