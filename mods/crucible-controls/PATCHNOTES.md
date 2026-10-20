# Crucible Editor Controls: patch notes since 1.13.0

## 1.23.0
- **This is the editor alone.** The mod now lives in this repository, without the server emulator it was made next to: the Test menu (Test in emulator, Stop the game, Play inside the editor), the test server the mod carried, and the prefabs and item-equipping change that only the test used are gone. Everything else is as it was in 1.22.0: the controls, the Tools menu and every tool.

The notes below are from before the move; the ones about testing in the emulator describe what the project it came from still does.

## 1.22.0
- **"Test in emulator" works wherever the game is installed, and on an install that was never played.** It needs nothing else on the computer: the server runs on the game's own Java (before, it looked for a full JDK 25 in a few folders of drive C: and stopped without one). Its database is kept in memory through the SQLite library of the system (Windows 10 and later have one, `winsqlite3.dll`) instead of the JDBC driver, which the game's Java has no module for. A new install has no scene cache either (the game fills it as it is played): the room the knight starts in, the camera and the light of the scene are made in code when there is none to copy.
- **The game's folder is found from the game's own properties and class path**, on any drive (a folder with `code/projectx-pcode.jar`), not from a fixed place; when it cannot be found the message says what was looked at.
- **Settings and the test server's files are in the mod's folder of Knight Launcher**: `<game>/mods/config/crucible-editor-controls/settings.properties` (the controls profiles, the tool layout, the toggles of the menus: a file you can read, copy or delete) and `.../test-server/` (the logs of the server and the client, the scenes sent). Nothing is kept in the registry any more; what an earlier version left there is taken over once.

## 1.21.0
- **One-time buttons work** (all the kinds: plain, multi, monster, whitespace, rocket-block control): a knight stepping on one sends its signal and the button stays down; a one-time pressure plate stays down when the knight steps off. Before, only timed buttons and ordinary pressure plates did anything.
- **Any item can be equipped in a test started from the editor**, in every match mode (the client the editor starts answers yes to the game's own "equip any item" check). A client started any other way asks as the game does.
- **The server in the mod is a trimmed one.** "Test in emulator" needs the game's scene machinery (logins, scenes, combat, monsters, the match modes), and that is what the mod carries; guilds, auctions, the market, mails, missions, dialogs, progress, the console and the dev tools are emptied out of it or left out, and so are the server's own commands for them.
- **The Striker's shield works:** holding it drives the knight forward (the game's own push, made to go two to three times as fast as walking) and uses up the shield's time (3500 ms of use; a blow costs 500; it breaks when empty and refills after a pause).
- **Team bases from your own file** (`prefab/lockdown/base.dat`, made in the editor): the one base is used for both teams; the server turns the whole prefab over for the other side and sets the Team Id of its team force field in code, so the one file is right for both. Knights start at its `spawn` marker, else the respawn pad, else the middle of its floor.
- **The other team cannot enter a base:** a team force field (the game's own barrier) shuts the doorway to every knight but the base's own team, as the game's collision rule for the arena's knights says.
- **Gear can be changed at any time** in a Lockdown match: the knight is given the game's PvP equip rule while it is in the match (the loadout menu of the client reads it), and the class pads work in any state of the match.
- **The control point's coloured ring** grows until it covers the whole outer ring (its size is the ring's diameter, 6, not 1), and the area that counts for the capture is that ring (radius 3, to the edge of a knight's body) instead of the 5 of the config.
- **The editor takes no input at all while a test is on, wherever the focus is:** the editor's own key handler (which sees the keys before any other), its camera, its tools and its mouse handler are shut, not only the keys of a later handler.

## 1.20.1
- Every name of the game's code the mod reaches by reflection (the camera, the selection box, the server's movement environment) is now written readable and turned into the game's by the build (`dev.skmods.emu.Game`), so a game update cannot leave a stale one behind.

