package dev.skmods.editor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Where the mod keeps what it keeps. Knight Launcher makes a folder for each mod, {@code <the game's folder>/mods/config/crucible-editor-controls}: the settings (the
 * controls profiles, the tool layout, the toggles of the menus) are a file in it ({@code settings.properties}, which can be read, copied to another computer or
 * deleted). Nothing is kept in the system's registry (where Java's preferences go on Windows), and nothing
 * depends on where the game is installed: its folder is found from the game's own properties and class path, on any drive.
 *
 * <p>What an earlier version of the mod left in the registry is read once, into the file, the first time.
 */
public final class ModConfig {
    private ModConfig() {
    }

    /** The folder name Knight Launcher gives this mod. */
    public static final String FOLDER = "crucible-editor-controls";

    private static Path game;
    private static Path dir;
    private static FilePrefs root;

    /**
     * The game's folder (the one with {@code code/projectx-pcode.jar}), found without assuming where it is: the {@code appdir} property of the game, the folder of the
     * jars on the class path, the resource folder's parent, the working folder. Null when none of them is it.
     */
    public static synchronized Path game() {
        if (game != null) return game;
        List<Path> tries = new ArrayList<>();
        String appdir = System.getProperty("appdir");
        if (appdir != null && !appdir.isBlank()) tries.add(Path.of(appdir));
        String rsrc = System.getProperty("resource_dir");
        if (rsrc != null && !rsrc.isBlank()) {
            Path p = Path.of(rsrc).toAbsolutePath();
            tries.add(p.getParent() == null ? p : p.getParent());
            tries.add(p);
        }
        String cp = System.getProperty("java.class.path", "");
        for (String e : cp.split(java.io.File.pathSeparator)) {
            if (e.isBlank()) continue;
            Path p = Path.of(e).toAbsolutePath();
            Path up = p.getParent();
            if (up != null && up.getFileName() != null && up.getFileName().toString().equals("code")) tries.add(up.getParent()); // <game>/code/projectx-pcode.jar
            if (up != null) tries.add(up);
        }
        tries.add(Path.of("").toAbsolutePath());
        for (Path p : tries) {
            try {
                if (p != null && Files.isRegularFile(p.resolve("code").resolve("projectx-pcode.jar"))) return game = p.toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                // not a path
            }
        }
        return null;
    }

    /** The mod's folder in the game's {@code mods/config} (made when it is not there), or a folder of the user's own when the game's cannot be written to. */
    public static synchronized Path dir() {
        if (dir != null) return dir;
        Path g = game();
        List<Path> tries = new ArrayList<>();
        if (g != null) tries.add(g.resolve("mods").resolve("config").resolve(FOLDER));
        tries.add(Path.of(System.getProperty("user.home"), ".sk-crucible-controls"));
        for (Path p : tries) {
            try {
                Files.createDirectories(p);
                if (Files.isWritable(p)) return dir = p;
            } catch (IOException | RuntimeException e) {
                // the next one
            }
        }
        return dir = Path.of(System.getProperty("java.io.tmpdir"), "sk-crucible-controls");
    }

    /** The settings, as {@link Preferences} that live in a file of the mod's folder (one node: the keys of the mod's settings are the keys of the file). */
    public static synchronized Preferences settings() {
        if (root == null) {
            root = new FilePrefs(dir().resolve("settings.properties"));
            root.importOld();
        }
        return root;
    }

    /**
     * Preferences kept in one properties file: a node's keys are written as {@code node/path/key=value}. Loaded when made, written (a moment later, at most 1 s) after
     * every change.
     */
    private static final class FilePrefs extends AbstractPreferences {
        private final Properties props;
        private final Path file;
        private final String path;
        private final FilePrefs top;
        private volatile long dirtyAt;

