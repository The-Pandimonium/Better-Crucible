package dev.skmods.editor;

import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.tools.Notepad;
import com.threerings.tudey.tools.SceneEditor;
import javax.swing.event.DocumentEvent;

/**
 * The editor's Notes tool, without the block of camera marks (see {@link MarksNotes}): what is shown is only what the designer wrote, and whatever is
 * typed is saved in the scene with the block put back after it, so the marks stay in the scene file.
 */
final class HiddenMarksNotepad extends Notepad {
    HiddenMarksNotepad(SceneEditor editor) {
        super(editor);
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        save();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        save();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        save();
    }

    private void save() {
        if (_scene == null) return;
        String shown = _area.getText();
        _scene.setNotes(MarksNotes.merge(shown, _scene.getNotes()));
    }

    @Override
    public void sceneChanged(TudeySceneModel scene) {
        super.sceneChanged(scene); // fills the text with the notes, block and all (the block is kept by save)
        String notes = scene.getNotes();
        String shown = MarksNotes.strip(notes);
        if (!shown.equals(_area.getText())) _area.setText(shown);
        _area.setCaretPosition(0);
    }
}
