package dev.skmods.editor;

import com.threerings.config.ArgumentMap;
import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.config.ManagedConfig;
import com.threerings.tudey.data.TudeySceneModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What the editor's extra tools (Find, Tags, Lint, Stats, Replace) work out about a scene, with no window and no editor: they only read the scene (and
 * its configurations) and make a {@link WireModel.Change} when they are to change something, which the editor applies as one undo step. So all of it
 * can be tried on a scene file without the editor ({@code test/dev/skmods/editor/ToolsCheck.java}).
 */
final class SceneTools {
    private SceneTools() {
    }

    // ---- what kind a thing is ----

    static String kindOf(TudeySceneModel.Entry e) {
        if (e instanceof TudeySceneModel.TileEntry) return "tile";
        if (e instanceof TudeySceneModel.PlaceableEntry) return "prop";
        if (e instanceof TudeySceneModel.AreaEntry) return "area";
        if (e instanceof TudeySceneModel.PathEntry) return "path";
        if (e instanceof TudeySceneModel.GlobalEntry) return "global";
        return "other";
    }

    /** The name of the thing's configuration, or "" when it has none. */
    static String configName(TudeySceneModel.Entry e) {
        try {
            return WireModel.hasConfig(e) ? WireModel.ref(e).getName() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    static String layerName(TudeySceneModel scene, TudeySceneModel.Entry e) {
        try {
            List<String> names = scene.getLayers();
            int i = scene.getLayer(e.getKey());
            return i >= 0 && i < names.size() ? names.get(i) : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---- what is read from the configurations, once ----

    /**
     * The tags and signals of things, read from their configurations. They are the same for everything that has the same configuration and arguments (a
     * floor is thousands of tiles of a few hundred kinds), so each is worked out once: that is what keeps a search of a large scene quick. A cache is for
     * one piece of work (the configurations may be edited between two).
     */
    static final class Cache {
        private final Map<List<Object>, List<String>> tags = new HashMap<>();
        private final Map<List<Object>, Set<String>> heard = new HashMap<>();
        private final Map<List<Object>, Boolean> controls = new HashMap<>();

        private static List<Object> key(TudeySceneModel.Entry e) {
            return List.of(e.getClass(), WireModel.ref(e));
        }

        List<String> tagsOf(TudeySceneModel.Entry e, ConfigManager cfg) {
            if (!WireModel.hasConfig(e)) return List.of();
            return tags.computeIfAbsent(key(e), k -> WireModel.tagsOf(e, cfg));
        }

        /** The signals the thing has a handler for (those the game sends itself are not left out). */
        Set<String> heardBy(TudeySceneModel.Entry e, ConfigManager cfg) {
            if (!WireModel.hasConfig(e)) return Set.of();
            return heard.computeIfAbsent(key(e), k -> {
                Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                try {
                    for (WireModel.Found f : WireModel.scan(e, cfg).hears) {
                        String name = ((com.threerings.tudey.config.HandlerConfig.Signal) f.node).name;
                        if (WireModel.real(name)) out.add(name);
                    }
                } catch (RuntimeException ex) {
                    // none read
                }
                return out;
            });
        }

        boolean renamable(TudeySceneModel.Entry e, ConfigManager cfg) {
            if (!WireModel.hasConfig(e)) return false;
            return controls.computeIfAbsent(key(e), k -> {
                try {
                    return WireModel.tagControl(e, cfg) != null;
                } catch (RuntimeException ex) {
                    return false;
                }
            });
        }
    }

    // ---- find ----

    static final class Hit {
        final TudeySceneModel.Entry entry;
        final String kind;
        final String text;

        Hit(TudeySceneModel.Entry entry, String kind, String text) {
            this.entry = entry;
            this.kind = kind;
            this.text = text;
        }
    }

    /**
     * A search that can be run a little at a time (the editor's window stays alive while a large scene is looked through). The words of the query must
     * all be in the thing's kind, its configuration's name, its tags, its signals, its layer or its description (which holds where it is, "Door at (12,
     * 40)"). The tags and signals, which are the costly part, are only read for what the other words did not find.
     */
    static final class Search {
        private final List<TudeySceneModel.Entry> entries;
        private final TudeySceneModel scene;
        private final ConfigManager cfg;
        private final Cache cache;
        private final String[] words;
        private final String kind;
        private int next;
        final List<Hit> hits = new ArrayList<>();

        Search(TudeySceneModel scene, ConfigManager cfg, String query, String kind, Cache cache) {
            this.scene = scene;
            this.cfg = cfg;
            this.cache = cache;
            this.entries = new ArrayList<>(scene.getEntries());
            this.words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
            this.kind = kind == null ? "" : kind;
        }

        int total() {
            return entries.size();
        }

        int done() {
            return next;
        }

        /** Looks at things for about this long; true when everything has been looked at. */
        boolean step(long nanos) {
            long end = System.nanoTime() + nanos;
            while (next < entries.size()) {
                if ((next & 63) == 0 && System.nanoTime() > end) return false;
                consider(entries.get(next++));
            }
            hits.sort(Comparator.comparing((Hit h) -> h.kind).thenComparing(h -> h.text));
            return true;
        }

        private void consider(TudeySceneModel.Entry e) {
            String k = kindOf(e);
            if (!kind.isEmpty() && !kind.equals(k)) return;
            String desc = WireModel.describe(e, cfg);
            String cheap = (k + " " + configName(e) + " " + desc + " " + layerName(scene, e)).toLowerCase(Locale.ROOT);
            List<String> missing = null;
            for (String w : words) {
                if (w.isEmpty() || cheap.contains(w)) continue;
                if (missing == null) missing = new ArrayList<>();
                missing.add(w);
            }
            String tags = null;
            if (missing != null) {
                // what is left is looked for in the tags and signals
                tags = String.join(" ", cache.tagsOf(e, cfg));
                String deep = (tags + " " + String.join(" ", cache.heardBy(e, cfg))).toLowerCase(Locale.ROOT);
                for (String w : missing) if (!deep.contains(w)) return;
            }
            if (tags == null) tags = String.join(" ", cache.tagsOf(e, cfg));
            hits.add(new Hit(e, k, desc + (tags.isEmpty() ? "" : "  [" + tags + "]")));
        }
    }

    /** Everything in the scene that matches the query, all at once (see {@link Search}). */
    static List<Hit> find(TudeySceneModel scene, ConfigManager cfg, String query, String kind) {
        Search s = new Search(scene, cfg, query, kind, new Cache());
        while (!s.step(Long.MAX_VALUE / 2)) {
            // until it is done
        }
        return s.hits;
    }

    // ---- stats ----

    /** A report on the scene, as text lines. */
    static List<String> stats(TudeySceneModel scene, ConfigManager cfg) {
        Cache cache = new Cache();
        List<String> out = new ArrayList<>();
        Collection<TudeySceneModel.Entry> all = scene.getEntries();
        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> byLayer = new LinkedHashMap<>();
        for (String l : scene.getLayers()) byLayer.put(l, 0);
        Map<String, Integer> byConfig = new HashMap<>();
        Map<String, Integer> byFolder = new TreeMap<>();
        Map<String, Integer> monsters = new TreeMap<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, tiles = 0;
        for (TudeySceneModel.Entry e : all) {
            String k = kindOf(e);
            byKind.merge(k, 1, Integer::sum);
            String layer = layerName(scene, e);
            byLayer.merge(layer, 1, Integer::sum);
            String name = configName(e);
            if (!name.isEmpty()) {
                byConfig.merge(k + " " + name, 1, Integer::sum);
                String[] p = name.split("/");
                String folder = p.length > 2 ? p[0] + "/" + p[1] : p.length > 1 ? p[0] : name;
                if (e instanceof TudeySceneModel.PlaceableEntry) byFolder.merge(folder, 1, Integer::sum);
                if (e instanceof TudeySceneModel.PlaceableEntry && name.contains("/Monster/")) {
                    int at = name.indexOf("/Monster/") + 9;
                    String family = name.substring(at).split("/")[0];
                    monsters.merge(family, 1, Integer::sum);
                }
            }
            if (e instanceof TudeySceneModel.TileEntry) {
                tiles++;
                float[] b = WireModel.bounds(e, cfg);
                minX = Math.min(minX, b[0]);
                minY = Math.min(minY, b[1]);
                maxX = Math.max(maxX, b[2]);
                maxY = Math.max(maxY, b[3]);
                int z = e.getElevation();
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        out.add("Things in the scene: " + all.size());
        StringBuilder kinds = new StringBuilder();
        for (var en : byKind.entrySet()) kinds.append(kinds.length() == 0 ? "" : ", ").append(en.getValue()).append(' ').append(en.getKey()).append(en.getValue() == 1 ? "" : "s");
        out.add("  " + kinds);
        if (tiles > 0) {
            out.add(String.format(Locale.ROOT, "Floor: %d tiles, from (%.0f, %.0f) to (%.0f, %.0f): %.0f by %.0f", tiles, minX, minY, maxX, maxY, maxX - minX, maxY - minY));
            out.add("  elevation steps " + minZ + " to " + maxZ);
        }
        out.add("Layers:");
        for (var en : byLayer.entrySet()) out.add("  " + (en.getKey().isEmpty() ? "(base layer)" : en.getKey()) + ": " + en.getValue());
        // tags and wiring
        Set<String> tags = new TreeSet<>();
        int wired = 0, sends = 0;
        for (TudeySceneModel.Entry e : all) {
            if (!WireModel.hasConfig(e)) continue;
            tags.addAll(cache.tagsOf(e, cfg));
            if (e instanceof TudeySceneModel.TileEntry) continue; // tiles do not send
            int mine = 0;
            for (WireModel.Slot s : WireModel.slots(e, cfg)) mine += s.sends(scene, cfg).size();
            if (mine > 0) wired++;
            sends += mine;
        }
        out.add("Tags: " + tags.size() + "; things that send signals: " + wired + " (" + sends + " signal" + (sends == 1 ? "" : "s") + " in all)");
        if (!monsters.isEmpty()) {
            int total = 0;
            StringBuilder m = new StringBuilder();
            for (var en : monsters.entrySet()) {
                total += en.getValue();
                m.append(m.length() == 0 ? "" : ", ").append(en.getValue()).append(' ').append(en.getKey());
            }
            out.add("Monsters: " + total + " (" + m + ")");
        }
        out.add("Props by kind:");
        byFolder.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10).forEach(en -> out.add("  " + en.getKey() + ": " + en.getValue()));
        out.add("Most used configurations:");
        byConfig.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12).forEach(en -> out.add("  " + en.getValue() + " x " + en.getKey()));
        return out;
    }

    // ---- lint ----

    enum Severity {
        ERROR, WARNING, INFO
    }

    static final class Issue {
        final Severity severity;
        final String text;
        final TudeySceneModel.Entry entry;
        /** Other things the issue is about (the identical props, the holders of a tag). */
        final List<TudeySceneModel.Entry> related;

        Issue(Severity severity, String text, TudeySceneModel.Entry entry, List<TudeySceneModel.Entry> related) {
            this.severity = severity;
            this.text = text;
            this.entry = entry;
            this.related = related;
        }
    }

    /** The signals each tag is sent, by whom: tag to signal to the things that send it. */
    static Map<String, Map<String, List<TudeySceneModel.Entry>>> sendsByTag(TudeySceneModel scene, ConfigManager cfg) {
        Map<String, Map<String, List<TudeySceneModel.Entry>>> out = new TreeMap<>();
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            if (!WireModel.hasConfig(e) || e instanceof TudeySceneModel.TileEntry) continue; // a tile does not send
            try {
                for (WireModel.Slot s : WireModel.slots(e, cfg)) {
                    for (WireModel.Hop h : s.sends(scene, cfg)) {
                        if (h.tag == null || h.tag.isEmpty() || WireModel.isPlaceholder(h.tag)) continue;
                        List<TudeySceneModel.Entry> list = out.computeIfAbsent(h.tag, x -> new TreeMap<String, List<TudeySceneModel.Entry>>(String.CASE_INSENSITIVE_ORDER)).computeIfAbsent(h.signal == null ? "" : h.signal, x -> new ArrayList<>());
                        if (!list.contains(e)) list.add(e);
                    }
                }
            } catch (RuntimeException ex) {
                // a thing that cannot be read sends nothing
            }
        }
        return out;
    }

    /** Things with a name that says they are not drawn in the game: markers, triggers, notes, cameras, spawn points, barriers, handlers. */
    private static final Pattern UNSEEN = Pattern.compile("(?i)(marker|trigger|\\bnotes?\\b|camera|spawn|barrier|handler|logic|\\bnull\\b|control point|waypoint|sound|music|hint)");

    /** False for a prop the game does not draw (a marker, a trigger, a note ...): where it stands needs no floor under it. */
    static boolean seenInGame(TudeySceneModel.PlaceableEntry e, ConfigManager cfg) {
        if (UNSEEN.matcher(configName(e)).find()) return false;
        try {
            com.threerings.tudey.config.PlaceableConfig.Original o = e.getConfig(cfg);
            if (o instanceof com.threerings.tudey.config.PlaceableConfig.Marker) return false;
            if (o != null && o.model == null && !(o instanceof com.threerings.tudey.config.PlaceableConfig.StatefulProp)) return false;
        } catch (RuntimeException ex) {
            // not read: assume it is drawn
        }
        return true;
    }

    /** True when there is a configuration of this name for the thing (an editor's validation sometimes says no for a perfectly good camera). */
    static boolean configExists(TudeySceneModel.Entry e, ConfigManager cfg) {
        try {
            @SuppressWarnings("unchecked")
            Class<ManagedConfig> type = (Class<ManagedConfig>) WireModel.cls(e);
            return cfg.getRawConfig(type, configName(e)) != null;
        } catch (RuntimeException ex) {
            return true; // cannot be told: not an error
        }
    }

    /** What the editor's own validation does not look for: wiring that leads nowhere, things stacked on each other, empty shapes. */
    static List<Issue> lint(TudeySceneModel scene, ConfigManager cfg) {
        Cache cache = new Cache();
        List<Issue> out = new ArrayList<>();
        Map<String, Map<String, List<TudeySceneModel.Entry>>> sends = sendsByTag(scene, cfg);
        Map<String, List<TudeySceneModel.Entry>> holders = new HashMap<>();
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            if (!WireModel.hasConfig(e)) continue;
            for (String t : cache.tagsOf(e, cfg)) holders.computeIfAbsent(t, x -> new ArrayList<>()).add(e);
        }
        // a signal sent to a tag nothing has
        for (var byTag : sends.entrySet()) {
            if (holders.containsKey(byTag.getKey())) continue;
            for (var bySignal : byTag.getValue().entrySet()) {
                TudeySceneModel.Entry from = bySignal.getValue().get(0);
                out.add(new Issue(Severity.WARNING, WireModel.describe(from, cfg) + " sends " + quote(bySignal.getKey()) + " to the tag " + quote(byTag.getKey()) + ", but nothing has that tag", from, bySignal.getValue()));
            }
        }
        // a thing that reacts to a signal nobody sends it (the game sends some itself)
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            if (!WireModel.hasConfig(e) || e instanceof TudeySceneModel.TileEntry) continue;
            Set<String> hears = cache.heardBy(e, cfg);
            if (hears.isEmpty()) continue;
            List<String> tags = cache.tagsOf(e, cfg);
            for (String signal : hears) {
                if (ServerSignals.sent(signal)) continue;
                boolean sent = false;
                for (String t : tags) {
                    Map<String, List<TudeySceneModel.Entry>> m = sends.get(t);
                    if (m != null && m.containsKey(signal)) sent = true;
                }
                if (!sent) out.add(new Issue(Severity.INFO, WireModel.describe(e, cfg) + " reacts to " + quote(signal) + (tags.isEmpty() ? "" : " on the tag " + quote(tags.get(0))) + ", and nothing in the scene sends it (the game may)", e, List.of(e)));
            }
        }
        // identical things at one place
        Map<String, List<TudeySceneModel.Entry>> stacked = new LinkedHashMap<>();
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            if (!(e instanceof TudeySceneModel.PlaceableEntry)) continue;
            try {
                com.threerings.math.Vector2f at = e.getTranslation(cfg);
                if (at == null) continue;
                String key = configName(e) + "|" + Math.round(at.x * 20f) + "|" + Math.round(at.y * 20f) + "|" + e.getElevation() + "|" + ((TudeySceneModel.PlaceableEntry) e).placeable.getArguments();
                stacked.computeIfAbsent(key, x -> new ArrayList<>()).add(e);
            } catch (RuntimeException ex) {
                // no place
            }
        }
        for (List<TudeySceneModel.Entry> group : stacked.values()) {
            if (group.size() > 1) out.add(new Issue(Severity.WARNING, group.size() + " identical props at the same place: " + WireModel.describe(group.get(0), cfg), group.get(0), group));
        }
        // shapes without points; configurations that are gone; props over no floor
        int floating = 0;
        TudeySceneModel.Entry firstFloating = null;
        List<TudeySceneModel.Entry> floatingAll = new ArrayList<>();
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            try {
                if (e instanceof TudeySceneModel.AreaEntry && ((TudeySceneModel.AreaEntry) e).vertices.length == 0) out.add(new Issue(Severity.WARNING, "an area with no points: " + WireModel.describe(e, cfg), e, List.of(e)));
                if (e instanceof TudeySceneModel.PathEntry && ((TudeySceneModel.PathEntry) e).vertices.length < 2) out.add(new Issue(Severity.WARNING, "a path with fewer than two points: " + WireModel.describe(e, cfg), e, List.of(e)));
                if (WireModel.hasConfig(e) && !configName(e).isEmpty() && !configExists(e, cfg)) out.add(new Issue(Severity.ERROR, WireModel.describe(e, cfg) + " refers to a configuration that does not exist (" + configName(e) + ")", e, List.of(e)));
                if (e instanceof TudeySceneModel.PlaceableEntry && seenInGame((TudeySceneModel.PlaceableEntry) e, cfg)) {
                    com.threerings.math.Vector2f at = e.getTranslation(cfg);
                    if (at != null && scene.getTileEntry((int) Math.floor(at.x), (int) Math.floor(at.y)) == null) {
                        floating++;
                        floatingAll.add(e);
                        if (firstFloating == null) firstFloating = e;
                    }
                }
            } catch (RuntimeException ex) {
                // not readable: nothing to say
            }
        }
        if (floating > 0) out.add(new Issue(Severity.INFO, floating + " prop" + (floating == 1 ? " that is" : "s that are") + " drawn in the game " + (floating == 1 ? "is" : "are") + " not over any floor tile (e.g. " + WireModel.describe(firstFloating, cfg) + "); markers, triggers and notes are not counted", firstFloating, floatingAll));
        out.sort(Comparator.comparing((Issue i) -> i.severity).thenComparing(i -> i.text));
        return out;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    // ---- tags ----

