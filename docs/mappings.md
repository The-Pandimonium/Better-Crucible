# Readable names for the Spiral Knights code jar

`projectx-pcode.jar` is obfuscated: about half of its classes, fields and methods are called `a`, `Kq`, `aTs` and so on. `mappings/` holds an
[Enigma](https://github.com/FabricMC/Enigma) mapping that gives them names you can read, and the tools to keep it current.

| file | what |
|---|---|
| `mappings/projectx-pcode.mapping` | **the mapping** (Enigma single-file format): everything below merged |
| `mappings/mapping.properties` | which build of the game the mapping fits: version and hashes of the two code jars |
| `mappings/personal/` | the hand-made names (Enigma directory format) |
| `mappings/source-libraries.mapping`, `source-revisions.txt` | names from the open-source libraries alone, and the revisions of those libraries they were read at |
| `mappings/reports/` | how many names, on what evidence, and the clashes that were settled |
| `tools/mappings/` | `SourceMatch` (library names, and carrying names over from the previous build), `Mapper` (merges, guesses the rest), `Verify`, `Link` |
| `work/deobf/` (not committed) | `game-deobf.jar` and the decompiled `src/`, made from the game's files |

## Where the names come from

Layers; where two give a name to the same thing, the later wins (carry < personal < source).

1. **Carried over** from the previous mapping, by matching each class of the old jar to the new one structurally (only during `update`).
2. **Personal** (`mappings/personal/`): names found by hand.
3. **The open-source libraries.** The jar bundles samskivert, Clyde, Narya and Nenya, obfuscated. `SourceMatch` reads their sources and matches each
   class to a source class of the same package by the shape of its members, round after round; names and parameter names come from the source.
4. **Guessed** from the code (`Mapper`) for the rest: loaded file names, stored tags, getters/setters, strings, supertypes, field types. These say
   little; check one against what the code does before relying on it. Guessed placeholders (`update3`) are regenerated at each update.

Names of anything that overrides something outside the jar (the JDK, other jars) are never changed, and methods that override each other are
renamed together.

## The deobfuscated jar

```bash
./sk deobf            # work/deobf/game-deobf.jar (merged code jars + config jar, renamed); `./sk deobf force` makes it again
./sk decompile        # work/deobf/src: the game's code as readable source
```

`./sk setup` makes the jar too. It is rebuilt automatically when the game's jars or the mapping change.

## After a game update

```bash
./sk mappings status           # the mapping fits build X, the installed game is build Y
./sk setup                     # copy the new jars
./sk mappings update           # make the mapping fit
./sk mappings check            # compile everything; lists what no longer compiles
./sk all
```

`update` does this:

1. reads the open-source libraries at the revisions in `mappings/source-revisions.txt` (`latest` takes their newest);
2. finds the previous jar (cached in `work/cache/jars` by the last `setup`/`update`, or `previous=PATH`) and carries the old names over to the new one;
3. merges carried, personal and library names, guesses the rest, writes `projectx-pcode.mapping`, `mapping.properties` and the reports;
4. caches the new jars for the next update.

Options: `fresh` (no carry-over: names only from the libraries, personal and guesses), `previous=JAR`, `latest`, `force` (go on although the
mapping already fits the installed build).

Limits: parameter names of carried classes are not carried; a class that changed shape a lot is not matched and falls back to the guessed
name. `check` reports the code that uses names that went away: fix those by renaming in `./sk mappings edit` (Enigma's window on the game's jars
and the mapping) or in the source. Hand-made names that did not carry over can be added to `mappings/personal/`.

## Why an update does not break the code

The game is obfuscated again with every build, so three things keep this project working across updates:

1. **Readable names everywhere.** The code is written with the names of the mapping and the build turns them into the game's. `./sk mappings update` carries the names over to the new jar.
2. **No obfuscated name in a string.** Reflection goes through `dev.skmods.emu.Game` (`Game.cls`, `field`, `method`, `fieldName`, `methodName`) with the *readable* name, for example `Game.field("com.threerings.projectx.dungeon.util.PawnAdvancerImpl#weaponSlot")`. The build (`Obfuscate`) rewrites the text of every such call to the game's own names from the mapping and **fails the build** when a name is missing from the mapping or names several things (add a descriptor: `Class#name(desc)`). A name written as plain text (`"blQ"`) would compile, run, and quietly do the wrong thing after the next update: that is what `Game` is for. Names of configs and data (`mask`, `tag`, ...) are not obfuscated by the game (it saves them by name) and stay plain strings.
3. **Names the code reaches must be names a person gave.** The placeholders the guess makes (`intValue3`, `update`) are not stable across an update, so a member reached through `Game` is named in `mappings/personal/` (for example `weaponSlot` and `drawWeapon` of `PawnAdvancerImpl`), which the update keeps.

### How the old names are found in the new jar

In this order: the shape of the members (kinds, types, order), what classes extend and implement, and **what the code says** (`SourceMatch.matchContents`): the texts and larger numbers a class loads, which JDK/library methods and fields it uses (their names are not obfuscated), and which already matched classes it uses; two classes are the same when these are nearly the same, and the pair is taken only when it is clearly each other's best. Checked on this game's jar with the names known (`SourceMatch --previous OLD NEW OUT REPORT --truth MAPPING`): the code alone, with no shape at all, matched 2767 of the top-level classes with none wrong; after the shape it adds about 470.

### The check after an update

`./sk mappings check` compiles the mods (and, in the project with the server, the server and its tests) against the new names, and then **plays**: a real client logs in to the server and plays a Lockdown match made of the game's own example level (`./sk test smoke`; the same as `JOPTS=-Demu.only=arena ./sk test client`), which fails when a name reached by reflection, or the game's behavior the server copies, is not what it was. Tried with a game whose classes and members were all renamed: the update, the compile and the match all pass.

## Using it by hand

```bash
java -jar lib/enigma-cli-4.0.2+local-all.jar deobfuscate GAME/code/projectx-pcode.jar out.jar mappings/projectx-pcode.mapping
```
