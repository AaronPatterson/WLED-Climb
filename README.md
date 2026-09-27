# WLED Climb

Android app for controlling a climbing wall with LED-lit holds (clear holds, LEDs behind them), driven by a [WLED](https://kno.wled.ge/) controller.

## Docs

- [`docs/requirements.md`](docs/requirements.md) — original feature brief and priorities (P0–P5), target user, constraints.
- [`docs/design.md`](docs/design.md) — best practices for this project, architecture (Kotlin + Jetpack Compose, MVVM, WLED JSON API), screens, and the incremental build plan.
- [`docs/kotlin-style.md`](docs/kotlin-style.md) — Kotlin coding conventions for this codebase: naming, state modeling, ViewModel/coroutine patterns, file organization.

## Licence

[GPL-3.0](LICENSE). Use it, change it, run it on your own wall, build something
else out of it. If you distribute a modified version you have to publish your
source under the same terms, which is the point: the one thing this is meant to
prevent is someone taking the work closed and selling it.

Early revisions of `WallMapper` contained a Kotlin port of WLED's
`WS2812FX::setUpMatrix()`. That code has been removed - per-pixel commands
address grid positions, so the strip indices it computed were read by nothing -
but it remains in the git history. WLED is licensed under the
[EUPL v1.2](https://github.com/wled/WLED/blob/main/LICENSE), which is
compatible with the GPL, so that history sits comfortably under this licence.
It would not have under a permissive one, which is part of why this licence was
chosen.

## Status

**Phase 0 (walking skeleton) complete**: the app connects to a hardcoded WLED controller IP and turns the wall on/off via WLED's `/json/state` endpoint, verified against a real controller.

**Phase 1 (setup screen) complete**: on first launch, the app asks for the controller's IP/hostname and tests it by pulling `/json/cfg`. On success it saves the address via DataStore and moves straight to wall control; future launches skip setup entirely. A "Change controller" button on the wall screen goes back to setup (pre-filled with the current address) to switch to a different one at any time.

**Phase 2 (grid layout) complete**: the wall screen now parses `/json/cfg`'s panel layout into a real grid (verified against a real controller's config with unit tests) and renders it as a read-only preview above the on/off toggle. It also reads WLED's optional `/2d-gaps.json`, if configured, to exclude matrix positions that don't have a real LED — on the wall this was tested against, that turned out to matter a lot: the grid is a sparse, irregular shape, not the solid rectangle the panel layout alone would suggest.

**Phase 3 (route editor) complete**: the grid is interactive - tap a hold to light it on the real wall, pick from a small palette of colours, and tap again to clear it. The whole wall fits on screen by default, with pinch to zoom and drag to pan for smaller screens.

**Phase 10 (version visibility) complete**: the build's `versionName` is shown on the wall screen, so it's possible to tell at a glance which build is on which device - which matters now that builds are sideloaded onto several.

**Phase 11 (navigation polish) complete**: the colour palette moved to a tray at the bottom of the screen and the zoom controls became compact icons, rather than sitting wherever they first fitted.

**Phase 4 (local save/load) in progress**: the storage layer is done - a Room database of walls and routes, with the DAOs covered by Robolectric tests that run them against real SQLite. There is no UI on top of it yet, so nothing is saveable from the app; that is the next piece of work. See [`docs/walls-and-routes.md`](docs/walls-and-routes.md) for the data model and [`docs/navigation.md`](docs/navigation.md) for where the screens are heading.

## Getting started (opening in Android Studio)

1. Open this folder in Android Studio.
2. Let Gradle sync automatically (the wrapper is checked in, so this should just work — no separate Gradle/AGP install needed). If it doesn't sync on its own, run **File → Sync Project with Gradle Files**.
3. Run the `app` configuration on an emulator or device on the same Wi-Fi as your WLED controller.
4. On first launch, enter your WLED controller's IP or hostname on the setup screen and tap **Test & save**. Later launches remember it and go straight to wall control.

Each phase of `docs/design.md`'s build plan is developed on its own branch and merged to `main` via pull request once it's working end-to-end.
