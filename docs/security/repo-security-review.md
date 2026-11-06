# ShizukuTendCF repository security review (post-merge)

Date: 2026-11-06
Reviewed tree: branch `claude/security-repo-review`, HEAD `270b8240` (the upstream merge
`c32c1c4b`, "Merge thejaustin/ShizukuPlus master (upstream) into ShizukuTendCF", three-way base
`9ceb9f49`; `api` submodule at `3c0daad9`).
Scope: the whole repository, with extra attention to what the merge added or reshaped (upstream's
`ExternalStartActivity`, `ManagerFlavours` secondary-flavour signing, the grant migration,
auto-grant and risky-API gates in `ShizukuService` / `ShizukuConfigManager`, the fork's trusted-
signer allowlist and headless/provision receivers, the update checker/installer and checksum
verification, CI and supply chain).
Method: static review of the source, build files and workflows; every finding was checked against
the code path it names, and line numbers are from this HEAD. No source, scripts or tests were
changed (portability PRs #43-#46 and a harness fix are in flight). No device or dynamic testing.

All line references are repository-relative. `M/` is short for
`manager/src/main/java/af/shizuku/manager/`, `S/` for `server/src/main/java/rikka/shizuku/server/`.

## 1. Summary

No Critical finding. One High, eight Medium, the rest Low or informational. The merge itself
closed more than it opened: the old uninstall-and-reinstall "force update" path (a real same-signer
bypass) is gone, `StarterActivity` is no longer exported, legacy non-TLS ADB authentication is
confined to adbd's own port, grants now record and re-check signers at start-up, and the manager
identity is decided by signature rather than package name. The remaining issues cluster around
three themes:

1. **Runtime re-validation of trust.** Signer and package-set checks run at server start, not
   while the server is up, so a uid freed by an uninstall carries its grant to the next install
   until the server restarts (S1). Scope rules, the binder firewall and the two "risky" developer
   options live only in server memory and default open until the manager pushes them (S3).
2. **The per-app API scopes are a text-pattern denylist with side doors.** Custom binder
   transactions, `elevateApp` and the SU-bridge branch skip the scope check entirely (S2), and the
   command matcher can be satisfied by shell glob expansion or by other system CLIs (S4). The
   scopes are a user-set restriction on an already-granted app, not primary authentication, so
   these are Medium rather than High.
3. **CI handles the release keystore more loosely than its comments claim** (C1), pulls
   unreviewed translations into signed releases when Crowdin is configured (C2), does not pin
   workflow token permissions (C3) and has no dependency verification (C4).

### Ranked findings

| ID | Sev | Area | Title |
|---|---|---|---|
| S1 | High | server | Stored grants are re-validated only at server start; a reused uid inherits the grant until restart |
| S2 | Medium | server | Per-app API scopes skipped by custom transactions 10005/10006, `elevateApp` and the `setenforce` branch |
| S3 | Medium | server | Scope rules, binder firewall and risky-API gates are in-memory only and default open until the manager syncs |
| S4 | Medium | server | `ScopeCommandMatcher` is a pattern denylist; glob expansion and alternative CLIs sidestep it |
| U1 | Medium | updater | Root and Shizuku installs write a fixed, shell-writable `/data/local/tmp/update.apk` with no symlink protection |
| C1 | Medium | CI | Decoded release keystore and `signing.properties` remain in the workspace for later steps, including a third-party action |
| C2 | Medium | CI | Crowdin translations are fetched straight into release builds without review (dormant until secrets exist) |
| C3 | Medium | CI | No top-level `permissions:` in `app.yml` / `pr-check.yml`; token scope depends on repository defaults |
| C4 | Medium | supply chain | No Gradle dependency verification or lockfiles; `mavenLocal()` precedes remote repositories |
| S5 | Low | server | A locally built release falls back to the debug keystore, and `signedLikeManager` would then trust that key |
| S6 | Low | server | `attachApplication` accepts a claimed package name when the package list is empty |
| S7 | Low | server | `elevateApp` resolves the target in user 0 regardless of the caller's user |
| M1 | Low | manager | Quick Settings tile starts and stops the privileged server from the lock screen |
| M2 | Low | manager | `ExternalStartActivity` cannot start in ADB mode (port 0) and starts root mode with no confirmation or opt-in |
| M3 | Low | manager | Unauthenticated START/STOP/REQUEST_BINDER broadcasts post heads-up error notifications on demand |
| M4 | Low | manager | Fake-ADB proxy pairing prompt cannot name the local app asking (opt-in feature) |
| M5 | Low | manager | Onboarding restore trusts a file in the app's external Downloads directory with privileged settings (API 24-29) |
| M6 | Low | manager | Grant-consent dialog has no overlay / tapjacking protection |
| U2 | Low | updater | Direct-download fallback follows redirects to any scheme or host |
| U3 | Low | updater | Unbounded response read in the update checker |
| U4 | Low | updater | `checkSignaturesMatch` fails open |
| U5 | Low | ADB | Pairing may dial another LAN host advertised over mDNS; any certificate accepted during pairing |
| U6 | Low | ADB | ADB private key stored in plaintext when AndroidKeyStore is unusable |
| U7 | Low | ADB | A rogue loopback TLS listener found by the port scan can be pinned as "adbd" (denial of service / confusion) |
| R1 | Low | manager | Reinstall copy of settings written world-readable (`chmod 644`) in `/data/local/tmp` |
| C5 | Low | CI | Gradle build scans of release builds are published to a public service |
| C6 | Low | CI | Secret scanning uses an old `checkout` major; Gitleaks removed; no CodeQL or Dependabot configuration |
| C7 | Low | repo | Committed dangling symlink to a Termux home path; committed Claude hook auto-runs a repository script |
| S8-S11, M7-M9, U8-U9, C8, D1-D2 | Info | various | Design notes and documentation corrections (sections 2-7) |

## 2. Server: trust core (`ShizukuService`, `ShizukuConfigManager`, flavours, allowlist)

### S1. Stored grants re-validated only at server start (High)

- `S/ShizukuConfigManager.java:437-441` (`storedEntry`), `:285, :316` (the only places
  `unconfirmedUids` is populated, both in start-up reconciliation),
  `api/server-shared/src/main/java/rikka/shizuku/server/ClientManager.java:106-112`,
  `S/ShizukuService.java:3736-3748` (`dispatchPackageChanged`).
- `storedEntry` returns the stored entry unchanged unless the uid is in `unconfirmedUids`:

  ```java
  ShizukuConfig.PackageEntry entry = findLocked(uid);
  if (entry == null || !entry.isAllowed() || !unconfirmedUids.contains(uid)) {
      return entry;
  }
  ```

  `unconfirmedUids` is filled only in the constructor. `ClientManager.addClient` calls
  `configManager.find(uid)` and sets `allowed = true` from it. `dispatchPackageChanged` drops
  client records but never config entries, and nothing in `manager/` calls it
  (`grep -rn dispatchPackageChanged manager/` is empty), so the running server never learns about
  a removal.
- Impact: the user uninstalls a granted app; Android hands the freed appId to the next install
  (the lowest free slot); `BinderSender` delivers the binder to that app if it declares the API
  permission; on attach, `storedEntry` returns the allowed entry and the new app has shell (or
  root) access with no dialog, until the server restarts. The trusted-signer path already solves
  this correctly (`TrustedSignerCache.confirmPositive` re-validates by package-set fingerprint,
  `S/ShizukuConfigManager.java:759-760, 828-846`); ordinary grants do not use that pattern. Upstream
  Shizuku behaves the same way, so this is inherited, but the fork's signer recording set out to
  close exactly this gap and currently closes it only across restarts.
- Fix: record the package-set fingerprint (name, version, first-install and last-update time)
  with each grant and treat a mismatch in `storedEntry` as "unconfirmed" so the existing
  `check()` / `SIGNER_CHANGED` path runs; have the server watch granted packages' APK directories
  (`ApkChangedObservers` already does this for the secondary manager) and mark the uid unconfirmed
  on delete; at minimum, make the manager actually send `dispatchPackageChanged` and have the
  handler mark `EXTRA_UID` unconfirmed. Add a scenario test for "uninstall, reinstall under the
  same uid, attach" (CLAUDE.md rule 6).

### S2. Scope checks skipped by custom transactions and elevation paths (Medium)

- `S/ShizukuService.java:3043-3072` (wire codes 10005/10006 grant/revoke runtime permission),
  `:3604-3621` (`elevateApp`), `:1794-1799` (`setenforce|chcon|restorecon` to
  `performAppOpsElevation`), `:3682-3698`.
- Code 10005 does `enforceCallingPermission("grantRuntimePermission")` and then calls
  `Android17Compat.grantRuntimePermission(packageName, permissionName, userId)` for any package and
  user. The paths that do enforce scopes map `pm` to `package_manager` and `appops` to `appops`
  (`:1478-1483`), `isBinderCallBlocked` maps `IPermissionManager` / `IAppOpsService` to `appops`
  (`:1144-1156`), and `getPackageGovernorPlus` checks `package_manager` (`:3543`).
- Impact: an app the user restricted with the `appops` or `package_manager` scope off can still
  grant itself (or any package) runtime permissions through transaction 10005, or obtain
  `SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`,
  `WRITE_SECURE_SETTINGS` and `DUMP` through `elevateApp`.
- Fix: add `isAppScopeDisabled(callingUid, null, "appops")` and `"package_manager"` checks to
  10005/10006, `elevateApp` and the `setenforce` branch, or route 10005/10006 through
  `isBinderCallBlocked("android.permission.IPermissionManager", ...)`.

### S3. Scope, firewall and risky-API state is in-memory and defaults open (Medium)

- `S/ShizukuService.java:992-993` (`featureEnabledMap`, `plusSettingsMap` are plain maps),
  `:1014` (`isFeatureEnabled` defaults false, so `binder_firewall` is off), `:1101-1102`
  (`isAppScopeDisabled` returns false when `per_app_disabled_scopes` is unset), `:1159`.
- Nothing in `ShizukuConfig` persists these; `setPlusSetting` / `updatePlusFeatureEnabled` are
  pushed by the manager (`:1345-1363`) from settings screens, Service Doctor and automation rules.
  After every boot-start or watchdog restart there is a window in which a scope-restricted or
  firewall-blocked app that attaches before the manager syncs gets unrestricted `newProcess` and
  `transactRemote`.
- Fix: persist `per_app_disabled_scopes`, `firewall_blocked_descriptors`, `binder_firewall` and
  the two `unrestricted_*` options in `ShizukuConfig` and load them in the constructor; keep the
  manager as the writer.

### S4. `ScopeCommandMatcher` is a text denylist (Medium)

- `S/util/ScopeCommandMatcher.kt:26-32, 41-42, 45-52, 60-73`; `S/ShizukuService.java:1476-1494`.
- The matcher finds tool names (`pm`, `am`, `appops`, `settings`, `svc`, `cmd package` ...) as
  whole words in the joined command line, and `isOpaque` refuses shells without `-c`, `eval`,
  `source`, `xargs`, `base64`, `service call`, backticks and `$(`/`${`. It does not model shell
  glob or brace expansion, so a `-c` script whose program path contains a wildcard resolves to a
  tool the regex never sees. It also only knows four tools: `content` (which can write settings
  providers), `cmd` for other services, `dumpsys`, `ime`, `wm` and `device_config` reach the same
  managers under names the rules do not list. The approach is a denylist on untrusted text and
  cannot be made complete.
- Fix: treat the four tool scopes as advisory and enforce them where the effect happens (the
  binder firewall on `IPackageManager`, `IActivityManager`, `IAppOpsService`, `ISettings...`
  already exists for `transactRemote`; extend it to the server-side proxies the shell tools use is
  not possible, so document the limit) or, for apps with any tool scope off, run `newProcess` in a
  restricted wrapper that masks the tool binaries (`PATH` with a private bin directory and no
  `/system/bin`). At minimum add the obvious missing names and glob characters to `OPAQUE`, and
  state in the settings UI that scopes are best-effort.

### S5. Local release builds fall back to the debug keystore; `signedLikeManager` trusts it (Low)

- `signing.gradle:16-57`: with no `signing.properties` and `CI != "true"`, the release
  `signingConfigs.sign` uses `CN=Android Debug`, password `android`, alias `androiddebugkey` (and
  generates one if missing). `S/ManagerFlavours.java:195-217` (`signedLikeManager`) accepts the
  other flavour when its signer set equals the running manager's. `PermissionDefiners.java:137-143`
  and `S/ShizukuService.java:243-250, 597-603` then treat it as the secondary manager (can call
  `updateFlagsForUid`, `exit`, `setPlusSetting`, receives `WRITE_SECURE_SETTINGS` / `DUMP`).
- CLAUDE.md names `:manager:assembleRelease` as the verification target; run locally without
  `SIGNING_PROPERTIES` it yields a debug-signed manager. CI refuses this (`signing.gradle:20-27`)
  and verifies the certificate hash (`.github/workflows/app.yml:329-343`), and
  `scripts/dev/build-release-local.sh` uses the real key, so shipped builds are protected.
- Note: the debug keystore is per machine, so `docs/trusted-signer-allowlist.md:183-190`
  overstates the exposure ("any debug-signed APK from any developer anywhere"); the attacker needs
  that machine's `debug.keystore`, an unprotected file with a public password (D1).
- Fix: in `signedLikeManager`, refuse the identical-signer branch when the manager's own
  certificate subject is `CN=Android Debug`; optionally fail `assembleRelease` everywhere without
  `signing.properties`. Correct the doc.

### S6. `attachApplication` accepts a claimed package name when the package list is empty (Low)

- `S/ShizukuService.java:840-853` skips the uid-to-package check when `packages.isEmpty()`;
  `:1462, :1472` use `caller.packageName`; `isAppScopeDisabled` (`:1104-1106`) uses only that
  name when given; `elevateApp` (`:3614-3620`) elevates the named package.
- During the documented early-boot package-manager gap a client can record a name with no scope
  rules, or another app's name.
- Fix: store such records as "package unverified" and re-resolve from `getPackagesForUidNoThrow`
  on the first privileged call; in `isAppScopeDisabled` always union the claimed name with the
  package list for the uid.

### S7. `elevateApp` resolves the package in user 0 (Low)

- `S/ShizukuService.java:3614`: `Android17Compat.getApplicationInfo(packageName, 0, 0)`. A
  work-profile client elevates the user-0 instance of its package. Same signer, so no cross-app
  escalation. Fix: use the caller's user id and require `ai.uid == Binder.getCallingUid()`.

### Informational (server)

- **S8** `ensureAdbTcpPersistence` (`S/ShizukuService.java:389-393, 402-410`) sets
  `adb_allowed_connection_time=0` (ADB authorisations never expire) and restarts adbd on
  `persist.adb.tcp.port` unconditionally on every start. Intentional under CLAUDE.md rule 7, but
  upstream ShizukuPlus users get a device-wide posture change with no setting and no INFO log.
- **S9** `secondaryManagerAppId` defaults to 0 until `:452` assigns it, so appId 0 counts as
  manager during construction (`:146, :597-603`). No binder is published before `:465`; initialise
  to -1 anyway.
- **S10** `getPlusSetting` (`:3709-3712`) returns any manager setting to any granted client,
  including `per_app_disabled_scopes`, `shadow_hidden_packages`, `firewall_blocked_descriptors`
  and `su_path`. Restrict to the manager or an allowlist.
- **S11** Trust-on-first-reconcile: an entry with no `signers` is given the current ones
  (`S/ShizukuConfigManager.java:568-595`, `GrantSigners.java:33-35`). One-time residual risk on
  the first run of this build; recording `grantedAt` with the entry would let a package whose
  `firstInstallTime` is later than the grant be refused even without signers.
- The OS runtime permission `af.shizuku.plus.permission.API_V23` (`protectionLevel="dangerous"`,
  `manager/src/main/AndroidManifest.xml:84-90`) counts as a full grant in
  `checkCallingPermission` (`S/ShizukuService.java:671-689`) and the start-up import
  (`S/ShizukuConfigManager.java:329-361`). This matches upstream; `PermissionDefiners` makes it
  safe against self-defined permissions, but the consent UI on that path is the system dialog.
- `api/server-shared/.../ClientManager.java:54-58`: `findClient` falls back to any record of the
  same uid, so a second process of a uid shares the first process's record (robustness only).

### Confirmed defences (server)

- `newProcess` enforces the caller permission before any command inspection or execution
  (`S/ShizukuService.java:1457`); every custom wire code enforces it (`:3009-3072`).
- Manager-only operations check identity server-side (`confirmPermission` `:2683`,
  `getFlagsForUid` `:2820`, `updateFlagsForUid` `:2842`, `exit`, `attachUserService`,
  `updatePlusFeatureEnabled`, `setPlusSetting`, `registerAIAutomationBridge`,
  `dispatchPackageChanged` `:739, 818, 1346, 1360, 3728, 3737`); the dialog intent sets a package
  (`:2658-2659`).
- Root mode no longer auto-allows every client (`:897-906`).
- `PermissionDefiners` (`:74-150`) honours a held API permission only when the definer is the
  manager (user 0, installed, name match) or the other flavour signed like it; a failed lookup is
  not cached as allow.
- `ManagerFlavours` trusts the other flavour by signature only (`:195-217`), picks the manager
  from the APK that started the server (`:93-114`), watches it for change
  (`S/ShizukuService.java:629-662`) and re-checks uid identity before granting it anything
  (`:243-250`).
- Trusted-signer path is fail-closed (`Trust` enum, `S/ShizukuConfigManager.java:686-697`), guards
  the name/certificate TOCTOU with `isInstalledUnderUid` (`:788, 848-853`), re-validates cached
  positives by package-set fingerprint (`TrustedSignerCache.java:212-219`), and the per-uid
  negative cooldown keeps one attacker uid from exhausting the 32/s budget
  (`TrustedSignerCache.java:126-129, 244-255`).
- Signer-change revocation also revokes OS runtime permissions and excludes the uid from the same
  start's import (`S/ShizukuConfigManager.java:292, 322-325, 342, 608-655`).
- Config file: 0660 group shell, `O_CREAT|O_EXCL|O_NOFOLLOW` temp file, `fstat` regular-file and
  size check, rename-based replace, directory fsync (`SafeConfigFile.java:283-346`);
  `SingleInstanceGuard` does lstat plus fstat dev/ino checks (`:153-194, 228-259`).
- Risky sub-binders are gated per transaction (`RiskyBinderGate.enforce` in
  `PrivilegedDataSourceImpl.kt:28`, `DeviceControlPlusImpl.kt:19`, `AppInspectorImpl.kt:24`,
  `BackupRestorePlusImpl.kt:26`); both developer options default off
  (`S/ShizukuService.java:1007-1014`).
- User services are refused when a scope is off (`ShizukuUserServiceManager.java:364-370`) and
  package ownership is verified against the caller (`UserServiceManager.java:50-60`).
- `transactRemote` restores the calling identity in `finally` (`Service.java:206-211`).

## 3. Server: Plus feature implementations

<!-- SERVER_IMPLS_PENDING -->

## 4. Manager: externally reachable surface

### Exported components

| Component | Type | Protection | Notes |
|---|---|---|---|
| `MainActivity`, launcher aliases, `HomeActivity`, `TvActivity`, app-management aliases | activity | none (launcher) | `HomeActivity` honours `start_service_via_wadb` (`M/home/HomeActivity.kt:885, 935`) |
| `settings.SettingsActivity` | activity | none | UI |
| `starter.ExternalStartActivity` | activity | none; action `af.shizuku.manager.action.START_SERVICE` | trampoline to non-exported `StarterActivity`, passes only `EXTRA_IS_ROOT` (M2) |
| `authorization.RequestPermissionActivity` | activity | `INTERACT_ACROSS_USERS_FULL` | consent dialog (M6) |
| `legacy.ShellRequestHandlerActivity` | activity | none; `REQUEST_BINDER` | token-checked binder hand-out, notification otherwise (M3) |
| `legacy.LegacyIsNotSupportedActivity` | activity | `af.shizuku.plus.permission.API_V23` | error dialog |
| `fleet.FleetProfileActivity` | activity | `INTERACT_ACROSS_USERS_FULL` | applies JSON profile from a path or data URI |
| `automation.locale.*EditActivity` | activity | none (Locale EDIT) | config dialogs, no side effects |
| `automation.locale.LocaleActionFireReceiver` | receiver | none; runtime `isAutomationPluginAllowed()` (default false) | start/stop |
| `automation.locale.LocaleConditionQueryReceiver` | receiver | none | returns running state |
| `widget.ShizukuWidgetProvider` | receiver | none | immutable PendingIntent to `StarterActivity`, no extras |
| `receiver.BinderRequestReceiver` | receiver | none; `REQUEST_BINDER` | hands the live binder to any callback (M7, by design) |
| `receiver.BootCompleteReceiver` | receiver | none | protected boot actions plus two vendor `QUICKBOOT_POWERON` actions any app can send |
| `receiver.ManualStartReceiver` / `ManualStopReceiver` | receiver | bearer token (`AuthenticatedReceiver`) | start/stop |
| `receiver.HeadlessStartStopReceiver` | receiver | `INTERACT_ACROSS_USERS_FULL` | HEADLESS_START/STOP/STATUS/LOG |
| `receiver.ProvisionAuthReceiver` | receiver | `INTERACT_ACROSS_USERS_FULL` | installs the automation token |
| `admin.DhizukuAdminReceiver` | receiver | `BIND_DEVICE_ADMIN` | device-admin callbacks |
| `ShizukuManagerProvider` | provider | `INTERACT_ACROSS_USERS_FULL` plus calling uid in {0, 1000, 2000} | server-to-manager binder delivery |
| `DhizukuProvider` | provider | none in manifest; runtime `isDhizukuModeEnabled()` (default false) and `AuthorizationManager.granted(uid)` | relays transactions with the manager's identity (M8) |
| `settings.SettingsSharingProvider` | provider | signature permission plus `checkSignatures` | cross-flavour settings sync |
| `service.ShizukuTileService` | service | `BIND_QUICK_SETTINGS_TILE` | M1 |
| `AICorePlusService`, `AdbPairingAccessibilityService` | service | `BIND_ACCESSIBILITY_SERVICE` | system-bound |
| `service.AdbProxyService` (not exported) | service | loopback TCP 127.0.0.1:15555, opt-in | reachable by any local app over TCP (M4) |

Application flags: `allowBackup="true"` with a custom agent; not debuggable; no cleartext traffic
flag or network security config (platform default blocks cleartext on API 28+); Sentry traces 0.0,
screenshots and view hierarchy off.

The compat stub (`compat/`, package `moe.shizuku.privileged.api`) exports a permission-less
`ForwardActivity`, `ForwardReceiver` and `ShizukuProviderProxy` that forward stock-Shizuku API
requests to the manager. The forwards carry the stub's own uid, so the manager's uid gate in
`ShizukuManagerProvider` still applies to anything that reaches it that way (fail closed).

### M1. Quick Settings tile acts on the privileged server from the lock screen (Low)

- `M/service/ShizukuTileService.kt:74-96, 104-155, 157-168, 191-224`; no `isLocked`, `isSecure`,
  `unlockAndRun` or `KeyguardManager` use in the tile or `TileOptionsActivity.kt`.
- Anyone holding a locked device can start the server (silently on rooted fleet devices with su
  auto-grant) or stop it. Stop sets `UserStopFlag`, which `BootCompleteReceiver.kt:61, 75, 101`
  and the watchdog honour until the next boot, so one tap defeats the fleet's self-healing.
- Fix: wrap Stop/Restart (and Start) in `unlockAndRun` when `isLocked` / `isSecure`.

### M2. `ExternalStartActivity` route is dead for ADB and unconfirmed for root (Low, confirmed bug)

- `M/starter/ExternalStartActivity.kt:19-23` passes only `EXTRA_IS_ROOT`;
  `M/starter/StarterActivity.kt:201-202` reads `port = 0`; `:326-329` rejects it:

  ```kotlin
  if (!root && !isSystem && port !in 1..65535) {
      log(error = IllegalArgumentException("Invalid port value: $port. ..."))
      return
  }
  ```

  The widget Start button has the same shape (`ShizukuWidgetProvider.kt:88-90`). The KDoc at
  `ExternalStartActivity.kt:11-12` ("otherwise ADB with port discovery") does not match, and no
  scenario test covers the exported route (`grep ExternalStart manager/src/test` is empty).
- Root mode: the trampoline reaches `startRoot()` (`StarterActivity.kt:484-520`) with no in-app
  confirmation, so any app on a rooted device with su auto-grant can start the server. The Locale
  plugin path requires the opt-in `allow_automation_plugin` (`LocaleActionFireReceiver.kt:23-29`);
  the two "any app may start me" doors are gated inconsistently.
- Fix: route ADB mode through the discovery flow (or treat port 0 as "probe"), gate the activity
  on `isAutomationPluginAllowed()` like the Locale receiver, and add a harness scenario
  (CLAUDE.md rule 6).

### M3. Unauthenticated broadcasts post heads-up notifications on demand (Low)

- `M/receiver/AuthenticatedReceiver.kt:54-63, 69-116` (channel `IMPORTANCE_HIGH`, default on),
  `M/legacy/ShellRequestHandlerActivity.kt:54-63`. Any app sending `${applicationId}.START`
  without a token re-posts the "Authentication Error" notification each time.
- Fix: rate-limit, post only when a (wrong) token was supplied, or drop the missing-token
  notification.

### M4. Fake-ADB proxy prompt cannot name the local app (Low, opt-in feature)

- `M/service/AdbProxyService.kt:205, 229-232` accepts any loopback client;
  `M/adb/FakeAdbClientHandler.kt:76-95` prompts via `FakeAdbPairingActivity.kt:46-53, 97-98` with
  "An app is trying to connect" and an RSA fingerprint. An accepted key gets `sh -c` through
  `Shizuku.newProcess` (`FakeAdbClientHandler.kt:160-164`) and persists indefinitely.
- Fix: resolve the peer with `ConnectivityManager.getConnectionOwnerUid()` (API 29+) and show the
  package label; deny when unresolvable; add a key list with revocation and an inactivity expiry.

### M5. Onboarding restore trusts the external Downloads copy with privileged settings (Low, API 24-29)

- `M/update/UpdateInstaller.kt:24-29, 92-99` (`isTrustedBackupLocation` trusts any hand-off
  candidate modified within 10 minutes), `M/onboarding/OnboardingActivity.kt:486-495`
  (`trustPrivileged = trusted`), `M/utils/SettingsBackupManager.kt:107-135` (`PRIVILEGED_KEYS`
  include `custom_su_path`, executed via `ProcessBuilder` in `M/utils/EnvironmentUtils.kt:135-138,
  180`).
- On Android 10 and below any app with `WRITE_EXTERNAL_STORAGE` can write that directory. The fleet
  is on Android 11+, so this affects upstream-style users only.
- Fix: below API 30 drop the external-dir candidate or never pass `trustPrivileged = true` for it.

### M6. Consent dialog lacks tapjacking protection (Low)

- `manager/src/main/res/layout/confirmation_dialog.xml:46-51` and
  `M/authorization/RequestPermissionActivity.kt:212-256` set neither
  `filterTouchesWhenObscured` nor `setHideOverlayWindows`; the app does use
  `filterTouchesWhenObscured` elsewhere (`M/management/AppViewHolder.kt:78`).
- Fix: `android:filterTouchesWhenObscured="true"` on Allow and Deny, and
  `window.setHideOverlayWindows(true)` on API 31+.

### Informational (manager)

- **M7** `BinderRequestReceiver.kt:72-113` and `ShellBinderRequestHandler.kt:70-107` hand the
  live binder to any callback; the comment at `:76-102` explains that the privilege boundary is
  the server's `enforceCallingPermission` on the real transaction uid (stock Shizuku's model).
  The token path in `ShellRequestHandlerActivity` therefore adds nothing today.
