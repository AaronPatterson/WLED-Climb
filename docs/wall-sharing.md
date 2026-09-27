# Sharing the wall

One child loads and edits a route while another is climbing. The app has to
tolerate that. For how routes are stored, see
[walls-and-routes.md](walls-and-routes.md).

## Requirements

- There will be a gesture to apply a route to the wall.
- Possibly a way to see that someone else has control, with an explicit action to
  take it over. Once taken over, applying a route might become automatic.

## A convention, not a lock

Worth being clear about the limits. WLED has no notion of ownership. It is a
stateless HTTP endpoint that does whatever it was last told, and nothing in the
protocol lets one device know another exists.

So this is a convention the app encodes and offers take over and release around.
Anyone is free to ignore or clear it, including from WLED's own interface. That
is a feature rather than a hole.

## Where the claim is stored

In the segment's name field, set and read through `/json/state`:

    POST {"seg":[{"id":0,"n":"wledclimb:eli"}]}   ->  {"success":true}
    GET  /json/state                              ->  seg[0].n == "wledclimb:eli"
    POST {"seg":[{"id":0,"n":""}]}                ->  cleared

Verified against the wall on WLED 16.0.1. Setting a name changes nothing about
what the LEDs display.

Chosen over two alternatives for a concrete reason. A claim file on the
controller's filesystem was the obvious idea - it is how the gap file already
works - but `/edit` returns **401** on this controller because a settings PIN is
configured, so the file API is not available to the app. `/json/state` is not
gated. Storing the claim in a preset slot would work and is readable through
`/presets.json`, but it consumes a slot and pollutes the preset list.

The name being visible in WLED's own interface is deliberate: someone at the
controller can see that the app believes a person holds it, and can clear it
without needing the app.

For what the segment name costs and the two hazards around it - a boundary change
wiping it, and presets capturing it - see "WLED behaviour worth knowing" in
[design.md](design.md).

## Taking control

Direction set 2026-09-27, to be built after offline mode ([design.md](design.md)
phase 16). Offline is what makes this tractable: a device that is not holding
the wall has somewhere sensible to be.

**The default is offline when someone else holds it.** Opening the app while
another device has a claim does not fight for the wall and does not refuse to
work. It shows the wall, lets routes be built and saved, and simply does not
push. That is the same state a person can choose deliberately, so nothing new
has to be built to represent it.

**Control is taken implicitly when nobody holds it**, and explicitly when
somebody does. Waiting to be granted something nobody is using would be
ceremony for its own sake; taking it from someone who is mid-climb should be a
decision with a button attached.

**A claim expires.** Someone who puts their phone down and walks off must not
hold the wall until the app is reopened, so a claim carries a timestamp and
lapses after a period of no interaction. The expiry is what keeps this a
convention rather than a lock that needs an administrator - see "A convention,
not a lock" above.

**Reconnecting is taking control.** A device coming back Live pushes what it
holds, because the alternative is a wall showing one route and an app claiming
another. This is the same act as an explicit takeover and should go through the
same path, rather than being a quiet side effect of the network returning.

### What this settles of the open questions below

**Detection versus claim: both, and detection is what makes the claim safe.**
The claim says who intends to hold the wall. Detection - noticing that the
wall's state no longer matches what this device last applied - catches every
case the claim cannot: WLED's own interface, a preset, someone who cleared the
name, a device that crashed without releasing. A device that detects divergence
drops itself to offline rather than pushing over the top, which means the app
degrades politely against people who never agreed to the convention.

It also means a first useful version needs no claim at all. Detection alone
gives "someone else is using the wall, so I am offline", and the claim adds
only the ability to say *who* and to take it deliberately.

## Scenarios not yet designed

Captured 2026-09-23. Neither is being built yet.

### Loading what is already on the wall

One child builds a route on their device and applies it. Another wants to pull
up whatever the wall is currently showing, then edit it or save it as their own
route.

**The obvious implementation is not available.** The app cannot read back which
holds are lit. `/json/live`, which would expose per-LED colour, returns
`501 {"error":4}` on this controller, and `/json/state` carries only the
segment's three colour slots - there is no per-pixel array anywhere in it. The
`"i"` command is effectively write-only: individual pixels can be set and never
asked about.

So this scenario cannot be served by reading the wall. The realistic routes are:

- **Share the route, not the pixels.** The claim already identifies who holds the
  wall; it could also name what they applied. The second device then needs the
  route itself, which means routes syncing between devices - phase 9 territory,
  and a much larger change than this scenario looks like from the outside.
- **Investigate the websocket live preview.** WLED's own UI renders a live view
  from somewhere, and if that channel carries LED data it may be readable. It
  would arrive in strip order rather than grid order, which would mean
  reintroducing the ledmap handling deliberately removed from `WallMapper`.

Worth knowing before anyone estimates this: it looks like a small feature and is
not one.

### Two people editing the same live route

Building on the same idea: two devices editing what is on the wall at once,
each polling periodically so changes made by the other appear.

Requirements as stated:

- Pull the wall's current state periodically and reflect it in the UI.
- Changes made by others must show up.
- Concurrency does not need to be perfect, but should minimise the chance of one
  person's change being stepped on.
- Possibly pull before applying, keeping local changes so they can be replayed
  onto whatever came back if it differs.

That last point is a sound approach and worth keeping. It is the same shape as a
rebase: take the other side's state, replay your own edits on top, push the
result. It needs edits held as operations - "light 4,4 blue" - rather than as a
finished picture, because a picture cannot be replayed onto a changed base.

The same readback problem applies, and harder. Polling "the wall's current
state" is exactly the thing that is not currently possible, so this scenario
depends entirely on solving the one above first.

## Open questions

- ~~**Detection versus claim.**~~ Answered under "Taking control" above: both,
  with detection alone enough for a first version.
- **Whether takeover makes applying automatic**, as the requirement suggests, or
  whether applying stays deliberate even when holding the wall.
