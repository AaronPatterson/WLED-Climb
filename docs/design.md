# WLED Climb App — Getting Started Plan

2026-09-19 · @Someone

## Working with Claude on this project

Since this is a new codebase and your first mobile app, the goal is to keep every change small enough that you can understand and test it before moving on.

- **Work in a git repo from day one.** Ask Claude to commit after each working increment with a clear message. This gives you checkpoints to return to if a change goes wrong, and lets you read the diff to see exactly what changed.
- **Build a "walking skeleton" first.** Before any UI polish, get the thinnest possible path working end-to-end: app connects to the WLED controller and can turn the wall on/off. This proves the hardest integration (talking to WLED) works before layering features on it.
- **Review diffs, don't just trust output.** Ask Claude to explain unfamiliar Kotlin/Android patterns as they show up (`ViewModel`, `StateFlow`, Compose, coroutines). You don't need to memorize Android — just understand what each new piece does before it becomes load-bearing.
- **Test on a real device or emulator after every phase**, not just at the end. WLED talks over your local network, so a real Android device on the same Wi-Fi as the WLED controller is the most realistic test.
- **Keep this doc as the source of truth for decisions** (architecture choices, WLED quirks you discover, open questions). Point Claude back to it in future sessions so context isn't lost between conversations.
- **Ask for options, not just answers**, when a decision affects later work (e.g. local storage format, navigation pattern) — these are cheap to change early and expensive later.

## Architecture

**Platform:** Native Android with Kotlin and Jetpack Compose. This is the standard modern stack Google recommends and has the best tooling, docs, and community support for a first app — you won't be fighting the framework while also learning it.

**App structure (MVVM):**

| Layer | Responsibility |
| --- | --- |
| UI (Compose) | Screens and widgets; renders state, sends user actions up |
| ViewModel | Holds UI state (`StateFlow`), talks to the repository, survives rotation |
| Repository | Single source of truth; decides whether data comes from WLED or local storage |
| Data — network | Talks to the WLED controller's JSON API over HTTP on the local Wi-Fi network |
| Data — local | Room database for saved routes, plus DataStore for app settings (controller IP, chosen grid layout) |

**Talking to WLED:** WLED exposes a JSON HTTP API on the device (typically `http://<wled-ip>/json`). The relevant endpoints:

- `GET /json/info` — device info, LED count, whether it's a 2D matrix
- `GET /json/cfg` — full configuration, including the 2D matrix layout and any configured LED gaps/skips
- `GET`/`POST /json/state` — read/set power, brightness, per-segment colors and effects (this is how you turn the wall on/off and light individual holds)
- `POST /json/state` with a `"ps"` (preset) field — save/apply presets (P1 feature)

The app will fetch `/json/cfg` once during setup to learn the wall's grid dimensions and hold positions, then use `/json/state` for all live control. This keeps the app a thin client over WLED's own capabilities, matching the constraint of not modifying WLED itself.

**Hold-to-LED mapping:** the core domain concept is a `Wall` — a 2D grid where each cell is either empty or maps to an LED index. A `Route` is just a sparse map of `{ledIndex -> color}` plus a name, notes, and optional preview image. This mapping is what lets the same route-editing UI work whether the grid comes from WLED's config or (later) from a photo overlay.

**Networking library:** Ktor or Retrofit, either works well with Compose/coroutines — this is a small enough decision to make once you start coding rather than up front.

## Key features and screens

| Screen | Purpose | Priority |
| --- | --- | --- |
| Setup | Enter WLED IP/DNS, test connection, pick grid spacing (1ft, 8in, 6in, offset), pull grid + gap layout from WLED | P0 |
| Home | Wall on/off switch, connection status, quick links to routes | P0 |
| Route editor | Grid view of the wall; tap a hold to toggle its light, pick its color; remembers last color chosen | P0 |
| Saved routes | List of routes with name, notes, optional preview; apply or delete | P0 |
| Route apply | Push a saved route's colors/on-off state to the wall | P0 |
| Preset save | Save a route as a native WLED preset | P1 |
| Wall photo overlay | Take/crop a photo of the wall, auto-detect hold grid, toggle holds by touching the photo | P2 |
| Light mode toggle | Switch between All Lights / Holds Only / No Holds Only | P2 |
| Route sharing | Upload/download routes to a shared server | P5 |

