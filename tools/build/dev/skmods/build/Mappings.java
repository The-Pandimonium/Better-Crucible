package dev.skmods.build;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dev.skmods.build.Builder.BuildFailed;

/**
 * The names of the game's classes (docs/mappings.md): what the mapping in mappings/ fits, and making it fit a new build of the game.
 *
 * <pre>
 *   ./sk mappings status                 which build of the game the mapping is for, and whether that is the one installed
 *   ./sk mappings update [options]       after the game was updated: new names from the new jar, the old names carried over to it
 *   ./sk mappings edit                   Enigma's window, on the game's jars and the mapping, to name things by hand
 *   ./sk mappings check                  compiles everything against the mapping and lists what does not compile (after an update)
 *
 *   update options:   fresh            do not carry the old names over (names are made again from the libraries and mappings/personal)
 *                     previous=JAR     the old build's projectx-pcode.jar, when it is not in the cache
 *                     latest           read the newest sources of the open-source libraries, not the revisions in mappings/source-revisions.txt
 *                     force            go on although the mapping already fits the installed game
 * </pre>
 */
final class Mappings {
    static final String PCODE = "projectx-pcode.jar", CONFIG = "projectx-config.jar";
    /** the open-source libraries the game's code was built with: name, where they are, and the folder of their sources */
    static final String[][] LIBRARIES = {
        {"samskivert", "https://github.com/samskivert/samskivert.git", "src/main/java"},
        {"clyde", "https://github.com/threerings/clyde.git", "core/src/main/java"},
        {"narya", "https://github.com/threerings/narya.git", "core/src/main/java"},
        {"nenya", "https://github.com/threerings/nenya.git", "core/src/main/java"},
    };

    final Builder b;
    final Path props, revisions, jarCache, work;

    Mappings(Builder b) {
        this.b = b;
        this.props = b.mappingsDir.resolve("mapping.properties");
        this.revisions = b.mappingsDir.resolve("source-revisions.txt");
        this.jarCache = b.cache.resolve("jars");
        this.work = b.cache.resolve("mappings");
    }

