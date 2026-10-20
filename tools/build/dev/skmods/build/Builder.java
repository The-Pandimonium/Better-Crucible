package dev.skmods.build;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.*;
import java.util.zip.*;
import javax.tools.*;
import dev.skmods.modapi.*;

/**
 * Builds, tests and runs everything of this project. The one implementation behind {@code ./sk} (sk.bat), the Ant targets of build.xml (IntelliJ) and
 * the batch files in scripts/:
 *
 * <pre>
 *   ./sk COMMAND [ARGS]                                        (run it in the project folder; docs/building.md says more)
 *
 *   setup                 the first thing: libraries downloaded, the game's jars copied, the deobfuscated jar made
 *   deobf [force]         work/deobf/game-deobf.jar: the game's code with the readable names (made again when the game or the mapping changed)
 *   decompile             the game's code as readable source in work/deobf/src
 *   emu-jar               work/dist/sk-emu.jar        (the server)
 *   mod ID | mods         work/dist/sk-ID.zip         (a client mod, or all of them)
 *   all                   the server and every mod
 *   new-mod ID            a new mod in mods/ID, ready to build        list: the mods there are
 *   run [ARGS]            starts the server (its options: ./sk run --help)
 *   test compile          compiles the server's tests
 *   test run CLASS [ARGS] runs a test class (JOPTS="-Dx=y" adds options to the java line)       test client: the real-client test
 *   test editor CLASS [ARGS]   the headless checks of the Crucible editor mod on a scene file
 *   mappings status|update|edit|check       the names of the game's classes: docs/mappings.md
 *   obfuscate-ide         what IntelliJ compiled turned back to the game's names (its run configurations run that)
 *   clean [all]           deletes what was built (all: and what was made from the game's files, which setup makes again); never the server's data
 * </pre>
 *
 * The code is written with the readable names of the game's classes (mappings/) and compiled against work/deobf/game-deobf.jar; what is built is
 * then turned back to the game's real names (docs/building.md).
 *
 * Settings, from the environment (SK_GAME_DIR, ENIGMA_JAR) or local.properties (game.dir, enigma.jar): where the game is, and the Enigma CLI.
 */
public final class Builder {
    // ---- the layout of the project ----
    final Path root = Path.of("").toAbsolutePath();
    /** everything made from the game's files, and everything built: none of it is in git */
    final Path work = root.resolve("work");
    final Path cache = work.resolve("cache");
    final Path deobfDir = work.resolve("deobf");
    final Path deobf = deobfDir.resolve("game-deobf.jar");
    final Path deobfSrc = deobfDir.resolve("src");
    /** the game's jars copied (IntelliJ's libraries and run configurations point at the copies) */
    final Path gameCopy = work.resolve("game");
    final Path build = work.resolve("build");
    final Path dist = work.resolve("dist");
    final Path data = work.resolve("data");
    final Path logs = work.resolve("logs");
    final Path lib = root.resolve("lib");
    final Path emulator = root.resolve("emulator");
    final Path mappingsDir = root.resolve("mappings");
    final Path mapping = mappingsDir.resolve("projectx-pcode.mapping");

    Path game, code, enigma;
    Mapping map;
    Properties local = new Properties();

    public static void main(String[] a) throws Exception {
        if (a.length == 0) {
            System.out.println("usage: ./sk COMMAND   (setup, deobf, decompile, emu-jar, mod ID, mods, all, new-mod ID, list, run, test, mappings, clean ...); see the top of Builder.java or docs/building.md");
            return;
        }
        Builder b = new Builder();
        try {
            b.run(a);
        } catch (BuildFailed e) {
            System.err.println("FAILED: " + e.getMessage());
            System.exit(1);
        }
    }

    static final class BuildFailed extends RuntimeException {
        BuildFailed(String m) {
            super(m);
        }
    }

    void run(String[] a) throws Exception {
        if (!Files.isDirectory(root.resolve("tools/build"))) throw new BuildFailed("run it in the project folder (this is " + root + ")");
        String cmd = a[0];
        switch (cmd) {
            case "list":
                for (String id : modIds()) System.out.println(id);
                return;
            case "clean":
                clean(a.length > 1 && a[1].equals("all"));
                return;
            case "new-mod":
                newMod(need(a, 1, "the id of the new mod (letters, digits and dashes: my-mod)"));
                return;
            default:
                break;
        }
        locate();
        switch (cmd) {
            case "setup":
                setup();
                break;
            case "deobf":
                setup(a.length > 1 && a[1].equals("force"));
                break;
            case "emu-jar":
                setup();
                emuJar();
                break;
            case "mod":
                setup();
                mod(need(a, 1, "the id of the mod (./sk list shows them)"));
                break;
            case "mods":
                setup();
                for (String id : modIds()) mod(id);
                break;
            case "all":
                setup();
                if (Files.isDirectory(root.resolve("emulator"))) emuJar();
                for (String id : modIds()) mod(id);
                break;
            case "obfuscate-ide":
                setup();
                obfuscateIde();
                break;
            case "decompile":
                setup();
                decompile();
                break;
            case "run":
                setup();
                runServer(Arrays.copyOfRange(a, 1, a.length));
                break;
            case "test":
                setup();
                test(Arrays.copyOfRange(a, 1, a.length));
                break;
            case "mappings":
                new Mappings(this).run(Arrays.copyOfRange(a, 1, a.length));
                break;
            default:
                throw new BuildFailed("unknown command " + cmd + " (./sk with no command lists them)");
        }
    }