## 1.20.0
- **Nothing of the editor's scene view takes input from the moment Test in emulator is clicked** (not only once the game is up): the view is disabled, no tool is active and the editor's keys are taken, until the game is gone or the test could not start.
- **No "test server is ready" box:** a message appears only when something is wrong; when it goes well the game is the answer.
- **The login screen is skipped:** the game is started with a name (your user name) and a password, the test server makes the account.
- **Prefabs** (the `prefab` folder, `prefab/README.md`): a scene stitched onto a level at its `000_Connectors` tiles. Lockdown levels get a start area for each team on their west and east connectors: a room with the three class pads (Guardian, Recon, Striker; standing on one changes the knight's shield class) and the place the team's knights start. The team start areas are in the mod and are read by the test server.

## 1.19.2
- **Fixed: knights walked through every actor** (doors, force fields, props that are actors) in a Lockdown match, and could not attack. The knight's own movement code (the game's advancer) needs an environment that also answers as the arena's; the test server's did not, so it fell back to a plain advancer that knows only the tiles. The knight now moves with the game's own advancer in all three modes (Lockdown, Super Brawl, Blast Network), so actors stop it and weapons work as in the game.
- **The editor does nothing while the game is up:** its tool is deactivated and every key of the editor's window is taken, so nothing flies the camera or changes the scene by accident; both come back when the game closes.
- **A knight in a match always has a weapon on** (a knight made for the test had every item but wore nothing, and the client lets a knight attack only with one in hand): Blast Network's own weapon, else the first sword.
- The focus is given to the game only when it is not already in it, and the activation is sent once, so the game's own windows (the Spiral Uplink and the like) are not sent a stream of activations.

