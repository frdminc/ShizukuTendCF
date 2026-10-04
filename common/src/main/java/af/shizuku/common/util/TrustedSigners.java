package af.shizuku.common.util;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The one list of APK signing certificates whose apps the server always allows. Only the server
 * evaluates it (ShizukuConfigManager); the manager asks the server through
 * {@link #FLAG_ALWAYS_ALLOWED} so both sides apply the same per-uid rule.
 * See docs/trusted-signer-allowlist.md.
 */
public final class TrustedSigners {

    /**
     * SHA-256 fingerprints (lowercase hex, no colons) of APK signing certificates that are always
     * granted Shizuku access, regardless of what is (or isn't) persisted in the config file.
     *
     * Works around a gap in the server's start-up reconciliation: it drops a UID's entry the moment
     * the live package set for that UID differs from what was last persisted — including a
     * transient/inconsistent read during an unrelated install or uninstall elsewhere on the device —
     * so a trusted app's grant could silently disappear and have to be re-approved by hand.
     *
     * Keyed by signing certificate, not package name or UID: a package-name allowlist is defeated
     * by installing another app under that name once the real one is gone, and UIDs are reassigned
     * across installs. This must never be a shared/debug keystore.
     */
    public static final Set<String> SHA256 = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            // stayturgid-agent release signing key (CN=stayturgid, O=stayturgid, C=US).
            "35bbc3d1a93c2a726df14bcc066bdc791f7f55f21b57d9455da27439c5ff9b6a"
    )));

    /**
     * Capability bit on the existing getFlagsForUid query: a server that enforces "trusted signers
     * are always allowed" sets it for such a uid when the caller's mask asks for it. It is computed
     * per call and never persisted, so a server without the rule answers {@code flags & mask} = 0
     * and the manager does not lock the row. A high bit, clear of ConfigManager's FLAG_ALLOWED /
     * FLAG_DENIED and of any low bit upstream may add, without a new binder transaction code.
     */
    public static final int FLAG_ALWAYS_ALLOWED = 1 << 30;

    /**
     * Wire code of IShizukuService.dispatchPermissionConfirmationResult (AIDL "= 104"). The AIDL
     * declares it oneway; sent without FLAG_ONEWAY, a server enforcing the trusted-signer rule
     * answers with one of the CONFIRMATION_* values below, the outcome that actually took effect.
     * Any other server runs the AIDL handler, which writes no reply at all.
     */
    public static final int CONFIRMATION_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 104;

    /** Not applied: the caller is not the manager, or the reply bundle was missing. */
    public static final int CONFIRMATION_IGNORED = 0;
    public static final int CONFIRMATION_ALLOWED = 1;
    /** Deny honoured; the uid was confirmed not trusted. */
    public static final int CONFIRMATION_DENIED = 2;
    /** Deny honoured because the signer lookup failed: a failed lookup never overrides a deny. */
    public static final int CONFIRMATION_DENIED_UNVERIFIED = 3;
    /** Deny not honoured: the uid was confirmed trusted, so the requester was allowed instead. */
    public static final int CONFIRMATION_DENY_OVERRIDDEN = 4;

    private TrustedSigners() {
    }

    /**
     * True if {@code pi} (fetched with GET_SIGNING_CERTIFICATES) is signed by a trusted
     * certificate. Below API 28 there is no SigningInfo, so nothing is trusted.
     */
    public static boolean isSignedByTrustedKey(@Nullable PackageInfo pi) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || pi == null || pi.signingInfo == null) {
            return false;
        }
        return containsTrusted(pi.signingInfo.getApkContentsSigners(), SHA256);
    }

    public static boolean containsTrusted(@Nullable Signature[] signers, Set<String> trusted) {
        if (signers == null || trusted.isEmpty()) {
            return false;
        }
        for (Signature signature : signers) {
            if (signature == null) {
                continue;
            }
            String digest = sha256Hex(signature.toByteArray());
            if (digest != null && trusted.contains(digest)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