A couple of things worth deciding early since kids (6+) are the target user:

- **Touch targets need to be large and forgiving** — the route editor grid should favor big tappable zones over precision, especially before the photo-overlay feature exists.
- **Color picking should be simple** — a small fixed palette (matching common hold colors) rather than a full color wheel, at least for v1.

## Incremental build plan

Each phase should end with something you can run on a device and see working, before moving to the next.

| Phase | Goal | Delivers |
| --- | --- | --- |
| 0 | Walking skeleton | Blank app that connects to a hardcoded WLED IP and can turn the wall fully on/off |
| 1 | Setup screen | Enter/save WLED IP, test connection, pull `/json/cfg` and display raw grid info |
| 2 | Grid layout | Turn the raw WLED grid into a real `Wall` model; pick/confirm grid spacing; render the grid on screen |
| 3 | Route editor (live) | Tap holds to toggle color/on-off, changes push live to the wall via `/json/state` |
| 4 | Local save/load | Room database; name + notes a route; save and list saved routes |
| 5 | Apply saved route | Load a saved route and push it to the wall in one action |
| 6 | WLED presets (P1) | Save a route as a native WLED preset |
| 7 | Photo overlay (P2) | Capture/crop a wall photo, manual or auto hold detection, toggle via the photo |
| 8 | Light modes (P2) | All Lights / Holds Only / No Holds Only toggle |
| 9 | Route sharing (P5) | Upload/download routes to a shared server |
| 10 | Version visibility | Show the build version in the app, so it's obvious which build is on which device |
| 11 | Navigation polish | Colour palette in a tray at the bottom of the screen; zoom control made compact and out of the way |
| 12 | Visual gap editor | Mark in the app which grid positions actually have holds, and upload the result to the controller — so moving holds around doesn't mean hand-editing a file |
| 13 | Kid-friendly effects | A small curated set of WLED effects and palettes to experiment with, rather than mirroring WLED's own UI |
| 14 | Brightness | A brightness control next to the on/off switch, so the wall can be dimmed for evening use without digging into WLED's own UI |
| 15 | Configurable palettes | A choice of predefined hold-colour palettes, plus the ability to build your own, from configuration |
| 16 | Working offline | Add, edit and delete routes with no controller in reach, with the wall controls showing that it is out of reach rather than failing |
| 17 | Repairing a changed wall | Show a route's missing holds where they used to be, dimmed, so the route can be rebuilt around the wall as it is now |

Phases 0–5 cover every P0 requirement and form a genuinely useful app on their own — that's the natural point to pause, use it on the real wall, and see what P1/P2 work actually turns out to matter.

Notes on the later phases:

- **Phase 10** is small — `BuildConfig.VERSION_NAME` surfaced somewhere unobtrusive. Worth doing early rather than in order, now that builds get sideloaded onto more than one device. It should also fix `versionName`, which still reads `0.1.0-phase0`.
- **Phase 11** follows on from Phase 3, which put the palette and zoom controls wherever they fitted rather than where they belong.
- **Phase 12** is already feasible: WLED's own 2D settings page uploads the gap file by POSTing it to `/upload` with the filename `/2d-gaps.json`, so no extra firmware support is needed. The app already knows how to *read* and interpret that file. Note the editor has to write `-1` for a position with no LED and `0` for one that has an LED which shouldn't be used — the two are not interchangeable (see above).
- **Phase 13** should stay deliberately small. The point is a few big obvious buttons, not a second WLED front end.
- **Phase 14** is a simplified WLED control like Phase 13, but it doesn't belong in the same screen. Brightness is an everyday adjustment — bright in daylight, dim in the evening — rather than something to experiment with, so it wants to sit beside the on/off switch where it's reachable in one tap. Technically it's one field, `{"bri": 0-255}` on `/json/state`: master brightness, not the per-segment `bri`. **The slider must not be allowed to reach 0** (see below).
- **Phase 15** comes out of finding that the palette had been chosen to look
  right on a screen rather than on an LED (see below). Once the values are
  worth tuning, they are worth letting someone else tune. Three parts: a set of
  predefined palettes to pick from, a builder for a custom one, and a home for
  both under configuration — alongside the controller address and the gap
  editor, per [navigation.md](navigation.md), rather than on the wall screen.
  The tray on the wall screen stays a handful of large swatches whatever the
  palette contains; the target user is six, and a configurable palette is for
  the adult setting it up.

  **Two palettes to ship with, so "predefined" means something on day one.**
  *WLED* is what the app uses today, taken from the controller's own
  quick-select swatches and fully saturated: `FF0000` `FFA000` `FFC800`
  `08FF00` `0000FF` `AA00FF`. *Original* is the set used before that, softer
  and less saturated in green, blue and yellow: `FF0000` `FFA000` `FFD500`
  `00C853` `2979FF` `AA00FF`. Its orange is WLED's, not the `FF6A00` it
  shipped with - that value was a defect rather than a preference, and
  preserving it would only preserve the bug. Purple is the same in both,
  because WLED has none to borrow.

  **The hard part is not the UI, it is that routes are stored by colour name.**
  `RouteHolds` serialises `x,y:Red`, which works precisely because `HoldColor`
  is a fixed enum — the values behind those names were retuned without touching
  a saved route. A configurable palette removes that guarantee.

  **Store the position rather than the colour.** `x,y:1` instead of
  `x,y:Orange`: a route records which *slot* each hold uses, and the palette in
  force supplies the colour when the route is drawn and pushed. Switching
  palettes then re-skins every saved route for nothing — no migration, no route
  able to reference a colour that no longer exists, and no way for editing a
  palette to corrupt stored data, because the stored data never named a colour
  in the first place. It also describes a route more honestly than a colour
  name does: holds sharing a colour are a group, and what has to survive is
  that the groups stay distinct, not which particular colour each one got.

  The migration from today is free. `HoldColor` is already an ordered enum, so
  its ordinal *is* the slot — `Red` is 0, `Orange` 1, and so on — which makes
  the rewrite mechanical and lossless. The cost is that the stored column stops
  being self-describing: `x,y:1` needs the palette to be read, where `x,y:Red`
  did not. That is a real loss when inspecting the database by hand, and a
  small price for the rest.

  **The edge case to settle is a palette smaller than the route needs.** A
  route using slot 5 applied to a four-colour palette has to do *something*,
  and both obvious answers are wrong: clamping and wrapping can both land two
  different slots on the same colour, merging two groups of holds into one and
  silently destroying the distinction the route was built on. Either palettes
  carry a fixed minimum size, or switching to a smaller one is refused rather
  than fudged.


