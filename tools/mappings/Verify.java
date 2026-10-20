import java.lang.classfile.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Verifies every class of a jar with the ClassFile API: Verify JAR GAMECODEDIR */
public class Verify {
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(Path.of(a[0]).toUri().toURL());
        try (var ds = Files.newDirectoryStream(Path.of(a[1]), "*.jar")) {
            for (Path p : ds) if (!p.getFileName().equals(Path.of(a[0]).getFileName()) && !p.getFileName().toString().contains("deobfuscated")) urls.add(p.toUri().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), Verify.class.getClassLoader());
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofClassLoading(loader))));
        int ok = 0, bad = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                byte[] b = z.getInputStream(e).readAllBytes();
                List<VerifyError> errs;
                try {
                    errs = cf.verify(b);
                } catch (Throwable t) {
                    errs = List.of(new VerifyError(t.toString()));
                }
                if (errs.isEmpty()) ok++;
                else {
                    bad++;
                    String m = errs.get(0).getMessage();
                    kinds.merge(m.substring(0, Math.min(400, m.length())), 1, Integer::sum);
                }
            }
        }
        System.out.println("verified ok " + ok + " bad " + bad);
        kinds.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12).forEach(System.out::println);
    }
}
