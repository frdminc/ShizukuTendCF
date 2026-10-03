# ShizukuTendCF: rebase onto ShizukuPlus — findings & plan

Date: 2026-10-03. Source reports in same dir: `A-shizukuplus-features.md` (agy/Gemini),
`B-our-delta.md` (codex), `C-api-research.md` (cursor). All claims below cross-checked
against the live trees with `git ls-tree`/`git show`.

## The decisive finding: shared thedjchi lineage

Our fork `frdminc/Shizuku` and `thejaustin/ShizukuPlus` are **both forks of
`thedjchi/Shizuku`** (our versionName is `13.7.0-thedjchi+stayturgid-releaseN`).
ShizukuPlus rewrote its git history (its "REWRITE:" commits), so the raw
`git merge-base` with us lands back in 2017 — but *by content* they share the whole
thedjchi feature set. ShizukuPlus then renamed the package `moe.shizuku.manager` →
`af.shizuku.manager` (manager keeps applicationId `moe.shizuku.privileged.api` for
Shizuku-client compatibility; its Plus API uses `af.shizuku.plus.api`).

Consequence: **most of "our" 20 features are inherited thedjchi features that
ShizukuPlus already has, usually in a newer form.** The genuine delta to port is only
the stayturgid/fleet-specific additions.

## Overlap matrix (verified against shizukuplus/master tree)

| Our feature (codex id) | In ShizukuPlus? | Action |
|---|---|---|
| F01 startup state machine / TCP | yes (their own) | use theirs |
| F02 boot start | yes, but **BootRetryWorker ABSENT** | port indefinite-retry worker |
| F03 watchdog | yes (WatchdogService) | use theirs |
| F04 automation broadcasts (AuthenticatedReceiver/Token) | yes | use theirs |
| F05 **headless start/stop/status + HeadlessLogger** | **ABSENT** | **PORT** |
| F06 **fleet JSON profiles + ProvisionAuth** | **ABSENT** | **PORT** |
| F07 **trusted-signer allowlist** (ShizukuConfigManager has no signer logic) | **ABSENT** | **PORT** (insert into their ConfigManager) |
| F08 TV/VR pairing | yes (AdbPairingAccessibilityService) | use theirs |
| F09 MediaTek / Android compat | yes (more extensive: Android17Compat) | use theirs |
| F10 stealth APK clone (StealthTutorialActivity) | ABSENT (they have server-side ApkSigner, different design) | skip (thedjchi-origin; their choice) |
| F11 in-app updater (UpdateHelper) | ABSENT | skip unless wanted; hardcoded to thedjchi URL, needs rework anyway |
| F12 bulk auth (ToggleAllViewHolder) | yes | use theirs |
| F13 Material 3 UI | yes (their own, newer) | use theirs |
| F14 **protocol-vs-build status line** | ABSENT (their ServerStatusViewHolder lacks it) | **PORT** (small) |
| F15 Fire OS notification icon | **already present** (aapt2-resources.cfg + ic_system_icon.xml) | no port needed |
| F16 translations | yes (their own) | use theirs; forward-port any stayturgid strings for ported features |
| F17 AGP 9.2.1 build | theirs is AGP **9.4.0** (newer) | use theirs |
| F18 CI signing / fork versioning | partial (they have idempotent releases + Obtainium versioning; but keep a debug-keystore fallback and no fork-id APK name) | adopt theirs, optionally re-add CI-secret enforcement + ShizukuTendCF APK naming |
| F19/F20 docs + repo config | n/a | rewrite for ShizukuTendCF |

**Net port list: F05 headless, F06 fleet profiles/provision-auth, F07 signer allowlist,
F02 BootRetryWorker, F14 status line.** Optionally F11 updater and F18 CI hardening.
Do NOT port the 12 reverted experiments (X01–X12 in B-our-delta.md).

## API submodule (report C)

- `frdminc/Shizuku-API` = identical to `thedjchi/Shizuku-API` (33★); +11 commits over
  upstream RikkaApps (AGP9 build modernization + MediaTek/A16/rish fixes). 0 behind.
- RikkaApps/Shizuku-API is **abandoned since May 2025**.
- `thejaustin/ShizukuPlus-API` (80★, pushed today) is a superset: it **already contains
  our runtime fixes** (MediaTek fallback, UserHandleCompat, Android17 compat) plus the
  Plus AIDL suite, and uses the `af.shizuku.*` namespace the ShizukuPlus app expects.
