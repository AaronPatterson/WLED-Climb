# WLED Climb — Requirements

Original project brief, kept here for reference alongside `design.md`.

I would like to build an app to control a climbing wall that has LED lights. The wall uses a WLED controller for the lights. I would like the app to have the following capabilities by priority:

- **P0:** Connect to WLED Controller and configure the app (see Configuration section for details)
- **P0:** Ability to turn all of the lights on the wall on and off (on/off switch for the wall).
- **P0:** Create climbing route by toggling lights for a climbing hold on and off with the ability to choose the color for the light.
- **P0:** Ability to save the climbing route locally on the device so it can be applied to the wall at a later time.
- **P0:** Ability to apply a previously created climbing route to the wall.
- **P1:** Ability to save the climbing route to WLED as a preset.
- **P2:** Ability to take a picture of the climbing wall and overlay that on the UI used for creating climbing routes. (See Wall Visualization)
- **P2:** Ability to toggle between light modes on the wall: All Lights, Holds Only, No Holds Only.
- **P5:** Ability to share the climbing route to a server for use by others.

## Target user

- Kids (6+)

## Configuration

- Point the app at the WLED controller IP/DNS name or integrate with Bluetooth.
- App should extract information about the 2D grid configured in WLED controller to understand what the wall layout is.
- App should pull in the gap file from WLED to understand where climbing holds are located.
- Choose from common t-nut layout spacings (1ft grid, 8in grid, 6in grid, offset grid, ...).
  - Can give a visual representation to help with the choice.

## Climbing route creation

- UI should give a visual representation of the wall layout.
- Allow turning on/off LED lights where climbing holds are located.
- Allow changing the color of the LED where climbing holds are located and the LED lights are turned on.
- Should remember the previous color selection when turning on the next light and apply the same color.

## Saving a climbing route

- Save the configuration for the route locally on the device in a way that it can be applied to the WLED controller at a later time.
- Need to be able to name the route.
- Need to be able to put notes or a description on the route.
- Would be nice to have a preview or picture associated with the route so it is easily identifiable in the UI.
- Routes must be movable off a device, so a reinstall or a new phone does not lose them. See [walls-and-routes.md](walls-and-routes.md#moving-routes-between-installs).

## Wall visualization

- Take a picture of the climbing wall and overlay that on the UI used for route creation.
- The picture should be croppable to the relevant section showing only the wall.
  - Could possibly do the cropping automatically, but that would be secondary.
- User should be able to touch the holds in the picture to toggle on/off the LED light for the hold.
- Hold spacing will need to be identified automatically from the picture.
  - Climbing walls are usually laid out in grid patterns.
  - There may not be a hold in every position of the grid, but there could be.

## Constraints

- Use existing WLED capabilities and WLED extensions as much as possible without needing to modify them.
  - If big enough limitations are hit, consider developing a specialized WLED extension for our needs.
