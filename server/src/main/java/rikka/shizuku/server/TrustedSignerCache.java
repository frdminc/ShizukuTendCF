package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bounds ShizukuConfigManager's trusted-signer lookups. It can only ever withhold trust, never
 * grant it:
 *
 * <ul>
 * <li>A positive is remembered together with the fingerprint of the uid's package set (names,
 * install/update times, version) at the moment the certificate was verified, and is believed only
 * while the caller's freshly read fingerprint is identical. A reused uid or a replaced, updated or
 * added package changes the fingerprint, which forgets the positive and forces a full lookup.</li>
 * <li>Concurrent callers for one uid share a single full lookup. A shared negative is used as is;
 * a shared positive was read before the joining caller arrived, so it must pass the same
 * fingerprint re-validation as a cached positive.</li>
 * <li>A negative is not a cached answer, only a rate limit: for {@link #NEGATIVE_INTERVAL_MS}
 * after a full lookup COMPLETED negative the uid is answered the same without another one. It is
 * never evicted early, so no amount of traffic for other uids shortens it. A trusted app caught
 * by it waits at most that long for its next real lookup.</li>
 * <li>Lookups a client can trigger are charged to a global budget of
 * {@link #MAX_BUDGETED_LOOKUPS} per interval across all uids; once it is spent they answer
 * UNCHECKED without a lookup. UNCHECKED is not recorded, so it starts no cooldown.</li>
 * </ul>
 */
final class TrustedSignerCache {

    static final long NEGATIVE_INTERVAL_MS = 1000;

    static final int MAX_POSITIVE = 16;

    /** Full lookups charged to the budget per {@link #NEGATIVE_INTERVAL_MS}, across all uids. */
    static final int MAX_BUDGETED_LOOKUPS = 32;

    /** How long a caller waits on another caller's lookup for the same uid before failing closed. */
    static final long JOIN_TIMEOUT_MS = 5000;

    interface Clock {
        long now();
    }

    /** Re-validates a cached positive against the uid's current packages. */
    interface PositiveCheck {
        boolean confirmed();
    }

    interface FullLookup {
        ShizukuConfigManager.Trust run();
    }

    private static final class Negative {
        final ShizukuConfigManager.Trust trust;
        final long at;

        Negative(ShizukuConfigManager.Trust trust, long at) {
            this.trust = trust;
            this.at = at;
        }
    }

    private static final class Pending {
        private ShizukuConfigManager.Trust result;
        // Guarded by the cache's lock, not this object's.
        int waiting;

        synchronized void complete(ShizukuConfigManager.Trust trust) {
            result = trust;
            notifyAll();
        }

        @Nullable
        synchronized ShizukuConfigManager.Trust await(long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (result == null) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return result;
        }
    }

    // Only a verified TRUSTED lookup adds here, so other uids cannot push the agent out.
    private final Map<Integer, String> positive = new LinkedHashMap<Integer, String>(MAX_POSITIVE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > MAX_POSITIVE;
        }
    };
    // Unbounded on purpose but self-limiting: expired entries are purged on every insert, so it
    // holds at most one entry per uid whose lookup completed in the last interval.
    private final Map<Integer, Negative> negative = new HashMap<>();
    private final Map<Integer, Pending> inFlight = new HashMap<>();
    private final ArrayDeque<Long> budgetedStarts = new ArrayDeque<>();

    /**
     * The trust of {@code uid}: a re-validated positive, the last negative if it is younger than
     * the interval, the result of another caller's lookup for the same uid, or a full lookup of
     * its own (charged to the budget if {@code budgeted}, and UNCHECKED once that is spent).
     *
     * A full lookup that throws is recorded as LOOKUP_FAILED, releases any caller waiting on it,
     * and rethrows.
     */
    ShizukuConfigManager.Trust resolve(int uid, boolean budgeted, Clock clock, PositiveCheck positiveCheck, FullLookup full) {
        if (positiveCheck.confirmed()) {
            return ShizukuConfigManager.Trust.TRUSTED;
        }
        for (int attempt = 0; ; attempt++) {
            Pending pending;
            boolean owner;
            synchronized (this) {
                ShizukuConfigManager.Trust recent = recentNegative(uid, clock.now());
                if (recent != null) {
                    return recent;
                }
                pending = inFlight.get(uid);
                owner = pending == null;
                if (owner) {
                    if (budgeted && !takeBudgetLocked(clock.now())) {
                        return ShizukuConfigManager.Trust.UNCHECKED;
                    }
                    pending = new Pending();
                    inFlight.put(uid, pending);
                } else {
                    pending.waiting++;
                }
            }
            if (owner) {
                return runOwned(uid, clock, full, pending);
            }
            ShizukuConfigManager.Trust shared = pending.await(JOIN_TIMEOUT_MS);
            if (shared == null) {
                return ShizukuConfigManager.Trust.LOOKUP_FAILED;
            }
            if (shared != ShizukuConfigManager.Trust.TRUSTED) {
                return shared;
            }
            if (positiveCheck.confirmed()) {
                return ShizukuConfigManager.Trust.TRUSTED;
            }
            // The shared positive no longer matches (or was never cacheable): look again once,
            // and fail closed rather than chase a uid whose packages keep changing.
            if (attempt > 0) {
                return ShizukuConfigManager.Trust.LOOKUP_FAILED;
            }
        }
    }

    private ShizukuConfigManager.Trust runOwned(int uid, Clock clock, FullLookup full, Pending pending) {
        ShizukuConfigManager.Trust result = ShizukuConfigManager.Trust.LOOKUP_FAILED;
        try {
            ShizukuConfigManager.Trust looked = full.run();
            if (looked == ShizukuConfigManager.Trust.TRUSTED || looked == ShizukuConfigManager.Trust.NOT_TRUSTED) {
                result = looked;
            }
        } finally {
            synchronized (this) {
                inFlight.remove(uid);
                if (result != ShizukuConfigManager.Trust.TRUSTED) {
                    putNegativeLocked(uid, result, clock.now());
                }
            }
            pending.complete(result);
        }
        return result;
    }

    private boolean takeBudgetLocked(long now) {
        while (!budgetedStarts.isEmpty()) {
            long first = budgetedStarts.peekFirst();
            if (now >= first && now - first < NEGATIVE_INTERVAL_MS) {
                break;
            }
            budgetedStarts.pollFirst();
        }
        if (budgetedStarts.size() >= MAX_BUDGETED_LOOKUPS) {
            return false;
        }
        budgetedStarts.addLast(now);
        return true;
    }

    /** Callers currently waiting on another caller's lookup for {@code uid}; for tests. */
    synchronized int waitingFor(int uid) {
        Pending pending = inFlight.get(uid);
        return pending == null ? 0 : pending.waiting;
    }

    synchronized boolean hasPositive(int uid) {
        return positive.containsKey(uid);
    }

    /**
     * True only if {@code uid} was verified trusted while its packages had exactly
     * {@code currentFingerprint}. Any mismatch, including a null fingerprint from a failed read,
     * forgets the positive.
     */
    synchronized boolean confirmPositive(int uid, @Nullable String currentFingerprint) {
        String verified = positive.get(uid);
        if (verified != null && verified.equals(currentFingerprint)) {
            return true;
        }
        positive.remove(uid);
        return false;
    }

    synchronized void putPositive(int uid, String fingerprint) {
        negative.remove(uid);
        positive.put(uid, fingerprint);
    }

    /** The last full lookup's negative answer if it is younger than the rate limit, else null. */
    @Nullable
    synchronized ShizukuConfigManager.Trust recentNegative(int uid, long now) {
        Negative n = negative.get(uid);
        if (n == null) {
            return null;
        }
        if (isExpired(n, now)) {
            negative.remove(uid);
            return null;
        }
        return n.trust;
    }

    synchronized void putNegative(int uid, ShizukuConfigManager.Trust trust, long now) {
        putNegativeLocked(uid, trust, now);
    }

    private void putNegativeLocked(int uid, ShizukuConfigManager.Trust trust, long now) {
        if (trust != ShizukuConfigManager.Trust.NOT_TRUSTED && trust != ShizukuConfigManager.Trust.LOOKUP_FAILED) {
            throw new IllegalArgumentException("not a lookup negative: " + trust);
        }
        for (Iterator<Negative> it = negative.values().iterator(); it.hasNext(); ) {
            if (isExpired(it.next(), now)) {
                it.remove();
            }
        }
        positive.remove(uid);
        negative.put(uid, new Negative(trust, now));
    }

    /** For tests. */
    synchronized int negativeCount() {
        return negative.size();
    }

    private static boolean isExpired(Negative n, long now) {
        return now < n.at || now - n.at >= NEGATIVE_INTERVAL_MS;
    }
}