- **Recommendation: if we adopt ShizukuPlus, switch the api submodule to
  `thejaustin/ShizukuPlus-API`** (report C option c). Our moe.shizuku API fork cannot
  serve the af.shizuku app. Only unique thing we'd lose is the AGP9 build + rish
  c++_static choice — forward-portable later if wanted.
  Caveat to verify first: ShizukuPlus-API's `server-shared/build.gradle` references a
  `:common` module not listed in its `settings.gradle` — confirm it builds standalone
  before pinning.

## Risks of adopting ShizukuPlus (report A)

- **AI-regression history (high):** its AI_DEVLOG records broken-compile / broken-submodule
  / dead-code regressions pushed straight to `master`. Pin to a **known-good tag/commit**,
  don't track their master blindly.
- Their CI keeps a debug-keystore fallback; their APK is just `shizukuplus.apk`.
- Licensing: Apache-2.0/MIT retained; our upstreaming to RikkaApps gets harder.

## Proposed execution plan

1. Rename to **ShizukuTendCF**: local dir `~/src/Shizuku` → `~/src/ShizukuTendCF`; GitHub
   `frdminc/Shizuku` → `frdminc/ShizukuTendCF` (GitHub keeps redirects); update doc
   references. API repo rename deferred — likely moot (we'd adopt ShizukuPlus-API).
2. Back up current master as `legacy/pre-shizukuplus` (branch + push) so nothing is lost.
3. New branch `shizukuplus-base` from a **pinned known-good** shizukuplus commit (not a
   moving master). Switch api submodule to ShizukuPlus-API at a matching pin.
4. Port the net delta (F05, F06, F07, F02, F14) as clean, individually-reviewable commits,
   adapting `moe.shizuku.manager` → `af.shizuku.manager` package paths. Re-add stayturgid
   strings/docs. Rebrand app/CI to ShizukuTendCF.
5. Build + adversary security review (fleet receivers = attack surface) before any force-push.
6. Flip `master` to the new base (force-push) only after the operator okays, with
   `legacy/pre-shizukuplus` retained.

## Genuine operator decisions (everything else I can proceed on)

D1. Strategy: port-the-delta onto a fresh ShizukuPlus base (only feasible option; literal
    `git rebase` across the 805-file/133k-line divergence is not viable). → recommended yes.
D2. Push plan: new branch + backup first, flip master later (safe) — vs force-replace now.
D3. API submodule: adopt ShizukuPlus-API (recommended) vs keep frdminc/Shizuku-API.
D4. Optional ports: in-app updater (F11) and CI-secret/APK-naming hardening (F18)?
D5. Pin ShizukuPlus to a reviewed commit (recommended) vs track their master.

---

## Operator decisions (confirmed 2026-10-03)

- Push plan: **safe** — backup + branch, operator flips `master` later.
- API submodule: **adopt ShizukuPlus-API**.
- Base: **track ShizukuPlus master** (not a pinned commit).
- Optional ports: **yes to both** in-app updater (F11) and CI hardening + APK naming (F18).

## Progress (2026-10-03) — STRUCTURAL PHASE COMPLETE, paused before feature port

Done and pushed to `frdminc/ShizukuTendCF`:
1. GitHub repo renamed `frdminc/Shizuku` → `frdminc/ShizukuTendCF`; local remote updated.
2. Local dir renamed `~/src/Shizuku` → `~/src/ShizukuTendCF`.
3. `legacy/pre-shizukuplus` = exact backup of old master (`a7178c9e`, incl. PR #23). Verified == master server-side.
4. `shizukuplus-base` branch created from `shizukuplus/master` (`9ceb9f49`); api submodule switched to
   `thejaustin/ShizukuPlus-API` (`5c8cccd7`). Pushed.
5. `.gitignore` hardened on the new base: `*.jks`, `*.keystore`, `/graft/`, `.ignore`, `.ralph-tui/`
   (the release keystore `shizuku-djbclark-release.jks` was NOT ignored on ShizukuPlus's gitignore — fixed before any add).
6. `master` is UNTOUCHED (still old fork). Operator flips it to `shizukuplus-base` after review.

## NEXT (feature-porting phase) — all on branch `shizukuplus-base`

Target package namespace is `af.shizuku.manager` (not `moe.shizuku.manager`); adapt package/import
paths and manifest wiring per feature. Source each file from `git show legacy/pre-shizukuplus:<path>`.

Ports, smallest/most-isolated first:
1. **F14** protocol-vs-build status line → their `manager/.../home/ServerStatusViewHolder.kt` + strings. (small)
2. **F07** trusted-signer allowlist → insert into their `server/.../ShizukuConfigManager.java`
   (their copy has no signer logic). Security-relevant: adversary review. Keep `docs/trusted-signer-allowlist.md`.
3. **F05** headless receiver + HeadlessLogger → new `HeadlessStartStopReceiver.kt`, `HeadlessLogger.kt`;
   wire into their `ShizukuReceiverStarter.kt` + manifest (perm INTERACT_ACROSS_USERS_FULL). Security-relevant.
   NOTE: our HeadlessLogger.init() was never called in the old fork (log_path="unavailable") — fix on port.
2. **F06** fleet JSON profiles + ProvisionAuth → `fleet/FleetProfileActivity.kt`, `FleetProfileApplier.kt`,
   `ProvisionAuthReceiver.kt`, `assets/fleet_profile_default.json`; adapt setters to their ShizukuSettings.
   Security-relevant (exported activity, no perm gate in old fork — reconsider). Adversary review.
5. **F02** BootRetryWorker (indefinite retry) → integrate with THEIR boot flow (they already have
   BootCompleteReceiver + NotifAttempt/Cancel/Restore + WatchdogAlarmReceiver — reconcile, don't duplicate).
6. **F11** in-app updater (UpdateHelper) → ShizukuPlus lacks it; rework release URL to ShizukuTendCF.
7. **F18** CI: re-add CI-secret enforcement (drop debug-keystore fallback) + ShizukuTendCF APK naming on their workflow.

Then: rebrand strings/docs to ShizukuTendCF, build, adversary security review of the fleet surface,
then operator flips `master`.

Do NOT port the 12 reverted experiments (X01–X12 above). Fire OS (F15) already present on base.

## Consistent TCP port (5555) — verified already on base, 2026-10-03

The operator's "consistent port number" feature (fixed/configurable ADB TCP port, default 5555)
is **already present in the ShizukuPlus base** and need not be ported. It is a shared thedjchi-origin
feature: base `manager/.../ShizukuSettings.java` has `KEY_TCP_PORT = "tcp_port"` with `getTcpPort()`
defaulting to `5555` — **byte-identical to our fork's key and default** — and fully localized settings
strings (`settings_tcp_port`, `settings_tcp_port_default` = "Default (5555)", `settings_tcp_port_hint`).
ShizukuPlus also *extends* it beyond ours: an ADB-proxy service on port **15555**
(`manager/.../service/AdbProxyService.kt`, `settings_adb_proxy_summary`) and an on-device ADB-TCP-mode
option (`settings_on_device_adb_tcp_summary`). Keep theirs.

Implication for the F06 fleet-profile port: the profile's `tcp_port` field maps to the **same**
`KEY_TCP_PORT = "tcp_port"` preference on the base, so `FleetProfileApplier` will drive the fixed port
correctly with no key translation. Just confirm the setter path matches their `ShizukuSettings` API.

## Progress (2026-10-03, later) — FEATURE PORT COMPLETE on `shizukuplus-base`

Commits on top of ShizukuPlus master `9ceb9f49` (all path-staged, diffs reviewed by the lead):
1. `f1101531` F14 protocol-vs-build status line (core/ui + manager strings, ServerStatusViewHolder).
2. `416a18e8` F02 BootRetryWorker + BootCompleteReceiver scheduling + cancel in setStartOnBoot(false).
3. `2fe6d627` F11 — decision: **use the base's existing updater** (`update/UpdateChecker.kt`) retargeted to
   frdminc/ShizukuTendCF instead of shipping a second updater. The ported standalone `UpdateHelper.kt`
   (digest-verified) is parked in the session scratchpad as `S3-UpdateHelper.kt.reference`; follow-up idea:
   add SHA-256 digest verification to the base updater.
4. `b2340dce` F18 CI: fail-fast on missing signing secrets, unsigned-release guard, `ShizukuTendCF-<ver>-<abi>.apk`.
5. `9dcfb2a3` F07 trusted-signer allowlist in ShizukuConfigManager + docs/trusted-signer-allowlist.md.
6. `05ac5adb` F05 HeadlessStartStopReceiver + HeadlessLogger (now initialised from ShizukuApplication);
   START delegates to the base's ShizukuReceiverStarter/AdbStartWorker, persisting ADB mode on fresh install.
7. `4572ab96` F06 FleetProfileActivity/Applier + ProvisionAuthReceiver + default profile asset + setAuthToken.
   **Hardened vs the old fork:** both gated by INTERACT_ACROSS_USERS_FULL (old activity was ungated and an
   open-with handler for all JSON; old receiver trusted Binder.getCallingUid() in onReceive). tcp_port validated.
8. `117c46a8` rebrand: versionName "ShizukuTendCF …", bug-report/About/releases links, README banner.
   Deliberately NOT changed: `app_name` label ("Shizuku+"), applicationId, ShizukuPlus wiki help links,
   their apps.json compat feed. Operator call on the label.

Vendor batch that produced F14/F02/F18 drafts: cursor (S1), agy (S2, S4), zcode (S3). Reports in scratchpad.

Remaining before flipping `master`: local compile result, adversary review findings (in flight), then
operator flips master → shizukuplus-base (legacy/pre-shizukuplus keeps the old history).

## Build + security review (2026-10-03)

- **Compile:** `:server:compileReleaseJavaWithJavac`, `:manager:compileShizukuplusDebugKotlin` and
  `…JavaWithJavac` all pass. Needed `7f4ce197`: ShizukuPlus master's own `OverlayManagerPlusImpl` was
  ahead of its pinned API submodule (`setActiveThemePackage` missing) — their tree was broken as
  checked out; api bumped to ShizukuPlus-API master `3f2ae3c`. Expect this class of breakage again
  while tracking their master; check the gitlink vs their code on every sync.
- **Adversary review** (`adversary-fleet-report.md` in the session scratchpad): initial verdict BLOCK on
  two High findings in the trusted-signer port — SigningInfo is API 28+ with minSdk 24 (server crash
  on Android 7–8), and the signer default overrode an explicit user DENY (no kill switch). Fixed in
  `e95a3cd8` together with the Medium/Low items: token-format validation for PROVISION_AUTH, scoped +
  correctly-ordered `clear_existing`, profile-source restriction and non-echoing errors, boot-retry
  armed only by the protected BOOT_COMPLETED and skipped in secondary users, HEADLESS_START keeps the
  base's user/already-running guards (wireless-ADB enable opt-out), private log dir below API 30, CI
  secrets via env, README disclosure. Second pass found revoke still did not stick (the manager's revoke
  stores an entry with neither flag); fixed in `ca52b44d` by applying the signer default only when no
  entry exists, plus provider-guard hardening. **Final adversary verdict: APPROVE as of `ca52b44d`.**
- **Open operator decision (review 1.3):** the stayturgid trusted-signer fingerprint is compiled into a
  publicly released build, so every installer of the public APK grants that key Shizuku access by
  default (revocable per-device now, after the fix). Options: (a) keep, disclosed (current state);
  (b) empty by default, supplied at build time via a `fleet` flavour / BuildConfig field;
  (c) runtime list from a shell-owned file the server reads (e.g. the existing
  `/data/user_de/0/com.android.shell/shizuku.json`), provisioned by `adb`.
- **Follow-ups (not blocking):** pin the release cert in CI with `apksigner verify --print-certs`;
  base's `IS_DEBUG` string/boolean coercion may make a "debug" dispatch publish a release (fails
  closed on signing); updater asset picker should match `ShizukuTendCF-<ver>-` + device ABI; add
  SHA-256 digest verification to the base updater (ported `UpdateHelper` reference in scratchpad);
  `app_name` label still "Shizuku+". Scripts must pass `-p af.shizuku.plus.api` (Drop-In flavour: `moe.shizuku.privileged.api`) to the
  headless broadcasts on API 26+.

## Ready to flip master

`shizukuplus-base` compiles and carries an APPROVE from the adversary review. To make it `master`
(history replacement, not a merge — the two histories are unrelated):

    git fetch origin
    git push origin shizukuplus-base:master --force-with-lease=master:a7178c9e4f6c860fd8d9db06dce4e4d99ea71122

`legacy/pre-shizukuplus` (= old master `a7178c9e`) stays as the rollback point. Existing PR branches
on the old history will no longer merge cleanly and should be re-raised against the new base.

Adversary text for a PR body, verbatim from `adversary-fleet-verdict3.md`:

> Adversary security review: **APPROVE**, as of `ca52b44d`. Earlier blocking findings were fixed: the
> trusted-signer lookup crashed the server on API 24–27, and trusted-signer apps could not be revoked.
> Remaining non-blocking items: (1) the trusted-signer list is built in and disclosed in the README; a
> fleet-only build or on-device opt-in is recommended; (2) a profile-file swap window remains on API
> 24–29; (3) CI hardening follow-ups: certificate pinning, the IS_DEBUG coercion, env-based secrets in
> the remaining steps; (4) update-checker ABI matching.

## Master flipped; operator decisions closed (2026-10-03, later)

- **`master` → `a4dbb58d`** (was `a7178c9e`), pushed with `--force-with-lease`; `legacy/pre-shizukuplus`
  is the rollback point. `shizukuplus-base` is now just an alias of master and can be deleted once nothing
  references it.
- **Review 1.3 decision: (a) keep the trusted-signer fingerprint built in, disclosed in the README.**
  Revisit (b, a fleet-only flavour) only if release APKs are ever published for people outside the fleet.
- **App label** renamed `Shizuku+` → `ShizukuTendCF` (flavour `resValue` in `manager/build.gradle` plus
  the `core/ui` fallbacks). The Drop-In flavour keeps the label `Shizuku` on purpose, since it occupies the
  stock package name.
- **`frdminc/Shizuku-API` archived on GitHub** (superseded by the thejaustin/ShizukuPlus-API submodule).
  No local checkout existed under `~/src`, so there was nothing to register with `~/src/justfile`.

## Queued follow-ups (operator said "queue all", 2026-10-03)

All are in base-inherited code; none blocks a release. Order is by value.

1. **Done 2026-10-03. CI: pin the release certificate.** After signing, run `apksigner verify --print-certs` and fail the
   job unless the SHA-256 equals the fork's own release certificate, `18a40a45…1ce431`
   (`CN=djbclark Shizuku Fork`). Catches a swapped keystore secret before a build is published.
   (Corrected 2026-10-03: this used to name `TRUSTED_SIGNER_SHA256`, which is the *agent's* certificate,
   not the key that signs these APKs.)
2. **Done 2026-10-03 (also fixed the same comparison on the `prerelease` input). CI: `IS_DEBUG` coercion.** `app.yml` compares a string input to a boolean in places; a "debug"
   workflow_dispatch can take the release path (fails closed on signing today, but fix the comparison).
3. **Done 2026-10-03. CI: env-based secrets in the remaining steps** (`Create signing.properties`, `sign_apk`) — same
   pattern as the Validate step, so secrets never appear in a shell-interpolated command line.
4. **Done 2026-10-03 (see "Follow-ups 4, 5 and 7" below).** **Updater: ABI + name matching.** `UpdateChecker` should pick the asset named
   `ShizukuTendCF-<ver>-<abi>.apk` for the device's primary ABI, falling back to the universal APK.
5. **Done 2026-10-03 (see "Follow-ups 4, 5 and 7" below).** **Updater: SHA-256 digest verification** before install, from a digest the release publishes
   (`UpdateHelper.kt.reference` in the session scratchpad has the ported check to lift from).
6. **Fleet profile swap window on API 24–29.** On Android 7–10 the app's external files dir is writable
   by apps holding `WRITE_EXTERNAL_STORAGE`; either accept the profile inline (`--es profile_json`) below
   API 30 or document the limitation. Moot if every fleet device is Android 11+.
7. **Done 2026-10-03 (see "Follow-ups 4, 5 and 7" below).** **Headless ADB start: one authorisation prompt, not a stack of them** (operator request, 2026-10-03).
   On a fresh install the manager's ADB key is unknown to adbd. While the "Allow USB debugging?" dialog
   is unanswered, the start worker keeps reconnecting and every attempt queues another dialog: about ten
   had to be accepted on the Titan 2. Make the start hold one connection open while authorisation is
   pending (or back off until it is answered), and say in the notification that a prompt is waiting.
   Related: a prompt raised while the phone is locked is lost and adbd then reports "prompt currently
   pending, skipping" until adbd restarts; and restarting adbd kills a server that was started from an
   adb shell. Procedure until fixed: unlock the phone first, trigger `HEADLESS_START` once, tick
   "Always allow".

## Follow-ups 4, 5 and 7: landed, reviewed, not yet device-tested (2026-10-03)

Both changes went through an adversary review (verdict BLOCK), fixes, and a second review (PASS).
Reports were in the session scratchpad and are summarised here because that is volatile.

1. **Updater (follow-ups 4 and 5).** Asset chosen by flavour and ABI with no "first APK" fallback;
   `SHA256SUMS` published by the release job and required by the app (fails closed).
   a. A build with this change cannot update to a release that has no `SHA256SUMS`.
   b. Removed from upstream behaviour: the uninstall-and-reinstall "force update" fallback (it
      installed an APK from any signer with no prompt) and `pm install -d`.
   c. Release tags must match `^v?[0-9][0-9A-Za-z._-]{0,63}$`; previously the tag reached a root
      shell command unescaped.
   d. Not tested: the root silent-install path (no rooted device in the fleet), and the release job's
      `SHA256SUMS` step (runs only on a published release).
2. **Single ADB authorisation prompt (follow-up 7).** One connection is held for up to 150 s after
   the key is offered; other start paths stand down; nothing retries after a timeout or rejection;
   the boot retry loop stops after one unanswered dialog per boot. The waiting text shows the app's
   key fingerprint (MD5, the form the system dialog shows).
   a. **Device result, SM-S921U1, Drop-In r2772, 2026-10-03:** after "Revoke USB debugging
      authorisations", one `HEADLESS_START` produced exactly one `adbd_auth: sending prompt` (key
      `shizuku+`); two more `HEADLESS_START` broadcasts during the wait logged "Start already in
      progress" and opened no connection; after the operator accepted, the server was RUNNING and the
      agent logged `UserService connected`. In-place update from r2759 and the authorised stop/start
      path also pass. Not exercised: the 150 s timeout path, "Attempt now" during the wait, a rejected
      prompt, the boot path.
      Pixel 7a and Titan 2 were then updated in place to r2772 with no prompts; stop/start and the
      agent's `UserService connected` pass on both. All three fleet devices run Drop-In r2772.
   b. The Mac's own adb client raised 4 prompts for the Mac's key after the revoke: `adb` retries an
      unauthorised TCP device by itself. That is the host adb client, not this app.
   c. Original test plan: fresh key, `HEADLESS_START`, expect exactly one
      dialog and `adb logcat -s adbd:*` showing one "sending prompt"; repeat the broadcast and tap
      "Attempt now" while waiting; accept; then the 150 s timeout path; then the authorised fast path.
   d. Unverified premise: that adbd dismisses its dialog when the offering connection closes.
3. **Auth-relay hole in the base, fixed (upstream candidate, report privately).** `StarterActivity`
   was exported and took a caller-chosen port. With adbd on plain TCP and this app's key authorised
   (the fleet setup), a local app could run a fake adbd on its own port, have this app sign the real
   adbd's challenge, and get an adb shell with no dialog. Now: the activity is not exported, other
   apps reach it only through the alias `.starter.ExternalStarterActivity`, and the port extra is
   ignored for the alias. `AdbPortProber.findActiveLoopbackPort` uses only the port adbd reports
   when it reports one.
   a. Consequence: an external `START_SERVICE` launch can no longer start over ADB (it shows
      "Invalid port value: 0"); root and system starts still work. It never carried a usable port
      without the extra, so this mostly formalises existing behaviour.
4. **Left open from the reviews (all Low).**
   a. Buttons pressed while a dialog is pending ("Attempt now", tile, Home) do nothing silently.
   b. A start that stands down returns as if it had succeeded, then its caller's 20 s wait for the
      service can time out while the other start is still waiting.
   c. `QUICKBOOT_POWERON` and the Locale FIRE receiver are reachable by any app and ignore the
      unanswered marker, so an app can raise this app's own dialog about every 150 s.
   d. The gate is check-then-act, not a mutex.
5. **CI certificate pin.** The runner's apksigner labels the line `V3.0 Signer: certificate SHA-256
   digest`; the check matches on the field name. Two runs failed on correctly signed builds before
   that was right.

## End-of-rebase upstreaming pass (operator instruction, 2026-10-03)

Do this last, once the new build works on all three fleet devices (SM-S921U1, Pixel 7a, Titan 2).

1. **Find, file and write PRs for everything generally useful** to thejaustin/ShizukuPlus and
   thejaustin/ShizukuPlus-API: bug fixes found while getting the fleet working (transaction-code
   handling in `Service.onTransact`, server-side exception logging, the CI Sentry `-x` failure without
   a token, anything else from the device debugging log below).
2. **Generalise what is fleet-specific in code but useful in principle**, so it can be offered upstream
   rather than carried as a private delta (for example a configurable trusted-signer list instead of a
   built-in fingerprint, headless start/stop/status receivers, fleet profiles).
3. **Review gate:** any PR code that has not already been checked by at least two other agents gets
   that review before the PR is opened.
4. Fixes to code that lives in the `api` submodule are carried in our own server class until then, so
   the submodule keeps tracking upstream.

## Final step: updated general rebase prompt (operator instruction, 2026-10-03)

After the upstreaming pass, copy an updated "rebase a fork onto ShizukuPlus" prompt to the clipboard.

1. **General, no placeholders.** The prompt must ask the user for whatever it needs (fork location,
   upstream, branch names, signing, devices) instead of carrying fill-in blanks.
2. **Discover the user's own delta.** Unlike the current prompt, which lists our specific features, it
   must search the fork for the user's own features and bug fixes, list them, and ask the user whether
   it found them all.
3. **Fold in what this rebase taught us** (api gitlink drift, CI without a Sentry token, on-device
   verification of server-side behaviour, signer and versionCode checks before a fleet rollout).
4. **End with the upstreaming step:** prompt the user to file PRs and issues against upstream the way
   we do it in the section above, including the two-other-agents review gate.

Starting point: `docs/rebase-on-shizukuplus-prompt.md` (the prompt as written on 2026-10-03).

## On-device findings (SM-S921U1, 2026-10-03)

1. **Fleet layout is the Drop-In flavour.** The stayturgid agent is hard-wired to
   `moe.shizuku.privileged.api`, and the server treats whatever is installed under that name as its
   manager. The standard flavour side by side with the old fork cannot work: two servers fight and user
   services are handed to the wrong one.
2. **Migration is uninstall then install.** The old fork (release46) is debug-signed with versionCode
   51407; the new builds are release-signed with versionCode = commit count. Grants survive, because
   `shizuku.json` lives in the shell user's directory.
3. **One tap per device after install.** The manager's ADB key is per-install, so `HEADLESS_START`
   stalls on the "Allow USB debugging?" prompt until it is accepted once. Until then start the server
   with the bundled `libshizuku.so` from an adb shell. If adbd logs "prompt currently pending, skipping"
   with no dialog on screen, `adb tcpip 5555` restarts adbd and the prompt comes back.
4. **Transaction renumbering bug (fixed in `737b8ae5`).** The base's `LegacyShizukuBinderProxy`
   subtracted 1 from every transaction code for all stock-API apps, so `addUserService` ran as
   `setSystemProperty`. Found with the server-side exception logging added in `5f361ad4`.
5. **Trust anchor was stale.** `TRUSTED_SIGNER_SHA256` named `6651cb15…be293e`, which matches no known
   keystore; the agent's release key and every deployed agent is `35bbc3d1…ff9b6a`. Replaced on the
   operator's instruction.
6. **`am broadcast` to a freshly installed app** needs `--include-stopped-packages -n <component>`.

## Carry into the next handoff (operator instruction, 2026-10-03)

1. **Titan 2: the stayturgid agent reports `sshd=down`** (SM-S921U1 and Pixel 7a report `sshd=up`).
   Seen in the agent's comonitor line after the Drop-In rollout; not investigated, and not known to be
   related to it.
2. **Restarting adbd kills a Shizuku server that was started from an adb shell.** `adb tcpip 5555` on the
   Pixel 7a and Titan 2 left both without Shizuku for about three minutes until the server was started
   again. A server started through `HEADLESS_START` should be checked for the same behaviour.
3. **GitGuardian: dropped permanently** (operator decision, 2026-10-03). The workflow
   (`.github/workflows/gitguardian.yaml` on the base) was removed on 2026-10-03 because the repo has
   no `GITGUARDIAN_API_KEY` secret and it failed on every push; the operator later decided not to
   restore it — TruffleHog (`.github/workflows/security.yml`) covers secret scanning. The Gitleaks
   job was removed from `security.yml` the same day for the same reason (needs a `GITLEAKS_LICENSE`
   org secret); restore it from git history if a license is ever added.
