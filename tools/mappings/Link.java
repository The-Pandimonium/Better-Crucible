import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Links every class of a jar or zip with the JVM's own verifier (run with -Xverify:all): the classes of the zip come first on the class path, then
 * the game's jars. A class is linked, not run: Link ZIP GAME/code
 */
public class Link {
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(Path.of(a[0]).toUri().toURL());
        try (var ds = Files.newDirectoryStream(Path.of(a[1]), "*.jar")) {
            for (Path p : ds) if (!p.getFileName().toString().contains("deobfuscated")) urls.add(p.toUri().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), Link.class.getClassLoader());
        int ok = 0, bad = 0;
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                String n = e.getName().substring(0, e.getName().length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(n, false, loader);
                    c.getDeclaredMethods();
                    c.getDeclaredFields();
                    ok++;
                } catch (Throwable t) {
                    bad++;
                    System.out.println("FAIL " + n + ": " + t);
                }
            }
        }
        System.out.println("linked ok " + ok + " bad " + bad);
    }
}