    /** Things with one configuration (a group of the holders or of the senders of a tag). */
    static final class Group {
        final String kind;
        final String config;
        final List<TudeySceneModel.Entry> entries = new ArrayList<>();
        final Set<String> signals = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        Group(String kind, String config) {
            this.kind = kind;
            this.config = config;
        }
    }

    static final class TagInfo {
        final String tag;
        final List<TudeySceneModel.Entry> holders = new ArrayList<>();
        /** The things that send signals to the tag. */
        final List<TudeySceneModel.Entry> senders = new ArrayList<>();
        final Set<String> kinds = new TreeSet<>();
        /** signal to the number of things that send it to this tag */
        final Map<String, Integer> sent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        /** What the holders react to, other than what the game sends itself. */
        final Set<String> heard = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        /** What the holders react to that the game sends itself ("capture:0"). */
        final Set<String> fromGame = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        /** The holders by what they are (a kind and a configuration), and the senders likewise. */
        final Map<String, Group> holderGroups = new TreeMap<>();
        final Map<String, Group> senderGroups = new TreeMap<>();
        /** True when the tag can be changed in at least one holder (it is an argument), so the tag can be renamed. */
        boolean renamable;

        TagInfo(String tag) {
            this.tag = tag;
        }

        /** Nothing has the tag (it is only sent to). */
        boolean dangling() {
            return holders.isEmpty();
        }