- **Phase 16** is the requirement
  [walls-and-routes.md](walls-and-routes.md) opened with and phase 4 did not
  deliver: *a saved route can be selected and edited without being connected to
  the wall*. Routes are stored, so the data half is done; what is missing is
  that the app still treats a missing controller as a dead end.

  **What is already true.** The wall's shape is stored as `holdGrid`, so a grid
  can be drawn with nothing to ask. Routes are stored by grid position and
  palette slot, so nothing about them needs the controller to interpret. Drafts
  persist on every edit. Offline editing needs none of that built again.

  **What has to change.** `WallUiState` has three shapes - `Connecting`,
  `Connected`, `Error` - and no way to say "working, but out of reach". Today a
  failed connect becomes `Error`, which takes the grid away entirely; that is
  the single biggest thing in the way, and it is a state-modelling change
  rather than a networking one. Every hold tap also pushes, so offline each
  edit would be a failure to handle rather than an ordinary action.

  **Which forces the question walls-and-routes.md already raised:** applying
  becomes explicit. Edit locally and apply deliberately is a different model
  from every tap going straight to the wall, and offline is what makes it
  unavoidable - there is no sense in which an edit made with no controller has
  been applied. Answering it for the offline case answers it for the online one
  too, so it is one decision, not two.

  **Coming back is the part that is easy to underestimate.** A controller that
  reappears has whatever it had before, which need not be what the app has been
  editing, and the app cannot read it back to find out - WLED answers
  `/json/live` with 501. So reconnecting has to either push what the app holds
  or ask, and doing it silently would let a reconnection overwrite the wall
  someone else was using. This is the same question as the one the restore on
  launch raises today, and the same place it belongs: alongside
  [wall-sharing.md](wall-sharing.md).

  **The indicator.** The power button shows that the controller is out of
  reach, rather than the screen saying so. It is already the control that
  answers "is the wall on?" from across a garage, and "there is no wall to
  answer for" is the same question with a third answer - which is why it goes
  there and not into a banner. It needs to be distinguishable without colour,
  since that is the whole point of a control a six-year-old reads at a glance.

  **As built.** The questions above were answered this way; the detail is in
  [walls-and-routes.md](walls-and-routes.md).

  - *Finding the wall offline.* The device remembers the id of the wall it last
    reached, in settings, and keeps it until a controller answers as a
    different wall - a new address saved in setup does not clear it. Not looked
    up by address: identity is the MAC, and the address is what DHCP hands to
    someone else.
  - *State.* `WallUiState` is `Loading`, `Ready` or `Error`, and `Ready` carries
    a `ControllerState` - `Connecting`, `Online` or `Offline` - beside the route.
    `Error` is left for having no stored wall to fall back on.
  - *Applying.* Explicit, with the wall following edits once applied until
    different work is opened. A per-device **auto-apply** setting, on by
    default, keeps every-tap-lights-a-hold for the children's devices and can
    be turned off on the one used to build routes while someone climbs.
  - *Coming back.* Neither push nor ask: reconnecting shows the work as not
    applied, and the next deliberate change puts it up. Launching does the
    same. The app only asks the controller again when the power button is
    tapped - it does not retry by itself.
  - *The indicator.* The power button has three shapes: filled (on), a solid
    ring (off), and a dashed ring round a struck-through glyph (out of reach,
    tap to try again). A spinner in a dashed ring while connecting.


- **Phase 17** is the other half of the promise in
  [walls-and-routes.md](walls-and-routes.md): *opening one diffs its lit
  positions against the current gap pattern so the holds that have gone can be
  shown and the route repaired.* The warning in the list is built. The diffing
  and showing is not - today a hold whose position no longer has a light is
  simply left out when the route is loaded.

  **Draw them, dimmed, where they used to be.** Not as a decoration: a route on
  a wall that has been re-drilled is repairable if you can see what it used to
  look like, and unrecoverable if you cannot. The dimmed hold says "there was
  one here", so the same move can be put back on whichever hold is nearest now.
  Tapping a dimmed hold removes it, which is how a route stops being stale -
  either every ghost is repositioned or dismissed, and then it matches the wall
  again.

  **The reason to build it is not convenience, it is that saving currently
  loses them.** Loading filters out the holds the wall no longer has, and
  saving writes back what was loaded, so the first save after a gap-file change
  discards them for good. "Kept, not discarded" is true of the stored route
  only until someone opens it and presses save - which is exactly what someone
  does when repairing a route by hand. That is a quiet data loss with no
  warning attached to it.

  **What has to change.** Loading has to return both sets rather than one, the
  UI state has to carry the orphans alongside the lit holds, and the grid has
  to draw at positions that have no light - today it draws from the wall's
  cells, and a ghost is by definition somewhere those say nothing is. Saving
  has to write orphans back, or the loss above survives the feature that was
  meant to fix it.

  **Storage needs nothing.** Routes already record `x,y` positions and a
  palette slot; a position the wall has no light at is perfectly representable
  and is already what gets stored. This is a presentation and editing change on
  top of data that is already correct.

## WLED behaviour worth knowing

Things that cost real debugging time, so they're written down rather than rediscovered.

