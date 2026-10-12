# Testing

Everything runs in `./gradlew test`, on the JVM, with no device or emulator.
CI runs it on every pull request.

## Three kinds of test

**Unit tests** cover the parts with no Android in them: parsing WLED's config,
the hold grid, the route backup format, the repositories against in-memory
DAOs.

**ViewModel tests** drive `WallViewModel`, `SetupViewModel` and `RootViewModel`
against fakes - `FakeWledClient`, `InMemoryWallDao`, `FakeWledSettings` - so
timing and failures can be controlled exactly. Most behaviour is pinned here,
because it is where a failure is easiest to read.

**End-to-end tests**, in `app/src/test/.../e2e`, drive the app as shipped. The
real `MainActivity`, the real Room database, the real DataStore settings and
the real OkHttp client, talking over a real socket to `FakeWledController` - a
MockWebServer that speaks enough WLED for the app. The tests type its address
into the setup screen, as a person would. Nothing in the app is swapped out to
make them possible: the only test hooks are `ClimbDatabase.closeForTests()` and
`DataStoreWledSettings.clear()`, which exist because both keep one instance per
process and Robolectric runs every test in one process.

They find things on screen the way a screen reader does. Every hold is
described as "Hold at column 2, row 1" or "Hold at column 2, row 1, lit Red",
so a test can tap any hold and read back what colour it is without the app
knowing it is being tested.

## What the end-to-end tests cannot see

Robolectric lays out the UI but draws no pixels, so anything only visual is
out of reach: a colour, a truncation, a layout that clips. The greyed
work-offline checkbox (#61) is the example - its row already reported itself
disabled, so no test could tell that the box inside it was still bright.
Catching that class of problem needs screenshot tests.

Real WLED firmware is out of reach too. `FakeWledController` models what the
app can observe, and no more.

## The screen size is deliberate

End-to-end tests run on Robolectric's default screen, a 320x470dp phone. On a
more realistic phone size - 411x891, 412x892, or 360x780 at the density of the
phone this app is used on - a dialog holding a focused text field never lets
Compose go idle. Robolectric's stand-in window manager and Compose keep
relaying out the dialog window, the test waits a minute, and the garbage from
the loop takes the rest of the suite down with an `OutOfMemoryError`.

Reduced to a bare `AlertDialog` with a focused `OutlinedTextField` and none of
this app's code, it does the same, and on the default screen it does not. So it
is an emulation problem rather than the app's, and since these tests assert
behaviour rather than pixels, the smaller phone costs nothing. The tablet tests
set qualifiers of their own and stay clear of text dialogs, seeding routes
straight into the database where they need some.

The test JVM is given 2 GB (`app/build.gradle.kts`). The default 512 MB was not
enough for a full app per test, and running short looked exactly like a hang.

## Waiting rather than assuming

The network and the database both answer on threads of their own. So a test
waits for what it expects - `waitFor`, `assertLit`, `waitForDisabled` -
rather than checking straight after a tap. Several of these tests first passed
or failed by timing alone, before they waited properly.

"Unlit" can be true of the state being replaced as well as of the new one, so
check for something only the new state has first.

## A regression test has to fail without its fix

Every regression test here was checked by putting the bug back and seeing the
test fail. Two tests written for this app passed against the code they were
meant to catch, and only that check showed it:

- `WallRouteOrderTest` was first written against an in-memory DAO that never
  suspends, so the race it was for could not happen in it.
- `SchemaMigrationTest` cannot catch an entity edited without a version bump at
  all, because the build rewrites the schema it reads. That one is caught in CI
  instead, by the committed schema having changed.

So when adding one: break the code, watch the test fail, then fix it.

Every row in the table below was checked that way, by putting the bug back
into the code and running the test meant to catch it. A negative check needs
particular care - "nothing was sent" asserted the moment something becomes
true can finish before the thing it is guarding against arrives. The test for
opening the app taking over the wall checks the order of what was sent
instead, and only that version caught it.

## Which test guards which past bug

| Bug | Caught by |
|---|---|
| Tablet routes button did nothing | `TabletLayoutTest` - the routes button puts the list away |
| Save then New left the blank wall titled with the saved name (#66) | `UnsavedChangesTest`, `WallRouteOrderTest` |
| Setup from the practice wall had an empty address (#63) | `PracticeWallTest` - offers the address already saved |
| Setup was a one-way door from the practice wall (#63) | `PracticeWallTest` - setup opened from it can be left again |
| Work offline offered while out of reach (#58) | `OfflineTest` - not offered while out of reach |
| Long press worked offline while out of reach (#58) | `OfflineTest` - a long press while out of reach |
| Renaming the same route twice closed the field (#60) | `RouteLifecycleTest`, `RouteTitleTest` |
| In-place rename showed the name from before the last rename (#53) | `RouteLifecycleTest`, `RouteTitleTest` |
| Rename dialog showed another route's name | `RouteLifecycleTest` checks each dialog shows its own route - but **cannot catch the key being removed**: every dialog leaves the composition when it closes, which discards its saved state, so no path in the current UI reaches the old bug. The key is defensive |
| Saving at the unsaved prompt duplicated an open route | `UnsavedChangesTest` - saves it in place |
| Deleting the open route left it on the wall | `RouteLifecycleTest` - clears the wall |
| Delete message promised the wall stays lit, for the route on it | `RouteLifecycleTest` - says the wall will be cleared |
| Opening the app pushed to the wall, taking it over | `PersistenceTest` - does not take over the wall |
| Working offline did not survive a restart | `OfflineTest` - survives the app being closed |
| A database this build cannot read, restored from a backup | **Not covered.** The recovery and its tests were in #64, which was closed |
| An entity edited without a schema version bump | CI's "Schema matches the one committed" step |