- **M8** `DhizukuProvider.kt:77-95` forwards caller-supplied binder transactions from the
  manager's uid once Dhizuku mode is on; enabling it lets every Shizuku-authorised app act with
  the manager's device-owner identity.
- **M9** Any Shizuku-authorised app can run `am` as shell and so reach HEADLESS_*,
  `PROVISION_AUTH` (replace the automation token) and `APPLY_FLEET_PROFILE` (`tcp_mode`,
  `start_on_boot`, `update_mode`, `_meta.clear_existing`), as `HeadlessStartStopReceiver.kt:21-27`
  itself notes. A grant to an app is a grant to re-provision the manager; say so in the fleet
  runbook.
- `HomeActivity.kt:885-887, 935-937` lets any app trigger an mDNS-probed ADB start, which can
  provoke one adbd "Allow debugging?" dialog per boot; the unanswered-dialog guard and the port
  rules confine it.
- The vendor `QUICKBOOT_POWERON` actions on `BootCompleteReceiver` are sendable by any app
  (`BootCompleteReceiver.kt:94-95, 103-104, 126-127`) and reach `ShizukuReceiverStarter.start`;
  same guards apply.

### Confirmed defences (manager)

- `StarterActivity` not exported; the trampoline passes nothing attacker-chosen;
  `EXTRA_USER_GESTURE` is never honoured from the alias (`UnansweredConfirmation.kt:29-32`,
  `StarterActivity.kt:201-205`).
