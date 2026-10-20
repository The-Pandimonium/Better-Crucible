package dev.skmods.editor;

import com.threerings.config.ArgumentMap;
import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.config.ManagedConfig;
import com.threerings.config.Parameter;
import com.threerings.config.ParameterizedConfig;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.HandlerConfig;
import com.threerings.tudey.config.TagConfig;
import com.threerings.tudey.config.TargetConfig;
import com.threerings.tudey.data.TudeySceneModel;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Wire tool knows about wiring in a scene, apart from any window.
 *
 * <p>How the game wires things. A thing in the scene (a prop, a tile, an area ...) answers to <b>tags</b>. A signal is sent <i>to a tag</i> and is
 * <i>named</i> ({@code open}); the things with that tag that have a handler for that signal react. Nothing here goes by what a parameter is called:
 * a configuration is <i>read</i> for what it is made of. A signal that is sent is an {@code ActionConfig.Signal} (a name, and a target that is a
 * {@code TargetConfig.Tagged}, or several of them); a signal that is heard is a {@code HandlerConfig.Signal}; a tag is a {@code TagConfig}. The
 * configuration of a thing (and of the actors and props it spawns, through their references) is resolved with the arguments the thing has, and
 * walked for these; then each is traced back to the <i>parameters</i> that set it (a parameter says where it writes, as a path through the
 * configuration), so that it can be changed by writing arguments on the thing, the way the editor does.
 *
 * <p>Changes are never made in place: the methods return a {@link Change}, the entries to add and to put in the scene (copies, changed) and those to
 * remove, and the editor applies it as one undoable step.
 */
final class WireModel {
    private WireModel() {
    }

    static final String MARKER = "Handling Marker";

    // ---- entries ----

    /** True when the entry has a configuration at all (anything in the scene that is made of one). */
    static boolean hasConfig(TudeySceneModel.Entry e) {
        try {
            return e != null && e.getReference() != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static ConfigReference<?> ref(TudeySceneModel.Entry e) {
        return e.getReference();
    }

    @SuppressWarnings("unchecked")
    static Class<? extends ManagedConfig> cls(TudeySceneModel.Entry e) {
        return (Class<? extends ManagedConfig>) e.getReferenceType();
    }

    static TudeySceneModel.PlaceableEntry placeable(TudeySceneModel.Entry e) {
        return e instanceof TudeySceneModel.PlaceableEntry && ((TudeySceneModel.PlaceableEntry) e).placeable != null ? (TudeySceneModel.PlaceableEntry) e : null;
    }

    /** What a thing is called to a person: its config's last name ("Pressure Plate"), with where it is. */
    static String describe(TudeySceneModel.Entry e, ConfigManager cfg) {
        TudeySceneModel.PlaceableEntry p = placeable(e);
        String name;
        if (p != null) name = p.placeable.getName();
        else if (hasConfig(e)) name = ref(e).getName();
        else name = String.valueOf(e);
        int i = name.lastIndexOf('/');
        String shortName = i >= 0 && i < name.length() - 1 ? name.substring(i + 1) : name;
        int slash = name.lastIndexOf('/', i - 1);
        if (i > 0 && slash >= 0 && shortName.matches("(?i)(base|parts|trigger.*|[0-9x ]+)")) shortName = name.substring(slash + 1);
        if (e instanceof TudeySceneModel.TileEntry) shortName += " tile";
        else if (e instanceof TudeySceneModel.AreaEntry) shortName += " area";
        else if (e instanceof TudeySceneModel.PathEntry) shortName += " path";
        try {
            com.threerings.math.Vector2f at = cfg == null ? null : e.getTranslation(cfg);
            if (at != null) return shortName + " at (" + Math.round(at.x) + ", " + Math.round(at.y) + ")";
        } catch (RuntimeException ignored) {
            // no position
        }
        return shortName;
    }

    /** What a thing is, in a word for a tag: its config's last name, or the one before when that is a generic part ("Base", "Trigger 3"). */
    static String nameWord(TudeySceneModel.Entry e) {
        String[] parts = ref(e).getName().split("/");
        String word = parts[parts.length - 1];
        for (int i = parts.length - 1; i > 0 && word.toLowerCase().matches("(base|parts|trigger.*|[0-9x ]+|.*\\(.*\\))"); i--) word = parts[i - 1];
        String t = word.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return t.isEmpty() ? "prop" : t;
    }

    /** An entry's key as a word for a tag (a prop's id, or a tile's place: "17-12"). */
    static String keyWord(TudeySceneModel.Entry e) {
        String t = String.valueOf(e.getKey()).replaceAll("[^0-9a-zA-Z]+", "-").replaceAll("^-|-$", "");
        return t.isEmpty() ? "x" : t;
    }

    /** A value in parentheses ("(Door Tag)", "((On))") is a stand-in the game's configs use for "not set": it is never kept as it is. */
    static boolean isPlaceholder(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("(") && t.endsWith(")");
    }

    static boolean real(String s) {
        return s != null && !s.isEmpty() && !isPlaceholder(s);
    }

    /** The arguments of a reference, created when there are none and {@code create}. */
    static ArgumentMap args(ConfigReference<?> ref, boolean create) {
        ArgumentMap m = ref.getArguments();
        if (m == null && create) {
            m = new ArgumentMap();
            try {
                Field f = ConfigReference.class.getDeclaredField("_arguments");
                f.setAccessible(true);
                f.set(ref, m);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("cannot give the reference arguments: " + ex);
            }
        }
        return m;
    }

    private static Parameter[] params(ManagedConfig c) {
        return c instanceof ParameterizedConfig && ((ParameterizedConfig) c).parameters != null ? ((ParameterizedConfig) c).parameters : new Parameter[0];
    }

    private static String[] pathsOf(Parameter p) {
        return p instanceof Parameter.Direct && ((Parameter.Direct) p).paths != null ? ((Parameter.Direct) p).paths : new String[0];
    }

    // ---- paths into configs ("implementation.handlers[0].action.actor") ----

    private static final Pattern TOKEN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)\\]|\\[\"([^\"]*)\"\\]");

    static Object read(Object root, String path) {
        Object cur = root;
        Matcher m = TOKEN.matcher(path);
        while (m.find() && cur != null) {
            if (m.group(1) != null) {
                Field f = field(cur.getClass(), m.group(1));
                if (f == null) return null;
                try {
                    cur = f.get(cur);
                } catch (IllegalAccessException e) {
                    return null;
                }
            } else if (m.group(2) != null) {
                int i = Integer.parseInt(m.group(2));
                if (cur.getClass().isArray()) cur = i < Array.getLength(cur) ? Array.get(cur, i) : null;
                else if (cur instanceof List) cur = i < ((List<?>) cur).size() ? ((List<?>) cur).get(i) : null;
                else return null;
            } else {
                String key = m.group(3);
                if (cur instanceof ConfigReference) cur = ((ConfigReference<?>) cur).getArguments() == null ? null : ((ConfigReference<?>) cur).getArguments().get(key);
                else if (cur instanceof Map) cur = ((Map<?, ?>) cur).get(key);
                else return null;
            }
        }
        return cur;
    }

    /** A field by the name a path gives it: the configuration files spell names with underscores ("state_actions"), the classes in camel case ("stateActions"). */
    private static Field field(Class<?> c, String name) {
        Field f = fieldExact(c, name);
        if (f != null || name.indexOf('_') < 0) return f;
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : name.toCharArray()) {
            if (ch == '_') up = true;
            else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return fieldExact(c, sb.toString());
    }