- **Two different pixel indices, and they are easy to confuse.** A hold has a position in the *grid* (`x + y * width`) and a position along the *physical strip* (the wiring order WLED's ledmap is built from). WLED's per-pixel `"i"` command addresses the **grid** one: it writes into the segment's 2D buffer (`setPixelColorXYRaw` → `pixels[x + y*vWidth()]`) and applies the ledmap itself when rendering. Sending the strip index instead lit a scattered, mirrored set of the wrong holds — on this wall the two are opposite corners, so it looked plausible but was wrong. `Wall.segmentIndexAt()` is the one to send; `Wall.ledIndexAt()` is the other.
- **Switching the wall on wipes the route.** WLED unfreezes every segment when it powers on (`json.cpp`, "unfreeze all segments when turning on"), which drops the per-pixel route while the app still shows it. The app re-pushes the route after powering on.
- **Brightness can wipe the route the same way powering on does.** The unfreeze guard is `if (bri && !onBefore)` — it keys on brightness crossing up from zero, *not* on the `"on"` field, so a slider dragged up from 0 drops the route exactly as switching the wall on does. And `bool on = root["on"] | (bri > 0)` means a `"bri"` sent without an `"on"` *derives* the power state, so `{"bri":0}` quietly switches the wall off. Floor the slider above zero so it never crosses that boundary; if it ever is allowed to reach 0, it needs the same re-push `toggleWall` does.
- **The first `"i"` command freezes the segment and clears it to black**; later ones don't re-clear. Effects and presets stop running while frozen, and `{"seg":{"frz":false}}` releases it.
- **Push the whole route, not just what changed.** WLED keeps previously set pixels, so an incremental message leaves a hold lit after the app cleared it. One request carrying the full desired state (`[0, pixelCount, "000000", index, colour, …]`) is self-healing and no larger in practice.
- **Realtime UDP (DDP/DRGB/WARLS) is deliberately not used.** It *would* address the raw strip, but it times out after 2.5s by default, gives no delivery confirmation, doesn't persist as WLED state, and can't be captured in a preset — which would block Phase 6.
- **A segment's name is free to use, and two things clear it without asking.**
  The name (`"n"` on a segment) is a cosmetic label WLED shows in its own UI to
  tell segments apart; it has no effect on rendering. `setName` and `clearName`
  are heap operations with no config serialization behind them, so there is no
  flash wear, and `cfg.cpp` never touches it - meaning it is absent from
  `cfg.json` and a reboot drops it. The hazards: a segment command that changes
  `start` or `stop` without also carrying `"n"` calls `clearName()` silently
  (`json.cpp`), and presets serialize the name, so saving one captures whatever
  it held at the time and applying it later restores that stale value. The app
  uses this field to hold the wall claim - see
  [wall-sharing.md](wall-sharing.md).
- **The gap file's `-1` and `0` mean different things.** `-1` is no LED at all; `0` is an LED that exists but is unused — and it still consumes a strip index, so treating them the same shifts every LED after it.
- **A colour picked on a screen does not arrive on the wall looking the same.**
  A display is emissive and dim; an LED behind a translucent hold is additive
  and very bright, so a channel that merely tints on screen can be swamped on
  the wall. Orange is where this showed: `FF6A00` looks orange in any design
  tool, but with green at 106 against full red it lit as a warm red. WLED's own
  quick-select swatches (`wled00/data/index.htm`) are chosen for LED output
  rather than for a screen, which makes them the better source - their orange
  is `FFA000`, green at 160. `HoldColor` takes its values from that set. Their
  set has no purple, so that one stays ours.

## Open questions

- ~~**Phase 4:** the panel/serpentine algorithm in `WallMapper`...~~ **Resolved.** Nothing wanted strip indices: `ledIndexAt` was called by `hasHoldAt` and by tests, and by nothing else. `Wall` now stores occupancy and `buildWall` marks panel rectangles instead of walking them in wiring order — eight tests pinning serpentine and orientation behaviour went with it. The estimate was close: ~60 lines to ~20, and 15 mapper tests to 11. `Panel` keeps its wiring flags, since they describe the controller's configuration faithfully and a gap-file editor may need them.