- Legacy RSA auth only on adbd's own reported TCP port (`M/adb/AdbStarter.kt:56-63, 224`), TLS
  required elsewhere (`M/adb/AdbClient.kt:129-131`), token size enforced (`:154-156`), TOFU pin
  with explicit confirmation on change (`AdbStarter.kt:199-206`, `StarterActivity.kt:355-363`).
- `ShizukuManagerProvider.kt:25-34, 67-72` checks the kernel uid; `SettingsSharingProvider.kt:92-100`
  checks signatures.
- Automation token: `SecureRandom` (`M/utils/Token.kt`), constant-time compare
  (`AuthenticatedReceiver.kt:49-50`), format check on provisioning (`ProvisionAuthReceiver.kt:20,
  41-46`), never logged; `IntentCrypto` fails closed.
- Fleet profile: canonicalised path allowlist (`FleetProfileApplier.kt:64-76`), refuses the
  manager's own content providers (`:102-114`), never echoes file contents, key allowlist
  (`:18-28, 162-166`), `clear_existing` never touches the token (`:145-155`).
- Backups exclude the ADB key, server pin, auth token, scanner API keys, trusted SSIDs and hidden
  packages (`SettingsBackupManager.kt:18-29, 235-243, 319`); privileged settings are held back on
  untrusted imports (`:107-135, 385`).
