# Navigation

Captured 2026-09-23, before phase 4. The app has two destinations - setup and the
wall - switched by whether a controller address is saved. That was enough while
the wall grid was the only screen.

For what is being navigated *between*, see [walls-and-routes.md](walls-and-routes.md).

## Requirements

- Power and brightness reachable from most screens, but not from configuration
  screens.
- Loading an existing route, editing it, and creating a new one is the main thing
  anyone does, so that is the landing experience.
- With no saved routes, drop straight into creating one.
- With saved routes, the one selected last time is selected again at launch.
- Somewhere to change configuration - today only the controller address, later
  more - and somewhere to see the version, possibly growing into an About
  section.
- Wall management (editing the gap file, setting a wall picture) has to live
  somewhere, in an experience much like setting a route.

## Patterns considered

| | Shape | Verdict |
| --- | --- | --- |
| A | Bottom navigation: Routes, Wall, Setup | Simplest, but two of three destinations are used monthly and would hold prime space permanently. A bottom bar also reads as stranded across a ten-inch tablet. |
| B | List-detail with an adaptive rail | Routes listed beside the editor, both visible. Selecting a route stops being a navigation event that hides the list. |
| C | Single screen with a drawer | Scales best as management grows, but hides everything behind a hamburger, and Material 3 discourages drawers for few destinations. |

**Chosen: both A and B, selected by window size.** They are not alternatives. The
Material 3 adaptive libraries switch between them from one implementation, along
two independent axes:

| | Library | Compact (phone) | Expanded (tablet) |
| --- | --- | --- | --- |
| Navigation chrome | `NavigationSuiteScaffold` | bottom bar | navigation rail |
| Content layout | `ListDetailPaneScaffold` | one pane, list to detail | list and detail side by side |

A phone therefore gets pattern A and the tablet gets pattern B, with no branching
on device type and no second layout to maintain. `ListDetailPaneScaffold` also
handles the back stack, which is the fiddly part: back from the editor returns to
the list on a phone and does nothing on a tablet, where the list never left.

The primary device is a tablet in landscape, which is what list-detail is for,
and it is the only option where selecting a route does not cost the list.

Two things follow that are easy to get wrong:

- The switch keys off **window** width, not device. A tablet in split screen gets
  the phone layout, which is correct but means available width is the trigger
  rather than the hardware. The tablet in portrait may land in medium rather than
  expanded, so that breakpoint is a decision to make deliberately.
- ~~The Compose BOM here is `2024.09.00`...~~ **Resolved, incidentally.** The
  BOM reached `2026.09.00` for unrelated reasons, so the prerequisite was
  already satisfied by the time this was built. The adaptive libraries are
  versioned independently of the BOM and carry explicit versions of their own.

Versions in use, all stable: adaptive, adaptive-layout and adaptive-navigation
at 1.3.0 - still the current stable line, 1.4.0 being alpha. navigation-suite is
not used yet, for the reason below.

## Where the wall controls live

Power and brightness sit in a top bar spanning the app, which is what satisfies
"reachable from most screens" without repeating them per screen. Configuration
screens are pushed as full destinations, and that is what takes the controls
away there.

## Management lives under configuration for now

Gap editing and the wall picture sit inside configuration rather than becoming a
top-level destination. They can be promoted later; the point for now is to keep
the surface the children see as small as possible.

This has a consequence. With management folded in there are only two top-level
destinations, Routes and Settings, and Settings is the rare one. A bottom bar
carrying two items where children should only ever touch one is worse than a
single icon in the top bar, so `NavigationSuiteScaffold` is **not** used yet -
settings is a pushed destination, which is also what removes the wall controls
from it. `ListDetailPaneScaffold` is still used, because list-beside-editor is
the part that earns its keep.

The navigation suite goes back in when management is promoted.
