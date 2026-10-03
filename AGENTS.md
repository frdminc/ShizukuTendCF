# Agent Guidelines (Claude / Gemini / Cursor / Aider / et al.)

This file is the canonical entry point for AI coding agents. It points to
the per-agent guides that already exist in this repo and lists the rules
that apply to every agent regardless of vendor.

## Per-agent guides

| Agent | File |
|-------|------|
| Claude Code | [`CLAUDE.md`](CLAUDE.md) |
| Gemini | [`GEMINI.md`](GEMINI.md) |
| Jules | [`JULES.md`](JULES.md) |

Read the guide for the agent you are. They share most rules but the
Claude file is the most fleshed-out — start there if your guide is sparse.

## Rules that apply to every agent

1. **Never reintroduce the items in `CLAUDE.md` → "Critical Crash Rules"**.
   Those each correspond to a real production crash.
2. **The build target is `:manager:assembleRelease`** for verification.
   Debug builds skip Sentry symbol upload and are fine for fast iteration.
3. **Do not edit `key.jks`, `signing.properties`, or files matching
   `secrets*`** — they are signing material.
4. **CI is GitHub Actions**, single workflow at `.github/workflows/app.yml`.
   Inspect it before assuming how a build works.
5. **Use `scripts/dev/*`** for common commands instead of re-deriving from
   `build.gradle` each session.

## Project quick-ref

- **Entry activity:** `MainActivity` (NOT `HomeActivity` — that's abstract)
- **Settings keys:** `manager/src/main/java/af/shizuku/manager/ShizukuSettings.java` inner class `Keys`
- **Preference XML:** `manager/src/main/res/xml/settings_*.xml`
- **Theme:** `Theme.Material3Expressive.*` — use M3 components, not AppCompat
- **App widgets:** `RemoteViews` only allows framework views — do NOT use
  `MaterialButton` / `MaterialSwitch` etc. inside `widget_*.xml`
## CFEngine reference book — query it, don't guess (2026-10-03)

`Learning CFEngine` (Diego Zamboni, 2nd ed.; covers CFEngine 3.12) is in the
local book knowledge base as slug `learning-cfengine` — 69 chapters, 210 code
blocks. **Before writing or reviewing CFEngine policy** — promise semantics,
`edit_line`, bundle/body syntax, class expressions, normal ordering, testing —
query it rather than relying on recall:

- `~/ops/site-private/bin/book-kb query '<regex>' learning-cfengine` — exact
  match, ~0.5–5k tokens. **This is what proves a term is or is not in the book.**
- `~/ops/site-private/bin/book-kb toc learning-cfengine` — ~2.5k-token chapter
  index; then read one chapter (~1.2k median). Add `--deep` for `file:line`
  anchors to every sub-heading.
- basic-memory `search_notes(project="books", query=…)` — semantic recall when
  you don't know the author's wording. Ranked by similarity, so a hit is **not**
  a match; confirm with the exact query above.

Never read `~/kb/raw/learning-cfengine.md`: that is the entire book, ~115k
tokens. The index exists so you don't have to. Full detail, including how to add
a book: `site-private/AGENTS.md` ("Book knowledge base").
