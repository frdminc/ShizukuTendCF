# Trusted signer allowlist — permanent Shizuku access for your own apps

Last updated: **2026-07-31**

## The problem this solves

`ShizukuConfigManager`'s constructor reconciles the persisted per-app
authorization file (`/data/user_de/0/com.android.shell/shizuku.json`) against
whatever's currently installed, **every time `shizuku_server` starts**:

```java
List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(entry.uid);
if (packages.isEmpty()) {
    // uid has gone away entirely -> drop its grant
}
if (packagesChanged) {
    // the package set for this uid differs from what was persisted -> drop its grant
}
```

In practice this means an app's Shizuku permission can silently disappear
with **no user-visible cause** — not just on uninstall/reinstall (which is
arguably correct), but from anything that makes
`getPackagesForUidNoThrow(uid)` report a different result than last time,
including transient reads during unrelated install/uninstall activity
elsewhere on the device. This was hit directly during stayturgid fleet work
on 2026-07-31: the production `org.stayturgid.agent`'s grant was found reset
(`"Authorized 0 applications"` in the manager app) twice in one session,
including once from nothing more than restarting `shizuku_server` locally —
no reboot, no reinstall, no obvious trigger. Recovering it required manually
reopening the app and re-approving the permission dialog each time.

For an app that's supposed to be an **always-on fleet automation agent**,
"silently loses privileged access for no visible reason, with no
notification" is a real reliability problem, not just an inconvenience.

## The fix: `TRUSTED_SIGNER_SHA256` in `ShizukuConfigManager`

`ShizukuConfigManager.find(uid)` — the method that actually gates every
permission check (see its call sites in `ShizukuService.getFlagsForUidInternal`)
— now checks a hardcoded allowlist of trusted **APK signing certificate SHA-256
fingerprints** before ever consulting the persisted config:

```java
private static final Set<String> TRUSTED_SIGNER_SHA256 = new LinkedHashSet<>(List.of(
        "6651cb1582a2ab9f83bc8203da4e4591bc76ef187e8b4c771dcc7a9768be293e"
));
```

If any package installed under the calling UID is signed by one of these
certificates, `find()` returns a synthetic entry with `FLAG_ALLOWED` set —
unconditionally, regardless of what's (or isn't) persisted. The existing
reconciliation/removal logic in the constructor is untouched; this is a
pure addition that short-circuits `find()` before it, so it can't affect
Shizuku's behavior for any other app.

## Why signing certificate, not package name or UID

Two things were considered and rejected before landing on this:

- **Package name allowlist** (`if (packageName.equals("org.stayturgid.agent"))`):
  defeated trivially — once the real app is uninstalled (which is exactly
  the scenario that motivated this fix), *any* app can be installed under
  that same package name and would inherit the trust. A signing-key check
  can't be forged without the actual private key.
- **UID allowlist**: UIDs are reassigned across installs/reinstalls, so a
  hardcoded UID would silently stop working (or worse, silently apply to
  whatever unrelated app got assigned that UID later) the first time the
  app was reinstalled.

## Why *not* "just trust whoever signed the currently-running Shizuku build"

This was a real alternative considered: instead of hardcoding a specific
fingerprint, dynamically read Shizuku's *own* signing certificate at runtime
and trust anything signed by the same key — "recompile with your own key,
no source changes needed." It's an appealing idea in principle, but **as
this fork is currently built, it would be a serious security hole**:
absent `signing.properties` (see `signing.gradle`), the build falls back to
the **shared, universally-known Android debug keystore**
(`CN=Android Debug`, password `"android"`, alias `"androiddebugkey"` — the
literal default every Android developer's machine uses). Verified directly
against the actual deployed release APK on 2026-07-31:

```
$ apksigner verify --print-certs shizuku-release.apk
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 5ca8d5fcce4bad08206eb7e112b5c9d63b35b0a8874e3d25af48e8e332a9596a
```

"Trust whoever signed this build" would, today, mean "trust *any* debug-signed
APK from *any* developer anywhere" — the opposite of the intended guarantee.
This is the same gap tracked as `OPTIONS.md`'s **H1 — CI signing secrets**.
If H1 is ever fixed (a dedicated, non-debug release key configured for CI),
the self-referential design becomes safe and genuinely more convenient —
but until then, an explicit hardcoded fingerprint for a *known, independently
verified* signing key is the only safe option, and costs nothing extra to
set up in the meantime.

## Adapting this for your own app (person or AI agent)

You don't need to touch anything except the one constant, and you don't need
this fork's own signing set up correctly — only *your own app's* key needs
to be a real, dedicated, non-shared signing identity (i.e., not the debug
keystore).

1. **Get your app's real signing certificate SHA-256**, from the actual APK
   you install on-device — not from a keystore file, so you're verifying
   what's actually installed, not what you think you built:

   ```bash
   apksigner verify --print-certs your-app.apk
   # or, from an already-installed app, pull it first:
   adb shell pm path <your.application.id>
   adb pull <path-from-above> /tmp/app.apk
   apksigner verify --print-certs /tmp/app.apk
   ```

   Confirm the `DN` line shows *your own* identity, not `CN=Android Debug` —
   if it does, you're using the shared debug keystore and should set up a
   dedicated one before relying on this mechanism.

2. **Add the SHA-256 digest** (lowercase hex, no colons — the digest as
   printed by `apksigner` is already in this format) to
   `TRUSTED_SIGNER_SHA256` in
   `server/src/main/java/rikka/shizuku/server/ShizukuConfigManager.java`.
   Add a one-line comment naming the app/identity it belongs to, matching
   the existing entry.

3. **Rebuild and release** your Shizuku fork as usual. No other files need
   to change — `find()` already checks every package under a UID, so any
   number of your own apps sharing that same signing key are covered by one
   entry.

4. Any app signed with that key now gets Shizuku access automatically the
   first time it's installed and asks — no interactive "Allow" dialog, no
   risk of the grant silently disappearing later.

## What this does *not* protect against

This is a trust decision, not a sandbox. If your signing key is ever
compromised, anything signed with it gets automatic, silent Shizuku access
— the same as any code-signing trust model. Keep your keystore file and
passwords secret with the same care you'd give any other production signing
key. This mechanism only removes the *interactive, easily-and-silently-lost*
approval step for keys you already fully trust — it doesn't change what
"trusting a key" means.
