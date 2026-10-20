package dev.skmods.editor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Which tools the tool bar shows, and in what order (the Tools menu changes it). It is kept for the next time the editor is started. A tool is known by
 * its name in the editor ("arrow" is Select, "selector" is Region, "tile_brush" is Tiles ...); a tool this does not know (a later version added one) is
 * put last and is hidden until it is chosen.
 */
final class ToolLayout {
    private ToolLayout() {
    }

    private static final String NODE = "dev/skmods/crucible-controls";

    /** The order of the tools by default: the ones that are shown first, then the others. */
    static final List<String> DEFAULT_ORDER = List.of("arrow", "selector", "mover", "eyedropper", "wire", "tile_brush", "ground_brush", "wall_brush", "placer", "area_definer", "global_editor", "eraser", "errors", "find",
        "layer_changer", "palette", "notepad", "path_definer", "tags", "lint", "stats", "marks", "measure", "replace");
    /** The tools that are shown by default. */
    static final Set<String> DEFAULT_VISIBLE = Set.of("arrow", "selector", "mover", "eyedropper", "wire", "tile_brush", "ground_brush", "wall_brush", "placer", "area_definer", "global_editor", "eraser", "errors", "find");

    private static List<String> order;
    private static Set<String> visible;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private static synchronized void load() {
        if (order != null) return;
        order = new ArrayList<>();
        visible = new LinkedHashSet<>();
        try {
            Preferences n = ModConfig.settings();
            String o = n.get("toolOrder", null), v = n.get("toolVisible", null);
            if (o != null) for (String s : o.split(",")) if (!s.isBlank() && !order.contains(s.trim())) order.add(s.trim());
            if (v != null) for (String s : v.split(",")) if (!s.isBlank()) visible.add(s.trim());
            if (o == null || v == null) {
                order.clear();
                visible.clear();
                order.addAll(DEFAULT_ORDER);
                visible.addAll(DEFAULT_VISIBLE);
            }
        } catch (RuntimeException e) {
            order.clear();
            visible.clear();
            order.addAll(DEFAULT_ORDER);
            visible.addAll(DEFAULT_VISIBLE);
        }
    }

    /** The order of all the tools there are (the given ones, in the order saved, others last). */
    static synchronized List<String> order(List<String> known) {
        load();
        List<String> out = new ArrayList<>();
        for (String s : order) if (known.contains(s)) out.add(s);
        for (String s : DEFAULT_ORDER) if (known.contains(s) && !out.contains(s)) out.add(s);
        for (String s : known) if (!out.contains(s)) out.add(s);
        return out;
    }

    static synchronized boolean visible(String tool) {
        load();
        return visible.contains(tool);
    }

    /** Sets the order and which are shown; Select is always shown (it is the tool the editor starts in). */
    static synchronized void set(List<String> nextOrder, Set<String> nextVisible) {
        load();
        order = new ArrayList<>(nextOrder);
        visible = new LinkedHashSet<>(nextVisible);
        visible.add("arrow");
        save();
        notifyListeners();
    }

    static synchronized void reset() {
        load();
        order = new ArrayList<>(DEFAULT_ORDER);
        visible = new LinkedHashSet<>(DEFAULT_VISIBLE);
        save();
        notifyListeners();
    }

    private static void save() {
        try {
            Preferences n = ModConfig.settings();
            n.put("toolOrder", String.join(",", order));
            n.put("toolVisible", String.join(",", visible));
        } catch (RuntimeException e) {
            // kept for this session
        }
    }

    static synchronized void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void notifyListeners() {
        for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
    }
}
