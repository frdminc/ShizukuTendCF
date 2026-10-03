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
