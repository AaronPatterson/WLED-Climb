# Getting the app onto a device

Builds are published as signed APKs on [GitHub releases](https://github.com/AaronPatterson/WLED-Climb/releases). Obtainium watches that page and offers each new release as an update, so a device only has to be set up once.

## Why not the Play Store

The app targets children, and declaring a child audience on Play pulls in the Families policy programme — content rating, data safety disclosures, a privacy policy URL. That's a lot of compliance paperwork for something that turns on LEDs in a garage. Sideloading to a handful of family devices avoids all of it.

It also sidesteps an unknown: nothing in Google's documentation confirms whether a Family Link supervised account can join a Play testing track, and that would have been discovered only after paying for a developer account and filling in the forms.

## One-time setup per device

### 1. Allow installing apps outside the Play Store

**On a supervised (child) device**, the parent does this in Family Link:

> Family Link → Signed-in devices → *[device]* → **Apps from unknown sources** → on

**On your own device**, Android prompts the first time an app tries to install another, and you grant it there.

Note that `adb install` needs none of this — it runs as the shell user and skips the installer UI entirely. That remains the fallback if anything here gets awkward.

### 2. Install Obtainium

From [Obtainium's releases](https://github.com/ImranR98/Obtainium/releases), take **`app-arm64-v8a-release.apk`**.

- **arm64-v8a** is right for any phone or tablet made in roughly the last decade. `app-release.apk` is a universal build that also works, just larger.
- **Not the `-fdroid-` variant.** It installs as a different package (`dev.imranr.obtainium.fdroid`) and self-updates from F-Droid instead of GitHub.

Worth verifying before installing, since this app will go on to install everything else:

```
apksigner verify --print-certs app-arm64-v8a-release.apk
```

The certificate SHA-256 should be `B3:53:60:1F:6A:1D:5F:D6:60:3A:E2:F5:0B:E8:0C:F3:01:36:7B:86:B6:AB:8B:1F:66:24:3D:A9:6C:D5:73:62`, published in Obtainium's own README.

### 3. Add the app

In Obtainium, add:

```
https://github.com/AaronPatterson/WLED-Climb
```

It picks up the latest release and offers every future one. Android will ask permission for Obtainium to install packages the first time.

## If it asks you to uninstall first

That means the installed build was signed with a different key — almost certainly an old debug build from before release signing existed. Uninstall it and let Obtainium install cleanly. **This wipes app data**, so the controller address has to be entered again.

Every device makes this transition at most once. Afterwards, releases update in place with nothing lost.

## Devices this does not work on

**Pinwheel phones.** Sideloading is blocked at the platform level rather than at the install step — an APK copied onto the device does not open, so Obtainium cannot run there either. The curated app library is a vetted set that a bespoke app will never join.

The only supported route is enabling the Google Play Store for that device in the Caregiver Portal and publishing the app to Play, which means a developer account, the Families policy programme for a child audience, and an unresolved question about whether a supervised account can join a testing track. That is a lot of machinery for one device, and it partly undoes the reason for having a Pinwheel.

Use a shared tablet for those users instead.

## Test builds don't interfere

Debug builds install as `com.wledclimb.app.debug`, a separate app that sits alongside the real one. Obtainium tracks `com.wledclimb.app` and cannot see it. See [releasing.md](releasing.md).

## Still worth doing

Register a **limited distribution account** on the Android Developer Console — free, no government ID, up to 20 devices. Google's developer-verification requirement reached its first countries on 30 September 2026 and expands globally during 2027; after that, apps from unregistered developers need extra steps to install. Registering keeps sideloading to family devices as frictionless as it is today.
