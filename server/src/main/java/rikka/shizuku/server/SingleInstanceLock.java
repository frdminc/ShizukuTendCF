package rikka.shizuku.server;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

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
 *
 * <p><b>The lock file is never followed through a symlink and never opened to everyone.</b> Its
 * directory is writable by shell, and the server may run as root, so a shell process could plant
 * the lock path as a symlink to a root-only file. The file is opened with {@code O_NOFOLLOW},
 * anything but a regular file is refused (the server then starts unlocked, as above), and its mode
 * is set through the open descriptor, never the path: {@code 0660}, group shell, the same policy as
 * {@code shizuku.json} (#419), so a root-created file still admits the next adb (shell) start.
 */
public final class SingleInstanceLock {

    private static final Logger LOGGER = new Logger("SingleInstanceLock");

    /**
     * Named after the process the starter sweeps for, so the lock excludes exactly the servers the
     * starter would have killed (both flavors share the server binary and the process name) and
     * nothing else: stock Shizuku's server must not block ours, nor the other way round.
     */
    static final String LOCK_NAME = ServerConstants.SERVER_NAME + ".lock";

    /** Mode of the lock file: owner and group (shell) read/write, nothing for others (#419). */
    private static final int LOCK_MODE = 0660;

    /**
     * Held for the life of the process. Both references are kept because a {@link FileLock} is
     * released when its channel is closed <em>or</em> collected: dropping them would hand the lock
     * back at the next GC, and a second server would then start hours later with no trace of why.
     */
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
        FileChannel channel = null;
        try {
            channel = openNoFollow(file);
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                LOGGER.e("another %s already holds %s; standing down so the running one keeps its clients and its grant table",
                        ServerConstants.SERVER_NAME, file);
                closeQuietly(channel);
                return false;
            }

            lockChannel = channel;
            lock = acquired;
            LOGGER.i("single-instance lock held on %s", file);
            return true;
        } catch (OverlappingFileLockException e) {
            // Only reachable if this JVM already holds it through another channel, which main()
            // cannot do twice.
            LOGGER.w("single-instance lock already held by this process");
            closeQuietly(channel);
            return true;
        } catch (Throwable tr) {
            LOGGER.w(tr, "cannot take the single-instance lock at %s; starting unlocked", file);
            closeQuietly(channel);
            return true;
        }
    }

    /**
     * Opens (creating if needed) {@code file} for locking without following a symlink at its last
     * component, and refuses anything that is not a regular file.
     */
    static FileChannel openNoFollow(File file) throws IOException, ErrnoException {
        if (isAndroid()) {
            return AndroidOpener.open(file);
        }
        // A plain JVM (unit tests): java.nio has the same O_NOFOLLOW open.
        return NioOpener.open(file);
    }

    private static boolean isAndroid() {
        return "Dalvik".equals(System.getProperty("java.vm.name"));
    }

    /** The server's path: every step after the open goes through the descriptor, not the name. */
    private static final class AndroidOpener {
        static FileChannel open(File file) throws IOException, ErrnoException {
            int flags = OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW;
            if (Build.VERSION.SDK_INT >= 27) {
                flags |= OsConstants.O_CLOEXEC;
            }
            // A symlink fails here with ELOOP: it is never followed, so never chmodded.
            FileDescriptor fd = Os.open(file.getAbsolutePath(), flags, LOCK_MODE);
            boolean ok = false;
            try {
                StructStat st = Os.fstat(fd);
                if (!OsConstants.S_ISREG(st.st_mode)) {
                    throw new IOException(file + " is not a regular file");
                }
                // Same policy as shizuku.json (#419): root needs no bits; shell gets in through the
                // group. Only the owner (or root) may change these; a shell start that opens a file
                // root created leaves it as it is.
                int uid = Os.getuid();
                if (uid != 0 && st.st_uid != uid) {
                    FileChannel channel = new FileOutputStream(fd).getChannel();
                    ok = true;
                    return channel;
                }
                if (uid == 0) {
                    try {
                        Os.fchown(fd, -1, android.os.Process.SHELL_UID);
                    } catch (ErrnoException e) {
                        LOGGER.w("cannot give %s to group shell: %s", file, e);
                    }
                }
                try {
                    Os.fchmod(fd, LOCK_MODE);
                } catch (ErrnoException e) {
                    LOGGER.w("cannot set the mode of %s: %s", file, e);
                }
                FileChannel channel = new FileOutputStream(fd).getChannel();
                ok = true;
                return channel;
            } finally {
                if (!ok) {
                    try {
                        Os.close(fd);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /** JVM only (unit tests). Not loaded on Android, whose minSdk predates java.nio.file. */
    @android.annotation.SuppressLint("NewApi")
    private static final class NioOpener {
        static FileChannel open(File file) throws IOException {
            Path path = file.toPath();
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.CREATE);
            options.add(StandardOpenOption.READ);
            options.add(StandardOpenOption.WRITE);
            options.add(LinkOption.NOFOLLOW_LINKS);
            FileChannel channel = FileChannel.open(path, options,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw----")));
            try {
                if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                    throw new IOException(file + " is not a regular file");
                }
            } catch (IOException | RuntimeException e) {
                closeQuietly(channel);
                throw e;
            }
            return channel;
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
        closeQuietly(lockChannel);
        lock = null;
        lockChannel = null;
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

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (Throwable ignored) {
        }
    }
}