## 1.19.1
- **The game inside the editor takes the keyboard.** The game's window is a Java window, and Java hands keys only to a window it counts as active; a window made the child of another process's never was, so the mouse worked and the keys did nothing. The window is now told it is active and its canvas gets the focus (the editor shares the game's input queue to do it), and again whenever the pointer is over the game. Tried with a real key press into a stand-in game window.
- **The panel at the right (tools, layers) folds away while the game is open** and comes back when the game closes or Test > Stop the game is used.
- **Lockdown knights can act:** the match's auto-targeting flag, which the game's own client reads to let a Lockdown knight move and attack, is set (it was off).
- The match knight uses the dungeon knight model, and the teams are named "Team 1", "Team 2" from the client's own texts.

## 1.19.0
- **Coliseum levels play as matches.** A level of Lockdown, Super Brawl or Blast Network (the mode the editor is set to, or the camera of the level) is run by the test server as the game's own match: the knight is put in a team (Lockdown has two, the others a team of one each), wears the match's actor (`Character/PC/PvP/...`), the countdown runs and the match starts, everything tagged `basegate` hears `open`, and a Lockdown control point fills its ring while a knight stands on it, takes the team's colour, tells its tag `capture:TEAM` (and `capture:-1` when lost) and adds to the team's score. The knight wears the Guardian class (the class pads change it). The test knight is given every item.
- **Fixed: a signal sent by tag reached a door twice** (once through the prop, once through the door it started), so `toggle` undid itself: the doors tagged `_flipdoor` never changed. Props and the actors they started now hear a signal once.
- **Play inside the editor (Windows):** the game's window is put over the scene view under the ribbon (Test > Play inside the editor, on by default) and follows the editor when it is resized; Test > Stop the game closes it. If that cannot be done the game keeps a window of its own and the message says why.

## 1.18.0
- **Test menu (next to Tools): Test in emulator.** Plays the scene being edited (hidden layers left out, the scene file itself untouched). The mod carries its own cut-down server (the emulator's classes and the SQLite driver are packed into it): the first time, it starts the server as a process of its own on a full JDK 25 (the game's own Java is trimmed and has no `java.sql`; the mod looks at `-Dsk.java`, `JAVA_HOME`, the path and the usual install folders), with nothing saved, and opens a second game window whose settings point at it (the installed game is not changed). Log in there with any name and the scene starts, with monsters, behaviors, damage, traps and loot as in the game's own runs; the next Test sends the new version of the scene to the knight already in it. The editor saves a scene with the generic Tudey scene type, and the client then draws it with its bare Tudey view (black): the test gives it the dungeon's own, which makes the client use its dungeon camera, HUD and environment. A scene with no camera of the dungeon's kind, no light or no background (an example for a game mode, a first sketch) is shown with the tutorial level's, so it is not black. Both processes stop with the editor. Their logs and files are in `~/.sk-emu/embedded` (`server-log.txt`, `client-log.txt`). No emulator needs to be set up or running.

## 1.17.8
- **Replace:** step 2 ("With this") is now the editor's own configuration picker, the tree with its filter as in the Place and Tiles tools, and it shows the kind of thing chosen in step 1 (props, tiles, actors, areas and every other kind).

## 1.17.7
- **Replace:** the two lists now say why they are empty ("First choose what to replace in step 1", "There is no other configuration of this kind", "The scene has nothing that can be replaced").

## 1.17.6
- **Measure:** the numbers take their direction from the camera's own rotation, so they stay complete and readable when the camera looks straight down (before, most strokes of each digit collapsed).

## 1.17.5
- **Measure:** the numbers are billboards: they stand up facing the camera and turn with it, so it is always clear which way to read them.

## 1.17.4
- **Fixed the visual artifact** seen while a ruler or highlight was drawn: the last line colour stayed active and tinted the rest of the scene (grass and ground turned brown or blue at some angles). The colour is now restored after the lines are drawn.
- **Measure:** the numbers are white, on both sides of the ruler, and centred on their ticks.

## 1.17.3
- **Measure:** numbers along the ruler (the distance from the start at each labelled tick, and the total length past the end).
- **Measure:** the start is green and the end is red; the line and the ruler change colour between them.

## 1.17.2
- **Measure:** right click now sets the end point. (The right button also turns the camera, so the point is set when the button is released without the mouse having moved.)

## 1.17.1
- **Selection outline removed.** The editor's own white outline is used for everything. The menu bar still counts what is selected.

## 1.17.0
- **"How it works" in every tool:** the editor's own tools (Select, Region, Move, Copy, Re-Layer, Palette, Notes, Tiles, Ground, Wall, Place, Areas, Paths, Globals, Eraser), Wire and Errors now have the collapsible explanation too. One open/closed setting covers all of them.
- All the explanations were rewritten in short, plain sentences.
- **Tools menu:** it can be opened again after it has been closed (the Controls menu had the same fault and is fixed too).
- **Notes tool:** the camera marks stored in the scene's notes are hidden. They stay in the scene file, and typing in Notes never loses them.
- **Fixed:** the explanations no longer stretch over the whole tool panel, and a tool with an unusual layout no longer stops the editor from opening.

## 1.16.0
- **Tools menu** (next to Controls): choose which tools the tool bar shows and in what order. By default it shows Select, Region, Move, Copy, Wire, Tiles, Ground, Wall, Place, Areas, Globals, Eraser, Errors and Find. The empty slot in the tool bar is gone.
- **Selection count** in the menu bar. (The coloured outline added with it was later removed, see 1.17.1.)
- **Find:** much faster on large scenes (the search runs in the background).
- **Tags:** tags are split by the kind of thing that has them and the kind that sends to them. Selecting opens the Select tool, not the Region.
- **Signals the server sends** (capture:-1, 0, 1) are known, so they are not reported as missing. The list can be edited in the Tools window.
- **Lint:** props the game does not draw (markers, triggers, notes) are no longer reported as being over no floor. The false camera error is gone.
- **Marks:** the camera marks are saved in the scene's notes, so they travel with the scene file and it still uploads as before. The tool shows where the camera is now.
- **Measure:** left click sets the start and right click the end. A ruler runs along the line, with a tick at every tile, longer at every 5 and longest at every 10.
- **Replace** redesigned in three steps: what to replace, what to replace it with, and where. What would change is outlined first, with a count.
- **"How it works" explanation** in each new tool, collapsible, with one setting for all of them.

## 1.14.0 – 1.15.0
- **Updated for the latest Spiral Knights version** (pxVersion 20261015001102).
- **Wire tool** moved to the empty spot in the tool bar, next to Errors, and **fixed so that areas draw their ray line**.
- **Seven new tools:** Find, Tags, Lint, Stats, Marks, Measure and Replace.
- **Rebuilt on the deobfuscated game code:** the mod is written with readable names and turned back to the obfuscated ones when it is built, and it can be built from IntelliJ.
