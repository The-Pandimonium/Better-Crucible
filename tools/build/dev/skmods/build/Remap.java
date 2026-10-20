package dev.skmods.build;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * The build tool that lets the code of this project be written with the readable names of the game's classes (mappings/) and still run against
 * the game's real, obfuscated jars.
 *
 * <pre>
 *   java -cp lib/enigma-cli-*.jar Remap.java deobf-jar MAPPING ENIGMA.jar GAMECODEDIR OUT.jar
 *       makes the jar to compile against: the game's code jars with the readable names
 *   java -cp ENIGMA.jar Remap.java obfuscate MAPPING GAMECODEDIR IN OUT [CLASSES...]
 *       turns compiled classes (a folder or a jar) back to the game's names; the result is what runs
 *   java -cp ENIGMA.jar Remap.java names MAPPING DEOBFNAME...
 *       prints the game's name for readable class names (for tools that patch the game's classes)
 * </pre>
 */
public final class Remap {
    /** the jars of the game that are written in the obfuscated names and are compiled against */
    public static final List<String> GAME_JARS = List.of("projectx-pcode.jar", "projectx-config.jar");

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "deobf-jar":
                deobfJar(Path.of(a[1]), Path.of(a[2]), Path.of(a[3]), Path.of(a[4]));
                break;
            case "obfuscate": {
                Mapping m = Mapping.load(Path.of(a[1]));
                Obfuscate o = new Obfuscate(m);
                for (String j : GAME_JARS) o.addGameJar(Path.of(a[2]).resolve(j));
                // more classes written with the readable names, only for finding what a class inherits
                for (int i = 5; i < a.length; i++) o.addOwnFrom(Path.of(a[i]));
                o.run(Path.of(a[3]), Path.of(a[4]));
                break;
            }
            case "names": {
                Mapping m = Mapping.load(Path.of(a[1]));
                for (int i = 2; i < a.length; i++) System.out.println(a[i] + " -> " + m.obfClass(a[i]));
                break;
            }
            default:
                throw new IllegalArgumentException("unknown command " + a[0]);
        }
    }

    public static void deobfJar(Path mapping, Path enigma, Path code, Path out) throws IOException, InterruptedException {
        Path merged = out.resolveSibling(out.getFileName() + ".merged.jar");
        mergeJars(code, merged);
        Files.deleteIfExists(out);
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx3g", "-jar", enigma.toString(), "deobfuscate", merged.toString(), out.toString(), mapping.toString()).inheritIO().start();
        if (p.waitFor() != 0 || !Files.exists(out)) throw new IOException("Enigma could not make " + out);
        Files.delete(merged);
        System.out.println("made " + out);
    }

    /** the classes of the game's two code jars in one jar (what Enigma is given, and what its window shows) */
    public static void mergeJars(Path code, Path merged) throws IOException {
        Files.createDirectories(merged.toAbsolutePath().getParent());
        Set<String> seen = new HashSet<>();
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(merged))) {
            for (String j : GAME_JARS) {
                try (ZipFile z = new ZipFile(code.resolve(j).toFile())) {
                    for (var e : Collections.list(z.entries())) {
                        if (e.isDirectory() || !e.getName().endsWith(".class") || !seen.add(e.getName())) continue;
                        zo.putNextEntry(new ZipEntry(e.getName()));
                        zo.write(z.getInputStream(e).readAllBytes());
                        zo.closeEntry();
                    }
                }
            }
        }
    }
}
