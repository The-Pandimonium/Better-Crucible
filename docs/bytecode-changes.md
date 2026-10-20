# What the mod changes in the game's code (`projectx-pcode.jar`)

The game's own classes are read from `projectx-pcode.jar`, changed, and written into the mod under the game's names; Knight Launcher puts them in place of the game's. This is the complete list for build `20261015001102` (`mappings/mapping.properties`). The changes are made by `mods/crucible-controls/patches/Patches.java` while the mod is built, and nothing else in the game's code is touched.

Names: the game's own (obfuscated) name first, then the readable name of [`mappings/projectx-pcode.mapping`](../mappings/projectx-pcode.mapping), which is the Enigma mapping the whole mod is written against (`CLASS <game name> <readable name>`; a line without a second name keeps the game's name).

## Changed classes

| # | class in the pcode | readable name | change |
|---|---|---|---|
| 1 | `com/threerings/projectx/tools/CrucibleSceneEditor` | `CrucibleSceneEditor` | **superclass** `com/threerings/projectx/tools/h` (`SceneEditorImpl`) becomes `dev/skmods/editor/ControlledEditor`; the constructor's call `h.<init>(String)` becomes `ControlledEditor.<init>(String)` |
| 2 | `com/threerings/projectx/tools/CrucibleSceneEditor$a` | `CrucibleSceneEditor$a` (`SceneEditorPrefsImpl`) | **superclass** `com/threerings/tudey/tools/h$a` (`SceneEditor$SceneEditorPrefs`) becomes `com/threerings/tudey/tools/ControlledPrefs`; the constructor's call `h$a.<init>(h, Preferences)` becomes `ControlledPrefs.<init>(h, Preferences)` |

That is all: two classes, one change each (a new superclass and the constructor call that goes with it). No method body, field or constant of the game is rewritten. Verified by comparing `javap -c -p` of both classes in the pcode and in the built mod: the diff is exactly the two lines of each row above.

## Classes the mod adds

`ControlledEditor` extends the class the editor extended before (`projectx/tools/h`, `SceneEditorImpl`), so everything the editor inherited is still there; it adds the controls and the tools. `ControlledPrefs` (in the game's package `com.threerings.tudey.tools`) extends `SceneEditor$SceneEditorPrefs` and leaves out the editor's own "modern controls" checkbox, because the Controls menu chooses now. The mod's other classes are in `dev/skmods/editor` (and `dev/skmods/emu/Game`, the helper that reaches the game's members by their readable names; the build turns the names into the game's).

## Not changed

The game's `projectx-pcode.jar` and `projectx-config.jar` on disk are never modified. Knight Launcher mounts the mod's classes in `code/config.jar`, ahead of the game's own.

## Reproducing the list

```
./sk setup
./sk mod crucible-controls                      (prints "patched <class> (<before> -> <after> bytes)" for each)
javap -c -p -cp work/game/code/projectx-pcode.jar 'com.threerings.projectx.tools.CrucibleSceneEditor' > before.txt
javap -c -p -cp <the mod's zip unpacked>      'com.threerings.projectx.tools.CrucibleSceneEditor' > after.txt
diff before.txt after.txt
```

The mapping file those names come from is `mappings/projectx-pcode.mapping` (50,000 lines, the whole jar); `./sk mappings update` makes it fit a new build of the game.