        /** The root. */
        FilePrefs(Path file) {
            super(null, "");
            this.file = file;
            this.props = new Properties();
            this.path = "";
            this.top = this;
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    System.err.println("[crucible-controls] cannot read " + file + ": " + e);
                }
            }
        }

        private FilePrefs(FilePrefs parent, String name) {
            super(parent, name);
            this.file = parent.file;
            this.props = parent.props;
            this.path = parent.path.isEmpty() ? name : parent.path + "/" + name;
            this.top = parent.top;
            newNode = !hasAnyKey();
        }

        private boolean hasAnyKey() {
            String pre = path + "/";
            synchronized (props) {
                for (String k : props.stringPropertyNames()) if (k.startsWith(pre)) return true;
            }
            return false;
        }

        private String k(String key) {
            return path.isEmpty() ? key : path + "/" + key;
        }

        @Override
        protected void putSpi(String key, String value) {
            synchronized (props) {
                props.setProperty(k(key), value);
            }
            changed();
        }

        @Override
        protected String getSpi(String key) {
            synchronized (props) {
                return props.getProperty(k(key));
            }
        }

        @Override
        protected void removeSpi(String key) {
            synchronized (props) {
                props.remove(k(key));
            }
            changed();
        }

        @Override
        protected void removeNodeSpi() {
            String pre = path + "/";
            synchronized (props) {
                for (String key : new ArrayList<>(props.stringPropertyNames())) if (key.startsWith(pre)) props.remove(key);
            }
            changed();
        }

        @Override
        protected String[] keysSpi() {
            String pre = path.isEmpty() ? "" : path + "/";
            TreeSet<String> out = new TreeSet<>();
            synchronized (props) {
                for (String key : props.stringPropertyNames()) if (key.startsWith(pre) && key.indexOf('/', pre.length()) < 0) out.add(key.substring(pre.length()));
            }
            return out.toArray(new String[0]);
        }

        @Override
        protected String[] childrenNamesSpi() {
            String pre = path.isEmpty() ? "" : path + "/";
            TreeSet<String> out = new TreeSet<>();
            synchronized (props) {
                for (String key : props.stringPropertyNames()) {
                    if (!key.startsWith(pre)) continue;
                    int slash = key.indexOf('/', pre.length());
                    if (slash > 0) out.add(key.substring(pre.length(), slash));
                }
            }
            return out.toArray(new String[0]);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return new FilePrefs(this, name);
        }

        @Override
        protected void syncSpi() {
            // the file is the only copy
        }

        @Override
        protected void flushSpi() throws BackingStoreException {
            save();
        }

        private void changed() {
            top.dirtyAt = System.currentTimeMillis();
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - top.dirtyAt >= 650) {
                    try {
                        top.save();
                    } catch (BackingStoreException e) {
                        System.err.println("[crucible-controls] " + e.getMessage());
                    }
                }
            }, "crucible-controls-settings");
            t.setDaemon(true);
            t.start();
        }

        void save() throws BackingStoreException {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                synchronized (props) {
                    try (OutputStream out = Files.newOutputStream(tmp)) {
                        props.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), "Crucible Editor Controls: settings (the controls profiles, the tool layout, the toggles of the menus)");
                    }
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new BackingStoreException("cannot write " + file + ": " + e);
            }
        }

        /** What earlier versions of the mod put in the system's preferences, taken over once (when the file has nothing yet). */
        void importOld() {
            try {
                if (!props.isEmpty()) return;
                String old = "dev/skmods/crucible-controls";
                if (Preferences.userRoot().nodeExists(old)) copy(Preferences.userRoot().node(old), this);
                if (!props.isEmpty()) save();
            } catch (BackingStoreException | RuntimeException e) {
                // nothing to take over, or the system's preferences cannot be read
            }
        }

        private static void copy(Preferences from, Preferences to) throws BackingStoreException {
            for (String key : from.keys()) to.put(key, from.get(key, ""));
            for (String child : from.childrenNames()) copy(from.node(child), to.node(child));
        }
    }
}
