package dev.skmods.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * Signals the game's server sends by itself (a control point sends {@code capture:-1}, {@code capture:0} or {@code capture:1}), as patterns where a
 * {@code *} stands for anything. Nothing in a scene sends them, so the tools must not say that nothing does. The list can be changed in the Tools window.
 */
final class ServerSignals {
    private ServerSignals() {
    }

    private static final String NODE = "dev/skmods/crucible-controls";
    static final List<String> DEFAULT = List.of("capture:*");

    private static List<String> patterns;
    private static List<Pattern> compiled;

    static synchronized List<String> patterns() {
        if (patterns == null) {
            String saved = null;
            try {
                saved = ModConfig.settings().get("serverSignals", null);
            } catch (RuntimeException e) {
                // the defaults
            }
            patterns = new ArrayList<>();
            if (saved == null) patterns.addAll(DEFAULT);
            else for (String p : saved.split(",")) if (!p.isBlank()) patterns.add(p.trim());
            compile();
        }
        return new ArrayList<>(patterns);
    }

    static synchronized void set(List<String> next) {
        patterns = new ArrayList<>();
        for (String p : next) if (!p.isBlank()) patterns.add(p.trim());
        try {
            ModConfig.settings().put("serverSignals", String.join(",", patterns));
        } catch (RuntimeException e) {
            // kept for this session
        }
        compile();
    }

    private static void compile() {
        compiled = new ArrayList<>();
        for (String p : patterns) {
            StringBuilder re = new StringBuilder();
            for (String part : p.toLowerCase(Locale.ROOT).split("\\*", -1)) {
                if (re.length() > 0) re.append(".*");
                re.append(Pattern.quote(part));
            }
            compiled.add(Pattern.compile(re.toString()));
        }
    }

    /** True when the game itself sends a signal of this name. */
    static synchronized boolean sent(String signal) {
        if (signal == null) return false;
        if (compiled == null) patterns();
        String s = signal.toLowerCase(Locale.ROOT);
        for (Pattern p : compiled) if (p.matcher(s).matches()) return true;
        return false;
    }
}