- All 41 PendingIntents are `FLAG_IMMUTABLE` except the RemoteInput reply and the network-callback
  resume, both with explicit components.
- No `WebView`, no `java.util.Random`, no `MODE_WORLD_*`, no `Runtime.exec` on intent data.

## 5. Updater, ADB keys and crypto

### Update pipeline (verified)

1. `UpdateChecker.checkForUpdate` queries `https://api.github.com/repos/frdminc/ShizukuTendCF/releases...`
   (`M/update/UpdateChecker.kt:28-34, 121-193`), filters tags with `SAFE_TAG` (`:158`), picks the
   asset by flavour and ABI with no "first APK" fallback (`:205-218`) and compares `rNNNN`
   (`:181-184`). Fallback is the Atom feed, which yields manual download only.
2. `UpdateManager.downloadUpdate` refuses any URL that is not
   `https://github.com/frdminc/ShizukuTendCF/releases/download/<tag>/<asset>` with safe segments
   (`M/update/UpdateVerifier.kt:157-172`, enforced at `UpdateManager.kt:150-155`).
3. Download via `DownloadManager` into app-specific external storage, or `downloadDirectly`.
4. `UpdateVerifier.verifyAndStage` fetches `SHA256SUMS` from the same release over HTTPS with a
   64 KB cap and strict parsing (`:183-196, 247-273`), and hashes while copying into a private
   `cacheDir/updates/update-XXXX.apk` (`:205-240`), so there is no hash-then-swap window in
   external storage. Fail closed after one re-download (`UpdateManager.kt:420-432`).
