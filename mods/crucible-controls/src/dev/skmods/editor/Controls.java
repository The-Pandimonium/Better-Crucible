package dev.skmods.editor;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.KeyStroke;

/**
 * The editor's controls as data: what can be bound, what the profiles bind, and which profile is in use. Two profiles are built in and cannot be
 * changed or removed:
 * <ul>
 * <li><b>Legacy</b>: the editor's own controls with its "modern controls" preference off (Ctrl for the camera), and its menu shortcuts, untouched (the mod does nothing under it);</li>
 * <li><b>Modern</b>: the editor's own controls with that preference on (Alt for the camera, Ctrl + left click erases), untouched likewise;</li>
 * <li><b>New</b>: the scheme of this mod (click to use the tool, Ctrl + right click to erase, the camera on the middle button and the arrow keys ...).</li>
 * </ul>
 * Anyone can make profiles of their own (copies of the one in use) and bind every action. Profiles are kept in the user's preferences.
 *
 * <p>Two kinds of control are bound here: the editor's <i>actions</i> of this mod (the camera, the tool buttons, the stamp), each with up to two
 * bindings (a key with its modifiers, a mouse button with its modifiers, a wheel with its modifiers), and the <i>menu commands</i> of the editor
 * (Undo, Copy, Raise Grid ...), each with its menu shortcut.
 *
 * <p>When several bindings fit an event, the one that asks for the most modifiers wins: with Left = use the tool and Ctrl + Left = turn the
 * camera, a left click with Ctrl held turns the camera.
 */
public final class Controls {
    private Controls() {
    }

    public static final int MASK = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;

    public enum Kind {
        KEY, MOUSE, WHEEL
    }

    /** Everything of the mod that can be bound. */
    public enum Act {
        USE_TOOL("Mouse", "Use the tool (click, hold to draw a stroke)", Kind.MOUSE),
        ERASE("Mouse", "Erase / the secondary action of the tool", Kind.MOUSE),
        SUSPEND_MOUSE("Mouse", "Hide what you hold so nothing is placed (hold the button)", Kind.MOUSE),
        SUSPEND_KEY("Mouse", "Hide what you hold so nothing is placed (hold the key)", Kind.KEY),
        TURN_CAMERA("Camera", "Turn the camera where it stands (drag)", Kind.MOUSE),
        ORBIT_CAMERA("Camera", "Swing the camera around what it looks at (drag)", Kind.MOUSE),
        PAN_CAMERA("Camera", "Slide the camera sideways (drag)", Kind.MOUSE),
        ZOOM_DRAG("Camera", "Zoom in and out (drag up and down)", Kind.MOUSE),
        ZOOM("Camera", "Zoom in and out (wheel)", Kind.WHEEL),
        MOVE_FORWARD("Camera", "Move the camera forward", Kind.KEY),
        MOVE_BACK("Camera", "Move the camera back", Kind.KEY),
        MOVE_LEFT("Camera", "Move the camera left", Kind.KEY),
        MOVE_RIGHT("Camera", "Move the camera right", Kind.KEY),
        MOVE_FAST("Camera", "Move faster (hold)", Kind.KEY),
        TURN_LEFT("Camera", "Turn the camera left", Kind.KEY),
        TURN_RIGHT("Camera", "Turn the camera right", Kind.KEY),
        TURN_UP("Camera", "Turn the camera up", Kind.KEY),
        TURN_DOWN("Camera", "Turn the camera down", Kind.KEY),
        ORBIT_LEFT("Camera", "Swing the camera round to the left", Kind.KEY),
        ORBIT_RIGHT("Camera", "Swing the camera round to the right", Kind.KEY),
        ORBIT_UP("Camera", "Swing the camera up around what it looks at", Kind.KEY),
        ORBIT_DOWN("Camera", "Swing the camera down around what it looks at", Kind.KEY),
        GRID_WHEEL("Placing", "Raise and lower the grid (wheel)", Kind.WHEEL),
        STAMP_WHEEL("Placing", "Rotate what you are placing (wheel)", Kind.WHEEL),
        STAMP_WHEEL_FINE("Placing", "Rotate what you are placing by 5 degrees (wheel)", Kind.WHEEL),
        STAMP_LEFT("Placing", "Rotate what you are placing, back", Kind.KEY),
        STAMP_RIGHT("Placing", "Rotate what you are placing, forward", Kind.KEY),
        STAMP_LEFT_FINE("Placing", "Rotate what you are placing by 5 degrees, back", Kind.KEY),
        STAMP_RIGHT_FINE("Placing", "Rotate what you are placing by 5 degrees, forward", Kind.KEY);