    private static Field fieldExact(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // up one
            }
        }
        return null;
    }

    /** A path cut in front of its last step: where the thing is that the parameter writes into, and what it writes (a field, an array element, an argument). */
    private static final class Split {
        String prefix;
        String field;
        String index;
        String key;
    }

    private static Split split(String path) {
        Matcher m = TOKEN.matcher(path);
        int start = -1;
        String g1 = null, g2 = null, g3 = null;
        while (m.find()) {
            start = m.start();
            g1 = m.group(1);
            g2 = m.group(2);
            g3 = m.group(3);
        }
        if (start < 0) return null;
        Split s = new Split();
        String before = path.substring(0, start);
        s.prefix = before.endsWith(".") ? before.substring(0, before.length() - 1) : before;
        if (g1 != null) s.field = g1;
        else if (g2 != null) s.index = "[" + g2 + "]";
        else s.key = g3;
        return s;
    }

    /** The declared type of what a path ends at (null when it ends at an array element or an argument). */
    private static Class<?> fieldType(Object root, String path) {
        Split s = split(path);
        if (s == null || s.field == null) return null;
        Object holder = s.prefix.isEmpty() ? root : read(root, s.prefix);
        if (holder == null) return null;
        Field f = field(holder.getClass(), s.field);
        return f == null ? null : f.getType();
    }

    // ---- a thing's configuration, read for what it is made of ----

    /** One configuration in the chain from a thing's own to the ones it spawns: the reference, the configuration as written, and as resolved with the arguments. */
    static final class Level {
        final ConfigReference<?> ref;
        final ManagedConfig base, resolved;

        Level(ConfigReference<?> ref, ManagedConfig base, ManagedConfig resolved) {
            this.ref = ref;
            this.base = base;
            this.resolved = resolved;
        }
    }

    /** Something found in a configuration, with the chain of configurations it was reached through and the objects it sits inside. */
    static final class Found {
        final Object node;
        final List<Level> chain;
        final List<Object> trail;

        Found(Object node, List<Level> chain, List<Object> trail) {
            this.node = node;
            this.chain = chain;
            this.trail = trail;
        }

        int level() {
            return chain.size() - 1;
        }
    }

    /** What the walk of one configuration found. */
    static final class Scan {
        final List<Level> root = new ArrayList<>();
        final List<Found> sends = new ArrayList<>();
        final List<Found> tags = new ArrayList<>();
        final List<Found> hears = new ArrayList<>();
        final Set<String> intrinsic = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    private static final Map<Object, Scan> SCANS = new HashMap<>();
    private static ConfigManager scansFor;

    private static final class Key {
        final Class<?> cls;
        final ConfigReference<?> ref;

        Key(Class<?> cls, ConfigReference<?> ref) {
            this.cls = cls;
            this.ref = ref;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).cls == cls && ((Key) o).ref.equals(ref);
        }

        @Override
        public int hashCode() {
            return cls.hashCode() * 31 + ref.hashCode();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Level level(ConfigManager cfg, Class<? extends ManagedConfig> cls, ConfigReference<?> ref) {
        try {
            ManagedConfig base = cfg.getConfig((Class) cls, ref.getName());
            ManagedConfig resolved = cfg.getConfig((Class) cls, (ConfigReference) ref);
            return resolved == null ? null : new Level(ref, base, resolved);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The configuration of a thing, walked (and remembered: things made from the same configuration with the same arguments share it). */
    static synchronized Scan scan(TudeySceneModel.Entry e, ConfigManager cfg) {
        if (scansFor != cfg) {
            SCANS.clear();
            scansFor = cfg;
        }
        if (!hasConfig(e)) return new Scan();
        Key key = new Key(cls(e), ref(e));
        Scan s = SCANS.get(key);
        if (s != null) return s;
        s = new Scan();
        Level l0 = level(cfg, cls(e), ref(e));
        if (l0 != null) {
            List<Level> chain = new ArrayList<>();
            chain.add(l0);
            s.root.addAll(chain);
            walk(l0.resolved, chain, new ArrayList<>(), cfg, s, new IdentityHashMap<>(), 0);
        }
        SCANS.put(key, s);
        return s;
    }

    private static final int DEPTH = 16;

    private static void walk(Object o, List<Level> chain, List<Object> trail, ConfigManager cfg, Scan out, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (o == null || depth > DEPTH) return;
        Class<?> c = o.getClass();
        // a trap cycle (the "Cycle" a trap tile is given) says it is a trap, whatever configuration it is made from: its logic answers to these signals
        if (o instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Trap.Cycle) {
            out.intrinsic.add("RESET");
            out.intrinsic.add("DOWN");
            out.intrinsic.add("UP");
            out.intrinsic.add("toggle");
            return;
        }
        if (c.isPrimitive() || o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Enum || o instanceof Character) return;
        if (seen.put(o, Boolean.TRUE) != null) return;
        // signals the game's own logic of a kind of actor answers to, which no handler in the config names
        if (o instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Door) {
            out.intrinsic.add("open");
            out.intrinsic.add("close");
            out.intrinsic.add("toggle");
        } else if (o instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Trap) {
            out.intrinsic.add("RESET");
            out.intrinsic.add("DOWN");
            out.intrinsic.add("UP");
            out.intrinsic.add("toggle");
        }
        if (o instanceof ActionConfig.Signal) out.sends.add(new Found(o, chain, new ArrayList<>(trail)));
        else if (o instanceof TagConfig) out.tags.add(new Found(o, chain, new ArrayList<>(trail)));
        else if (o instanceof HandlerConfig.Signal) out.hears.add(new Found(o, chain, new ArrayList<>(trail)));
        if (c.isArray()) {
            if (c.getComponentType().isPrimitive()) return;
            trail.add(o);
            for (int i = 0; i < Array.getLength(o); i++) walk(Array.get(o, i), chain, trail, cfg, out, seen, depth + 1);
            trail.remove(trail.size() - 1);
            return;
        }
        if (o instanceof Collection) {
            trail.add(o);
            for (Object x : (Collection<?>) o) walk(x, chain, trail, cfg, out, seen, depth + 1);
            trail.remove(trail.size() - 1);
            return;
        }
        if (o instanceof ConfigReference) return;
        String name = c.getName();
        if (!(name.startsWith("com.threerings.tudey") || name.startsWith("com.threerings.projectx") || name.startsWith("com.threerings.config"))) return;
        trail.add(o);
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class || Modifier.isTransient(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof ConfigReference) {
                        Class<? extends ManagedConfig> target = refTarget(f);
                        if (target != null) {
                            Level nl = level(cfg, target, (ConfigReference<?>) v);
                            if (nl != null) {
                                List<Level> deeper = new ArrayList<>(chain);
                                deeper.add(nl);
                                walk(nl.resolved, deeper, new ArrayList<>(), cfg, out, seen, depth + 1);
                            }
                        }
                    } else {
                        walk(v, chain, trail, cfg, out, seen, depth + 1);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // a field that cannot be read or a reference that does not resolve adds nothing
                }
            }
        }
        trail.remove(trail.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ManagedConfig> refTarget(Field f) {
        Type t = f.getGenericType();
        if (t instanceof ParameterizedType) {
            Type[] a = ((ParameterizedType) t).getActualTypeArguments();
            if (a.length == 1 && a[0] instanceof Class && ManagedConfig.class.isAssignableFrom((Class<?>) a[0])) return (Class<? extends ManagedConfig>) a[0];
        }
        return null;
    }

    // ---- tags and signals of a thing ----

    /** The tags a thing answers to: the ones it has in its own right and the ones in its configuration (and the actors it spawns). */
    static List<String> tagsOf(TudeySceneModel.Entry e, ConfigManager cfg) {
        List<String> out = new ArrayList<>();
        if (!hasConfig(e)) return out;
        try {
            String[] own = e.getTags(cfg);
            if (own != null) for (String t : own) if (real(t) && !out.contains(t)) out.add(t);
        } catch (RuntimeException ignored) {
            // no tags of its own
        }
        for (Found f : scan(e, cfg).tags) {
            String[] all = ((TagConfig) f.node).tags;
            if (all != null) for (String t : all) if (real(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** The tag a thing answers to (the first), or null when it has none (or only a stand-in). */
    static String tagOf(TudeySceneModel.Entry e, ConfigManager cfg) {
        List<String> t = tagsOf(e, cfg);
        return t.isEmpty() ? null : t.get(0);
    }

    /** The signals a thing has a handler for, and the ones its kind of logic answers to. */
    static Set<String> signalsOf(TudeySceneModel.Entry e, ConfigManager cfg) {
        Set<String> found = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (!hasConfig(e)) return found;
        Scan s = scan(e, cfg);
        for (Found f : s.hears) found.add(((HandlerConfig.Signal) f.node).name);
        found.addAll(s.intrinsic);
        // handlers the thing carries as an argument (a handling marker's)
        for (Slot slot : slots(e, cfg)) if (slot instanceof HandlerSlot) found.add(((HandlerSlot) slot).signalName(e, cfg));
        found.removeIf(x -> !real(x));
        return found;
    }

    /** True when a thing has anything to do with wiring: a tag (or one to set), a signal it hears or a signal it sends to a tag. */
    static boolean valid(TudeySceneModel.Entry e, ConfigManager cfg) {
        if (!hasConfig(e)) return false;
        try {
            if (!tagsOf(e, cfg).isEmpty()) return true;
            Scan s = scan(e, cfg);
            if (!s.hears.isEmpty() || !s.intrinsic.isEmpty()) return true; // it reacts to a signal
            if (tagControl(e, cfg) != null) return true; // it can be given a tag
            return !slots(e, cfg).isEmpty(); // it sends a signal to a tag
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---- parameters that set a thing: where a value can be written ----

    /** One argument to set, at the level of the chain that owns the parameter. */
    static final class Step {
        final String key;
        final Level level;

        Step(String key, Level level) {
            this.key = key;
            this.level = level;
        }
    }

    /** The arguments to write, from the thing's own down: {@code [key]}, or {@code [Actor, (State 0 Tag)]} when the first is a whole reference and the second a key inside it. */
    static final class Control {
        final List<Step> steps;
        /** True when the value is a whole target ({@code TargetConfig}), not a string. */
        final boolean target;

        Control(List<Step> steps, boolean target) {
            this.steps = steps;
            this.target = target;
        }

        boolean sameAs(Control o) {
            if (o == null || o.steps.size() != steps.size() || o.target != target) return false;
            for (int i = 0; i < steps.size(); i++) if (!steps.get(i).key.equals(o.steps.get(i).key)) return false;
            return true;
        }

        /** Writes the value into the arguments of a copy of the thing. */
        void write(TudeySceneModel.Entry copy, Object value) {
            ConfigReference<?> holder = ref(copy);
            for (int i = 0; i < steps.size() - 1; i++) {
                Step s = steps.get(i);
                ArgumentMap a = args(holder, true);
                Object v = a.get(s.key);
                if (!(v instanceof ConfigReference)) {
                    v = defaultRef(s.level, s.key);
                    if (v == null) throw new IllegalStateException("no default for " + s.key);
                    a.put(s.key, v);
                }
                holder = (ConfigReference<?>) v;
            }
            args(holder, true).put(steps.get(steps.size() - 1).key, value);
        }
    }

    /** A copy of the reference a parameter has as its default (without the arguments that only pass the parent's parameters along: "(State 0 Tag)" = "(State 0 Tag)"). */
    private static ConfigReference<?> defaultRef(Level level, String key) {
        for (Parameter p : params(level.base)) {
            if (!key.equals(p.name)) continue;
            for (String path : pathsOf(p)) {
                Object def = read(level.base, path);
                if (!(def instanceof ConfigReference)) continue;
                ConfigReference<?> copy = new ConfigReference<>(((ConfigReference<?>) def).getName());
                ArgumentMap from = ((ConfigReference<?>) def).getArguments();
                if (from != null) {
                    ArgumentMap to = args(copy, true);
                    for (Map.Entry<String, Object> en : from.entrySet()) if (!(en.getValue() instanceof String && isPlaceholder((String) en.getValue()))) to.put(en.getKey(), en.getValue());
                }
                return copy;
            }
        }
        return null;
    }

    /**
     * The parameters that set {@code field} of {@code container} (found in the configuration at chain index {@code at}), as the arguments to write; null
     * when none does (the value is fixed in the configuration). A parameter says where it writes by a path: through the level's own configuration, or
     * into the arguments of a reference to the next level's.
     */
    private static List<Step> trace(List<Level> chain, int from, int at, Object container, String field) {
        if (from >= chain.size()) return null;
        Level level = chain.get(from);
        for (Parameter p : params(level.base)) {
            for (String path : pathsOf(p)) {
                Split s = split(path);
                if (s == null) continue;
                Object holder = s.prefix.isEmpty() ? level.resolved : read(level.resolved, s.prefix);
                if (holder == null) continue;
                if (from == at) {
                    boolean hit = holder == container && ((s.field != null && s.field.equals(field)) || (s.index != null && s.index.equals(field)));
                    if (hit) {
                        List<Step> out = new ArrayList<>();
                        out.add(new Step(p.name, level));
                        return out;
                    }
                } else {
                    // the path may go through more than one level's reference on its way ("placeable[\"Actor\"][\"(Tag)\"]" reaches the reference two levels down)
                    for (int j = from + 1; j <= at; j++) {
                        Object next = chain.get(j).ref;
                        if (s.key != null && sameRef(holder, next)) {
                            // the parameter passes its value on as an argument of that level's reference: that level's parameter of that name does the rest
                            List<Step> sub = trace(chain, j, at, container, field);
                            if (sub != null && sub.get(0).key.equals(s.key)) {
                                // (when that parameter is itself a whole reference, what is written goes into its arguments: the steps below it stay)
                                List<Step> out = new ArrayList<>();
                                out.add(new Step(p.name, level));
                                out.addAll(sub.subList(1, sub.size()));
                                return out;
                            }
                        } else if (s.field != null && sameRef(read(level.resolved, path), next)) {
                            // the parameter is the whole reference: what is written goes into its arguments
                            List<Step> sub = trace(chain, j, at, container, field);
                            if (sub != null) {
                                List<Step> out = new ArrayList<>();
                                out.add(new Step(p.name, level));
                                out.addAll(sub);
                                return out;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The same reference: resolving a configuration copies the references in it, so the one a path reaches and the one the walk found are equal, not identical. */
    private static boolean sameRef(Object a, Object b) {
        return a == b || (a instanceof ConfigReference && b instanceof ConfigReference && a.equals(b));
    }

    private static Control control(Found f, Object container, String field, boolean target) {
        List<Step> steps = trace(f.chain, 0, f.level(), container, field);
        return steps == null ? null : new Control(steps, target);
    }

    /** The control of the tag a thing answers to (its first {@code TagConfig}'s first tag), or null when it has none to set. */
    static Control tagControl(TudeySceneModel.Entry e, ConfigManager cfg) {
        for (Found f : scan(e, cfg).tags) {
            TagConfig t = (TagConfig) f.node;
            if (t.tags == null) continue;
            Control c = control(f, t.tags, "[0]", false);
            if (c != null) return c;
        }
        return null;
    }

    // ---- actions: the signals inside them ----

    static boolean isMarker(TudeySceneModel.Entry e) {
        return hasConfig(e) && MARKER.equals(ref(e).getName());
    }

    /** One thing a connection does: sends this signal to the things with this tag. */
    static final class Hop {
        final String signal, tag;

        Hop(String signal, String tag) {
            this.signal = signal;
            this.tag = tag;
        }

        boolean same(Hop o) {
            return o != null && signal.equalsIgnoreCase(o.signal) && tag.equals(o.tag);
        }
    }

    /** What an action does when it only sends signals to tagged things; null when it does anything else. */
    static List<Hop> hops(ActionConfig a) {
        List<Hop> hops = new ArrayList<>();
        List<ActionConfig> others = new ArrayList<>();
        scan(a, hops, others);
        return others.isEmpty() ? hops : null;
    }

    /** Splits an action into the signals it sends to tags (one hop per tag) and everything else it does. */
    static void scan(ActionConfig a, List<Hop> hops, List<ActionConfig> others) {
        if (a == null) return;
        if (a instanceof ActionConfig.Signal) {
            ActionConfig.Signal s = (ActionConfig.Signal) a;
            List<Hop> mine = new ArrayList<>();
            if (s.name != null && tags(s.target, s.name, mine)) hops.addAll(mine);
            else others.add(a);
        } else if (a instanceof ActionConfig.Compound && !((ActionConfig.Compound) a).stopOnFailure) {
            ActionConfig[] all = ((ActionConfig.Compound) a).actions;
            if (all != null) for (ActionConfig x : all) scan(x, hops, others);
        } else {
            others.add(a);
        }
    }

    private static boolean tags(TargetConfig t, String signal, List<Hop> out) {
        if (t instanceof TargetConfig.Tagged) {
            out.add(new Hop(signal, ((TargetConfig.Tagged) t).tag));
            return true;
        }
        if (t instanceof TargetConfig.Compound) {
            TargetConfig[] all = ((TargetConfig.Compound) t).targets;
            if (all == null) return true;
            for (TargetConfig x : all) if (!tags(x, signal, out)) return false;
            return true;
        }
        return false;
    }

    /**
     * The one action a wrapper action (a delayed one, a conditional one ...) holds: an action with a single field of type {@code ActionConfig}. The
     * Start Up Trigger's action is a delay around the signals, say; the signals are in it, and the delay stays.
     */
    private static Field wrapped(ActionConfig a) {
        if (a == null || a instanceof ActionConfig.Signal || a instanceof ActionConfig.Compound) return null;
        Field found = null;
        for (Class<?> k = a.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !ActionConfig.class.isAssignableFrom(f.getType())) continue;
                if (found != null) return null;
                f.setAccessible(true);
                found = f;
            }
        }
        return found;
    }

    /** Like {@link #scan}, but through the wrappers at the root of the action: what they hold is what is read. */
    static void scanRoot(ActionConfig a, List<Hop> hops, List<ActionConfig> others) {
        Field w = wrapped(a);
        if (w != null) {
            try {
                scanRoot((ActionConfig) w.get(a), hops, others);
                return;
            } catch (IllegalAccessException ignored) {
                // read it as it is
            }
        }
        scan(a, hops, others);
    }

    /** The action with these signals as the ones it sends, and the rest of it as it was: a wrapper at the root stays around what it holds. */
    static ActionConfig rewrite(ActionConfig a, List<Hop> hops) {
        Field w = wrapped(a);
        if (w != null) {
            try {
                ActionConfig inner = (ActionConfig) w.get(a);
                ActionConfig copy = (ActionConfig) a.clone();
                w.set(copy, rewrite(inner, hops));
                return copy;
            } catch (IllegalAccessException ignored) {
                // write it without the wrapper's care
            }
        }
        List<ActionConfig> others = new ArrayList<>();
        scan(a, new ArrayList<>(), others);
        ActionConfig built = build(others, hops);
        return built == null ? emptyAction() : built;
    }

    /** An action that does what {@code others} do and sends these signals (one action per hop, so each can be changed on its own). */
    static ActionConfig build(List<ActionConfig> others, List<Hop> hops) {
        List<ActionConfig> all = new ArrayList<>(others);
        for (Hop h : hops) {
            ActionConfig.Signal s = new ActionConfig.Signal();
            s.name = h.signal;
            TargetConfig.Tagged t = new TargetConfig.Tagged();
            t.tag = h.tag;
            s.target = t;
            all.add(s);
        }
        if (all.size() == 1) return all.get(0);
        ActionConfig.Compound c = new ActionConfig.Compound();
        c.actions = all.toArray(new ActionConfig[0]);
        return c;
    }

    static ActionConfig emptyAction() {
        ActionConfig.Compound c = new ActionConfig.Compound();
        c.actions = new ActionConfig[0];
        return c;
    }

    // ---- change ----

    /** A change to the scene: entries to add, copies of entries with their new values, and the keys of entries to remove. */
    static final class Change {
        final List<TudeySceneModel.Entry> added = new ArrayList<>();
        final List<TudeySceneModel.Entry> updated = new ArrayList<>();
        final List<Object> removed = new ArrayList<>();
        /** What was done, for the status line. */
        final List<String> notes = new ArrayList<>();

        boolean isEmpty() {
            return added.isEmpty() && updated.isEmpty() && removed.isEmpty();
        }
    }

    static TudeySceneModel.Entry copyIn(Change change, TudeySceneModel.Entry original) {
        for (TudeySceneModel.Entry u : change.updated) if (u.getKey().equals(original.getKey())) return u;
        TudeySceneModel.Entry c = (TudeySceneModel.Entry) original.clone();
        change.updated.add(c);
        return c;
    }

    /** Adds the entries and changes of one change to another (the later change wins on the same entry). */
    static void merge(Change into, Change other) {
        into.added.addAll(other.added);
        for (TudeySceneModel.Entry u : other.updated) {
            into.updated.removeIf(x -> x.getKey().equals(u.getKey()));
            into.updated.add(u);
        }
        into.removed.addAll(other.removed);
        into.notes.addAll(other.notes);
    }

    // ---- markers and the paths through them ----

    /** The handling marker that answers to this tag, or null. */
    static TudeySceneModel.Entry markerOf(TudeySceneModel scene, ConfigManager cfg, String tag) {
        if (tag == null || tag.isEmpty()) return null;
        for (TudeySceneModel.Entry en : scene.getEntries()) if (isMarker(en) && tag.equals(tagOf(en, cfg))) return en;
        return null;
    }

    // ---- slots: the places where a thing sends signals ----

    /** One connection of a slot, as shown. */
    static final class Link {
        final Hop hop;
        /** What it goes through (a handling marker, as text), or null when it goes straight to its targets. */
        final String via;
        /** How many other slots send through the same marker handler (changing this connection changes theirs too). */
        final int sharedWith;

        Link(Hop hop, String via, int sharedWith) {
            this.hop = hop;
            this.via = via;
            this.sharedWith = sharedWith;
        }
    }

    /** What a person picks to connect to: a thing (with the tag it answers to) and the signal to send it. */
    static final class Target {
        final TudeySceneModel.Entry entry;
        final String tag, signal;

        Target(TudeySceneModel.Entry entry, String tag, String signal) {
            this.entry = entry;
            this.tag = tag;
            this.signal = signal;
        }

        Hop hop() {
            return new Hop(signal, tag);
        }
    }

    /**
     * A place where a thing sends signals: a signal action whose name and tag parameters can be set, a handler of a handling marker (what it does when
     * it hears a signal, or at start up), the action of a trigger, or a signal fixed in a configuration (shown, not changed). Each has a list of
     * connections that can be added to, changed and removed one by one; {@link #set} writes the whole list back in the way that takes the fewest parts.
     */
    abstract static class Slot {
        final Object ownerKey;
        final String label;

        Slot(Object ownerKey, String label) {
            this.ownerKey = ownerKey;
            this.label = label;
        }

        /** What to call it (a switch's outputs are told apart by name, so two of the same label get a number). */
        String title() {
            return label;
        }

        /** The same slot of the same thing (the label says which). */
        boolean is(Slot o) {
            return o != null && ownerKey.equals(o.ownerKey) && label.equals(o.label);
        }

        abstract List<Link> links(TudeySceneModel scene, ConfigManager cfg);

        /** The signals it sends itself (not through a marker it points at), for finding who else uses a path. */
        abstract List<Hop> sends(TudeySceneModel scene, ConfigManager cfg);

        /** What it does besides sending signals, as text (null when nothing). */
        String other(TudeySceneModel scene, ConfigManager cfg) {
            return null;
        }

        /** Makes the slot send exactly these, in the change. */
        abstract void set(TudeySceneModel scene, ConfigManager cfg, List<Hop> hops, Change change);

        boolean removable() {
            return false;
        }

        /** False for what is fixed in a configuration. */
        boolean editable() {
            return true;
        }

        /** The handling marker the slot's connections go through, or null. */
        TudeySceneModel.Entry relay(TudeySceneModel scene, ConfigManager cfg) {
            return null;
        }

        List<Hop> hops(TudeySceneModel scene, ConfigManager cfg) {
            List<Hop> out = new ArrayList<>();
            for (Link l : links(scene, cfg)) out.add(l.hop);
            return out;
        }

        TudeySceneModel.Entry owner(TudeySceneModel scene) {
            return scene.getEntry(ownerKey);
        }
    }

    /** The slots a thing has. */
    static List<Slot> slots(TudeySceneModel.Entry e, ConfigManager cfg) {
        List<Slot> out = new ArrayList<>();
        if (!hasConfig(e)) return out;
        Scan scan = scan(e, cfg);
        if (scan.root.isEmpty()) return out;
        Level l0 = scan.root.get(0);
        // values the thing's own parameters hold whole: a list of handlers, an action
        List<Object> wholes = new ArrayList<>();
        for (Parameter p : params(l0.base)) {
            String[] paths = pathsOf(p);
            if (paths.length == 0) continue;
            Class<?> type = fieldType(l0.resolved, paths[0]);
            Object arg = ref(e).getArguments() == null ? null : ref(e).getArguments().get(p.name);
            Object value = arg != null ? arg : read(l0.base, paths[0]);
            if ((type != null && HandlerConfig[].class.isAssignableFrom(type)) || value instanceof HandlerConfig[]) {
                HandlerConfig[] all = value instanceof HandlerConfig[] ? (HandlerConfig[]) value : new HandlerConfig[0];
                for (int i = 0; i < all.length; i++) if (all[i] instanceof HandlerConfig.ActionHandlerConfig) out.add(new HandlerSlot(e.getKey(), p.name, i, handlerLabel(all[i])));
                wholes.add(read(l0.resolved, paths[0]));
            } else if ((type != null && ActionConfig.class.isAssignableFrom(type)) || value instanceof ActionConfig) {
                out.add(new ParamSlot(e.getKey(), p.name, value instanceof ActionConfig ? (ActionConfig) value : null));
                wholes.add(read(l0.resolved, paths[0]));
            }
        }
        List<ControlSlot> controlled = new ArrayList<>();
        List<Slot> fixed = new ArrayList<>();
        for (Found f : scan.sends) {
            boolean inside = false;
            for (Object w : wholes) if (w != null && (f.node == w || f.trail.stream().anyMatch(t -> t == w))) inside = true;
            if (inside) continue;
            ActionConfig.Signal s = (ActionConfig.Signal) f.node;
            Control nameCtl = control(f, s, "name", false);
            Control tagCtl = null;
            if (s.target instanceof TargetConfig.Tagged) tagCtl = control(f, s.target, "tag", false);
            if (tagCtl == null) tagCtl = control(f, s, "target", true);
            if (nameCtl != null && tagCtl != null) {
                boolean dup = false;
                for (ControlSlot c : controlled) if (c.nameCtl.sameAs(nameCtl) && c.tagCtl.sameAs(tagCtl)) dup = true;
                if (!dup) controlled.add(new ControlSlot(e.getKey(), s, nameCtl, tagCtl));
            } else {
                FixedSlot fs = new FixedSlot(e.getKey(), s, fixed.size() + 1);
                if (!fs.hops.isEmpty()) fixed.add(fs); // a signal sent to nothing a tag names (the activator, itself) is nothing to wire
            }
        }
        for (ControlSlot c : controlled) for (ControlSlot o : controlled) if (o != c && o.tagCtl.sameAs(c.tagCtl)) c.sharedTag = true;
        // controlled ones first (their labels made unique), then handlers and actions, then what is fixed
        Set<String> labels = new LinkedHashSet<>();
        List<Slot> result = new ArrayList<>();
        for (ControlSlot c : controlled) {
            String l = c.label;
            for (int i = 2; !labels.add(l); i++) l = c.label + " " + i;
            c.rename(l);
            result.add(c);
        }
        result.addAll(out);
        result.addAll(fixed);
        return result;
    }

    static String handlerLabel(HandlerConfig h) {
        if (h instanceof HandlerConfig.Signal) return "When it receives “" + ((HandlerConfig.Signal) h).name + "”";
        String n = h.getClass().getSimpleName();
        if (n.equals("Startup")) return "When the scene starts";
        return "On " + n.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }

    /** How many other slots send this signal to this tag (through a marker's handler, say). */
    static int usersOf(TudeySceneModel scene, ConfigManager cfg, String tag, String signal, Slot except) {
        int n = 0;
        for (TudeySceneModel.Entry en : scene.getEntries()) {
            if (!hasConfig(en)) continue;
            for (Slot s : slots(en, cfg)) {
                if (except != null && except.is(s)) continue;
                for (Hop h : s.sends(scene, cfg)) if (tag.equals(h.tag) && signal.equalsIgnoreCase(h.signal)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** True when something other than {@code except} still sends to this tag at all. */
    static boolean tagUsed(TudeySceneModel scene, ConfigManager cfg, String tag, Slot except) {
        for (TudeySceneModel.Entry en : scene.getEntries()) {
            if (!hasConfig(en)) continue;
            for (Slot s : slots(en, cfg)) {
                if (except != null && except.is(s)) continue;
                for (Hop h : s.sends(scene, cfg)) if (tag.equals(h.tag)) return true;
            }
        }
        return false;
    }

    // ---- the slot kinds ----

    private static String labelOf(String param) {
        String s = param.trim();
        if (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length() - 1).trim();
        String t = s.replaceAll("(?i)\\s*signal\\s*$", "").trim();
        return t.isEmpty() ? "Signal" : t;
    }

    /**
     * A signal action whose name and tag are set by parameters: a switch's output. Several connections go through a handling marker this slot makes
     * and looks after. Some props send their outputs to one shared tag (a pressure plate's On and Off): a tag change then touches the others, so the
     * others are kept working by giving the marker a handler for each of their signals too.
     */
    static final class ControlSlot extends Slot {
        final Control nameCtl, tagCtl;
        final String signal, tag;
        boolean sharedTag;
        private String shown;

        ControlSlot(Object ownerKey, ActionConfig.Signal action, Control nameCtl, Control tagCtl) {
            super(ownerKey, labelOf(nameCtl.steps.get(nameCtl.steps.size() - 1).key));
            this.shown = label;
            this.nameCtl = nameCtl;
            this.tagCtl = tagCtl;
            this.signal = real(action.name) ? action.name : null;
            List<Hop> hs = new ArrayList<>();
            WireModel.tags(action.target, action.name == null ? "" : action.name, hs);
            this.tag = hs.size() == 1 && real(hs.get(0).tag) ? hs.get(0).tag : null;
        }

        void rename(String l) {
            this.shown = l;
        }

        @Override
        String title() {
            return shown;
        }

        @Override
        boolean is(Slot o) {
            return o instanceof ControlSlot && ownerKey.equals(o.ownerKey) && nameCtl.sameAs(((ControlSlot) o).nameCtl);
        }

        boolean connected() {
            return signal != null && tag != null;
        }

        private Hop direct() {
            return connected() ? new Hop(signal, tag) : null;
        }

        @Override
        List<Hop> sends(TudeySceneModel scene, ConfigManager cfg) {
            List<Hop> l = new ArrayList<>();
            if (direct() != null) l.add(direct());
            return l;
        }

        /** The marker and the handler this output goes through, when it goes through a pure relay; else null. */
        private TudeySceneModel.Entry relayWith(TudeySceneModel scene, ConfigManager cfg, List<Hop> into) {
            if (!connected()) return null;
            TudeySceneModel.Entry m = markerOf(scene, cfg, tag);
            if (m == null) return null;
            HandlerSlot h = handlerSlot(m, cfg, signal);
            if (h == null) return null;
            List<Hop> hs = WireModel.hops(h.action(m, cfg));
            if (hs == null) return null;
            if (into != null) into.addAll(hs);
            return m;
        }

        @Override
        TudeySceneModel.Entry relay(TudeySceneModel scene, ConfigManager cfg) {
            return relayWith(scene, cfg, null);
        }

        /** The other outputs of the thing that use the same tag parameter, and are connected. */
        private List<ControlSlot> liveSiblings(TudeySceneModel scene, ConfigManager cfg) {
            List<ControlSlot> l = new ArrayList<>();
            if (!sharedTag) return l;
            TudeySceneModel.Entry o = owner(scene);
            if (o == null) return l;
            for (Slot s : slots(o, cfg)) if (s instanceof ControlSlot && !s.is(this) && ((ControlSlot) s).tagCtl.sameAs(tagCtl) && ((ControlSlot) s).connected()) l.add((ControlSlot) s);
            return l;
        }

        @Override
        List<Link> links(TudeySceneModel scene, ConfigManager cfg) {
            List<Link> l = new ArrayList<>();
            List<Hop> hs = new ArrayList<>();
            TudeySceneModel.Entry m = relayWith(scene, cfg, hs);
            if (m != null) {
                int shared = usersOf(scene, cfg, tag, signal, this);
                for (Hop h : hs) l.add(new Link(h, describe(m, cfg), shared));
            } else if (direct() != null) {
                l.add(new Link(direct(), null, 0));
            }
            return l;
        }

        @Override
        void set(TudeySceneModel scene, ConfigManager cfg, List<Hop> hops, Change change) {
            TudeySceneModel.Entry owner = owner(scene);
            if (owner == null) return;
            List<ControlSlot> sibs = liveSiblings(scene, cfg);
            boolean hasSibs = !sibs.isEmpty();
            TudeySceneModel.Entry m = relayWith(scene, cfg, null);
            int shared = m == null ? 0 : usersOf(scene, cfg, tag, signal, this);
            String oldTag = tag == null ? "" : tag, oldSignal = signal == null ? "" : signal;
            if (hops.isEmpty()) {
                write(change, owner, hasSibs ? oldTag : "", ""); // the others keep the tag
                if (m != null && shared == 0) sweep(scene, cfg, oldTag, oldSignal, this, change);
                return;
            }
            if (m != null) {
                if (hops.size() == 1 && shared == 0 && !hasSibs) {
                    // one connection left: straight to it, and the relay goes
                    write(change, owner, hops.get(0).tag, hops.get(0).signal);
                    sweep(scene, cfg, oldTag, oldSignal, this, change);
                } else {
                    changeHandler(scene, cfg, change, m, handlerSlot(m, cfg, oldSignal), hops);
                    change.notes.add("the path through " + describe(m, cfg) + " now sends " + hops.size() + " signal" + (hops.size() == 1 ? "" : "s") + (shared > 0 ? " (shared with " + shared + " other" + (shared == 1 ? "" : "s") + ")" : ""));
                }
                return;
            }
            if (hops.size() == 1 && (!hasSibs || hops.get(0).tag.equals(oldTag))) {
                write(change, owner, hops.get(0).tag, hops.get(0).signal);
                return;
            }
            // several connections (or one that must not disturb the other outputs sharing the tag): through a handling marker
            String mine = !oldSignal.isEmpty() ? oldSignal : slug(label);
            TudeySceneModel.Entry existing = oldTag.isEmpty() ? null : markerOf(scene, cfg, oldTag);
            if (existing != null && handlerSlot(existing, cfg, mine) == null) {
                // the tag already is a marker's (the others use it): this output gets a handler there
                merge(change, addHandler(scene, cfg, existing, false, mine));
                TudeySceneModel.Entry pending = copyIn(change, existing);
                changeHandler(scene, cfg, change, existing, handlerSlot(pending, cfg, mine), hops);
                write(change, owner, oldTag, mine);
                return;
            }
            String newTag = freshPathTag(scene, cfg, owner, label, change);
            List<String> names = new ArrayList<>();
            List<List<Hop>> lists = new ArrayList<>();
            names.add(mine);
            lists.add(hops);
            for (ControlSlot s : sibs) {
                String n = s.signal;
                if (names.stream().anyMatch(x -> x.equalsIgnoreCase(n))) {
                    change.notes.add("not changed: " + s.title() + " and " + shown + " send the same signal on a shared tag");
                    return;
                }
                names.add(n);
                List<Hop> keep = new ArrayList<>();
                keep.add(new Hop(n, oldTag));
                lists.add(keep);
            }
            change.added.add(newMarker(owner, cfg, newTag, names, lists));
            write(change, owner, newTag, mine);
            change.notes.add("made a handling marker for " + hops.size() + " connections from " + describe(owner, cfg) + " (" + shown + ")");
            if (!sibs.isEmpty()) change.notes.add("the other output" + (sibs.size() == 1 ? "" : "s") + " of it go" + (sibs.size() == 1 ? "es" : "") + " through it too, unchanged");
        }

        private void write(Change change, TudeySceneModel.Entry owner, String tagValue, String signalValue) {
            TudeySceneModel.Entry s = copyIn(change, owner);
            nameCtl.write(s, signalValue);
            if (tagCtl.target) {
                TargetConfig.Tagged t = new TargetConfig.Tagged();
                t.tag = tagValue;
                tagCtl.write(s, t);
            } else {
                tagCtl.write(s, tagValue);
            }
            change.notes.add(describe(owner, null) + ": " + shown + (signalValue.isEmpty() ? " is not connected" : " sends \"" + signalValue + "\" to " + tagValue));
        }
    }

    /** A signal sent from something fixed in a configuration: shown (and drawn), not changed. */
    static final class FixedSlot extends Slot {
        final List<Hop> hops = new ArrayList<>();

        FixedSlot(Object ownerKey, ActionConfig.Signal action, int n) {
            super(ownerKey, "Fixed signal " + n);
            if (action.name != null) WireModel.tags(action.target, action.name, hops);
            hops.removeIf(h -> !real(h.tag) || !real(h.signal));
        }

        @Override
        List<Link> links(TudeySceneModel scene, ConfigManager cfg) {
            List<Link> l = new ArrayList<>();
            for (Hop h : hops) l.add(new Link(h, null, 0));
            return l;
        }

        @Override
        List<Hop> sends(TudeySceneModel scene, ConfigManager cfg) {
            return new ArrayList<>(hops);
        }

        @Override
        String other(TudeySceneModel scene, ConfigManager cfg) {
            return "is fixed in its configuration (no parameter sets it)";
        }

        @Override
        void set(TudeySceneModel scene, ConfigManager cfg, List<Hop> hops, Change change) {
            change.notes.add("not changed: this signal is fixed in the configuration");
        }

        @Override
        boolean editable() {
            return false;
        }
    }

    /** A handler of a thing that holds its handlers in a parameter (a handling marker's): what it does when it hears a signal, or at start up. */
    static final class HandlerSlot extends Slot {
        final String param;
        final int index;

        HandlerSlot(Object ownerKey, String param, int index, String label) {
            super(ownerKey, label);
            this.param = param;
            this.index = index;
        }

        HandlerConfig[] all(TudeySceneModel.Entry owner, ConfigManager cfg) {
            return handlersOf(owner, cfg, param);
        }

        ActionConfig action(TudeySceneModel.Entry owner, ConfigManager cfg) {
            HandlerConfig[] all = owner == null ? new HandlerConfig[0] : all(owner, cfg);
            return index < all.length && all[index] instanceof HandlerConfig.ActionHandlerConfig ? ((HandlerConfig.ActionHandlerConfig) all[index]).action : null;
        }

        /** The name of the signal it hears (empty for other kinds of handler). */
        String signalName(TudeySceneModel.Entry owner, ConfigManager cfg) {
            HandlerConfig[] all = owner == null ? new HandlerConfig[0] : all(owner, cfg);
            return index < all.length && all[index] instanceof HandlerConfig.Signal ? ((HandlerConfig.Signal) all[index]).name : "";
        }

        @Override
        List<Hop> sends(TudeySceneModel scene, ConfigManager cfg) {
            List<Hop> hs = new ArrayList<>();
            scanRoot(action(owner(scene), cfg), hs, new ArrayList<>());
            return hs;
        }

        @Override
        List<Link> links(TudeySceneModel scene, ConfigManager cfg) {
            List<Link> l = new ArrayList<>();
            for (Hop h : sends(scene, cfg)) l.add(new Link(h, null, 0));
            return l;
        }

        @Override
        String other(TudeySceneModel scene, ConfigManager cfg) {
            List<ActionConfig> others = new ArrayList<>();
            scanRoot(action(owner(scene), cfg), new ArrayList<>(), others);
            return others.isEmpty() ? null : "also does " + others.size() + " other thing" + (others.size() == 1 ? "" : "s") + " (left alone)";
        }

        @Override
        void set(TudeySceneModel scene, ConfigManager cfg, List<Hop> hops, Change change) {
            TudeySceneModel.Entry o = owner(scene);
            if (o != null) changeHandler(scene, cfg, change, o, this, hops);
        }

        @Override
        boolean removable() {
            return true;
        }
    }

    /** The action a thing's parameter holds whole (a trigger's "Action"). */
    static final class ParamSlot extends Slot {
        final String param;
        final ActionConfig dflt;

        ParamSlot(Object ownerKey, String param, ActionConfig dflt) {
            super(ownerKey, param);
            this.param = param;
            this.dflt = dflt;
        }

        private ActionConfig action(TudeySceneModel scene) {
            TudeySceneModel.Entry o = owner(scene);
            ArgumentMap a = o == null ? null : ref(o).getArguments();
            Object v = a == null ? null : a.get(param);
            return v instanceof ActionConfig ? (ActionConfig) v : dflt;
        }

        @Override
        List<Hop> sends(TudeySceneModel scene, ConfigManager cfg) {
            List<Hop> hs = new ArrayList<>();
            scanRoot(action(scene), hs, new ArrayList<>());
            return hs;
        }

        @Override
        List<Link> links(TudeySceneModel scene, ConfigManager cfg) {
            List<Link> l = new ArrayList<>();
            for (Hop h : sends(scene, cfg)) l.add(new Link(h, null, 0));
            return l;
        }

        @Override
        String other(TudeySceneModel scene, ConfigManager cfg) {
            List<ActionConfig> others = new ArrayList<>();
            scanRoot(action(scene), new ArrayList<>(), others);
            return others.isEmpty() ? null : "also does " + others.size() + " other thing" + (others.size() == 1 ? "" : "s") + " (left alone)";
        }

        @Override
        void set(TudeySceneModel scene, ConfigManager cfg, List<Hop> hops, Change change) {
            TudeySceneModel.Entry o = owner(scene);
            if (o == null) return;
            args(ref(copyIn(change, o)), true).put(param, rewrite(action(scene), hops));
            change.notes.add(describe(o, cfg) + ": " + param + " now sends " + hops.size() + " signal" + (hops.size() == 1 ? "" : "s"));
        }
    }

    /** The handlers a thing's parameter holds: the argument if it has one, else the configuration's own. */
    static HandlerConfig[] handlersOf(TudeySceneModel.Entry owner, ConfigManager cfg, String param) {
        ArgumentMap a = ref(owner).getArguments();
        Object h = a == null ? null : a.get(param);
        if (h instanceof HandlerConfig[]) return (HandlerConfig[]) h;
        Scan scan = scan(owner, cfg);
        if (!scan.root.isEmpty()) {
            Level l0 = scan.root.get(0);
            for (Parameter p : params(l0.base)) {
                if (!param.equals(p.name) || pathsOf(p).length == 0) continue;
                Object def = read(l0.base, pathsOf(p)[0]);
                if (def instanceof HandlerConfig[]) return (HandlerConfig[]) def;
            }
        }
        return new HandlerConfig[0];
    }

    /** The handler slot of a thing that hears this signal, or null. */
    static HandlerSlot handlerSlot(TudeySceneModel.Entry e, ConfigManager cfg, String signal) {
        if (signal == null) return null;
        for (Slot s : slots(e, cfg)) if (s instanceof HandlerSlot && signal.equals(((HandlerSlot) s).signalName(e, cfg))) return (HandlerSlot) s;
        return null;
    }

    /** Puts a new action in one handler of a thing: the signals are {@code hops}, whatever else the handler did stays. */
    static void changeHandler(TudeySceneModel scene, ConfigManager cfg, Change change, TudeySceneModel.Entry marker, HandlerSlot slot, List<Hop> hops) {
        if (slot == null) return;
        TudeySceneModel.Entry copy = copyIn(change, marker);
        HandlerConfig[] all = handlersOf(copy, cfg, slot.param);
        if (slot.index < 0 || slot.index >= all.length || !(all[slot.index] instanceof HandlerConfig.ActionHandlerConfig)) return;
        HandlerConfig.ActionHandlerConfig h = (HandlerConfig.ActionHandlerConfig) all[slot.index].clone();
        h.action = rewrite(h.action, hops);
        HandlerConfig[] next = all.clone();
        next[slot.index] = h;
        args(ref(copy), true).put(slot.param, next);
    }

    /** The parameter of a thing that holds its handlers, or null. */
    private static String handlersParam(TudeySceneModel.Entry e, ConfigManager cfg) {
        Scan scan = scan(e, cfg);
        if (scan.root.isEmpty()) return null;
        Level l0 = scan.root.get(0);
        for (Parameter p : params(l0.base)) {
            String[] paths = pathsOf(p);
            if (paths.length > 0) {
                Class<?> type = fieldType(l0.resolved, paths[0]);
                if (type != null && HandlerConfig[].class.isAssignableFrom(type)) return p.name;
            }
        }
        return null;
    }

    /** Takes a handler away (a handling marker's way of hearing a signal). */
    static void removeHandler(TudeySceneModel scene, ConfigManager cfg, Change change, TudeySceneModel.Entry marker, HandlerSlot slot) {
        TudeySceneModel.Entry copy = copyIn(change, marker);
        HandlerConfig[] all = handlersOf(copy, cfg, slot.param);
        if (slot.index < 0 || slot.index >= all.length) return;
        List<HandlerConfig> keep = new ArrayList<>(java.util.Arrays.asList(all));
        keep.remove(slot.index);
        args(ref(copy), true).put(slot.param, keep.toArray((HandlerConfig[]) Array.newInstance(HandlerConfig.class, 0)));
        change.notes.add("removed a handler of " + describe(marker, cfg));
    }

    /** Gives a handling marker a new handler: for a signal it hears (by name), or for the start of the scene. */
    static Change addHandler(TudeySceneModel scene, ConfigManager cfg, TudeySceneModel.Entry marker, boolean startup, String signalName) {
        Change change = new Change();
        String param = handlersParam(marker, cfg);
        if (param == null) {
            change.notes.add("not changed: " + describe(marker, cfg) + " has no handlers to add to");
            return change;
        }
        TudeySceneModel.Entry copy = copyIn(change, marker);
        HandlerConfig[] all = handlersOf(copy, cfg, param);
        HandlerConfig.ActionHandlerConfig h;
        if (startup) {
            h = new HandlerConfig.Startup();
        } else {
            HandlerConfig.Signal s = new HandlerConfig.Signal();
            s.name = signalName;
            h = s;
        }
        h.action = emptyAction();
        HandlerConfig[] next = java.util.Arrays.copyOf(all, all.length + 1);
        next[all.length] = h;
        args(ref(copy), true).put(param, next);
        change.notes.add("gave " + describe(marker, cfg) + " a handler " + (startup ? "for the start of the scene" : "for \"" + signalName + "\""));
        return change;
    }

    // ---- making and sweeping markers ----

    private static String slug(String s) {
        String t = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return t.isEmpty() ? "go" : t;
    }

    /** Every tag in use in the scene, and in a change not yet applied. */
    private static Set<String> tagsInUse(TudeySceneModel scene, ConfigManager cfg, Change pending) {
        Set<String> used = new LinkedHashSet<>();
        for (TudeySceneModel.Entry en : scene.getEntries()) if (hasConfig(en)) used.addAll(tagsOf(en, cfg));
        if (pending != null) {
            for (TudeySceneModel.Entry en : pending.updated) if (hasConfig(en)) used.addAll(tagsOf(en, cfg));
            for (TudeySceneModel.Entry en : pending.added) {
                TudeySceneModel.PlaceableEntry p = placeable(en);
                if (p != null && p.placeable.getArguments() != null && p.placeable.getArguments().get("Tag") instanceof String) used.add((String) p.placeable.getArguments().get("Tag"));
            }
        }
        return used;
    }

    /** A tag no thing has yet, for the marker that carries one thing's several connections. */
    private static String freshPathTag(TudeySceneModel scene, ConfigManager cfg, TudeySceneModel.Entry owner, String label, Change pending) {
        Set<String> used = tagsInUse(scene, cfg, pending);
        String base = "_" + nameWord(owner) + "-" + keyWord(owner) + "_" + slug(label) + "-path";
        String t = base;
        for (int i = 2; used.contains(t); i++) t = base + "-" + i;
        return t;
    }

    /** A tag made for one connection: it names both ends ("_pressure-plate-403_to_iron-gate-401"), and nothing has it yet. */
    static String connectionTag(TudeySceneModel.Entry from, TudeySceneModel.Entry to, ConfigManager cfg, TudeySceneModel scene, Change pending) {
        Set<String> used = tagsInUse(scene, cfg, pending);
        String base = "_" + nameWord(from) + "-" + keyWord(from) + "_to_" + nameWord(to) + "-" + keyWord(to);
        String t = base;
        for (int i = 2; used.contains(t); i++) t = base + "-" + i;
        return t;
    }

    /** A handling marker next to a thing, with the tag and a handler for each of the signals it hears, which sends the hops on. */
    private static TudeySceneModel.PlaceableEntry newMarker(TudeySceneModel.Entry near, ConfigManager cfg, String tag, List<String> signals, List<List<Hop>> hops) {
        TudeySceneModel.PlaceableEntry m = new TudeySceneModel.PlaceableEntry();
        m.placeable = new ConfigReference<com.threerings.tudey.config.PlaceableConfig>(MARKER);
        com.threerings.math.Vector2f at = near.getTranslation(cfg);
        TudeySceneModel.PlaceableEntry np = placeable(near);
        float z = np == null || np.transform == null ? 0f : np.transform.getTranslation().z;
        m.transform = new com.threerings.math.Transform3D(new com.threerings.math.Vector3f((at == null ? 0f : at.x) + 0.5f, (at == null ? 0f : at.y) + 0.5f, z), new com.threerings.math.Quaternion());
        HandlerConfig[] hs = new HandlerConfig[signals.size()];
        for (int i = 0; i < hs.length; i++) {
            HandlerConfig.Signal h = new HandlerConfig.Signal();
            h.name = signals.get(i);
            h.action = build(new ArrayList<>(), hops.get(i));
            hs[i] = h;
        }
        ArgumentMap a = args(m.placeable, true);
        a.put("Tag", tag);
        a.put("Handlers", hs);
        return m;
    }

    /**
     * An output no longer sends this signal to this tag: if the tag belongs to a handling marker whose handler for the signal nobody uses any more
     * (and does nothing but pass signals on), the handler goes, and the marker with it when it has no handlers left and nothing sends to it.
     */
    static void sweep(TudeySceneModel scene, ConfigManager cfg, String tag, String signal, Slot except, Change change) {
        if (tag == null || tag.isEmpty() || signal == null || signal.isEmpty()) return;
        TudeySceneModel.Entry m = markerOf(scene, cfg, tag);
        if (m == null) return;
        HandlerSlot h = handlerSlot(m, cfg, signal);
        if (h == null) return;
        // only a handler that does nothing but pass signals on is a relay that can go; one that does more is left for its owner
        if (WireModel.hops(h.action(m, cfg)) == null) return;
        if (usersOf(scene, cfg, tag, signal, except) > 0) return;
        if (h.all(m, cfg).length == 1 && !tagUsed(scene, cfg, tag, except)) {
            change.removed.add(m.getKey());
            change.updated.removeIf(u -> u.getKey().equals(m.getKey()));
            change.notes.add("removed the handling marker " + describe(m, cfg) + " (nothing used it any more)");
            return;
        }
        removeHandler(scene, cfg, change, m, h);
        change.notes.add("(the handler for \"" + signal + "\" was a relay nothing used any more)");
    }

    /**
     * The elevation step a thing stands at. Areas and paths have none (it reads as the smallest int, which put their rays billions of units under the scene):
     * they take the one of the tile under their middle, and the given one where there is no tile.
     */
    static int elevationOf(TudeySceneModel scene, TudeySceneModel.Entry e, ConfigManager cfg, int fallback) {
        int own = e.getElevation();
        if (own != Integer.MIN_VALUE) return own;
        try {
            float[] b = bounds(e, cfg);
            TudeySceneModel.TileEntry tile = scene.getTileEntry((int) Math.floor((b[0] + b[2]) / 2f), (int) Math.floor((b[1] + b[3]) / 2f));
            if (tile != null) return tile.getElevation();
        } catch (RuntimeException ignored) {
            // no tile under it
        }
        return fallback;
    }

    /** Where a thing is, as a rectangle in the scene: {minX, minY, maxX, maxY} and its elevation step; at least a little over a tile when it has no size. */
    static float[] bounds(TudeySceneModel.Entry e, ConfigManager cfg) {
        com.threerings.math.Rect r = new com.threerings.math.Rect();
        e.getBounds(cfg, r);
        float minX = r.getMinimumExtent().x, minY = r.getMinimumExtent().y, maxX = r.getMaximumExtent().x, maxY = r.getMaximumExtent().y;
        if (!(maxX - minX > 0.01f) || !(maxY - minY > 0.01f)) {
            com.threerings.math.Vector2f at = e.getTranslation(cfg);
            float cx = at == null ? 0f : at.x, cy = at == null ? 0f : at.y;
            minX = cx - 0.5f;
            maxX = cx + 0.5f;
            minY = cy - 0.5f;
            maxY = cy + 0.5f;
        }
        return new float[]{minX, minY, maxX, maxY, e.getElevation()};
    }

    // ---- what can be connected to ----

    /** Everything in the scene that answers to this tag: props, tiles, areas ... */
    static List<TudeySceneModel.Entry> tagged(TudeySceneModel scene, ConfigManager cfg, String tag) {
        List<TudeySceneModel.Entry> out = new ArrayList<>();
        if (tag == null || tag.isEmpty()) return out;
        for (TudeySceneModel.Entry en : scene.getEntries()) if (hasConfig(en) && tagsOf(en, cfg).contains(tag)) out.add(en);
        return out;
    }

    /** The tiles of the scene that have a tag, by tag (a floor of traps is many tiles with one tag). */
    static Map<String, List<TudeySceneModel.Entry>> tileGroups(TudeySceneModel scene, ConfigManager cfg) {
        Map<String, List<TudeySceneModel.Entry>> out = new java.util.TreeMap<>();
        for (TudeySceneModel.Entry en : scene.getEntries()) {
            if (!(en instanceof TudeySceneModel.TileEntry)) continue;
            for (String t : tagsOf(en, cfg)) out.computeIfAbsent(t, k -> new ArrayList<>()).add(en);
        }
        return out;
    }

    /** The signals a group of tagged things reacts to. */
    static Set<String> groupSignals(List<TudeySceneModel.Entry> group, ConfigManager cfg) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TudeySceneModel.Entry e : group) out.addAll(signalsOf(e, cfg));
        return out;
    }

    /** Things that can be connected to one at a time: those with a tag or a tag to set; tiles that have a tag come as groups by tag instead. */
    static List<TudeySceneModel.Entry> receivers(TudeySceneModel scene, ConfigManager cfg) {
        List<TudeySceneModel.Entry> out = new ArrayList<>();
        for (TudeySceneModel.Entry en : scene.getEntries()) {
            if (!hasConfig(en)) continue;
            try {
                if (en instanceof TudeySceneModel.TileEntry) {
                    // a tile with a tag is in a group; one with none that can be given one (a spike floor, say) is offered by itself
                    if (tagOf(en, cfg) == null && tagControl(en, cfg) != null) out.add(en);
                } else if (tagOf(en, cfg) != null || tagControl(en, cfg) != null) {
                    out.add(en);
                }
            } catch (RuntimeException ignored) {
                // a thing whose config cannot be read is not offered
            }
        }
        return out;
    }

    // ---- changing ----

    /**
     * Settles the tags of the things to be connected, so each connection reaches the thing it is made to and no other:
     * <ul><li>a thing with no tag, or a stand-in such as "(Door Tag)", gets a tag made for this connection;</li>
     * <li>a thing whose tag other things have too gets a tag of its own, unless something else already sends to that shared tag
     * (it would stop reaching the others), in which case the tag stays and a note says so;</li>
     * <li>a thing with a tag of its own keeps it.</li></ul>
     * Returns the targets with the tags to use.
     */
    static List<Target> prepare(TudeySceneModel scene, ConfigManager cfg, Slot slot, TudeySceneModel.Entry from, List<Target> targets, Change change) {
        List<Target> out = new ArrayList<>();
        // tiles of one kind that have no tag and are given one together (a field of spikes) get the same new tag
        Map<String, String> sharedNew = new HashMap<>();
        for (Target t : targets) {
            if (t.entry == null) {
                out.add(t);
                continue;
            }
            String cur = tagOf(t.entry, cfg);
            Control ctl = tagControl(t.entry, cfg);
            String tag = cur;
            if (isMarker(t.entry) && cur != null) {
                tag = cur; // a marker is a path others share: its tag stays
            } else if (cur == null) {
                String kind = t.entry instanceof TudeySceneModel.TileEntry ? ref(t.entry).getName() : null;
                if (kind != null && sharedNew.containsKey(kind)) tag = sharedNew.get(kind);
                else tag = real(t.tag) ? t.tag : connectionTag(from, t.entry, cfg, scene, change);
                if (kind != null) sharedNew.put(kind, tag);
                if (ctl != null) {
                    ctl.write(copyIn(change, t.entry), tag);
                    change.notes.add("gave " + describe(t.entry, cfg) + " the tag " + tag);
                }
            } else {
                int holders = 0;
                for (TudeySceneModel.Entry en : scene.getEntries()) if (hasConfig(en) && tagsOf(en, cfg).contains(cur)) holders++;
                if (holders > 1) {
                    if (ctl != null && !tagUsed(scene, cfg, cur, slot)) {
                        tag = connectionTag(from, t.entry, cfg, scene, change);
                        ctl.write(copyIn(change, t.entry), tag);
                        change.notes.add(describe(t.entry, cfg) + " shared the tag " + cur + " with " + (holders - 1) + " other thing" + (holders == 2 ? "" : "s") + ": it has its own tag " + tag + " now");
                    } else {
                        change.notes.add(describe(t.entry, cfg) + " shares the tag " + cur + " with " + (holders - 1) + " other thing" + (holders == 2 ? "" : "s") + ", which will react too (something else sends to it, so it was left alone)");
                    }
                }
            }
            out.add(new Target(t.entry, tag, t.signal));
        }
        return out;
    }

    /** Adds connections to a slot. */
    static Change add(TudeySceneModel scene, ConfigManager cfg, Slot slot, List<Target> targets) {
        Change change = new Change();
        TudeySceneModel.Entry from = slot.owner(scene);
        List<Target> ready = prepare(scene, cfg, slot, from, targets, change);
        List<Hop> hops = slot.hops(scene, cfg);
        for (Target t : ready) {
            boolean dup = false;
            for (Hop h : hops) if (h.same(t.hop())) dup = true;
            if (!dup) hops.add(t.hop());
        }
        slot.set(scene, cfg, hops, change);
        return change;
    }

    /** Takes one connection away from a slot. */
    static Change remove(TudeySceneModel scene, ConfigManager cfg, Slot slot, Link link) {
        Change change = new Change();
        List<Hop> hops = slot.hops(scene, cfg);
        for (int i = 0; i < hops.size(); i++) if (hops.get(i).same(link.hop)) {
            hops.remove(i);
            break;
        }
        slot.set(scene, cfg, hops, change);
        return change;
    }

    /** Changes one connection of a slot into another (a different thing, or a different signal). */
    static Change replace(TudeySceneModel scene, ConfigManager cfg, Slot slot, Link link, Target target) {
        Change change = new Change();
        Target ready = prepare(scene, cfg, slot, slot.owner(scene), java.util.Collections.singletonList(target), change).get(0);
        List<Hop> hops = slot.hops(scene, cfg);
        for (int i = 0; i < hops.size(); i++) if (hops.get(i).same(link.hop)) hops.set(i, ready.hop());
        slot.set(scene, cfg, hops, change);
        return change;
    }

    /** Writes the slot's connections again the shortest way (a relay that passes on a single signal becomes a direct connection, and goes). */
    static Change simplify(TudeySceneModel scene, ConfigManager cfg, Slot slot) {
        Change change = new Change();
        slot.set(scene, cfg, slot.hops(scene, cfg), change);
        return change;
    }
}