5. `validateApk` size/magic/package-name (`:480-491`), then root `pm install -r`, Shizuku stdin
   `pm install -r`, or the system installer via a read-only FileProvider URI. No uninstall, no `-d`.
   CI publishes `SHA256SUMS` (`.github/workflows/app.yml:814-822`) and verifies the signing cert.

A compromised GitHub account could make the app download a malicious APK, but installation still
fails without the release signing key (platform same-signer rule); a network attacker cannot alter
anything. No GitHub token or user-configurable update URL exists; the update channel can be set by
a fleet profile (`FleetProfileApplier.kt:209-232`). The "TLS fallback" from commit `64420ece` is in
`AdbKey.kt:520-530` (protocol-name lookup for the ADB TLS socket, same key and trust managers), not
in the updater, and accepts no extra certificates (U8).

### U1. Fixed, shell-writable install path without symlink protection (Medium)

- `M/update/UpdateManager.kt:543-557` (root) and `:755-787` (Shizuku):

  ```
  val tmp = "/data/local/tmp/update.apk"
  Shell.cmd("cp '${file.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp")
  "cat > /data/local/tmp/update.apk && chmod 644 /data/local/tmp/update.apk && pm install -r ..."
  ```

- `/data/local/tmp` is `shell:shell 0771` and not sticky, so `fs.protected_symlinks` does not
  apply. Any app already authorised by Shizuku (shell uid) can pre-create `update.apk` as a
  symlink; the root path's `cp` / `chmod 644` then follows it and overwrites or re-modes an
  arbitrary root-owned file (shell-to-root integrity escalation on rooted devices). On the
  shell-uid path the same race lets another shell-uid app swap the file before `pm install -r`;
  the same-signer and no-downgrade checks reduce that to denial of service.