        public final String group, title;
        public final Kind kind;

        Act(String group, String title, Kind kind) {
            this.group = group;
            this.title = title;
            this.kind = kind;
        }
    }

    /** One way of triggering an action: a key, a mouse button or the wheel, with the modifiers (Shift, Ctrl, Alt, Meta) that must be held. */
    public static final class Binding {
        public final Kind kind;
        /** The key code, or the mouse button (1 left, 2 middle, 3 right); unused for the wheel. */
        public final int code;
        public final int mods;

        public Binding(Kind kind, int code, int mods) {
            this.kind = kind;
            this.code = kind == Kind.WHEEL ? 0 : code;
            this.mods = mods & MASK;
        }

        public static Binding key(int code, int mods) {
            return new Binding(Kind.KEY, code, mods);
        }

        public static Binding mouse(int button, int mods) {
            return new Binding(Kind.MOUSE, button, mods);
        }

        public static Binding wheel(int mods) {
            return new Binding(Kind.WHEEL, 0, mods);
        }

        public String text() {
            String m = InputEvent.getModifiersExText(mods);
            String what;
            switch (kind) {
                case KEY: what = KeyEvent.getKeyText(code); break;
                case MOUSE: what = (code == 1 ? "Left" : code == 2 ? "Middle" : code == 3 ? "Right" : "Button " + code) + " button"; break;
                default: what = "Wheel"; break;
            }
            return m.isEmpty() ? what : m + "+" + what;
        }

        String store() {
            return kind.name().charAt(0) + ":" + code + ":" + mods;
        }

