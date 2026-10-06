package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Decides, for one UID in the config, whether its packages are still installed. A config entry is
 * where a UID's grant lives, so removing it revokes the grant; this only answers "gone" when the
 * device positively said so.
 *
 * <p>Two readings are available, and each fails silently in the same shape as a real "nothing":
 * <ul>
 *   <li>the installed-package list of the UID's user (empty when the read failed: a real user always
 *       has packages, so an empty list is a failed read, passed here as {@code null});</li>
 *   <li>a packages-for-UID lookup, asked in its throwing form so a call that never got through is
 *       told apart from an answer.</li>
 * </ul>
 * The rule: the UID is gone only when its user's list was read and does not name it, <em>and</em>
 * the lookup answered without throwing and named nothing. Anything else that names a package means
 * present; anything that could not be read means unknown, and the entry is kept.
 *
 * <p>Adapted from rushiranpise/Shizuku-Next 954386ad (Apache-2.0).
 */
final class UidPackages {

    private UidPackages() {
    }

    /**
     * @param namedByUserList the packages the UID's user's installed list names for this UID, or
     *                        {@code null} when that list could not be read
     * @param lookup          the throwing packages-for-UID lookup
     * @return the UID's packages; an empty list when it is positively gone; {@code null} when it
     * cannot be told (keep the entry)
     */
    @Nullable
    static List<String> of(@Nullable List<String> namedByUserList, Callable<String[]> lookup) {
        if (namedByUserList != null && !namedByUserList.isEmpty()) {
            return namedByUserList;
        }

        String[] answered;
        try {
            answered = lookup.call();
        } catch (Throwable tr) {
            return null;
        }
        if (answered != null && answered.length > 0) {
            return new ArrayList<>(Arrays.asList(answered));
        }

        // The lookup said "nothing". Believe it only if the user's list was read and agrees.
        return namedByUserList == null ? null : Collections.emptyList();
    }
}
