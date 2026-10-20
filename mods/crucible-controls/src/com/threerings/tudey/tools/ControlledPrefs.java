package com.threerings.tudey.tools;

/**
 * The Crucible editor's preferences without the editor's own "modern controls" checkbox: which controls are in use is chosen in the Controls
 * menu now (the profiles Legacy and Modern are that checkbox's two states). The two methods are overridden without the annotation the editor's
 * preference window looks for, so the property is not listed; the game's class {@code CrucibleSceneEditor$a} is rebuilt on this one.
 */
public class ControlledPrefs extends SceneEditor.SceneEditorPrefs {
    public ControlledPrefs(SceneEditor editor, java.util.prefs.Preferences prefs) {
        editor.super(prefs);
    }

    @Override
    public void setNewKeyBindings(boolean on) {
        // chosen by the profile, not here
    }

    @Override
    public boolean getNewKeyBindings() {
        return dev.skmods.editor.Controls.isModern();
    }
}
