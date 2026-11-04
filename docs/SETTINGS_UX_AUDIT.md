# Settings UX audit (2026-10-23)

A pass over every settings screen (`res/xml/settings_*.xml`, ~190 rows) for where things
live, how controls behave and how they look. Ranked by how much each one confuses people.
Already done in this round: About regrouped into App info / Updates / Project info, the update
card replaced by normal rows, Help and Report a bug moved to About (`f212640b`, `cdaaf8f5`).

Moving a row keeps its key, so saved values and search keep working; only the screen it opens
on changes. Fragments that look rows up with `requireNotNull(findPreference(...))` must move
their wiring with the row (`PersonalizationSettingsFragment` does this for `translation`).

## 1. Corners: five controls for one idea (high) - DONE `02ac89c3`

Appearance > Shape & icon style has `rounded_edges_enabled`, `corner_rounding_override`,
`shape_style`, `home_card_corner_style` and `settings_card_corner_style`. They overlap: Rounded
edges off is the same as "sharp" in the other three, and the two card sliders use their own
radii (settings cards 12/24/28/36, `SettingsItemDecoration`) that sit off the M3 shape scale the
rest of the app now uses.

Proposal: one **Corner rounding** slider (Sharp, Gentle, Standard, Large, Maximum) that drives
the theme's shape scale for everything, with **Shape style** (Rounded, Squircle, Cut, Classic)
beside it. Drop the two card-specific sliders, or move them under a "Customize per surface"
row for people who really want different home and settings cards. Migrate: Rounded edges off
-> Sharp; card styles -> nearest scale step.

## 2. Names that don't match each other (high, cheap) - DONE `6a0e4434`

- "Elastic Animations" is called Expressive animations everywhere else (code, changelog,
  M3 terms). Pick one; "Expressive animations" matches the M3 name.
- "Expressive Icon Shapes" (switch, enables Shape style) and "Expressive icon shape" (picker:
  Cookie, Clover...) sit four rows apart and differ by one letter. Rename the switch to what it
  does, e.g. "Expressive container shapes", or fold it into the Shape style control.
- Experimental features has a section called "Personalization", the same name as the
  main-screen group. Rename it "Home & app identity".
- Developer options: "(Experimental)" is repeated in a section title and three row titles,
  and "↳ System Theming Bridge" fakes nesting with an arrow. Drop the suffixes (the screen is
  already Developer options) and show the child relationship with `dependency` or a sub-label.

## 3. Rows on the wrong screen (medium) - DONE `bbfb7e6d` `f637bfa5` `c985f085` `9dfa4ea8` `c37769e4` (Hide launcher icon stays in Advanced: App icon & name only shows with its experimental switch)

- Main screen: "App interactions" sits under **Personalization**, but it holds swipe and
  long-press actions plus Verified installation and Package governor, which are security.
  Done: Verified installation and Package governor were moved to **Startup & behavior > Network security**.
  App interactions gestures were streamlined into a compact expressive card (`AppInteractionsPreference`)
  directly embedded within **Personalization / Appearance**, completely removing `AppManagementSettingsFragment` as a separate subpage.
- Advanced & diagnostics > Diagnostics is a grab-bag: Hide launcher icon, Hide Backup &
  restore, Update app database, Scripting. Hide launcher icon belongs with **App icon & name**
  (stealth sits next to camouflage); Scripting with **ADB tools**; Update app database (it fetches
  the app descriptions and enhancement suggestions the Apps list shows) was moved to **About > Updates**
  alongside app release updates (`settings_about.xml`).
- Color & theme holds Bold typography, which isn't colour. Move it to a **Text & layout**
  group with One-handed mode, Expanded section headers and Edge-to-edge.
- Activity log is split: "Enable activity log" (Advanced) and "Show Activity Log" (Home
  screen, Appearance). Put both in Advanced > Diagnostics, or make the Home switch depend on
  the first so it can't be on while logging is off.

## 4. Writing style (medium, blocked on translations) - DEFERRED

64 of ~190 titles use Title Case ("Custom Accent Color", "Local ADB Proxy Server", "Reset ADB
Keys") next to sentence case ("Start on boot"). M3 and Android use sentence case. Product
names stay capitalised (Service Doctor, Binder Firewall, Shadow Binder). This is the ~185-string
pass already planned; it waits until translations are refreshed, because each changed English
string goes stale in every locale.

## 5. Controls (low) - accent/shape pickers DONE `1bce2d01`; long-press info row open

- App interactions uses an empty-title info row (`lp_category_info`) as a label above the
  long-press switches; the rest of Settings uses section labels. Make long-press its own
  section.
- `custom_accent` is a slider over named colours; a row of colour swatches shows the choice
  instead of naming it (fits with the 1.5 accent regeneration).

## Suggested order

1. Names (section 2): string-only, one commit, low risk. Needs translations for renamed keys.
2. Corner consolidation (section 1): one migration, removes two sliders.
3. Row moves (section 3): one commit per move, each with its fragment wiring.
4. Sentence case (section 4) after the translation refresh.