- Fix: unique name (`mktemp -p /data/local/tmp update.XXXXXX.apk`), `rm -f` first and refuse a
  symlink, or avoid the file with `pm install -S <size>` from stdin for both paths; `chmod 600`
  is enough.

### U2-U4. Updater hardening (Low)

- **U2** `downloadDirectly` (`UpdateManager.kt:219-241`) follows `Location` headers to any
  scheme or host. Only a redirect from GitHub itself could trigger it and the SHA-256 check still
  holds, but an `http://` redirect would expose the bytes. Reject non-HTTPS and off-host targets.
- **U3** `UpdateChecker.kt:527` reads the JSON body with no cap (contrast `MAX_CHECKSUMS_BYTES`
  in `UpdateVerifier.kt:159`). Cap it.
- **U4** `checkSignaturesMatch` (`UpdateManager.kt:624-632`) returns true on empty arrays or any
  exception. Authenticity is enforced by `pm` / the installer, so this only misroutes the UX;
  return false.

### U5-U7. ADB pairing and key handling (Low)

- **U5** For `_adb-tls-pairing._tcp`, `AdbMdns.kt:165-169, 192-198` accepts any site-local or
  link-local host, `AdbPairingService.kt` dials `resolvedHost`, and `PairingAdbServerTrustManager`
  (`M/adb/AdbServerPin.kt:243-278`) accepts any certificate. SPAKE2 binds the code to the TLS
  session (`AdbPairingClient.kt:296-300`) so a rogue peer gets one online guess at the six-digit
  code; the realistic effect is a confusing failed pairing. Require a loopback or own-interface
  address for pairing as the connect path does.
- **U6** `AdbKey.kt:297-304, 385-387, 405-407`: when the Keystore AES-GCM key cannot be created,
  `encrypt()` returns the plaintext and the PKCS#8 key is stored base64 in the private
  device-protected prefs under `adbkey`, with a warning log only; the
  "software keystore fallback" setting is not consulted. The key is excluded from every backup and
  export, so exposure requires root or a same-uid compromise. Surface the degraded state in
  Service Doctor and offer regeneration.
- **U7** `LoopbackAdbScan.kt:72` dials every port 32768-60999 on 127.0.0.1. The signing-oracle
  relay concern was ruled out: `allowLegacyAuth` defaults false (`AdbClient.kt:55`) and `connect()`
  throws `AdbTlsRequiredException` before signing anything if the peer does not answer `A_STLS`
  (`:129-131`); nothing is signed before TLS, and TLS client auth cannot be relayed. Residual: a
  rogue TLS listener found by the scan can be presented to the user as "adbd's key" and, if
  confirmed, pinned, after which the real adbd is refused as a changed key for the rest of the
  boot. Name the port and the discovery source in the confirm-key UI.

