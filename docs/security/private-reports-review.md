# Private security reports: review against the merged tree

Reviewed: 2026-11-06, against `claude/security-reports-review` at `00fe5d20`
(= `origin/ccr-467cf4aa-ysrqe9`, which carries the 2026-11-05 upstream ShizukuPlus merge
`c32c1c4b` and its compile fixes). Read-only: no source was changed for this review.

This document re-verifies every finding recorded in the fork's private and internal security
reports and notes, says whether each is fixed in the current code, and lists what the reports
missed. Line numbers below are for commit `00fe5d20` and will drift.

## 1. Sources reviewed

| # | Source | What it holds |
|---|--------|---------------|
| S1 | `_archive/notes/security_issue_notes.md` (archived 2026-07-12, upstream scratch) | one finding: `BinderRequestReceiver` exported with no caller check |
| S2 | `docs/SHIZUKUPLUS-REBASE-PLAN.md`, "Build + security review (2026-10-03)" and "Master flipped" | adversary review of the fleet surface: two High, several Medium/Low, follow-ups, operator decision 1.3 |
| S3 | `docs/SHIZUKUPLUS-REBASE-PLAN.md`, "Follow-ups 4, 5 and 7", item 3 "Auth-relay hole" and item 4 (two re-reviews, 2026-10-03/04) | `StarterActivity` relay hole; single-prompt race findings a-d and the three deferred Low items |
| S4 | `docs/trusted-signer-allowlist.md` | design of the always-allowed signer rule and its stated limitations (debug keystore, API 24-27, stored Allow) |
| S5 | `SECURITY.md`, `.github/workflows/security.yml` | policy; secret scanning (Gitleaks and GitGuardian removed, TruffleHog kept) |
| S6 | `README.md` disclosure block | public disclosure of the built-in trusted signer |
| S7 | `AI_DEVLOG.md` (upstream's devlog, merged) | security entries that touch the fleet surface: Dhizuku V2 gap, `elevateApp` grants, 2026-10-22 security round, Dependabot/BouncyCastle |
| S8 | git history (`git log --all -i --grep=secur`, plus vuln/adversar/harden) | 60+ `fix(security)` commits, used to locate the code each report refers to |

### Reports referenced but not reachable from this checkout

These are named in the sources above and were **not** read; nothing below is inferred from them.

- site-private `memory/handoffs/ShizukuTendCF/reports-2026-10-03/` (full re-review reports: codex and
  antigravity/Gemini; Titan 2 sshd diagnosis)
- site-private `memory/handoffs/ShizukuTendCF/reports-2026-10-05/` (`a2-scenario-catalogue-zcode.md`)
- `adversary-fleet-report.md`, `adversary-fleet-verdict3.md` (session scratchpad of 2026-10-03; only the
  verdict text quoted in S2 survives)
- `OPTIONS.md` item **H1 - CI signing secrets** (referenced by S4; the file is not in the repo)
- GitHub private vulnerability reports: the repo's security-advisories API returned an empty list and
  code-scanning alerts returned 403 for this session's token, so neither could be confirmed empty.

## 2. Status of every recorded finding

Severity is what the finding carries **today** (Info for fixed or accepted items). "Fix" is a
concrete change, not a wish.

| ID | Finding (source) | Status | Severity now | Where verified |
|----|------------------|--------|--------------|----------------|
| F1 | `BinderRequestReceiver` exported, hands out the server binder without checking the caller (S1) | **Not a defect** (upstream's documented client API; archive note's own fix was abandoned) | Info | `AndroidManifest.xml:505-515`, `receiver/BinderRequestReceiver.kt:32-113`, `shell/ShellBinderRequestHandler.kt:60-119` |
| F2 | `SigningInfo` is API 28+ with minSdk 24: signer lookup crashed the server on Android 7-8.1 (S2 High) | **Fixed** | Info | `TrustedSigners.java:79`, `ShizukuConfigManager.java:755,921`, `ManagerFlavours.java:86-88,182-186` |
| F3 | Signer default overrode an explicit user Deny, no kill switch (S2 High; fixed `e95a3cd8`, reversed `c42374e4`) | **By design** since 2026-10-03 (revoke and deny are ignored for the fleet key) | Accepted risk, see F16 and F17 for what it now implies | `ShizukuConfigManager.java:411-417,662-675,990-996`, `ShizukuService.java:2698-2712,2847-2855` |
| F4 | `PROVISION_AUTH` token-format validation (S2 Medium) | **Fixed** | Info | `receiver/ProvisionAuthReceiver.kt:20,41-48`; manifest `:581-589` (`INTERACT_ACROSS_USERS_FULL`) |
| F5 | `clear_existing` scoped and correctly ordered (S2 Medium) | **Fixed** (scoped to known keys; runs after parse, before per-key value validation, see F21) | Low | `fleet/FleetProfileApplier.kt:140-155` |
| F6 | Fleet profile source restriction and non-echoing errors (S2 Medium) | **Partly fixed**: source restricted, JSON errors sanitised; per-key errors still echo values (F21) | Low | `fleet/FleetProfileApplier.kt:48-51,64-86,100-116,164,181,202,219,234,244` |
| F7 | Profile-file swap window on API 24-29 (S2 remaining item 2) | **Open, documented** (fleet is API 30+) | Low | `fleet/FleetProfileApplier.kt:57-62,89` |
| F8 | Boot-retry armed only by protected `BOOT_COMPLETED`, skipped in secondary users (S2) | **Fixed** | Info | `receiver/BootCompleteReceiver.kt:126-131`, `receiver/BootRetryWorker.kt:40,93,103-109` |
| F9 | `HEADLESS_START` keeps user and already-running guards; wireless-ADB enable opt-out (S2) | **Fixed**, with a change: the receiver no longer enables wireless ADB at all, so there is no opt-out and no setting that forbids the start worker from toggling `adb_wifi_enabled` | Low | `receiver/HeadlessStartStopReceiver.kt:43-82,95-101,200`, `adb/WirelessDebugging.kt:1231-1263` |
| F10 | Headless diagnostics log in a private dir below API 30 (S2) | **Fixed** | Info | `utils/HeadlessLogger.kt:54-60` |
| F11 | CI secrets via env, not interpolation; README disclosure (S2) | **Fixed** | Info | `.github/workflows/app.yml:119-127,166-201,236-239`; `README.md:14-20` |
| F12 | "Provider-guard bypasses" closed in `ca52b44d` (S2) | **Holds**: the content-URI authority guard in `FleetProfileApplier` is unchanged. The other half of that commit (revoke sticks) was reversed the next day, see F3 | Info | `fleet/FleetProfileApplier.kt:102-114` |
| F13 | Operator decision 1.3: fleet fingerprint compiled into the public build, disclosed (S2) | **As decided**; disclosure present | Accepted risk | `TrustedSigners.java:40-43` (one digest, `35bbc3d1...ff9b6a`), `README.md:14-20` |
| F14 | Follow-ups: CI certificate pin, `IS_DEBUG` coercion, updater ABI match, updater SHA-256 (S2) | **All fixed** | Info | `app.yml:127` (boolean expression), `:325-345` (pin `18a40a45...1ce431`, every signer must match), `update/UpdateChecker.kt:174-217`, `update/UpdateVerifier.kt`, `update/UpdateManager.kt:148,401` |
| F15 | Auth-relay hole: exported `StarterActivity` with caller-chosen port (S3 item 3) | **Fixed on two layers**: not exported, upstream's `ExternalStartActivity` forwards no extras; and `AdbClient` refuses legacy RSA auth except on the port adbd itself reports (`AdbTlsRequiredException`), so a fake adbd never receives a signature | Info (see N15 for the functional regression) | `AndroidManifest.xml:347-363`, `starter/ExternalStartActivity.kt:19-23`, `adb/AdbClient.kt:129-131,152-156`, `adb/AdbStarter.kt:56-63,224`, `adb/AdbPortProber.kt:129-135`, `adb/AdbMdns.kt:181-197` |
| F16 | S4 limitation: signers recorded only on API 28+, so a grant on Android 7-8.1 is not protected | **Partly fixed**; the start-up check behaves as documented, but the runtime takeover in F17 affects every API level | Medium (folded into F17) | `ShizukuConfigManager.java:563-576`, `GrantSigners.java:33-43` |
| F17 | S4 reliability finding: constructor reconciliation drops grants on transient reads | **Fixed** for the transient-read case (unreadable keeps the entry withheld until confirmed). **New gap**: once confirmed, a stored grant is never re-checked while the server runs (see 3.2) | Medium | `ShizukuConfigManager.java:259-316,437-479,501-505` |
| F18 | S4: a stored Allow is honoured without a signer check after the digest is removed | **As documented** | Low | `ShizukuConfigManager.java:413` |
| F19 | S4: the deployed release APK was debug-signed; "trust whoever signed this build" would be a hole (H1) | **Fixed for CI** (release build refuses a throwaway key; cert pinned to `CN=djbclark Shizuku Fork`); the doc text is stale. Debug/local builds still fall back to the debug keystore by design | Info (doc update) | `signing.gradle:17-26`, `app.yml:174-182,325-345` |
| F20 | `QUICKBOOT_POWERON` and Locale `FIRE_SETTING` could raise the adbd dialog every ~150 s (S3 item 4c); check-then-act gate (4d); `AdbAuthPendingException` terminal, stood-down `finally` skipped, exported route asks, gesture only on the non-exported component (S3 re-review) | **Fixed** (marker gate, CAS in `AdbAuthWait.tryBegin`, terminal in `connectAdaptive`, `UnansweredConfirmation`) | Info; but see N4 for the fleet default of the plugin gate | `receiver/ShizukuReceiverStarter.kt:106-123`, `adb/AdbAuthWait.kt:163-173`, `adb/AdbClient.kt:196-198,254`, `adb/AdbStarter.kt:129-134,297-298,333-340,449-462`, `starter/StarterActivity.kt:153-166,339-343`, `worker/UnansweredConfirmation.kt:19-32` |
| F24 | Dhizuku V2 binder had no authorisation gate; `DhizukuProvider` exported with no permission (S7) | **Fixed** for V2 (mirrors V1's uid gate); provider stays unpermissioned because Dhizuku clients are arbitrary apps | Low | `AndroidManifest.xml:667-673`, `DhizukuProvider.kt:182-198`, `DhizukuV1Binder:19-48`, `DhizukuV2Binder:61-79,122,137` |
| F25 | `elevateApp` grants `WRITE_SECURE_SETTINGS` + `DUMP` + seven AppOps to any authorised client for its own package (S7, "by design, flagged") | **Open**: per-app API scopes do not gate it, although they gate `pm`/`appops` through `newProcess` | Medium-Low | `ShizukuService.java:3604-3621,3673-3698`; no `isAppScopeDisabled` call there |
| F26 | ADB private key kept out of settings backups (`9385a7cf`, S8) | **Fixed** and survives the merge | Info; see F27 | `backup/SettingsBackupManager.kt:18-28,90-91`, `ShizukuBackupAgent.kt:45,79-85`, `res/xml/backup_descriptor.xml:7`, `res/xml/data_extraction_rules.xml:9,13` |
| F28 | Secret scanning: Gitleaks and GitGuardian removed, TruffleHog `--only-verified` kept (S5) | **As decided**; note the workflow's `paths-ignore` skips `**.md` and `docs/**`, so a secret pasted into a doc is never scanned | Low | `.github/workflows/security.yml:6-13,27-33` |
| F29 | Hard-coded tokens or keys in the tree (checked because S2 mentions a default profile token) | **None found**: the automation token is `SecureRandom` per device and compared in constant time; `IntentCrypto` uses an AndroidKeyStore key; only the signer digest is credential-shaped | Info | `utils/Token.kt:12-22`, `ShizukuSettings.java:706-722`, `shell/ShellBinderRequestHandler.kt:48-52`, `IntentCrypto.kt:17-32` |

## 3. Findings the reports do not contain (new)

Ranked by severity. Each was verified by reading the code at `00fe5d20`.

### 3.1 N1 - Grant laundering through `grantRuntimePermission` (transaction 10005) - Medium

Any client that already holds a Shizuku grant can call `BINDER_TRANSACTION_grantRuntimePermission`
(`ShizukuService.java:3043-3057`): the only check is `enforceCallingPermission`, then
`Android17Compat.grantRuntimePermission(packageName, permissionName, userId)` for **any** package and
**any** permission. `af.shizuku.plus.permission.API_V23` is declared `dangerous`
(`AndroidManifest.xml:84-90`), so `pm grant` succeeds, and `checkCallingPermission`
(`ShizukuService.java:671-684`) treats an app that holds it as fully authorised without a dialog; the
next server start imports it as a persisted entry (`ShizukuConfigManager.java:329-361`). Unlike
`pm grant` through `newProcess` (matched by `ScopeCommandMatcher`) and `IPermissionManager` through
`transactRemote`, this transaction is not scope-gated.

Impact: one compromised or malicious authorised app silently authorises any other app. On the fleet,
the agent is always authorised, so anything that can drive the agent's binder gets this.

Fix: in transactions 10005 and 10006 refuse the three Shizuku API permissions
(`ServerConstants.PERMISSION`, `PERMISSION_LEGACY`, `PERMISSION_ORIGINAL`) outright, and gate both on
`isAppScopeDisabled(uid, pkg, "appops")`; write an activity-log line on every grant.

### 3.2 N2 - A stored grant is inherited by whoever gets the UID next, until the server restarts - Medium

Reconciliation (package set and signers) runs in the constructor and, for `unconfirmedUids`, in
`storedEntry()`. Once a uid is confirmed, `storedEntry` returns the entry with no further check
(`ShizukuConfigManager.java:437-440`). Android reuses the lowest freed appId, so after "uninstall
granted app A, install app B", B frequently receives A's uid; `ClientManager.addClient`
(`:109-112`) then marks it allowed and `attachApplication` (`ShizukuService.java:841`) only checks
that B's own name is under that uid. Any package name works. Nothing marks a uid unconfirmed on
removal: `dispatchPackageChanged` (`ShizukuService.java:3736-3749`) has no caller in the manager.
On API 24-27 the same takeover also survives a restart when the attacker reuses A's package name
(signers unreadable, entry kept: `:563-576`), which is the S4 limitation (F16) made concrete.

Fix: (1) have the manager forward `PACKAGE_REMOVED`/`PACKAGE_REPLACED`/`PACKAGE_FULLY_REMOVED` to
`dispatchPackageChanged`, which adds the uid to `unconfirmedUids` so `storedEntry` re-runs
`check(entry, livePackages, signers)` and treats `PACKAGES_CHANGED`/`SIGNER_CHANGED` as removal;
(2) run the same check on first sight of a uid in `addClient`/`attachApplication`; (3) persist each
package's `firstInstallTime` in the entry so a same-name reinstall is caught on 24-27.

### 3.3 N3 - The fleet signing key is manager-equivalent, not just always-allowed - Medium (accepted-risk escalation)

`ManagerFlavours.signedLikeManager` (`ManagerFlavours.java:151-158`) returns true for any package
signed by a `TrustedSigners` key. An APK signed with the fleet key and installed under the **other**
flavour's package name (`moe.shizuku.privileged.api` on a Plus fleet, `af.shizuku.plus.api` on a
Drop-In fleet) is treated as the manager (`ShizukuService.java:597-603,629-637`): it may call
`updateFlagsForUid` (grant anyone), `setPlusSetting`, `exit`, `registerAIAutomationBridge`, and is
handed `WRITE_SECURE_SETTINGS`, `DUMP` and the usage-stats ops (`:231-262`). S4 and the README
describe the key as "always allowed Shizuku access"; neither says it also unlocks manager-only
transactions. This is within the owner's 2026-10-03 decision, but the disclosure understates it.

Fix: either drop the `TrustedSigners` short-circuit from `signedLikeManager` (the fleet agent does
not need to be the manager), or state in `README.md` and `docs/trusted-signer-allowlist.md` that the
key is manager-equivalent. Also document the only on-device lever that exists today: per-app API
scopes do apply to trusted UIDs (`ShizukuService.java:1100` exempts only the manager) and the
manager still offers the scope dialog on locked rows (`management/AppViewHolder.kt:277-283`), so
turning off `shell` plus the tool scopes is the de-facto kill switch. Decide whether that is intended
(document it) or not (exempt trusted UIDs from scopes).

### 3.4 N4 - On the fleet, any app can stop Shizuku through the Locale plugin receiver - Medium (fleet), Low (new installs)

`LocaleActionFireReceiver` (`automation/locale/LocaleActionFireReceiver.kt:22-36`) is exported without
a permission and honours `ACTION_STOP` whenever `allow_automation_plugin` is on.
`decideSecurityDefaultsIfNeeded` (`ShizukuSettings.java:863-877`) sets that key to `existing`, which
is true for every upgraded install, so every fleet device has it on. `ShizukuReceiverStarter.stop()`
(`:174-181`) has no marker or caller check and sets `UserStopFlag`, which keeps the watchdog and
reconnect paths from reviving the server until the next boot or a manual start. The start path is
marker-guarded (F20); the stop path is not. `allow_automation_plugin` is not in
`FleetProfileApplier.knownKeys` (`:18-28`), so a fleet profile cannot turn it off.

Fix: add `allow_automation_plugin` to the fleet profile keys and ship `false` in the default profile;
or in `decideSecurityDefaultsIfNeeded` treat the fork's builds as new installs for this key; and, per
CLAUDE.md rule 6, add QUICKBOOT and Locale `FIRE_SETTING` start/stop scenarios to the one-prompt
harness (neither is named there today).

### 3.5 N5 - `elevateApp` is not scope-gated (F25) - Medium-Low

Covered in the table. Fix: refuse when `isAppScopeDisabled(uid, pkg, "appops") ||
isAppScopeDisabled(uid, pkg, "system_settings")`, remove the raw `pm grant` exec fallback, and log to
the activity log.

### 3.6 Low

- **N6 F21 - Fleet profile errors echo attacker-controlled values.** `"Unknown key: $key"` (`:164`),
  `"$key: ${e.message}"` (`:181`) with messages built from `$value` (`:202,219,234,244`) reach the toast
  (`FleetProfileActivity.kt:23`) and `<ext files>/fleet/last-apply.json` (`FleetApplyReport.kt:52`),
  whose own comment says it must never carry profile contents. `clear_existing` also runs before value
  validation, so an all-bad profile clears the covered keys and applies nothing. `mode` accepts any
  int (`:196`) and `putValue` (`:251-271`) stores whatever JSON type arrives for boolean keys. Caller is
  shell/root only. Fix: report counts or key names from the whitelist only; validate before clearing;
  type-check booleans.
- **N7 F7 - API 24-29 file swap window.** `allowedDirs()` includes `getExternalFilesDir(null)` on every
  API level. Fix: include it only on `SDK_INT >= R`, as `HeadlessLogger.init` already does, and fix the
  manifest comment (`AndroidManifest.xml:591-593`) that still advertises `/sdcard/Download/profile.json`.
- **N8 F27 - ADB private key plaintext fallback.** When AndroidKeyStore is unusable, `AdbKey`
  stores the PKCS#8 key unencrypted in the DE prefs file (`adb/AdbKey.kt:385-387,445-450`) with no
  log line and no setting. Fix: keep the key in memory only (regenerate next start) or at least write
  an activity-log entry; `KEY_SOFTWARE_KEYSTORE_FALLBACK_ENABLED` exists but `AdbKey` ignores it.
- **N9 - `StarterActivity` fallback retries on `AdbAuthTimeoutException`.** `startAdbWithFallback`
  (`starter/StarterActivity.kt:410-412`) retries any `SocketTimeoutException`, and
  `AdbAuthTimeoutException` extends it (`adb/AdbAuthWait.kt:291-293`). Only `fallbackPort == port`
  prevents a second prompt. Fix: add the type to the `throw e` list.
- **N10 - Scope rules are keyed by the client-supplied package name** (`ShizukuService.java:1105-1111`;
  `:841-853` skips the uid-to-package check when PM returns an empty list). A shared-uid sibling can
  pick a name with no scope rule. Fix: resolve the package from the uid server-side.
- **N11 - Dialog Deny is always one-time** (`authorization/RequestPermissionActivity.kt:77,198,232`
  set `IS_ONETIME=true`), so `FLAG_DENIED` is never stored from the dialog and a denied app can
  re-prompt without limit. Behavioural; decide whether "Deny" should persist.
- **N12 - Manager re-resolution by name.** After start, `getManagerApplicationInfo()`
  (`ShizukuService.java:128-131,611-621`) re-resolves the primary manager by package name with no
  signature check; the uninstall watcher exits the server (`:458-462`) but a same-named install racing
  that callback would be adopted. Fix: compare signers on re-resolution.
- **N13 F9 - No "never touch wireless debugging" setting.** The start worker turns `adb_wifi_enabled`
  on whenever a start needs it (`adb/WirelessDebugging.kt:1231-1263`). If an operator must be able to
  forbid that, add a `Keys` entry checked in `WirelessDebugging.round()` before `turnOn()`.
- **N14 F28 - Secret scanning skips docs.** Remove `**.md` and `docs/**` from the `paths-ignore` of
  `security.yml`, or run TruffleHog on the whole tree on a schedule.

### 3.7 Info

- `ExternalStartActivity` (exported, no permission) lets any app pop the starter UI; it forwards no
  extras and lands on the unanswered-prompt confirmation, not adbd's dialog.
- `ShizukuWidgetProvider` accepts `STATE_CHANGED` from any app (forced refresh only).
- `LocaleConditionQueryReceiver` tells any app whether Shizuku is running.
- `FleetApplyReport` writes `last-apply.json` to the external files dir on every API level (no
  secrets; the error strings from N6 are the only content of note).
- `dispatchPackageChanged` is dead code on the manager side; stale `ClientRecord`s are cleared only
  by binder death.
- `app.yml` declares no top-level `permissions:` block, so non-release jobs run with the repository's
  default `GITHUB_TOKEN` scope. Add `permissions: contents: read` at the top and keep `contents: write`
  on the release job.
- `StarterActivity.kt:200-206` still computes `external` by comparing the component class name to
  detect the removed alias `ExternalStarterActivity`; it is always false now. Correctness rests on
  `exported="false"` plus the trampoline forwarding nothing, which holds. Delete the dead check and
  its comment.

### 3.8 N15 - Functional regression in the public start route (not a vulnerability)

`ExternalStartActivity` and the home-screen widget launch `StarterActivity` with no port, and
`ViewModel.start()` (`starter/StarterActivity.kt:327-330`) fails with "Invalid port value: 0" before
any port discovery runs. The fork's old alias behaved the same (S3 item 3a), so this is fail-closed,
but `START_SERVICE` automation on a non-root device silently does nothing. Fix: when
`!root && !isSystem && port == 0`, resolve the port through `AdbPortProber.findActiveLoopbackPort`
or open `AdbDialogFragment()` without the gesture, or route the trampoline through
`StartWirelessAdbViewHolder.start`, which already does that.

## 4. Stale, duplicated or contradictory report text

- **The APPROVE verdict's premise no longer holds.** S2 records "Final adversary verdict: APPROVE as of
  `ca52b44d`", whose second High fix was "revoke sticks for trusted-signer apps". `c42374e4`
  (2026-10-04) reversed exactly that by owner decision. The tree is consistent with the decision, but
  anyone reading S2 alone will think the kill switch exists. Add a one-line note under the verdict.
- **`docs/trusted-signer-allowlist.md` opens with code that is no longer current.** The
  `getPackagesForUidNoThrow` / `packagesChanged` snippet (lines 11-19) describes the pre-`e95a3cd8`
  reconcile; the current code keeps unreadable entries withheld until confirmed (F17). Mark it historical.
- **The same doc's "debug keystore" section is stale.** CI now refuses a release without the real
  keystore and pins `18a40a45...1ce431` (`CN=djbclark Shizuku Fork`); the "serious security hole" text
  and the H1 reference describe the 2026-07-31 state. The warning is still right for local and debug
  builds, which keep the fallback.
- **The doc and README understate the key's reach** (N3).
- **The S3 relay text names `.starter.ExternalStarterActivity`**, which the merge replaced with upstream's
  `ExternalStartActivity`; the behaviour described still holds.
- **S1 duplicates upstream's design discussion** now embedded as the comment in
  `BinderRequestReceiver.kt:72-102`; the note's proposed fix (caller identity from broadcast extras,
  `SHIZUKU_TOKEN` in the shell loader) was abandoned for the right reason and should stay abandoned.