    static String need(String[] a, int i, String what) {
        if (a.length <= i || a[i].isBlank()) throw new BuildFailed("give " + what);
        return a[i].trim();
    }

    // ---- where things are ----

    void locate() throws IOException {
        Path lp = root.resolve("local.properties");
        if (Files.exists(lp)) try (var in = Files.newInputStream(lp)) {
            local.load(in);
        }
        String g = System.getenv("SK_GAME_DIR");
        if (g == null || g.isBlank()) g = local.getProperty("game.dir");
        if (g == null || g.isBlank()) {
            for (String c : new String[] {"C:/Program Files (x86)/Steam/steamapps/common/Spiral Knights", System.getProperty("user.home") + "/.steam/steam/steamapps/common/Spiral Knights"}) {
                if (Files.isDirectory(Path.of(c, "code"))) {
                    g = c;
                    break;
                }
            }
        }
        if (g == null || !Files.exists(Path.of(g, "code", "projectx-pcode.jar"))) throw new BuildFailed("the Spiral Knights folder is not found: set SK_GAME_DIR, or game.dir in local.properties (looked at " + g + ")");
        game = Path.of(g);
        code = game.resolve("code");
        enigma = findEnigma("cli");
        if (enigma == null) throw new BuildFailed("the Enigma CLI is not found (lib/enigma-cli-*.jar): set ENIGMA_JAR, or enigma.jar in local.properties");
        map = Mapping.load(mapping);
    }

    /** the Enigma jar of a kind ("cli" or "swing"): the environment, local.properties, or lib/ (the project's own build of it is there); null when there is none */
    Path findEnigma(String kind) throws IOException {
        if (kind.equals("cli")) {
            String e = System.getenv("ENIGMA_JAR");
            if (e == null || e.isBlank()) e = local.getProperty("enigma.jar");
            if (e != null && !e.isBlank()) return Path.of(e);
        }
        if (!Files.isDirectory(lib)) return null;
        try (var ds = Files.newDirectoryStream(lib, "enigma-" + kind + "-*.jar")) {
            for (Path p : ds) return p;
        }
        return null;
    }

    // ---- setup ----

    static final List<String> LIB_JARS = List.of("lwjgl.jar", "lwjgl-opengl.jar", "lwjgl-glfw.jar", "lwjgl-openal.jar", "commons-beanutils.jar", "commons-digester.jar", "commons-logging.jar");
    /** the jars the server's own code needs (not the game's): where they are downloaded from */
    static final Map<String, String> EMU_LIBS = new LinkedHashMap<>();

    static {
        EMU_LIBS.put("sqlite-jdbc.jar", "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar");
        EMU_LIBS.put("slf4j-api.jar", "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar");
    }

    void setup() throws Exception {
        setup(false);
    }

    void setup(boolean force) throws Exception {
        fetchLibs();
        Files.createDirectories(gameCopy.resolve("lib"));
        Files.createDirectories(gameCopy.resolve("code"));
        for (String j : LIB_JARS) copyIfChanged(code.resolve(j), gameCopy.resolve("lib").resolve(j));
        try (var ds = Files.newDirectoryStream(code, "*.jar")) {
            // config.jar goes last on a class path (zz-): the Knight Launcher puts the classes of the mods that are mounted there, and the game's own come first
            for (Path p : ds) if (!p.getFileName().toString().contains("deobfuscated")) copyIfChanged(p, gameCopy.resolve("code").resolve(p.getFileName().toString().equals("config.jar") ? "zz-config.jar" : p.getFileName().toString()));
        }
        Mappings.warnIfStale(this);
        // what the deobfuscated jar was made from: the mapping and the game's two code jars, by content (file times say nothing when a jar is swapped for another)
        String made = Mappings.sha256(mapping) + " " + Mappings.sha256(code.resolve(Remap.GAME_JARS.get(0))) + " " + Mappings.sha256(code.resolve(Remap.GAME_JARS.get(1)));
        Path stamp = deobfDir.resolve("game-deobf.stamp");
        boolean stale = force || !Files.exists(deobf) || !Files.exists(stamp) || !Files.readString(stamp).trim().equals(made);
        if (stale) {
            Remap.deobfJar(mapping, enigma, code, deobf);
            Files.writeString(stamp, made + "\n");
        } else System.out.println("the deobfuscated jar is up to date: " + deobf);
        Mappings.cacheIfFits(this);
    }

    /** downloads the two small libraries of the server (SQLite's JDBC driver and the logging API it loads) when they are missing */
    void fetchLibs() throws IOException, InterruptedException {
        Files.createDirectories(lib);
        if (!Files.isDirectory(root.resolve("emulator"))) return; // no server in this project: its libraries are not needed
        for (var e : EMU_LIBS.entrySet()) {
            Path to = lib.resolve(e.getKey());
            if (Files.exists(to)) continue;
            System.out.println("downloading " + e.getValue());
            download(e.getValue(), to);
        }
    }

