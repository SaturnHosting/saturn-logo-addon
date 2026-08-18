# Saturn Logo Builder

Saturn Logo Builder is a client-side [Meteor Client](https://meteorclient.com/) addon for constructing tall, mostly vertical Minecraft schematics. It loads a schematic from the game's `schematics` directory, maps it to a world origin, selects the required blocks from your hotbar or offhand, and works from the bottom of the structure upward.

The addon is intended for large wall art, lettering, and server logos. Its placement tracker is latency-aware: a position that has just been clicked is kept pending until the client has had time to receive the server's block update, which reduces duplicate placements on laggy connections.

> [!IMPORTANT]
> Automation and air placement may be prohibited by a server's rules or anti-cheat. Use this addon only where you have permission.

## Features

- Loads multi-region Litematica `.litematic` files and Sponge palette-based `.schem` files.
- Builds in ascending Y order, making it suitable for tall vertical designs.
- Places multiple blocks per tick with a configurable delay and retry limit.
- Optionally travels between unfinished positions using Meteor's ElytraFly.
- Draws configurable ghost boxes around blocks that are still missing.
- Tracks sent placements as pending so the same coordinate is not immediately clicked again.
- Waits for client/server state to settle, then rescans the complete schematic and retries only positions that are still missing.
- Restores the ElytraFly settings it temporarily changed when the builder is disabled.

## Compatibility

The current source tree targets the following versions:

| Component | Version |
| --- | --- |
| Minecraft | 26.1.x (built against 26.1.2) |
| Meteor Client | 26.1.2-SNAPSHOT |
| Fabric Loader | 0.19.2 |
| Java | 25 or newer |

Minecraft and Meteor internals change frequently. Use a build made for your exact Minecraft release instead of assuming that a jar for another release will work.

## Installation

1. Install Fabric Loader and a compatible Meteor Client build for Minecraft 26.1.x.
2. Download the addon jar from the repository's [Releases page](https://github.com/SaturnHosting/saturn-logo-addon/releases), or [build it from source](#building-from-source).
3. Put the addon jar in the same game profile's `mods` directory as Meteor Client.
4. Start Minecraft and confirm that the **Saturn** category appears in Meteor's Modules screen.

Fabric API is not a direct dependency of this project. If another installed mod requires it, follow that mod's installation instructions separately.

## Quick start

1. Put a supported schematic in `<game directory>/schematics/`. The addon creates this directory automatically when needed.
2. Join the world where the structure should be built.
3. Open Meteor's Modules screen and select **Saturn → Vertical Builder**.
4. Choose the file under **Schematic**.
5. Set **X**, **Y**, and **Z** to the world coordinates where the schematic origin should be mapped. For a typical logo, **Y** is the bottom of the design.
6. Put every required block type in your hotbar or offhand. The builder does not pull materials from the main inventory.
7. If **Auto Fly** is enabled, equip an elytra and make sure there is enough safe space in front of the wall.
8. Enable **Vertical Builder**. Watch the rendered ghost boxes and Meteor chat messages for progress, missing materials, occupied positions, or completion.

Resetting the coordinate settings uses the player's current block position as the reset value. Changing the schematic or its origin clears the current placement progress and starts a fresh scan.

## Placement and latency handling

Minecraft placement requests are asynchronous: a client can send a click before the corresponding block update comes back from the server. Repeating that click too early is especially dangerous with air placement because the second request may place a block above the intended coordinate.

Vertical Builder handles this with a small state machine:

1. A coordinate is eligible only when it is missing, replaceable, within reach (or reachable by auto-flight), and its item is available.
2. After a placement request is sent successfully, the coordinate becomes **pending** and cannot be sent again during the same placement pass.
3. When no other currently attemptable coordinate remains, the builder waits for world updates to settle.
4. It then scans every schematic coordinate against the client world's latest block state.
5. Correct blocks are confirmed, still-missing pending blocks are released for another pass, and occupied mismatches are marked failed.
6. A position is abandoned after the configured number of placement passes, allowing the rest of the schematic to finish.

The settlement time is based on the latency reported by the Minecraft client:

```text
wait = clamp(2 × ping + 100 ms, 500 ms, 5000 ms)
```

This greatly reduces ordinary latency-related duplicates, but it is not a server acknowledgement protocol. A severely overloaded server can process a request later than both its reported ping and the five-second ceiling. For the safest behavior, disable **Air Place** whenever the structure can be built against existing support blocks.

## Settings reference

### General

| Setting | Default | Description |
| --- | ---: | --- |
| `schematic` | None | File loaded from the game directory's `schematics` folder. |
| `x` | `0` | World X coordinate to which the schematic origin is mapped. |
| `y` | `64` | World Y coordinate to which the schematic origin/bottom is mapped. |
| `z` | `0` | World Z coordinate to which the schematic origin is mapped. |

### Placing

| Setting | Default | Description |
| --- | ---: | --- |
| `air-place` | On | Sends a synthetic interaction at otherwise unsupported target positions. Disable it on servers that reject air placement or when support faces are available. |
| `range` | `4.5` | Maximum eye-to-block distance for a placement attempt. |
| `blocks-per-tick` | `1` | Maximum number of placement requests sent in one tick. Higher values are faster but more likely to trigger server limits. |
| `delay` | `0` ticks | Pause after a non-empty placement batch. |
| `max-retries` | `8` passes | Number of sent placement passes allowed for a position that remains missing. |

### Flight

| Setting | Default | Description |
| --- | ---: | --- |
| `auto-fly` | On | Enables Meteor ElytraFly and travels toward the nearest attemptable missing block. Requires an equipped elytra. |
| `standoff` | `2` blocks | Distance maintained in front of the wall. Keep this lower than `range`. |

### Render

| Setting | Default | Description |
| --- | ---: | --- |
| `render` | On | Renders ghost boxes for missing schematic blocks. |
| `shape-mode` | Both | Selects filled sides, outlines, or both. |
| `side-color` | Light blue | Fill color and opacity of ghost boxes. |
| `line-color` | Light blue | Outline color and opacity of ghost boxes. |

Rendering is capped at 4,000 missing blocks per frame to keep very large schematics manageable.

## Supported schematic files

| Format | Support | Notes |
| --- | --- | --- |
| Litematica `.litematic` | Supported | Reads all regions, palettes, block states, region positions, and negative region sizes. Air entries are ignored. |
| Sponge `.schem` | Supported | Reads palette-based Sponge data stored in `Data` or `BlockData`. |
| `.schematic` | Limited | The extension is accepted only when the file uses a Sponge-compatible palette/data layout. Classic MCEdit numeric-ID schematics are not supported. |

The builder is designed primarily for full-block wall art. Although palette block-state properties are read, directional or interactive blocks may not acquire the desired state from a normal player placement. Entities, block-entity data, scheduled ticks, biomes, and schematic air are not placed.

## Troubleshooting

### The schematic does not appear in the list

- Confirm that the file is in the active game's `schematics` directory, not the `mods` directory.
- Use `.litematic` or `.schem`. A file with the legacy `.schematic` extension must actually contain Sponge-compatible data.
- Reopen the setting list after copying the file.
- Check the Minecraft log for `Failed to load` and the parser error that follows it.

### The module loads but places nothing

- Put the exact required block items in the hotbar or offhand.
- Move within the configured `range`, or enable **Auto Fly** with an elytra equipped.
- Verify that the target positions are air or replaceable. The builder will not overwrite solid blocks.
- If **Air Place** is off, each target needs a valid neighboring support face.
- Confirm that the X/Y/Z origin maps the schematic to the location you intended.

### Auto-flight does not start

- Equip an elytra in the chest slot.
- Make sure **Auto Fly** is enabled and that at least one missing block is otherwise attemptable.
- Keep **Standoff** below **Range**.
- Allow enough open space around the logo; the addon steers ElytraFly but does not provide obstacle avoidance.

### The builder keeps waiting or revisiting positions

The builder can only retry blocks whose items are available and whose positions can be reached and placed. Missing materials, unsupported targets with Air Place disabled, or unreachable blocks can leave work outstanding. Read the Meteor chat warnings, restock or correct the obstruction, and reactivate the module if necessary.

### Extra blocks still appear during extreme lag

Increase `delay`, reduce `blocks-per-tick`, and avoid Air Place when possible. The pending/settlement mechanism uses client-observed world updates and reported ping; it cannot prove that a heavily delayed server has permanently rejected an earlier request.

## Building from source

Requirements:

- A Java 25 JDK.
- Git. A system Gradle installation is not required because the repository includes the Gradle wrapper.

Clone and build:

```bash
git clone https://github.com/SaturnHosting/saturn-logo-addon.git
cd saturn-logo-addon
./gradlew clean build
```

On Windows, use `gradlew.bat clean build` instead. The compiled jar is written to `build/libs/`; with the current project properties it is named `addon-template-0.1.0.jar`.

The project has no automated test source set at present, so `./gradlew build` primarily verifies resource processing, Java compilation, remapping, and jar creation.

## Project structure

```text
src/main/java/lat/saturn/addon/
├── LogoBuilder.java              Meteor addon entry point and category registration
└── modules/
    ├── VerticalBuilder.java      Placement, flight, rendering, and retry state machine
    └── Schematic.java            Litematica and Sponge schematic reader
```

Build versions are centralized in `gradle/libs.versions.toml`, while addon metadata and compatibility declarations live in `src/main/resources/fabric.mod.json`.

## Contributing

Bug reports should include the Minecraft and Meteor versions, schematic format, relevant settings, reported ping, and the corresponding Minecraft log excerpt. Pull requests should run `./gradlew clean build` before submission and keep behavior compatible with the versions listed above.

## License

This project is released under [CC0 1.0 Universal](LICENSE). See the license file for the full terms and disclaimer.