### R1. Reinstall copy written world-readable (Low)

- `M/utils/SettingsBackupManager.kt:330-336` writes the secret-stripped settings JSON via a
  Shizuku shell with `chmod 755` on the directory and `chmod 644` on the file in
  `/data/local/tmp/shizukuplus`. SELinux keeps untrusted apps out of `shell_data_file` on current
  targets, and `withoutSecrets` strips API keys, hidden packages and trusted SSIDs, but the copy
  still lists every enabled feature, TCP mode and automation state. The only reader is the next
  install acting as shell, so `chmod 600` on the file and `700` on the directory lose nothing.

### Informational

- **U8** TLS fallback in `AdbKey.kt:520-530` is benign (see above); effectively dead on API 30+.
- **U9** `HomeActivity.kt:891-905` still parses `EXTRA_UPDATE_FILE_PATH` from any sender, but
  `ACTION_MIGRATE_UPDATE` ends in the refusing stub `UpdateInstaller.forceUpdateWithShizuku`
  (`UpdateInstaller.kt:126-133`). Drop the extra and branch.
- `_archive/patches/{old,new}_AdbKey.kt` keep copies of the old accept-any-certificate trust
  manager; outside every source set, not compiled.

### Confirmed defences

- Sentry is opt-in by explicit consent (`HomeActivity.kt:907-928`), `isSendDefaultPii=false`, no
  user id, screenshots and view hierarchy off, breadcrumbs and messages scrubbed of IP/MAC/SSID/
  email (`TelemetryScrubber.kt`), only INFO+ lines become breadcrumbs, an empty DSN disables init
  (`ShizukuApplication.kt:137-141`). This matches `docs/PRIVACY.md`.
- Accessibility auto-fill is opt-in, scoped to Settings packages, matches only a six-digit code
  and `ip:port`, keeps the code in-process and disables itself within five minutes
  (`AdbPairingAccessibilityService.kt:39-41, 136-170`).
- `SecureRandom` wherever a nonce is needed; no hard-coded keys, IVs or salts; BouncyCastle 1.86 is
  used only for the self-signed client certificate and as a lowest-priority RSA fallback
  (`AdbKey.kt:432, 449`); no `TrustManager` or `HostnameVerifier` overrides for web traffic.

## 6. CI, supply chain and repository hygiene

### C1. Release keystore stays in the workspace for later steps (Medium)

- `.github/workflows/app.yml:186-200` writes `signing.properties` (passwords) and decodes the
  keystore to `$GITHUB_WORKSPACE/key.jks`. The job comment at `:121-122` says "the keystore
  itself is given to the one step that writes it, so other steps and third-party actions never
  see it", but both files then sit in the checkout for every later step: `gradle/actions/setup-gradle`
  (`:206`), the pre-push guard, `crowdin/github-action` (`:221-232`, third party, runs after the
  keystore is written when `HAS_CROWDIN` is true), the build itself with `--info` and a published
  build scan, and the Sentry upload. All actions are SHA-pinned, which limits this to a
  compromised pinned action or a leaked workspace file.
- Fix: write both files to `$RUNNER_TEMP` and point `SIGNING_PROPERTIES` at them (the build
  already supports that env var, `signing.gradle:4-5`); move the Crowdin fetch before the keystore
  step; delete both files in an `always()` step; correct the comment.

### C2. Crowdin translations flow straight into signed releases (Medium, dormant)

- `.github/workflows/app.yml:217-232` downloads translations into the release build with
  `continue-on-error`, and `crowdin-sync.yml` sets `auto_approve_imported: true` on upload.
  `download_translations` with `skip_untranslated_strings: false` and no approved-only filter pulls
  whatever translators submitted. `HAS_CROWDIN` is false today, so this is dormant.
- Impact once enabled: anyone with translator access can put arbitrary text, misleading
  instructions or links into a signed release's UI without a PR; malformed format strings can
  crash string formatting at runtime.
- Fix: keep translations out of the release job; take them only through the
  `download-translations` PR path with `export_only_approved: true`, and run the resource lint on
  that PR.

### C3. Workflow token permissions not pinned (Medium)

- `app.yml` and `pr-check.yml` have no top-level `permissions:` block (only the `release` job sets
  `contents: write`). Jobs that run third-party actions and Gradle plugins get whatever the
  repository default is; `actions/checkout` persists the token in `.git/config` by default.
- Fix: add `permissions: contents: read` at the top of both workflows (and `security.yml`),
  keep `contents: write` only on `release` and `dependency-submission`, and set
  `persist-credentials: false` where no push follows.

### C4. No dependency verification (Medium)

- No `gradle/verification-metadata.xml`, no `*.lockfile` anywhere; root `build.gradle` only forces
  a few versions. `settings.gradle:5, 27` and `api/settings.gradle:5, 34` list `mavenLocal()` in
  both plugin and dependency repositories, so a poisoned local Maven repository on a build machine
  wins. JitPack is correctly restricted to two groups (`settings.gradle:28-34`).
  `scripts/dev/setup-android-sdk.sh:49-71` installs a user-level init script that rewrites Maven
  Central to Google's HTTPS mirror (fine, but it is global to that user).
- Fix: enable Gradle dependency locking for the release configurations and commit
  `verification-metadata.xml` with checksums (and PGP where available); remove `mavenLocal()` or
  move it behind a property for local API development (`api.useLocal` already exists).

### C5-C7. Lower-priority CI and repository items

- **C5** `build-scan-publish: true` (`app.yml:207-210`) publishes every release build's scan,
  including `--info` console output, dependency graph and paths, to scans.gradle.com; the link
  appears in the public Actions log. Nothing secret was found in what is logged, but
  `SENTRY_DSN` is appended to `gradle.properties` (`:243`) and a build scan is one more place to
  audit. Disable for release jobs or keep scans private.
