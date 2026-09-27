# Walls and routes

The domain model behind phase 4. For how these are navigated between, see
[navigation.md](navigation.md); for what happens when two people use the wall at
once, see [wall-sharing.md](wall-sharing.md).

## Requirements

- A saved route can be selected and edited **without being connected to the
  wall**.
- The app stores a wall: its dimensions and gap pattern. That is what makes
  offline editing possible, and it means a new route can be created with no
  controller present.
- Selecting a route does not apply it to the wall, even when the wall is on.
  Applying is a deliberate action. *Relaxed - see Applying below: online, every
  change goes to the wall, and working offline is how to build without it.*
- The stored wall has to be checked against the real one on reconnect, because it
  can change - a new gap file, a resized matrix.
- A wall changing must not silently destroy routes built against the old layout.
- Future: more than one wall. Routes are linked to a wall from the start so that
  this does not need a migration later.
- Future: marking a route read-only so it cannot be changed by accident, with the
  flag removable when an edit is intended.

## What this changes beneath the surface

Two of those requirements invert assumptions the app is currently built on.

**A wall stops being something fetched and becomes something stored.** `Wall`
used to be rebuilt from `/json/cfg` on every launch, and `WallUiState` had
exactly three shapes - `Connecting`, `Connected`, `Error` - with no way to
express "showing a route while disconnected". Offline editing needs the layout
persisted, and needs disconnection to stop being an error.

It now opens the stored wall first and asks the controller second. The device
remembers which wall it last reached (`WledSettings.lastWallId`), because a
wall is identified by its controller's MAC and there is no asking for that
offline. It is kept when setup saves a different address and replaced only when
a controller answers as a different wall: a new address is often the same
controller on a new lease, and the MAC decides, not the address. `WallUiState` is
`Loading`, `Ready` or `Error`, and `Ready` carries a `ControllerState` -
`Connecting`, `Online` with power and brightness, or `Offline` - beside the
route rather than instead of it. `Error` is left for the case with nothing to
fall back on: a first run, with no controller answering.

When the controller answers again:

- **The same wall** keeps the work on screen, which is newer than anything
  stored, and is sent to the wall - see Applying below. If its shape changed while
  away, the holds on screen are carried across by position rather than by
  segment index, which a new width would scramble.
- **A different wall** - another controller given the address - opens as it
  was left, and what was built offline stays with the wall it was built on.

A failed push, toggle or connect marks the controller `Offline` and keeps the
grid; it used to replace the screen with an error.

**Applying was going to become explicit, and did not.** Edit locally, apply
deliberately was the model offline editing seemed to need. It was built, with an
Apply button and a per-device auto-apply setting, and replaced before release by
something simpler - see the next section.

## Applying

Online, every change goes to the wall: a tapped hold lights, and opening a route
puts it up. That is the experience a six-year-old needs, and it is the same on
every device.

What building a route while someone climbs needs instead is a way to stop
talking to the controller: **working offline**, or pausing. A long press on the
power button, or "Work offline" in the menu, leaves the controller alone with it
in reach. Everything offline allows works, and nothing reaches the wall - not an
edit, not power, not brightness. Tapping the power button goes back online.

- **Coming back sends what is on screen.** Reconnecting, from a pause or from
  the controller having been out of reach, is always a deliberate tap, and
  someone who has come back to the wall wants it to show what they see. With
  nothing on screen, nothing is sent: that is not a route, and sending it would
  clear the wall of whatever someone is climbing.
- **Opening the app sends nothing.** The draft and last route come back on
  screen, not on the wall. The app cannot read the wall back (`/json/live`
  answers 501), so it cannot tell whether someone else's route is on it now,
  and launching is not a request to replace one. The first change sends the
  whole route.
- **A pause survives the app being closed.** It is persisted, and a paused
  device opens its stored wall without asking the controller at all. A
  relaunch that reconnected would put the half-built route up at the next tap,
  which is the one thing pausing is for preventing.

This relaxes the requirement above that selecting a route does not apply it -
deliberately, since a child's device wants exactly that, and the adult's device
has pausing for the case the requirement was protecting.

## Fingerprinting

Each wall stores a fingerprint of its dimensions and gap pattern, and every route
records the fingerprint it was created against. On reconnect the app recomputes
from `/json/cfg` and `/2d-gaps.json` and compares.

Routes that no longer match are **kept, not discarded**. They carry a warning in
the list, and opening one diffs its lit positions against the current gap pattern
so the holds that have gone can be shown and the route repaired.

What a wall stores is therefore occupancy, not wiring. `Wall` used to record
where each LED sat along the physical strip, ported from WLED's own
`setUpMatrix()`; that turned out to be read by nothing, because per-pixel
commands address grid positions and WLED applies its own ledmap when rendering.
The strip indices and the serpentine walk that produced them are gone, which is
what leaves a wall small enough to be worth storing: width, height, and which
cells hold a light.

## What a stored wall does not keep

The grid is stored as `holdGrid`: one character per cell, row-major, '1' where a
hold can be lit. That is the gap file *resolved* against panel coverage and
flattened to a yes or no, and it is deliberately not reversible. WLED's -1 (no
LED wired) and 0 (an LED wired but unused) both land on '0', and only the second
consumes a strip index, so a gap file rebuilt from `holdGrid` would shift every
LED after the first gap.

The raw gap values are therefore **not** stored. Phase 12's gap editor refetches
`/2d-gaps.json` when it opens, which costs nothing: it has to be connected to
upload the result anyway, and a fresh copy beats a possibly stale one. This is
also why the column is not named after the gap file - a name suggesting it could
be written back would be an invitation to that bug.

## Moving routes between installs

Routes live in the app's private database, which nothing outside the app can
read - not another install, not a file manager, not `adb` on an unrooted phone.
That makes a reinstall, a new phone, or the debug build and the release build
sitting side by side into three ways to lose work that took real time on a real
wall.

**Export** writes every route for the connected wall to a JSON file through the
system document picker. **Import** reads one back. The picker rather than a path
the app picks: the file is the person's, it lands somewhere they keep things, and
the app needs no storage permission at all - it is handed one file and can see
nothing else.

The file carries the holds in exactly the text the database stores, `x,y:SLOT`.
That is what makes it a backup rather than a snapshot: coordinates survive the
wall being rebuilt from a different gap file, and palette slots survive the
palette being retuned or replaced (Phase 15). A file written today still means
something after either has happened.

Row ids and `wallId` are deliberately left out. They identify rows in one
database and nothing in another, and carrying them would invite an import to
overwrite by id - which is how a restore becomes data loss.

**Importing merges and never removes or overwrites.** The usual reason to restore
is that routes exist in two places and both are wanted, so:

- The same name with the same holds is the same route arriving twice, and is
  skipped. Importing a file again is therefore harmless.
- The same name with *different* holds comes in numbered - `Warmup (2)` - rather
  than overwriting what is here or hiding behind a duplicate name.
- Anything else is added.

A backup of another wall is refused outright, matched on the controller MAC.
Holds are positions on a specific wall, so restoring one wall's routes onto
another produces routes that are wrong rather than routes that are stale, and
nothing afterwards could tell the difference.

JSON rather than the database file itself, for three reasons: a database file
carries Room's schema identity hash, so restoring one into an app built from
different source fails as "routes cannot be saved for this wall"; it would
replace rather than merge; and JSON is legible, which matters for a format whose
job is to still be readable later.

## Open questions

- **What "last selected route" survives.** Process death, certainly. Whether it
  survives switching walls is a different question.