    void run(String[] a) throws Exception {
        if (a.length == 0) throw new BuildFailed("mappings what? status | update | edit | check (docs/mappings.md)");
        Set<String> flags = new HashSet<>();
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq > 0) values.put(a[i].substring(0, eq), a[i].substring(eq + 1));
            else flags.add(a[i]);
        }
        switch (a[0]) {
            case "status":
                status();
                break;
            case "update":
                b.setup();
                update(flags, values.get("previous"));
                break;
            case "edit":
                edit();
                break;
            case "check":
                b.setup();
                check();
                break;
            default:
                throw new BuildFailed("unknown mappings command " + a[0]);
        }
    }

    // ---- what the mapping fits ----

    Properties read() throws IOException {
        Properties p = new Properties();
        if (Files.exists(props)) try (var in = Files.newInputStream(props)) {
            p.load(in);
        }
        return p;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    String gameVersion() {
        try {
            for (String line : Files.readAllLines(b.game.resolve("getdown.txt"))) if (line.startsWith("version =")) return line.substring(9).trim();
        } catch (IOException ignored) {
            // not written
        }
        return "unknown";
    }

    /** whether the mapping is the one for the installed game: true, false, or null when nothing is recorded */
    Boolean fits() throws IOException {
        Properties p = read();
        String have = p.getProperty(PCODE);
        if (have == null) return null;
        return have.equals(sha256(b.code.resolve(PCODE)));
    }

    /** the share of the mapping's top-level classes that are in the jar under the obfuscated name written there: about 1 when the mapping is for this jar */
    double classFit() throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile z = new ZipFile(b.code.resolve(PCODE).toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) if (e.getName().endsWith(".class")) names.add(e.getName().substring(0, e.getName().length() - 6));
        }
        int have = 0, total = 0;
        for (String line : Files.readAllLines(b.mapping)) {
            if (!line.startsWith("CLASS ")) continue;
            String[] t = line.split("\\s+");
            if (t.length < 3) continue; // no new name given
            total++;
            if (names.contains(t[1])) have++;
        }
        return total == 0 ? 0 : (double) have / total;
    }

    void status() throws Exception {
        b.locate();
        Properties p = read();
        System.out.println("installed game build: " + gameVersion());
        System.out.println("mapping is for build: " + p.getProperty("game.version", "(not recorded)"));
        Boolean f = fits();
        if (f == null) {
            System.out.printf("nothing is recorded in mappings/mapping.properties; %.1f%% of the mapping's classes are in the installed jar%n", classFit() * 100);
            System.out.println("(./sk setup records it when that is nearly all of them)");
        } else if (f) {
            System.out.println("the mapping fits the installed game");
        } else {
            System.out.println("the game changed: the mapping does not fit it any more. Run ./sk mappings update");
        }
        Path prev = cachedJar(p.getProperty(PCODE), PCODE);
        System.out.println("the jar the mapping is for is " + (prev != null ? "in the cache (" + b.root.relativize(prev) + "): old names can be carried over" : "not in the cache (an update will make the names afresh unless you give previous=JAR)"));
    }

    /** a warning when the installed game is not the one the mapping is for (the deobfuscated jar would then have wrong names) */
    static void warnIfStale(Builder b) throws IOException {
        Mappings m = new Mappings(b);
        Boolean f = m.fits();
        if (f != null && !f) {
            System.out.println();
            System.out.println("!! The game was updated (it is build " + m.gameVersion() + ", the mapping is for build " + m.read().getProperty("game.version", "?") + ").");
            System.out.println("!! Names that did not change are right, the rest are wrong or missing. Run:   ./sk mappings update");
            System.out.println();
        }
    }

    /**
     * Called when the deobfuscated jar was made: when the mapping fits the installed game the game's two jars are kept in work/cache/jars (the next update
     * needs the old ones to carry the names over, and the game's own folder will have the new ones by then). When nothing was recorded and the mapping is
     * clearly for this jar, that is recorded.
     */
    static void cacheIfFits(Builder b) throws IOException {
        Mappings m = new Mappings(b);
        Boolean f = m.fits();
        if (f == null && m.classFit() > 0.99) {
            m.record();
            f = true;
        }
        if (f == null || !f) return;
        String sha = m.read().getProperty(PCODE);
        for (String j : List.of(PCODE, CONFIG)) {
            Path to = m.jarCache.resolve(sha.substring(0, 12)).resolve(j);
            if (!Files.exists(to)) {
                Files.createDirectories(to.getParent());
                Files.copy(b.code.resolve(j), to);
            }
        }
    }

    void record() throws IOException {
        Properties p = read();
        p.setProperty("game.version", gameVersion());
        p.setProperty(PCODE, sha256(b.code.resolve(PCODE)));
        p.setProperty(CONFIG, sha256(b.code.resolve(CONFIG)));
        store(p);
    }

    void store(Properties p) throws IOException {
        StringBuilder sb = new StringBuilder("# What mappings/projectx-pcode.mapping is for: the build of the game and the hashes of its two code jars. Written by ./sk mappings update.\n");
        for (String k : new TreeSet<>(p.stringPropertyNames())) sb.append(k).append('=').append(p.getProperty(k)).append('\n');
        Files.writeString(props, sb.toString());
    }

    Path cachedJar(String sha, String jar) {
        if (sha == null) return null;
        Path p = jarCache.resolve(sha.substring(0, 12)).resolve(jar);
        return Files.exists(p) ? p : null;
    }

    // ---- an update ----

    void update(Set<String> flags, String previousOpt) throws Exception {
        Properties old = read();
        Boolean f = fits();
        if (Boolean.TRUE.equals(f) && !flags.contains("force")) {
            System.out.println("the mapping already fits the installed game (build " + gameVersion() + "): nothing to do (force makes the names again anyway)");
            return;
        }
        boolean fresh = flags.contains("fresh");
        Files.createDirectories(work);
        String stamp = String.valueOf(System.currentTimeMillis());

        // 1. the sources of the open-source libraries
        List<Path> roots = librarySources(flags.contains("latest"));

        // 2. the names the libraries give
        Path libMapping = work.resolve("source-libraries.mapping"), libReport = work.resolve("source-report.md");
        List<String> cmd = new ArrayList<>(List.of("-Xmx4g", b.root.resolve("tools/mappings/SourceMatch.java").toString(), b.code.resolve(PCODE).toString(), libMapping.toString(), libReport.toString()));
        for (Path r : roots) cmd.add(r.toString());
        java(cmd);

        // 3. the names of the old build, carried over to the new jar by the shape of the classes
        Path carried = null;
        if (!fresh) {
            Path prevPcode = previousOpt != null ? Path.of(previousOpt) : cachedJar(old.getProperty(PCODE), PCODE);
            if (prevPcode == null || !Files.exists(prevPcode)) {
                throw new BuildFailed("the old build's jar is not in the cache (work/cache/jars), so its names cannot be carried over.\n"
                    + "  - give it:  ./sk mappings update previous=PATH/projectx-pcode.jar   (the old jar from a backup of the game)\n"
                    + "  - or start the names afresh (the guessed names change, so more of the code stops compiling):  ./sk mappings update fresh");
            }
            Path prevConfig = prevPcode.resolveSibling(CONFIG);
            Path prevCode = work.resolve("previous-code");
            Builder.deleteTree(prevCode);
            Files.createDirectories(prevCode);
            Files.copy(prevPcode, prevCode.resolve(PCODE));
            if (Files.exists(prevConfig)) Files.copy(prevConfig, prevCode.resolve(CONFIG));
            else Files.copy(b.code.resolve(CONFIG), prevCode.resolve(CONFIG)); // the config jar is only a few classes
            Path prevDeobf = work.resolve("previous-deobf.jar");
            Remap.deobfJar(b.mapping, b.enigma, prevCode, prevDeobf);
            carried = work.resolve("carried.mapping");
            java(List.of("-Xmx4g", b.root.resolve("tools/mappings/SourceMatch.java").toString(), "--previous", prevDeobf.toString(), b.code.resolve(PCODE).toString(), carried.toString(), work.resolve("carried-report.md").toString()));
        }

        // 4. the whole mapping: the carried names, the hand-made ones, the library ones, and names read out of the code for the rest
        Path out = work.resolve("projectx-pcode.mapping"), report = work.resolve("mapping-report.md");
        List<String> mapper = new ArrayList<>(List.of("-Xmx4g", b.root.resolve("tools/mappings/Mapper.java").toString(), b.game.toString(), out.toString(), report.toString()));
        if (carried != null) mapper.addAll(List.of("--carry", carried.toString()));
        else mapper.addAll(List.of("--personal", b.mappingsDir.resolve("personal").toString()));
        mapper.addAll(List.of("--source", libMapping.toString()));
        java(mapper);

        // 5. in place (the old mapping is kept in the cache)
        if (Files.exists(b.mapping)) Files.copy(b.mapping, work.resolve("projectx-pcode-" + stamp + ".mapping.old"));
        Files.copy(out, b.mapping, StandardCopyOption.REPLACE_EXISTING);
        Path reports = b.mappingsDir.resolve("reports");
        Files.createDirectories(reports);
        Files.copy(report, reports.resolve("mapping-report.md"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(libReport, reports.resolve("source-report.md"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(libMapping, b.mappingsDir.resolve("source-libraries.mapping"), StandardCopyOption.REPLACE_EXISTING);
        Path conflicts = Path.of(report + ".conflicts.md");
        if (Files.exists(conflicts)) Files.copy(conflicts, reports.resolve("conflicts.md"), StandardCopyOption.REPLACE_EXISTING);
        record();

        // 6. the new jar to compile against, and the cache for the next update
        b.map = Mapping.load(b.mapping);
        b.setup(true);
        System.out.println();
        System.out.println("The mapping now fits the game (build " + gameVersion() + "). " + (fresh ? "The names were made afresh." : "The old names were carried over to it."));
        System.out.println("Next:  ./sk mappings check    (lists the code that does not compile against the new names; fix it, then ./sk all)");
    }

    /** the sources of the libraries: cloned into work/cache/sources (at the revisions of mappings/source-revisions.txt unless the newest is asked for) */
    List<Path> librarySources(boolean latest) throws Exception {
        Map<String, String> pinned = new HashMap<>();
        if (Files.exists(revisions)) for (String line : Files.readAllLines(revisions)) {
            String[] t = line.trim().split("\\s+");
            if (t.length >= 2) pinned.put(t[0], t[1]);
        }
        Path srcs = b.cache.resolve("sources");
        Files.createDirectories(srcs);
        List<Path> roots = new ArrayList<>();
        StringBuilder rev = new StringBuilder();
        for (String[] lib : LIBRARIES) {
            Path dir = srcs.resolve(lib[0]);
            if (!Files.isDirectory(dir.resolve(".git"))) {
                System.out.println("cloning " + lib[1]);
                b.exec(List.of("git", "clone", "--quiet", lib[1], dir.toString()));
            } else if (latest) {
                b.exec(List.of("git", "-C", dir.toString(), "pull", "--quiet"));
            }
            String want = latest ? null : pinned.get(lib[0]);
            if (want != null) b.exec(List.of("git", "-C", dir.toString(), "checkout", "--quiet", want));
            String head = gitOut(dir, "rev-parse", "--short=7", "HEAD");
            rev.append(lib[0]).append(' ').append(head).append(' ').append(lib[1]).append('\n');
            roots.add(dir.resolve(lib[2]));
        }
        Files.writeString(revisions, rev.toString());
        return roots;
    }

    String gitOut(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return s;
    }

    /** runs a single-file program of tools/mappings with the JDK this runs on */
    void java(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Builder.javaExe());
        cmd.addAll(args);
        b.exec(cmd);
    }

    // ---- the window ----

    void edit() throws Exception {
        b.locate();
        Path swing = b.findEnigma("swing");
        if (swing == null) throw new BuildFailed("Enigma's window is not in lib/ (lib/enigma-swing-*.jar)");
        Path merged = b.deobfDir.resolve("game-merged.jar");
        Remap.mergeJars(b.code, merged);
        System.out.println("Enigma opens the game's code and mappings/projectx-pcode.mapping: save with File > Save Mappings (it writes the same file)");
        b.exec(List.of(Builder.javaExe(), "-Xmx3g", "-jar", swing.toString(), "-jar", merged.toString(), "-mappings", b.mapping.toString()));
    }

    // ---- checking the code against the mapping ----

    /** compiles the server, its tests and every mod, one after the other, and prints what does not compile in each (an update changes names; this finds where the code used them) */
    void check() throws Exception {
        List<String> failed = new ArrayList<>();
        try {
            b.emuBuild();
            System.out.println("the server compiles");
        } catch (BuildFailed e) {
            failed.add("the server (emulator/src)");
            System.out.println(e.getMessage());
        }
        try {
            if (failed.isEmpty()) {
                b.testCompile();
                System.out.println("the tests compile");
            }
        } catch (BuildFailed e) {
            failed.add("the tests (emulator/test)");
            System.out.println(e.getMessage());
        }
        for (String id : b.modIds()) {
            try {
                b.mod(id);
            } catch (BuildFailed e) {
                failed.add("mod " + id);
                System.out.println(e.getMessage());
            }
        }
        if (failed.isEmpty()) {
            System.out.println("everything compiles against the mapping");
            // compiling says the readable names are all there; playing says the names the code gives to reflection, and the game's own behavior, still fit
            if (Files.isDirectory(b.root.resolve("emulator"))) try {
                System.out.println("smoke test: a real client plays a Lockdown match on this build ...");
                b.smoke();
                System.out.println("the smoke test passes");
            } catch (BuildFailed e) {
                failed.add("the smoke test (JOPTS=-Demu.only=arena ./sk test client shows it)");
                System.out.println(e.getMessage());
            }
        }
        if (failed.isEmpty()) System.out.println("the update is done: ./sk all");
        else throw new BuildFailed("does not compile against the new names: " + failed.stream().collect(Collectors.joining(", ")));
    }
}