- **C6** `security.yml` pins `actions/checkout` at a v3 commit (Node 16, deprecated), Gitleaks was
  removed pending a licence, TruffleHog runs `--only-verified`, and there is no CodeQL, dependency
  review action or `.github/dependabot.yml` (alerts exist via dependency submission; update PRs
  do not). Add CodeQL (Java/Kotlin) and `dependency-review-action` on `pull_request`, and a
  Dependabot config for `github-actions` and `gradle`.
- **C7** `skills -> /data/data/com.termux/files/home/skills` is a committed symlink to an
  absolute Termux home path (dangling everywhere else). `.claude/settings.json` registers a
  `PostToolUse` hook that runs `scripts/dev/check-build.sh` (a Gradle compile) on every edit by a
  Claude session; `.agents/skills/mcp-setup/SKILL.md` tells agents to run unpinned `npx -y`
  MCP servers. These are agent-supply-chain conveniences inside the repository's own trust
  boundary; keep them, but pin the `npx` packages and drop the symlink.
- **C8** (Info) The release job deletes and recreates the tag before publishing
  (`app.yml:545-549`); versions are commit counts, so a collision needs a history rewrite.
  `scripts/github-ci-run` force-pushes an overlay commit to `ci/run/*` branches; it neutralises
  workflows that would fire on those branches, which is the right call.

### Confirmed defences (CI)

- Every action is pinned to a commit SHA; the Gradle wrapper is validated (`app.yml:203-204`).
- CI refuses to sign a release with a throwaway key (`signing.gradle:20-27`) and verifies that
  every published APK is signed by the fork's certificate
  (`18a40a45fb5919eec915049231ea5bbc6bf1ec5d7d7cd1b70409a000091ce431`, `app.yml:328-343`).
- Secrets reach scripts only through `env`, never interpolated into script text
  (`app.yml:186-200, 236-239, 312-318`); `SHA256SUMS` is published for every asset.
- `pr-check.yml` uses `pull_request`, not `pull_request_target`, so fork PRs get a read-only
  token; `issue-auto-label.yml` only string-matches the issue body.
- `scripts/dev/build-release-local.sh` reads signing material from 1Password into a `mktemp`
  directory with mode 700 that is removed on exit, and clears the service-account token.
- `.gitignore` excludes `signing.properties`, `local.properties` and the keystore; no key, token or
  credential file is tracked.

## 7. Documentation accuracy

- **D1** `docs/trusted-signer-allowlist.md:183-190` says a self-trusting design would trust "any
  debug-signed APK from any developer anywhere". The debug keystore is generated per machine
  (`signing.gradle:38-46` even creates a fresh one), so the exposure is "any APK signed with that
  build machine's debug keystore". Still a hole, but a smaller one; the doc also predates the CI
  certificate check.
- **D2** `docs/PRIVACY.md` claims (opt-in crash reporting, scrubbing, ADB key never backed up,
  secrets kept out of plain exports and the reinstall copy) were checked against the code and hold.
  Add a line that the reinstall copy is readable by anything running as shell, and that a grant to
  an app lets it re-provision the manager (M9).
- `ExternalStartActivity.kt:11-12` KDoc describes behaviour the code does not have (M2).
- `app.yml:121-122` describes keystore handling the job does not have (C1).

## 8. Hardening plan

Ordered by risk reduction per effort. Items marked (test) should start with a failing scenario
in `manager/src/test/java/af/shizuku/manager/harness/` or `server/src/test/`, per CLAUDE.md rule 6.

1. **S1** Re-validate ordinary grants at attach time by package-set fingerprint; watch granted
   packages' APK directories; wire `dispatchPackageChanged`. (test)
2. **S2** Add scope checks to transactions 10005/10006, `elevateApp` and the `setenforce` branch.
   (test)
3. **U1** Unique, non-followable temp path or stdin install for both install paths.
4. **C1, C3** Move keystore files to `$RUNNER_TEMP`, reorder the Crowdin step, add top-level
   read-only permissions, `persist-credentials: false`.
5. **S3** Persist scope, firewall and risky-API state in `ShizukuConfig`.
6. **C2** Take translations only via reviewed PRs with approved-only export.
7. **C4** Dependency locking plus `verification-metadata.xml`; drop `mavenLocal()`.
8. **M1, M6, M2** Keyguard gate on the tile; `filterTouchesWhenObscured` and
   `setHideOverlayWindows` on the consent dialog; fix or gate the external start route. (test for M2)
9. **S4** Document scopes as best-effort; add the missing tool names and glob characters to the
   opaque pattern; consider a masked `PATH` for scope-restricted apps.
10. **S5, D1** Refuse debug-signed managers in `signedLikeManager`; correct the allowlist doc.
11. **R1, U2-U6, M3-M5, S6-S7, C5-C7** as listed above.

## 9. Coverage and limits

- Reviewed in full: the server trust core and its new files, the manager manifest and every
  exported component's code, `update/`, `adb/`, `security/`, `backup/`, `fleet/`, `receiver/`,
  `starter/`, `authorization/`, `automation/locale`, the compat stub, all six workflows,
  `signing.gradle`, root and manager build files, `scripts/dev`, `pre-push-guard`,
  `common/.../TrustedSigners.java`, `docs/PRIVACY.md` and `docs/trusted-signer-allowlist.md`.
- The Plus feature implementations (`StorageProxyImpl`, `ApkPatcherImpl`, `BackupRestorePlusImpl`,
  `PrivilegedDataSourceImpl`, `VirtualMachineManagerImpl` and the rest) were reviewed for gate
  wiring and the access-control inventory in section 3; their internal logic was read for
  path handling, archive handling and process execution, not line by line for every method.
- Native code (`manager/src/main/jni`, `starter/`) was spot-checked: `starter.cpp` executes
  `app_process` with a fixed argv and runs `pm path` with constant package names only.
- The `api` submodule (upstream ShizukuPlus-API) was read only where it feeds the scoped code
  (`Service.java`, `ClientManager.java`, `UserServiceManager.java`, provider manifest).
- No dynamic testing, no device, no fuzzing; SELinux statements are from policy knowledge, not
  measured on a fleet device. Unit tests were not run as part of this review (CI's
  `spotlessCheck` was run separately and passes).