    static void download(String url, Path to) throws IOException, InterruptedException {
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        Path part = to.resolveSibling(to.getFileName() + ".part");
        HttpResponse<Path> r = c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(part));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(part);
            throw new BuildFailed("could not download " + url + " (HTTP " + r.statusCode() + ")");
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
    }

    static void copyIfChanged(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        if (Files.exists(to) && Files.size(to) == Files.size(from) && Files.getLastModifiedTime(to).toMillis() >= Files.getLastModifiedTime(from).toMillis()) return;
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /** what the code is compiled against: the game's code with the readable names, and the game's libraries */
    List<Path> readableClasspath() throws IOException {
        List<Path> cp = new ArrayList<>();
        cp.add(deobf);
        try (var ds = Files.newDirectoryStream(gameCopy.resolve("lib"), "*.jar")) {
            for (Path p : ds) cp.add(p);
        }
        return cp;
    }

    List<Path> emuLibs() {
        return EMU_LIBS.keySet().stream().map(lib::resolve).toList();
    }

    /** what the game runs on (its own jars, with the real names) */
    List<Path> gameClasspath() {
        List<Path> cp = new ArrayList<>();
        for (String j : List.of("projectx-pcode.jar", "projectx-config.jar", "lwjgl.jar", "lwjgl-opengl.jar", "lwjgl-glfw.jar", "commons-beanutils.jar", "commons-digester.jar", "commons-logging.jar")) cp.add(code.resolve(j));
        return cp;
    }

    static String join(Collection<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator));
    }

    // ---- compiling and turning back ----

    /** compiles the .java files under the folders (or the single files) named; the classes go to out */
    void compile(List<Path> sources, List<Path> classpath, Path out, List<String> extra) throws IOException {
        deleteTree(out);
        Files.createDirectories(out);
        List<Path> files = new ArrayList<>();
        for (Path d : sources) {
            if (Files.isRegularFile(d)) {
                files.add(d);
                continue;
            }
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> w = Files.walk(d)) {
                w.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        if (files.isEmpty()) return;
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new BuildFailed("this is a JRE: run it with a JDK 25");
        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diag, null, StandardCharsets.UTF_8)) {
            List<String> opts = new ArrayList<>(List.of("-Xlint:none", "-proc:none", "-encoding", "UTF-8", "-d", out.toString(), "-cp", join(classpath)));
            opts.addAll(extra);
            boolean ok = jc.getTask(null, fm, diag, opts, null, fm.getJavaFileObjectsFromPaths(files)).call();
            StringBuilder errors = new StringBuilder();
            int n = 0;
            for (var d : diag.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR && n++ < 60) errors.append(d).append('\n');
            }
            if (!ok) throw new BuildFailed("compiling " + sources.stream().map(root::relativize).toList() + " failed:\n" + errors);
        }
    }

    /** classes written with the readable names (a folder) to the game's names; other classes of the code are read only, for what they pass on */
    void obfuscate(Path in, Path out, Path... others) throws IOException {
        deleteTree(out);
        Obfuscate o = new Obfuscate(map);
        for (String j : Remap.GAME_JARS) o.addGameJar(code.resolve(j));
        for (Path p : others) if (Files.exists(p)) o.addOwnFrom(p);
        o.run(in, out);
    }

    // ---- the server ----

    Path emuReadable() {
        return build.resolve("emu-readable");
    }

    Path emuClasses() {
        return build.resolve("emu");
    }

    void emuBuild() throws IOException {
        List<Path> cp = new ArrayList<>(emuLibs());
        cp.addAll(readableClasspath());
        compile(List.of(emulator.resolve("src")), cp, emuReadable(), List.of());
        obfuscate(emuReadable(), emuClasses());
    }

    void emuJar() throws IOException {
        emuBuild();
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        // relative to the jar (work/dist): the libraries are in lib/ of the project
        mf.getMainAttributes().putValue("Class-Path", "../../lib/sqlite-jdbc.jar ../../lib/slf4j-api.jar");
        Files.createDirectories(dist);
        Path jar = dist.resolve("sk-emu.jar");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            addTree(jo, emuClasses());
        }
        System.out.println("built " + jar);
    }

    /** starts the server: the jar made when there is none, the game's jars behind it */
    void runServer(String[] args) throws Exception {
        if (!Files.exists(dist.resolve("sk-emu.jar"))) emuJar();
        List<Path> cp = new ArrayList<>();
        cp.add(dist.resolve("sk-emu.jar"));
        cp.addAll(emuLibs());
        cp.addAll(gameClasspath());
        List<String> cmd = new ArrayList<>(List.of(javaExe(), "-Xmx2g", "-Dcom.threerings.io.enumPolicy=ORDINAL", "-cp", join(cp), "dev.skmods.emu.Main", "--game", game.toString(), "--data", data.toString()));
        cmd.addAll(Arrays.asList(args));
        exec(cmd);
    }

    static String javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** runs a program with this console, and fails when it fails */
    void exec(List<String> cmd) throws IOException, InterruptedException {
        exec(cmd, root);
    }

    void exec(List<String> cmd, Path dir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) throw new BuildFailed(cmd.get(0) + " ended with code " + rc);
    }

    // ---- the tests ----

    Path testReadable() {
        return build.resolve("test-readable");
    }

    Path testClasses() {
        return build.resolve("test");
    }

    void test(String[] a) throws Exception {
        if (a.length == 0) throw new BuildFailed("test what? compile | run CLASS [ARGS] | client | editor CLASS [ARGS]");
        switch (a[0]) {
            case "compile":
                testCompile();
                break;
            case "run":
                testCompileIfStale();
                testRun(need(a, 1, "the test class (dev.skmods.emu.Training ...)"), Arrays.copyOfRange(a, 2, a.length));
                break;
            case "client":
                testCompileIfStale();
                testRun("dev.skmods.emu.EmuClientTest", Arrays.copyOfRange(a, 1, a.length));
                break;
            case "embedded": { // the scene tests against the server as the Crucible mod carries it (trimmed and hollowed)
                testCompileIfStale();
                Path emb = embeddedClasses("crucible-controls");
                List<Path> cp = new ArrayList<>(List.of(testClasses(), emb));
                cp.addAll(emuLibs());
                cp.addAll(gameClasspath());
                runTest(cp, "dev.skmods.emu.EmuClientTest", Arrays.copyOfRange(a, 1, a.length));
                break;
            }
            case "smoke":
                smoke();
                break;
            case "mod": // the checks of a mod that has tests: the mod as built (its patches in the game's classes), run with the tests of mods/ID/test
                modCheck(need(a, 1, "the mod"), need(a, 2, "the check class (dev.skmods.toolkit.SmokeTest ...)"), Arrays.copyOfRange(a, 3, a.length));
                break;
            case "editor":
                emuBuild();
                editorCheck(need(a, 1, "the check class (dev.skmods.editor.WireCheck ...)"), Arrays.copyOfRange(a, 2, a.length));
                break;
            default:
                throw new BuildFailed("unknown test command " + a[0]);
        }
    }

    void testCompile() throws IOException {
        emuBuild();
        List<Path> cp = new ArrayList<>();
        cp.add(emuReadable());
        cp.addAll(emuLibs());
        cp.addAll(readableClasspath());
        compile(List.of(emulator.resolve("test")), cp, testReadable(), List.of());
        obfuscate(testReadable(), testClasses(), emuReadable());
        Files.writeString(build.resolve(".test-stamp"), String.valueOf(System.currentTimeMillis()));
    }

    /** the tests are compiled again when a source is newer than the last compile */
    void testCompileIfStale() throws IOException {
        Path stamp = build.resolve(".test-stamp");
        boolean stale = !Files.exists(stamp) || !Files.isDirectory(testClasses());
        if (!stale) {
            long t = Files.getLastModifiedTime(stamp).toMillis();
            stale = newerThan(emulator, t) || Files.getLastModifiedTime(mapping).toMillis() > t || Files.getLastModifiedTime(deobf).toMillis() > t;
        }
        if (stale) testCompile();
    }

    static boolean newerThan(Path dir, long t) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) w.filter(x -> x.toString().endsWith(".java"))::iterator) if (Files.getLastModifiedTime(p).toMillis() > t) return true;
        }
        return false;
    }

    void testRun(String main, String[] args) throws Exception {
        List<Path> cp = new ArrayList<>(List.of(testClasses(), emuClasses()));
        cp.addAll(emuLibs());
        cp.addAll(gameClasspath());
        runTest(cp, main, args);
    }

    /**
     * The test an update of the game is checked with: a real client (the game's own client library) logs in to the server, walks, and plays a Lockdown match
     * made from the game's own example level (teams, the start areas stitched on, doors that flip, a control point taken, the knight's own movement code
     * with actors and weapons): the things that fail when a name the code reaches by reflection is not what the game has any more.
     */
    void smoke() throws Exception {
        testCompileIfStale();
        List<Path> cp = new ArrayList<>(List.of(testClasses(), emuClasses()));
        cp.addAll(emuLibs());
        cp.addAll(gameClasspath());
        exec(new ArrayList<>(List.of(javaExe(), "-Xmx3g", "-Demu.builtinAi=true", "-Dcom.threerings.io.enumPolicy=ORDINAL", "-Demu.game=" + game, "-Demu.only=arena", "-cp", join(cp), "dev.skmods.emu.EmuClientTest")));
    }

    void runTest(List<Path> cp, String main, String[] args) throws Exception {
        runTest(cp, main, args, root);
    }

    void runTest(List<Path> cp, String main, String[] args, Path dir) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(javaExe()));
        String jopts = System.getenv("JOPTS");
        if (jopts != null && !jopts.isBlank()) cmd.addAll(Arrays.asList(jopts.trim().split("\\s+")));
        cmd.addAll(List.of("-Xmx3g", "-Demu.builtinAi=true", "-Dcom.threerings.io.enumPolicy=ORDINAL", "-Demu.game=" + game));
        if (!dir.equals(root)) cmd.add("-Dsk.test.dir=" + dir); // a mod's check runs in a folder of its own: what it writes goes there
        cmd.addAll(List.of("-cp", join(cp), main));
        cmd.addAll(Arrays.asList(args));
        exec(cmd, dir);
    }

    /** the headless checks of the Crucible editor mod's tools (no editor window): the mod's source and the editor checks of the tests, compiled together */
    void editorCheck(String main, String[] args) throws Exception {
        List<Path> cp = new ArrayList<>(List.of(emuReadable()));
        cp.addAll(emuLibs());
        cp.addAll(readableClasspath());
        Path readable = build.resolve("editor-check-readable"), classes = build.resolve("editor-check");
        compile(List.of(root.resolve("mods/crucible-controls/src"), root.resolve("mods/crucible-controls/test")), cp, readable, List.of());
        obfuscate(readable, classes, emuReadable());
        List<Path> run = new ArrayList<>(List.of(classes, emuClasses()));
        run.addAll(emuLibs());
        run.addAll(gameClasspath());
        runTest(run, main, args);
    }

    /**
     * Runs a check of a mod (mods/ID/test): the mod is built first, and the check runs against the game's real classes with the mod's patches in them
     * (the classes of the mod, patched game classes among them, come before the game's jars), so what is tried is what the launcher would load.
     */
    void modCheck(String id, String main, String[] args) throws Exception {
        mod(id);
        Path dir = root.resolve("mods").resolve(id);
        if (!Files.isDirectory(dir.resolve("test"))) throw new BuildFailed("mods/" + id + " has no test folder");
        boolean usesEmu = Files.exists(dir.resolve("build.properties")) && Files.readString(dir.resolve("build.properties")).contains("uses=emu");
        List<Path> cp = new ArrayList<>();
        if (usesEmu) {
            cp.add(emuReadable());
            cp.addAll(emuLibs());
        }
        cp.addAll(readableClasspath());
        Path readable = build.resolve("mods").resolve(id + "-check-readable"), classes = build.resolve("mods").resolve(id + "-check");
        compile(List.of(dir.resolve("src"), dir.resolve("test")), cp, readable, List.of());
        if (usesEmu) obfuscate(readable, classes, emuReadable());
        else obfuscate(readable, classes);
        List<Path> run = new ArrayList<>(List.of(classes, build.resolve("mods").resolve(id).resolve("classes")));
        if (usesEmu) {
            run.add(emuClasses());
            run.addAll(emuLibs());
        }
        run.addAll(gameClasspath());
        Path cwd = build.resolve("mods").resolve(id + "-run"); // the checks write their files in their working folder, which is not the project
        Files.createDirectories(cwd);
        runTest(run, main, args, cwd);
    }

    // ---- the mods ----

    List<String> modIds() throws IOException {
        List<String> ids = new ArrayList<>();
        Path mods = root.resolve("mods");
        if (!Files.isDirectory(mods)) return ids;
        try (var ds = Files.newDirectoryStream(mods)) {
            for (Path p : ds) if (Files.isDirectory(p) && Files.exists(p.resolve("mod.json"))) ids.add(p.getFileName().toString());
        }
        Collections.sort(ids);
        return ids;
    }

    void mod(String id) throws Exception {
        Path dir = root.resolve("mods").resolve(id);
        if (!Files.exists(dir.resolve("mod.json"))) throw new BuildFailed("there is no mod " + id + " (mods/" + id + "/mod.json); the mods are " + modIds());
        Json.check(Files.readString(dir.resolve("mod.json")), "mods/" + id + "/mod.json");
        Properties bp = new Properties();
        if (Files.exists(dir.resolve("build.properties"))) try (var in = Files.newInputStream(dir.resolve("build.properties"))) {
            bp.load(in);
        }
        List<String> uses = split(bp.getProperty("uses", ""));
        boolean usesEmu = uses.contains("emu");
        for (String u : uses) if (!u.equals("emu")) throw new BuildFailed("mods/" + id + "/build.properties: uses=" + u + " (only emu is known: the classes of the server)");
        Path work = build.resolve("mods").resolve(id);
        List<Path> cp = new ArrayList<>();
        if (usesEmu) {
            emuBuild();
            cp.add(emuReadable());
            cp.addAll(emuLibs());
        }
        cp.addAll(readableClasspath());
        Path readable = work.resolve("readable"), classes = work.resolve("classes");
        compile(List.of(dir.resolve("src")), cp, readable, List.of());
        if (usesEmu) obfuscate(readable, classes, emuReadable());
        else obfuscate(readable, classes);

        // what the mod does to the game's own classes
        Path patches = dir.resolve("patches/Patches.java");
        if (Files.exists(patches)) applyPatches(id, patches, work, classes);

        Path stage = work.resolve("stage");
        deleteTree(stage);
        copyTree(classes, stage, null);
        if (usesEmu) {
            for (String pattern : split(bp.getProperty("include-from-emu", ""))) copyTree(emuClasses(), stage, pattern);
            trimEmu(stage, bp);
        }
        if (Boolean.parseBoolean(bp.getProperty("include-server-libs", "false"))) serverLibs(stage.resolve("sk-server"));
        for (String d : split(bp.getProperty("include-dirs", ""))) { // folders of the project (prefab) that go into the mod as they are, at its root
            Path from = root.resolve(d);
            if (!Files.isDirectory(from)) throw new BuildFailed("mods/" + id + "/build.properties: include-dirs=" + d + " (no such folder in the project)");
            copyTree(from, stage.resolve(d), null);
        }
        Path rsrc = dir.resolve(bp.getProperty("resources", "rsrc"));
        if (Files.isDirectory(rsrc)) copyTree(rsrc, stage.resolve("rsrc"), null);
        String buildUi = bp.getProperty("build-ui", "").trim(); // a class of the mod whose main writes interface files (the game's exporter) into the folder it is given
        if (!buildUi.isEmpty()) {
            Path out = stage.resolve("rsrc").resolve(bp.getProperty("build-ui-out", "ui/" + id));
            List<Path> ucp = new ArrayList<>(List.of(stage));
            ucp.addAll(gameClasspath());
            exec(new ArrayList<>(List.of(javaExe(), "-Dcom.threerings.io.enumPolicy=ORDINAL", "-cp", join(ucp), buildUi, out.toString())));
        }
        Files.copy(dir.resolve("mod.json"), stage.resolve("mod.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(dist);
        Path zip = dist.resolve("sk-" + id + ".zip");
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(zip))) {
            addTree(zo, stage);
        }
        System.out.println("built " + zip);
    }

    /**
     * The libraries the server needs besides the game's own (the SQLite driver without the natives of other systems than the common desktop ones, and slf4j),
     * put in a mod as sk-server/*.jar: a mod that carries the server (include-from-emu) starts it from there. They are downloaded by setup into lib/.
     */
    void serverLibs(Path to) throws IOException {
        Path sqlite = lib.resolve("sqlite-jdbc.jar"), slf4j = lib.resolve("slf4j-api.jar");
        if (!Files.exists(sqlite) || !Files.exists(slf4j)) throw new BuildFailed("lib/sqlite-jdbc.jar and lib/slf4j-api.jar are needed (./sk setup downloads them)");
        Files.createDirectories(to);
        Files.copy(slf4j, to.resolve("slf4j-api.jar"), StandardCopyOption.REPLACE_EXISTING);
        List<String> keep = List.of("Windows/x86_64/", "Linux/x86_64/", "Linux/aarch64/", "Mac/x86_64/", "Mac/aarch64/");
        try (ZipFile in = new ZipFile(sqlite.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(to.resolve("sqlite-jdbc.jar")))) {
            for (var en = in.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("org/sqlite/native/") && !e.isDirectory()) {
                    String rest = n.substring("org/sqlite/native/".length());
                    if (keep.stream().noneMatch(rest::startsWith)) continue;
                }
                out.putNextEntry(new ZipEntry(n));
                if (!e.isDirectory()) in.getInputStream(e).transferTo(out);
                out.closeEntry();
            }
        }
    }

    /**
     * A mod that carries only part of the server: {@code exclude-from-emu} names classes (of dev/skmods/emu, with their inner classes) that are left out, and
     * {@code stub-from-emu} names classes that are in the mod hollowed out (Stub): the same names, so the rest links, but nothing of what they do.
     */
    void trimEmu(Path stage, Properties bp) throws IOException {
        Set<String> drop = new HashSet<>(split(bp.getProperty("exclude-from-emu", ""))), hollow = new HashSet<>(split(bp.getProperty("stub-from-emu", "")));
        if (drop.isEmpty() && hollow.isEmpty()) return;
        Path base = stage.resolve("dev/skmods/emu");
        if (!Files.isDirectory(base)) return;
        try (Stream<Path> w = Files.walk(base)) {
            for (Path f : w.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = base.relativize(f).toString().replace('\\', '/');
                rel = rel.substring(0, rel.length() - 6);
                String outer = rel.contains("$") ? rel.substring(0, rel.indexOf('$')) : rel;
                if (drop.contains(outer)) Files.delete(f);
                else if (hollow.contains(outer)) Files.write(f, Stub.hollow(Files.readAllBytes(f)));
            }
        }
    }

    /** the server as the mod with a trimmed one carries it, for the tests: the classes of {@code mods/ID/build.properties} */
    Path embeddedClasses(String id) throws IOException {
        Properties bp = new Properties();
        try (var in = Files.newInputStream(root.resolve("mods").resolve(id).resolve("build.properties"))) {
            bp.load(in);
        }
        Path to = build.resolve("emu-embedded");
        deleteTree(to);
        for (String pattern : split(bp.getProperty("include-from-emu", ""))) copyTree(emuClasses(), to, pattern);
        trimEmu(to, bp);
        return to;
    }

    /** compiles mods/ID/patches/Patches.java (it implements ModPatches) and runs it on the game's classes */
    void applyPatches(String id, Path source, Path work, Path classes) throws Exception {
        Path out = work.resolve("patches");
        compile(List.of(source.getParent()), List.of(gameCopy), out, List.of("-sourcepath", root.resolve("tools/build").toString(), "-implicit:none"));
        // the API (ModPatches, Patcher, Hook, Arg) is this program's own: the class of the mod is loaded by a loader whose parent has them
        try (URLClassLoader l = new URLClassLoader(new URL[] {out.toUri().toURL()}, Builder.class.getClassLoader())) {
            Object o = Class.forName("Patches", true, l).getDeclaredConstructor().newInstance();
            if (!(o instanceof ModPatches mp)) throw new BuildFailed("mods/" + id + "/patches/Patches.java: Patches must implement ModPatches");
            try (Patcher p = new Patcher(map, code, classes, List.of(classes))) {
                mp.apply(p);
                p.flush();
            }
        } catch (ReflectiveOperationException e) {
            throw new BuildFailed("mods/" + id + "/patches/Patches.java: " + e);
        }
    }

    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    // ---- a new mod ----

    void newMod(String id) throws IOException {
        if (!id.matches("[a-z][a-z0-9-]*")) throw new BuildFailed("the id of a mod is lower case letters, digits and dashes, starting with a letter: " + id);
        Path dir = root.resolve("mods").resolve(id);
        if (Files.exists(dir)) throw new BuildFailed("mods/" + id + " exists already");
        String pkg = "dev.skmods." + id.replace("-", "");
        String name = Arrays.stream(id.split("-")).map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).collect(Collectors.joining(" "));
        String px = "";
        try {
            locate();
            for (String line : Files.readAllLines(game.resolve("getdown.txt"))) if (line.startsWith("version =")) px = line.substring(9).trim();
        } catch (IOException | RuntimeException ignored) {
            // the game is not found: the version of the game is to be written in by hand
        }
        Files.createDirectories(dir.resolve("src/" + pkg.replace('.', '/')));
        Files.createDirectories(dir.resolve("patches"));
        Files.writeString(dir.resolve("mod.json"), "{\n  \"mod\": {\n    \"name\": \"" + name + "\",\n    \"description\": \"What the mod does.\",\n    \"author\": \"" + System.getProperty("user.name") + "\",\n    \"version\": \"1.0.0\",\n    \"type\": \"class\",\n    \"pxVersion\": \"" + px + "\"\n  }\n}\n");
        Files.writeString(dir.resolve("src/" + pkg.replace('.', '/') + "/Hooks.java"),
            "package " + pkg + ";\n\n/**\n * The code of the mod. Written with the readable names of the game's classes (mappings/projectx-pcode.mapping); the build turns them back to the\n"
            + " * game's own. A method here that is public and static can be called from the game's classes: see patches/Patches.java.\n */\npublic final class Hooks {\n    private Hooks() {\n    }\n\n"
            + "    /** An example of a hook: called from a method of the game that is patched to call it. */\n    public static void hello(Object whoCalled) {\n        System.out.println(\"[" + id + "] called from \" + whoCalled);\n    }\n}\n");
        Files.writeString(dir.resolve("patches/Patches.java"),
            "import dev.skmods.modapi.*;\n\n/**\n * What the mod does to the game's own classes. Names are the readable ones (a class, a method, a descriptor); the build turns them into the\n * game's. The Patcher can:\n"
            + " *\n *   p.injectAtStart(class, method, descriptor, Hook.of(owner, method, descriptor, Arg...))   call a static method of the mod first thing in a method\n"
            + " *   p.injectAtEnd(...)                                                                          before every return (Arg.RETURNED is the value)\n"
            + " *   p.redirectCalls(class, inMethod, owner, method, descriptor, Hook)                          replace calls of a method with a call of the mod's\n"
            + " *   p.retargetSuper(class, newSuperclass)                                                       make a game class extend one of the mod's\n"
            + " *   p.transform(class, ClassTransform)                                                          anything else (the ClassFile API)\n"
            + " *\n * Arg: THIS, param(i), RETURNED, of(int), of(String), NULL.\n */\npublic final class Patches implements ModPatches {\n"
            + "    @Override\n    public void apply(Patcher p) throws Exception {\n"
            + "        // p.injectAtStart(\"com.threerings.projectx.client.hud.StatusUi\", \"updateHealthDifference\", \"(Ljava/lang/String;ZIIIILjava/lang/String;)V\",\n"
            + "        //     Hook.of(\"" + pkg + ".Hooks\", \"hello\", \"(Ljava/lang/Object;)V\", Arg.THIS));\n    }\n}\n");
        Files.writeString(dir.resolve("build.properties"), "# uses=emu                       the mod uses classes of the server (emulator/src): they are compiled against too\n"
                + "# include-from-emu=dev/skmods/devtools/**   and these of them go into the mod\n"
                + "# include-server-libs=true        the SQLite driver and slf4j go into the mod (sk-server/), for a mod that starts the server itself\n"
                + "# resources=rsrc                  a folder that is copied into the mod as rsrc/ (default: rsrc, if there is one)\n");
        // IntelliJ: a module for it
        Path iml = dir.resolve("sk-mod-" + id + ".iml");
        Files.writeString(iml, imlFor(id));
        Path modules = root.resolve(".idea/modules.xml");
        if (Files.exists(modules)) {
            String m = Files.readString(modules);
            String entry = "      <module fileurl=\"file://$PROJECT_DIR$/mods/" + id + "/sk-mod-" + id + ".iml\" filepath=\"$PROJECT_DIR$/mods/" + id + "/sk-mod-" + id + ".iml\" />\n";
            if (!m.contains(entry)) Files.writeString(modules, m.replace("    </modules>", entry + "    </modules>"));
        }
        System.out.println("made mods/" + id + ":\n  mod.json            the Knight Launcher's description of the mod (name, version, ...)\n  src/                the code, in the package " + pkg + "\n  patches/Patches.java  what it changes in the game's classes\n  build.properties    options\nbuild it with: ./sk mod " + id + "  (or the run configuration \"Build mod\" in IntelliJ)");
    }

    static String imlFor(String id) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<module type=\"JAVA_MODULE\" version=\"4\">\n  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"false\">\n"
            + "    <output url=\"file://$MODULE_DIR$/../../work/build/idea/mods/" + id + "/classes\" />\n    <exclude-output />\n    <content url=\"file://$MODULE_DIR$\">\n"
            + "      <sourceFolder url=\"file://$MODULE_DIR$/src\" isTestSource=\"false\" />\n      <sourceFolder url=\"file://$MODULE_DIR$/patches\" isTestSource=\"false\" />\n    </content>\n"
            + "    <orderEntry type=\"inheritedJdk\" />\n    <orderEntry type=\"sourceFolder\" forTests=\"false\" />\n    <orderEntry type=\"library\" name=\"game-readable\" level=\"project\" />\n"
            + "    <orderEntry type=\"module\" module-name=\"sk-buildtools\" />\n  </component>\n</module>\n";
    }

    // ---- IntelliJ ----

    void obfuscateIde() throws IOException {
        Path idea = build.resolve("idea"), obf = build.resolve("obf");
        Path emu = idea.resolve("emu/classes");
        if (!Files.isDirectory(emu)) throw new BuildFailed("nothing is built yet: use Build > Build Project in IntelliJ first");
        obfuscate(emu, obf.resolve("emu"));
        List<Path> modClasses = new ArrayList<>();
        Path mods = idea.resolve("mods");
        if (Files.isDirectory(mods)) try (var ds = Files.newDirectoryStream(mods)) {
            for (Path m : ds) if (Files.isDirectory(m.resolve("classes"))) modClasses.add(m.resolve("classes"));
        }
        Path tests = idea.resolve("emu/test-classes");
        if (Files.isDirectory(tests)) {
            List<Path> others = new ArrayList<>(modClasses);
            others.add(emu);
            obfuscate(tests, obf.resolve("emu-test"), others.toArray(new Path[0]));
        }
        for (Path m : modClasses) {
            String id = m.getParent().getFileName().toString();
            obfuscate(m, obf.resolve("mods").resolve(id), emu);
        }
    }

    /** the game's code as readable source (for reading: IntelliJ shows it when you navigate into a game class) */
    void decompile() throws Exception {
        Files.createDirectories(deobfSrc);
        exec(List.of(javaExe(), "-Xmx4g", "-jar", enigma.toString(), "decompile", "cfr", code.resolve("projectx-pcode.jar").toString(), deobfSrc.toString(), mapping.toString()));
    }

    // ---- cleaning ----

    /** deletes what was built; with all, what was made from the game's files too (setup makes it again). The server's data (work/data) is never touched. */
    void clean(boolean all) throws IOException {
        List<Path> gone = new ArrayList<>(List.of(build, dist));
        if (all) gone.addAll(List.of(deobfDir, gameCopy, cache));
        for (Path p : gone) {
            if (Files.exists(p)) {
                deleteTree(p);
                System.out.println("deleted " + root.relativize(p));
            }
        }
        System.out.println("work/data (the server's database and files) and work/logs are left as they are");
    }

    // ---- files ----

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> w = Files.walk(p)) {
            for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }

    /** copies a folder (only the files that match a glob, relative to it, when one is given) */
    static void copyTree(Path from, Path to, String glob) throws IOException {
        if (!Files.isDirectory(from)) return;
        PathMatcher pm = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> w = Files.walk(from)) {
            for (Path f : w.filter(Files::isRegularFile).toList()) {
                Path rel = from.relativize(f);
                if (pm != null && !pm.matches(Path.of(rel.toString().replace('\\', '/')))) continue;
                Path t = to.resolve(rel.toString());
                Files.createDirectories(t.getParent());
                Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void addTree(ZipOutputStream zo, Path dir) throws IOException {
        addEntries(dir, (name, bytes) -> {
            zo.putNextEntry(new ZipEntry(name));
            zo.write(bytes);
            zo.closeEntry();
        });
    }

    interface Sink {
        void put(String name, byte[] bytes) throws IOException;
    }

    static void addEntries(Path dir, Sink sink) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path f : w.filter(Files::isRegularFile).sorted().toList()) {
                String name = dir.relativize(f).toString().replace('\\', '/');
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                sink.put(name, Files.readAllBytes(f));
            }
        }
    }

    /** a check that a mod's description is JSON (the launcher stops at a mistake in it, and says little) */
    static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        static void check(String text, String what) {
            Json j = new Json(text);
            try {
                j.ws();
                j.value();
                j.ws();
                if (j.i != text.length()) throw new IllegalStateException("text after the end");
            } catch (RuntimeException e) {
                throw new BuildFailed(what + " is not valid JSON (near character " + j.i + "): " + e.getMessage());
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        void value() {
            if (i >= s.length()) throw new IllegalStateException("it ends too early");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return;
                }
                while (true) {
                    ws();
                    string();
                    ws();
                    expect(':');
                    ws();
                    value();
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return;
                }
            } else if (c == '[') {
                i++;
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return;
                }
                while (true) {
                    ws();
                    value();
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return;
                }
            } else if (c == '"') string();
            else {
                int st = i;
                while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
                String t = s.substring(st, i);
                if (!(t.equals("true") || t.equals("false") || t.equals("null") || t.matches("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?"))) throw new IllegalStateException("not a value: " + t);
            }
        }

        void string() {
            expect('"');
            while (s.charAt(i) != '"') {
                if (s.charAt(i) == '\\') i++;
                i++;
            }
            i++;
        }

        void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalStateException("expected " + c + " but found " + s.charAt(i));
            i++;
        }
    }
}