        /** Something has it and reacts to signals, and none is sent (and the game does not send them). */
        boolean unused() {
            if (holders.isEmpty() || heard.isEmpty()) return false;
            for (String h : heard) if (sent.containsKey(h)) return false;
            return true;
        }
    }

    static List<TagInfo> tags(TudeySceneModel scene, ConfigManager cfg) {
        Cache cache = new Cache();
        Map<String, TagInfo> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            if (!WireModel.hasConfig(e)) continue;
            List<String> tags = cache.tagsOf(e, cfg);
            if (tags.isEmpty()) continue;
            Set<String> hears = cache.heardBy(e, cfg);
            boolean ctl = cache.renamable(e, cfg);
            String kind = kindOf(e), name = configName(e);
            for (String t : tags) {
                TagInfo i = map.computeIfAbsent(t, TagInfo::new);
                i.holders.add(e);
                i.kinds.add(kind);
                for (String h : hears) (ServerSignals.sent(h) ? i.fromGame : i.heard).add(h);
                if (ctl && t.equals(tags.get(0))) i.renamable = true;
                i.holderGroups.computeIfAbsent(kind + " " + name, k -> new Group(kind, name)).entries.add(e);
            }
        }
        for (var byTag : sendsByTag(scene, cfg).entrySet()) {
            TagInfo i = map.computeIfAbsent(byTag.getKey(), TagInfo::new);
            for (var bySignal : byTag.getValue().entrySet()) {
                i.sent.merge(bySignal.getKey(), bySignal.getValue().size(), Integer::sum);
                for (TudeySceneModel.Entry s : bySignal.getValue()) {
                    if (!i.senders.contains(s)) i.senders.add(s);
                    Group g = i.senderGroups.computeIfAbsent(kindOf(s) + " " + configName(s), k -> new Group(kindOf(s), configName(s)));
                    if (!g.entries.contains(s)) g.entries.add(s);
                    g.signals.add(bySignal.getKey());
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Gives the things that have the tag {@code from} the tag {@code to}, and makes everything that sends to {@code from} send to {@code to}, as
     * one change. What cannot be renamed (a tag that is fixed in a configuration) is said in the notes.
     */
    static WireModel.Change renameTag(TudeySceneModel scene, ConfigManager cfg, String from, String to) {
        WireModel.Change change = new WireModel.Change();
        if (to == null || to.trim().isEmpty() || to.trim().equals(from)) {
            change.notes.add("nothing to do");
            return change;
        }
        String nw = to.trim();
        int renamed = 0, fixed = 0, sendersChanged = 0;
        for (TudeySceneModel.Entry e : new ArrayList<>(scene.getEntries())) {
            if (!WireModel.hasConfig(e)) continue;
            if (from.equals(WireModel.tagOf(e, cfg))) {
                WireModel.Control ctl = WireModel.tagControl(e, cfg);
                if (ctl == null) {
                    fixed++;
                    change.notes.add(WireModel.describe(e, cfg) + " has the tag fixed in its configuration: not renamed");
                } else {
                    ctl.write(WireModel.copyIn(change, e), nw);
                    renamed++;
                }
            }
        }
        for (TudeySceneModel.Entry e : new ArrayList<>(scene.getEntries())) {
            if (!WireModel.hasConfig(e) || e instanceof TudeySceneModel.TileEntry) continue;
            for (WireModel.Slot s : WireModel.slots(e, cfg)) {
                List<WireModel.Hop> hops = s.sends(scene, cfg);
                boolean any = false;
                for (WireModel.Hop h : hops) if (from.equals(h.tag)) any = true;
                if (!any || !s.editable()) continue;
                List<WireModel.Hop> next = new ArrayList<>();
                for (WireModel.Hop h : hops) next.add(from.equals(h.tag) ? new WireModel.Hop(h.signal, nw) : h);
                s.set(scene, cfg, next, change);
                sendersChanged++;
            }
        }
        change.notes.add("renamed the tag " + from + " to " + nw + ": " + renamed + " thing" + (renamed == 1 ? "" : "s") + ", " + sendersChanged + " sender" + (sendersChanged == 1 ? "" : "s") + (fixed > 0 ? ", " + fixed + " left (fixed in the configuration)" : ""));
        return change;
    }

    // ---- replace ----

    static final class Used {
        final String name;
        final String kind;
        int count;

        Used(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    /** The configurations the scene uses, with how many things use each: what Replace offers to replace. */
    static List<Used> usedConfigs(TudeySceneModel scene) {
        Map<String, Used> map = new TreeMap<>();
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            String n = configName(e);
            if (n.isEmpty()) continue;
            map.computeIfAbsent(kindOf(e) + "|" + n, k -> new Used(n, kindOf(e))).count++;
        }
        return new ArrayList<>(map.values());
    }

    /** The things that use a configuration (of the kind), at most {@code limit} when it is given (the rest are not looked for). */
    static List<TudeySceneModel.Entry> usersOf(TudeySceneModel scene, String kind, String name, Set<Object> only) {
        List<TudeySceneModel.Entry> out = new ArrayList<>();
        for (TudeySceneModel.Entry e : scene.getEntries()) {
            if (!kind.equals(kindOf(e)) || !name.equals(configName(e))) continue;
            if (only != null && !only.contains(e.getKey())) continue;
            out.add(e);
        }
        return out;
    }

    /** The names of the configurations a thing could be given in place of its own (those of its configuration type). */
    static List<String> alternatives(ConfigManager cfg, TudeySceneModel.Entry sample) {
        List<String> out = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Class<ManagedConfig> type = (Class<ManagedConfig>) WireModel.cls(sample);
            com.threerings.config.ConfigGroup<ManagedConfig> group = cfg.getGroup(type);
            if (group != null) for (ManagedConfig c : group.getConfigs()) out.add(c.getName());
        } catch (RuntimeException ex) {
            // none offered
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Gives every thing whose configuration is {@code from} (or only those whose key is in {@code only}) the configuration {@code to}, which must be
     * one of the same type. Position, rotation and elevation stay; the arguments stay too when {@code keepArguments}.
     */
    static WireModel.Change replace(TudeySceneModel scene, ConfigManager cfg, String from, String to, boolean keepArguments, Set<Object> only) {
        WireModel.Change change = new WireModel.Change();
        int n = 0;
        for (TudeySceneModel.Entry e : new ArrayList<>(scene.getEntries())) {
            if (!WireModel.hasConfig(e) || !from.equals(configName(e))) continue;
            if (only != null && !only.contains(e.getKey())) continue;
            @SuppressWarnings("unchecked")
            Class<ManagedConfig> type = (Class<ManagedConfig>) WireModel.cls(e);
            if (cfg.getRawConfig(type, to) == null) {
                change.notes.add("there is no " + type.getSimpleName() + " called " + to);
                return change;
            }
            TudeySceneModel.Entry copy = WireModel.copyIn(change, e);
            ConfigReference<?> nw = new ConfigReference<>(to);
            if (keepArguments) {
                ArgumentMap old = WireModel.ref(e).getArguments();
                if (old != null && !old.isEmpty()) {
                    ArgumentMap into = WireModel.args(nw, true);
                    for (Map.Entry<String, Object> en : old.entrySet()) into.put(en.getKey(), en.getValue());
                }
            }
            copy.setReference(nw);
            n++;
        }
        change.notes.add("replaced " + from + " by " + to + " in " + n + " thing" + (n == 1 ? "" : "s"));
        return change;
    }

    static Set<Object> keysOf(TudeySceneModel.Entry[] entries) {
        Set<Object> keys = new HashSet<>();
        for (TudeySceneModel.Entry e : entries) keys.add(e.getKey());
        return keys;
    }
}