- **Manifest comment for `FleetProfileActivity`** advertises a path the code rejects (N7).
- **CLAUDE.md rule 6** asks for scenarios before touching the one-prompt marker; the scenarios do not
  cover QUICKBOOT or the Locale plugin, which are the two unauthenticated callers (N4).

## 5. Open findings ranked, then recommended fixes

Open, by severity:

1. **N1 Medium** - grant laundering via transaction 10005.
2. **N2 Medium** - UID reuse inherits a stored grant until restart (all API levels); same-name reinstall
   survives restart on API 24-27 (F16).
3. **N3 Medium (accepted-risk escalation)** - fleet key is manager-equivalent and undisclosed as such;
   the only on-device lever (scopes) is undocumented.
4. **N4 Medium on the fleet** - any app can stop Shizuku through `FIRE_SETTING` and suppress revival.
5. **N5 / F25 Medium-Low** - `elevateApp` outside the scope system.
6. **Low** - N6 (profile error echo and ordering), N7 (API 24-29 swap window), N8 (plaintext key
   fallback), N9 (timeout retry), N10 (scope keyed by client name), N11 (deny never persists), N12
   (manager re-resolution by name), N13 (no wireless-ADB opt-out), N14 (docs skipped by secret scan).
7. **Info / functional** - N15 and the items in 3.7.