        static Binding load(String s) {
            try {
                String[] p = s.split(":");
                Kind k = p[0].equals("K") ? Kind.KEY : p[0].equals("M") ? Kind.MOUSE : Kind.WHEEL;
                return new Binding(k, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Binding && ((Binding) o).kind == kind && ((Binding) o).code == code && ((Binding) o).mods == mods;
        }

        @Override
        public int hashCode() {
            return kind.hashCode() * 31 + code * 7 + mods;
        }
    }

    /** A named set of bindings. */
    public static final class Profile {
        public String name;

        @Override
        public String toString() {
            return name;
        }

        public final boolean builtIn;
        /** False for Legacy and Modern: the mod's actions are off and the editor behaves as it always did. */
        public final boolean active;
        public final Map<Act, List<Binding>> binds = new EnumMap<>(Act.class);
        /** The menu shortcuts that differ from the editor's own, by menu path ("Edit/Copy"); a null value is "no shortcut". */
        public final Map<String, KeyStroke> accel = new LinkedHashMap<>();

        Profile(String name, boolean builtIn, boolean active) {
            this.name = name;
            this.builtIn = builtIn;
            this.active = active;
        }

        public List<Binding> of(Act a) {
            return binds.computeIfAbsent(a, k -> new ArrayList<>());
        }

        Profile copy(String as) {
            Profile p = new Profile(as, false, true);
            for (Map.Entry<Act, List<Binding>> e : binds.entrySet()) p.binds.put(e.getKey(), new ArrayList<>(e.getValue()));
            p.accel.putAll(accel);
            return p;
        }
    }

    private static final List<Profile> PROFILES = new ArrayList<>();
    private static Profile current;
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static boolean loaded;

    /** The built-in profile of the editor's own controls. */
    static Profile legacy() {
        Profile p = new Profile("Legacy", true, false);
        // what the editor itself does (tudey.tools.h and its camera controller): left and right button are the tool's, the camera is Ctrl + a button, Alt + wheel is the grid
        p.of(Act.USE_TOOL).add(Binding.mouse(1, 0));
        p.of(Act.ERASE).add(Binding.mouse(3, 0));
        p.of(Act.SUSPEND_KEY).add(Binding.key(KeyEvent.VK_ALT, 0));
        p.of(Act.ORBIT_CAMERA).add(Binding.mouse(1, InputEvent.CTRL_DOWN_MASK));
        p.of(Act.ZOOM_DRAG).add(Binding.mouse(2, InputEvent.CTRL_DOWN_MASK));
        p.of(Act.PAN_CAMERA).add(Binding.mouse(3, InputEvent.CTRL_DOWN_MASK));
        p.of(Act.ZOOM).add(Binding.wheel(InputEvent.CTRL_DOWN_MASK));
        p.of(Act.GRID_WHEEL).add(Binding.wheel(InputEvent.ALT_DOWN_MASK));
        p.of(Act.STAMP_WHEEL).add(Binding.wheel(0));
        p.of(Act.STAMP_WHEEL_FINE).add(Binding.wheel(InputEvent.SHIFT_DOWN_MASK));
        return p;
    }

    /** The built-in profile of the editor's "modern controls": the camera on Alt instead of Ctrl, Ctrl + left click erases, Meta hides what is held. */
    static Profile modern() {
        Profile p = new Profile("Modern", true, false);
        p.of(Act.USE_TOOL).add(Binding.mouse(1, 0));
        p.of(Act.ERASE).add(Binding.mouse(1, InputEvent.CTRL_DOWN_MASK));
        p.of(Act.SUSPEND_KEY).add(Binding.key(KeyEvent.VK_META, 0));
        p.of(Act.ORBIT_CAMERA).add(Binding.mouse(1, InputEvent.ALT_DOWN_MASK));
        p.of(Act.ZOOM_DRAG).add(Binding.mouse(2, InputEvent.ALT_DOWN_MASK));
        p.of(Act.PAN_CAMERA).add(Binding.mouse(3, InputEvent.ALT_DOWN_MASK));
        p.of(Act.ZOOM).add(Binding.wheel(InputEvent.ALT_DOWN_MASK));
        p.of(Act.GRID_WHEEL).add(Binding.wheel(InputEvent.META_DOWN_MASK));
        p.of(Act.STAMP_WHEEL).add(Binding.wheel(0));
        p.of(Act.STAMP_WHEEL_FINE).add(Binding.wheel(InputEvent.SHIFT_DOWN_MASK));
        return p;
    }

    /** The built-in profile of this mod's scheme. */
    static Profile fresh() {
        Profile p = new Profile("New", true, true);
        p.of(Act.USE_TOOL).add(Binding.mouse(1, 0));
        p.of(Act.ERASE).add(Binding.mouse(3, InputEvent.CTRL_DOWN_MASK));
        p.of(Act.SUSPEND_MOUSE).add(Binding.mouse(3, 0));
        p.of(Act.TURN_CAMERA).add(Binding.mouse(1, InputEvent.CTRL_DOWN_MASK));
        p.of(Act.TURN_CAMERA).add(Binding.mouse(2, 0));
        p.of(Act.ORBIT_CAMERA).add(Binding.mouse(2, InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.ORBIT_CAMERA).add(Binding.mouse(1, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.ZOOM).add(Binding.wheel(0));
        p.of(Act.MOVE_FORWARD).add(Binding.key(KeyEvent.VK_W, 0));
        p.of(Act.MOVE_BACK).add(Binding.key(KeyEvent.VK_S, 0));
        p.of(Act.MOVE_LEFT).add(Binding.key(KeyEvent.VK_A, 0));
        p.of(Act.MOVE_RIGHT).add(Binding.key(KeyEvent.VK_D, 0));
        p.of(Act.MOVE_FAST).add(Binding.key(KeyEvent.VK_SHIFT, 0));
        p.of(Act.TURN_LEFT).add(Binding.key(KeyEvent.VK_LEFT, 0));
        p.of(Act.TURN_RIGHT).add(Binding.key(KeyEvent.VK_RIGHT, 0));
        p.of(Act.TURN_UP).add(Binding.key(KeyEvent.VK_UP, 0));
        p.of(Act.TURN_DOWN).add(Binding.key(KeyEvent.VK_DOWN, 0));
        p.of(Act.ORBIT_RIGHT).add(Binding.key(KeyEvent.VK_Z, 0));
        p.of(Act.ORBIT_LEFT).add(Binding.key(KeyEvent.VK_C, 0));
        p.of(Act.ORBIT_LEFT).add(Binding.key(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.ORBIT_RIGHT).add(Binding.key(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.ORBIT_UP).add(Binding.key(KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.ORBIT_DOWN).add(Binding.key(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.STAMP_WHEEL).add(Binding.wheel(InputEvent.ALT_DOWN_MASK));
        p.of(Act.STAMP_WHEEL_FINE).add(Binding.wheel(InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.STAMP_LEFT).add(Binding.key(KeyEvent.VK_Q, 0));
        p.of(Act.STAMP_RIGHT).add(Binding.key(KeyEvent.VK_E, 0));
        p.of(Act.STAMP_LEFT_FINE).add(Binding.key(KeyEvent.VK_Q, InputEvent.SHIFT_DOWN_MASK));
        p.of(Act.STAMP_RIGHT_FINE).add(Binding.key(KeyEvent.VK_E, InputEvent.SHIFT_DOWN_MASK));
        // the arrow keys are the camera's now: the grid goes up and down on the page keys (the selection keeps the editor's Ctrl + Up / Down)
        p.accel.put("View/Raise Grid", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0));
        p.accel.put("View/Lower Grid", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0));
        return p;
    }

    private static final String NODE = "dev/skmods/crucible-controls";

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        PROFILES.add(legacy());
        PROFILES.add(modern());
        PROFILES.add(fresh());
        String active = "New";
        try {
            Preferences root = ModConfig.settings();
            active = root.get("active", "New");
            if (active.equals("Old")) active = "Legacy"; // what Legacy was called
            Preferences profiles = root.node("profiles");
            for (String name : profiles.childrenNames()) {
                if (name.equals("Old") || name.equals("New") || name.equals("Legacy") || name.equals("Modern")) continue;
                Preferences n = profiles.node(name);
                Profile p = new Profile(n.get("name", name), false, true);
                for (Act a : Act.values()) {
                    String s = n.get("a." + a.name(), null);
                    if (s == null) continue;
                    List<Binding> list = p.of(a);
                    for (String one : s.split(";")) {
                        Binding b = one.isEmpty() ? null : Binding.load(one);
                        if (b != null && b.kind == a.kind) list.add(b);
                    }
                }
                for (String key : n.keys()) {
                    if (!key.startsWith("m.")) continue;
                    String v = n.get(key, "none");
                    p.accel.put(n.get("p." + key.substring(2), key.substring(2)), v.equals("none") ? null : KeyStroke.getKeyStroke(v));
                }
                PROFILES.add(p);
            }
        } catch (BackingStoreException | RuntimeException e) {
            System.err.println("[editor] the saved control profiles could not be read: " + e);
        }
        current = byName(active);
        if (current == null) current = byName("New");
    }

    private static Profile byName(String name) {
        for (Profile p : PROFILES) if (p.name.equals(name)) return p;
        return null;
    }

    static synchronized void save() {
        try {
            Preferences root = ModConfig.settings();
            root.put("active", current.name);
            Preferences profiles = root.node("profiles");
            for (String old : profiles.childrenNames()) profiles.node(old).removeNode();
            for (Profile p : PROFILES) {
                if (p.builtIn) continue;
                Preferences n = profiles.node(key(p.name));
                n.put("name", p.name);
                for (Act a : Act.values()) {
                    StringBuilder sb = new StringBuilder();
                    for (Binding b : p.of(a)) sb.append(sb.length() > 0 ? ";" : "").append(b.store());
                    n.put("a." + a.name(), sb.toString());
                }
                int i = 0;
                for (Map.Entry<String, KeyStroke> e : p.accel.entrySet()) {
                    n.put("m." + i, e.getValue() == null ? "none" : e.getValue().toString());
                    n.put("p." + i, e.getKey());
                    i++;
                }
            }
            root.flush();
        } catch (BackingStoreException | RuntimeException e) {
            System.err.println("[editor] the control profiles could not be saved: " + e);
        }
    }

    private static String key(String name) {
        return name.replace('/', '_');
    }

    // ---- the profiles ----

    public static synchronized List<Profile> profiles() {
        ensureLoaded();
        return new ArrayList<>(PROFILES);
    }

    /** True under the built-in Modern profile: the editor is told its "modern controls" preference is on. */
    public static boolean isModern() {
        Profile p = current();
        return p.builtIn && p.name.equals("Modern");
    }

    public static synchronized Profile current() {
        ensureLoaded();
        return current;
    }

    public static synchronized void use(Profile p) {
        ensureLoaded();
        if (p == null || !PROFILES.contains(p)) return;
        current = p;
        save();
        changed();
    }

    /** A new profile of its own, a copy of {@code from}; null when the name is taken or empty. */
    public static synchronized Profile add(String name, Profile from) {
        ensureLoaded();
        name = name == null ? "" : name.trim().replace('/', '_');
        if (name.isEmpty() || byName(name) != null) return null;
        Profile p = from.copy(name);
        PROFILES.add(p);
        save();
        return p;
    }

    public static synchronized boolean remove(Profile p) {
        ensureLoaded();
        if (p.builtIn || !PROFILES.remove(p)) return false;
        if (current == p) current = byName("New");
        save();
        changed();
        return true;
    }

    public static synchronized boolean rename(Profile p, String name) {
        ensureLoaded();
        name = name == null ? "" : name.trim().replace('/', '_');
        if (p.builtIn || name.isEmpty() || byName(name) != null) return false;
        p.name = name;
        save();
        return true;
    }

    /** Something about the profile in use changed (its choice, or one of its bindings): the editor takes it up. */
    public static void changed() {
        List<Runnable> copy;
        synchronized (Controls.class) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Runnable r : copy) r.run();
    }

    public static synchronized void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    // ---- what an event means ----

    private static int normMods(int mods, int keyCode) {
        mods &= MASK;
        if (keyCode == KeyEvent.VK_SHIFT) mods &= ~InputEvent.SHIFT_DOWN_MASK;
        else if (keyCode == KeyEvent.VK_CONTROL) mods &= ~InputEvent.CTRL_DOWN_MASK;
        else if (keyCode == KeyEvent.VK_ALT) mods &= ~InputEvent.ALT_DOWN_MASK;
        else if (keyCode == KeyEvent.VK_META) mods &= ~InputEvent.META_DOWN_MASK;
        return mods;
    }

    /** The action bound to this key press, if any: of the bindings whose modifiers are all held, the one that asks for the most. */
    public static Act keyAct(KeyEvent e) {
        Profile p = current();
        if (!p.active) return null;
        int have = normMods(e.getModifiersEx(), e.getKeyCode());
        Act best = null;
        int bestN = -1;
        for (Act a : Act.values()) {
            if (a.kind != Kind.KEY) continue;
            for (Binding b : p.of(a)) {
                if (b.code != e.getKeyCode() || (b.mods & have) != b.mods) continue;
                if (((have & ~b.mods) & ~InputEvent.SHIFT_DOWN_MASK) != 0) continue; // Ctrl, Alt and Meta must match: Ctrl+Z is not "Z"
                int n = Integer.bitCount(b.mods);
                if (n > bestN) {
                    best = a;
                    bestN = n;
                }
            }
        }
        return best;
    }

    /** The action a mouse button pressed (or clicked, or dragged) with these modifiers means. */
    public static Act mouseAct(int button, int modifiersEx) {
        Profile p = current();
        if (!p.active) return null;
        int have = modifiersEx & MASK;
        Act best = null;
        int bestN = -1;
        for (Act a : Act.values()) {
            if (a.kind != Kind.MOUSE) continue;
            for (Binding b : p.of(a)) {
                if (b.code != button || (b.mods & have) != b.mods) continue;
                int n = Integer.bitCount(b.mods);
                if (n > bestN) {
                    best = a;
                    bestN = n;
                }
            }
        }
        return best;
    }

    public static Act mouseAct(MouseEvent e) {
        return mouseAct(e.getButton(), e.getModifiersEx());
    }

    public static Act wheelAct(MouseWheelEvent e) {
        Profile p = current();
        if (!p.active) return null;
        int have = e.getModifiersEx() & MASK;
        Act best = null;
        int bestN = -1;
        for (Act a : Act.values()) {
            if (a.kind != Kind.WHEEL) continue;
            for (Binding b : p.of(a)) {
                if ((b.mods & have) != b.mods) continue;
                int n = Integer.bitCount(b.mods);
                if (n > bestN) {
                    best = a;
                    bestN = n;
                }
            }
        }
        return best;
    }

    /** A short text of what an action is bound to in a profile ("Ctrl+Left button, Middle button"). */
    public static String describe(Profile p, Act a) {
        StringBuilder sb = new StringBuilder();
        for (Binding b : p.of(a)) sb.append(sb.length() > 0 ? ", " : "").append(b.text());
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
