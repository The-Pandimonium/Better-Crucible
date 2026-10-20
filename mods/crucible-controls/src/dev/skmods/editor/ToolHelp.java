package dev.skmods.editor;

import java.util.HashMap;
import java.util.Map;

/** The explanation shown at the top of the editor's own tools and of Wire and Errors (the other tools carry theirs). */
final class ToolHelp {
    private ToolHelp() {
    }

    private static final Map<String, String> TEXTS = new HashMap<>();

    static {
        String sel = "The menu bar shows how many things are selected.";
        TEXTS.put("arrow", "Click a thing to select it and edit its settings. Drag a selected thing to move it. " + sel);
        TEXTS.put("selector", "Drag a rectangle to select everything inside it. " + sel);
        TEXTS.put("mover", "Click a thing, or the selection, to pick it up. Move the mouse and click to put it down. The mouse wheel rotates what you carry (hold Shift for small steps).");
        TEXTS.put("eyedropper", "Click a thing to carry a copy of it, then click to put copies down. The mouse wheel rotates what you carry (hold Shift for small steps).");
        TEXTS.put("layer_changer", "Choose the target layer, then use <b>Move</b> or <b>Copy</b> to send the selected things to it. You can also click a single thing to send it.");
        TEXTS.put("palette", "Keeps the configurations you have picked so the other tools can reuse them quickly.");
        TEXTS.put("notepad", "Free text saved with the scene. The camera marks from the Marks tool are stored here too, but they are hidden so they cannot be edited by accident.");
        TEXTS.put("tile_brush", "Pick a tile from the list, then click or drag in the scene to paint it. The mouse wheel rotates the tile.");
        TEXTS.put("ground_brush", "Pick a ground from the list, then click or drag in the scene to paint it.");
        TEXTS.put("wall_brush", "Pick a wall from the list, then click or drag in the scene to build it.");
        TEXTS.put("placer", "Pick a prop or other object from the list, then click in the scene to place it. The mouse wheel rotates it (hold Shift for small steps).");
        TEXTS.put("area_definer", "Click points in the scene to outline an area, then finish it. Spawners, triggers and cameras use areas.");
        TEXTS.put("path_definer", "Click points in the scene to lay out a path for things to follow.");
        TEXTS.put("global_editor", "Lists the scene's global settings, which are not placed anywhere in the scene. You can add, edit or remove them.");
        TEXTS.put("eraser", "Click a thing to erase it, or drag to erase everything the pointer passes over. Ctrl+Z undoes it.");
        TEXTS.put("wire", "Shows how signals travel. Click a prop to see lines in <b>orange</b> to everything it sends to and everything that sends to it. Choose two things to connect or disconnect them; each change is one undo step.");
        TEXTS.put("errors", "Checks the scene the same way saving does and lists the problems. Click a line to move the camera to the problem, which is marked in <b>red</b>.");
    }

    /** The explanation for the tool of this name, or null for a tool that has none (or carries its own). */
    static String of(String name) {
        return TEXTS.get(name);
    }
}