Everything else recorded in the private reports (F2, F4, F5, F8, F10, F11, F12, F14, F15, F20, F24,
F26, F29) is fixed in `00fe5d20`; F3, F13 and F18 are accepted by owner decision.

Recommended order of work (server first, since each item there is one small guard):

1. `ShizukuService.onTransact` 10005/10006: refuse the three API permissions, gate on the `appops`
   scope, log. (N1)
2. `ShizukuConfigManager`: re-check on first sight of a uid and on package removal; wire the manager's
   package-change broadcast to `dispatchPackageChanged`; persist `firstInstallTime`. (N2, F16)
3. Decide N3: drop the trusted-key short-circuit in `ManagerFlavours.signedLikeManager`, or disclose
   it and document scopes as the kill switch.
4. `elevateApp`: scope gate, drop the exec fallback. (N5)
5. Fleet profile: add `allow_automation_plugin`, ship `false`; marker-guard or token-guard `stop()`;
   add the two harness scenarios. (N4)
6. `FleetProfileApplier`: non-echoing per-key errors, validate before `clear_existing`, API 30 gate on
   the external dir, fix the manifest comment. (N6, N7)
7. `AdbKey`: no silent plaintext fallback. (N8)
8. Doc updates listed in section 4, plus `permissions: contents: read` at the top of `app.yml` and the
   `paths-ignore` change in `security.yml`.
