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

## The app opens on the wall

`NavigableListDetailPaneScaffold` starts on the list pane unless told
otherwise, which meant launching into a route picker with the restored route
hidden behind it - the list covering the answer to the question it was asking.

It now starts on the detail pane with the list seeded behind it in the history,
so back from the wall reaches the routes rather than leaving the app. That
satisfies both of the launch requirements above at once: with saved routes the
last one is already restored and visible, and with none, a blank wall *is*
dropping straight into creating one.

## Why the routes list is not swiped to

Swiping between the wall and the routes was considered and rejected on a
concrete conflict rather than on taste: the grid uses `detectTransformGestures`
for pinch-zoom and **drag to pan**, so a horizontal swipe is already how you
move around a wall too big for the screen. A gesture cannot mean "pan the wall"
and "leave the wall" at the same time, and whichever won would be wrong
sometimes.

Material also reserves swiping for tabs - content within one destination -
rather than for moving between destinations, and a gesture with nothing on
screen to advertise it is a poor fit for a six-year-old.

## What the top bar carries, and where

The left of an app bar is navigation. The routes list is the only place this
bar navigates to, so that is what sits there.

It was a hamburger, which was wrong twice: the icon promises a navigation
drawer and there is none, and it put the app's settings in the position someone
reaches for to go somewhere. The settings moved to an overflow menu at the far
right, which is where Material puts one, leaving the middle of the bar to the
wall's own controls - brightness and power - and keeping navigation and wall
controls from sitting shoulder to shoulder as though they were the same kind of
thing.

## The route manager is a screen, not a picker

It is the list pane of the list-detail layout, which means it is a full screen
on a phone and sits beside the wall on a tablet. What it has to do:

- **Show each route, not just name it.** A small preview of the lit holds, so a
  route is recognisable without opening it. The data is already there - a
  stored route is grid positions and palette slots, and the wall's shape is
  stored too, so a thumbnail can be drawn with no controller present.
- **Everything the dialogs do today**: save, save as new, rename, delete,
  reset, and starting a new route.
- **Tapping a route opens it on the wall.** Selecting is not a separate step
  from going to look at it.

Two consequences worth deciding before building it. A preview has to be drawn
from the *stored* wall shape rather than the connected one, or the list cannot
be used offline - and a route saved against a different shape should presumably
preview as it was saved, which is the same diff that phase 17 draws in the
editor. And a list of previews is a list of small grids, so it wants a fixed
thumbnail size rather than one scaled per route, or two routes on the same wall
will not look comparable.

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
