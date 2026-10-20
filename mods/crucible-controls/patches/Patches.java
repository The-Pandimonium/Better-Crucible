import dev.skmods.modapi.*;

/**
 * The Crucible editor keeps being the game's own, with one change: it extends the mod's editor (the editor with assignable controls), and its
 * preferences extend the mod's, which leave out the checkbox for the editor's own "modern controls" (the Controls menu chooses now).
 */
public final class Patches implements ModPatches {
    @Override
    public void apply(Patcher p) throws Exception {
        p.retargetSuper("com.threerings.projectx.tools.CrucibleSceneEditor", "dev.skmods.editor.ControlledEditor");
        p.retargetSuper("com.threerings.projectx.tools.CrucibleSceneEditor$SceneEditorPrefsImpl", "com.threerings.tudey.tools.ControlledPrefs");
    }
}
