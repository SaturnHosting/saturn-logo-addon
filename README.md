# Saturn Logo Addon

Builds vertical schematics with *speed*.

Vertical Builder loads `.litematic`, Sponge `.schem`, and legacy `.schematic`
files from the Minecraft game directory's `schematics/` folder.

## Development

Requirements:

- Java 21
- Minecraft 1.21.11
- Fabric Loader 0.18.2
- a compatible Meteor Client 1.21.11 build

Build the distributable addon JAR with:

```shell
./gradlew clean build
```

The resulting JAR is generated under `build/libs/`. At runtime, place it in the
game's `mods/` folder alongside Fabric Loader and the compatible Meteor Client
build. Compatibility with other Minecraft versions has not been tested.
