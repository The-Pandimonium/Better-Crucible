# Better-Crucible

**Crucible Editor Controls**: a [Knight Launcher](https://github.com/lucasluqui/KnightLauncher) mod for Spiral Knights' Crucible scene editor. The editor with simpler, assignable controls and a set of tools for building scenes.

* **Controls**: WASD to move, arrow keys, Ctrl + left drag or the middle button to turn the camera, the wheel to zoom, Ctrl + right click to delete what is under the cursor, hold the right button to hide the held piece so nothing is placed, Q/E and Alt + wheel to rotate the piece (Shift for 5 degrees), Page Up/Down to raise and lower the grid. The **Controls** menu assigns every control, with profiles (Legacy and Modern, the editor's own two schemes; New; and your own).
* **Tools** (the **Tools** menu chooses which the tool bar shows, and in what order): Wire (connect anything that sends a signal to anything with a tag, one connection at a time), Errors (the validation errors with Move Camera buttons), Find, Tags (rename a tag everywhere), Lint (signals to nowhere, stacked props, empty shapes), Stats, Marks (camera bookmarks), Measure, Replace. Every tool has a collapsible explanation. A Path button (View > Path Buttons) on every property of the config editors copies its path.

[`mods/crucible-controls/PATCHNOTES.md`](mods/crucible-controls/PATCHNOTES.md) lists what changed in each version.

## Install

Put `sk-crucible-controls.zip` (built as below) in `<Spiral Knights>/mods/`, open the launcher and enable "Crucible Editor Controls". The settings are a file in the folder the launcher makes for the mod, `<Spiral Knights>/mods/config/crucible-editor-controls/settings.properties`. The mod is made for one build of the game (`pxVersion` in `mod.json`); after a game update it has to be built again, see below.

## Build

Needs a JDK 25, the game installed (`SK_GAME_DIR`, or `game.dir` in `local.properties`, or the Steam folder).

```
./sk setup                    copies the game's jars, makes the deobfuscated jar with the readable names
./sk mod crucible-controls    work/dist/sk-crucible-controls.zip
```

The code is written with the readable names of [`mappings/`](mappings/projectx-pcode.mapping) and the build turns them back into the game's own ([docs/mappings.md](docs/mappings.md)). After a game update:

```
./sk setup
./sk mappings update
./sk mappings check
./sk mod crucible-controls
```

`mods/crucible-controls/patches/Patches.java` is the whole of what the mod changes in the game's own classes: the game's editor class is made to extend the mod's.

GPL-3.0, see [LICENSE](LICENSE).
