package dev.skmods.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/**
 * Turns code written (and compiled) against the readable names back into code for the game's real jar: every class, field and method that has a
 * readable name in the mapping is given its obfuscated name again, in the references of the code and in the methods that override one.
 *
 * <p>A reference names the class it is found through, which may be a subclass of the one that declares the member, so a member is looked up
 * through the superclasses and interfaces (of the game's classes and of the code being turned).
 *
 * <p>Strings are not touched, except the text given to the methods of {@code dev.skmods.emu.Game} ({@code Game.cls}, {@code field}, {@code method},
 * {@code fieldName}, {@code methodName}): that is a readable name, which is replaced by the game's own. A name that is not in the mapping, or that names
 * two things, fails the build, so reflection never holds a name of the game that the next update changes without telling.
 */
final class Obfuscate {
    static final class Info {
        String name; // readable name
        String sup;
        List<String> ifs = List.of();
        boolean game;
        /** readable method name + readable descriptor -> the name in the game's jar */
        final Map<String, String> methods = new HashMap<>();
        final Map<String, String> fields = new HashMap<>();
        final Set<String> ownFields = new HashSet<>();
    }

    final Mapping map;
    final Map<String, Info> info = new HashMap<>();

    Obfuscate(Mapping map) {
        this.map = map;
    }

    /** the classes of a game jar, as the lookups need them (no code is read). */
    void addGameJar(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    String obf = cr.getClassName();
                    Info i = new Info();
                    i.game = true;
                    i.name = map.deobfClass(obf);
                    i.sup = cr.getSuperName() == null ? null : map.deobfClass(cr.getSuperName());
                    i.ifs = Arrays.stream(cr.getInterfaces()).map(map::deobfClass).toList();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                            if (name.startsWith("<")) return null;
                            String nn = map.methods.getOrDefault(obf + "." + name + desc, name);
                            i.methods.putIfAbsent(nn + map.desc(desc, true), name);
                            return null;
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                            String nn = map.fields.getOrDefault(obf + "." + name, name);
                            i.fields.putIfAbsent(nn + " " + map.desc(desc, true), name);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    info.putIfAbsent(i.name, i);
                }
            }
        }
    }

    /** a class of the code being turned (written with readable names, so nothing is looked up for its own name). */
    void addOwn(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        Info i = new Info();
        i.name = cr.getClassName();
        i.sup = cr.getSuperName();
        i.ifs = Arrays.asList(cr.getInterfaces());
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                i.ownFields.add(name + " " + desc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Info old = info.get(i.name);
        if (old != null && old.game) return; // a replacement of a class of the game: its members are named as the game's are
        info.put(i.name, i);
    }

    /** classes (a folder or a jar) that are not turned but are part of the code: what they extend matters for the ones that are */
    void addOwnFrom(Path in) throws IOException {
        if (Files.isDirectory(in)) {
            try (Stream<Path> w = Files.walk(in)) {
                for (Path f : w.filter(x -> x.toString().endsWith(".class")).toList()) addOwn(Files.readAllBytes(f));
            }
        } else {
            try (ZipFile z = new ZipFile(in.toFile())) {
                for (var e : Collections.list(z.entries())) if (e.getName().endsWith(".class")) addOwn(z.getInputStream(e).readAllBytes());
            }
        }
    }

    /** the name in the game's jar of a method that is named by its readable name, found through the supertypes. */
    String method(String owner, String name, String desc) {
        if (name.startsWith("<") || owner.startsWith("[")) return name;
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(owner);
        String key = name + desc;
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            Info i = info.get(c);
            if (i == null) continue;
            if (i.game) {
                String o = i.methods.get(key);
                if (o != null) return o;
            }
            if (i.sup != null) q.add(i.sup);
            q.addAll(i.ifs);
        }
        return name;
    }

    String field(String owner, String name, String desc) {
        if (owner.startsWith("[")) return name;
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(owner);
        String key = name + " " + desc;
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            Info i = info.get(c);
            if (i == null) continue;
            if (i.game) {
                String o = i.fields.get(key);
                if (o != null) return o;
            } else if (i.ownFields.contains(key)) return name;
            if (i.sup != null) q.add(i.sup);
            q.addAll(i.ifs);
        }
        return name;
    }

    @SuppressWarnings("deprecation")
    final Remapper remapper = new Remapper() {
        @Override
        public String map(String internalName) {
            return map.obfClass(internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return method(owner, name, descriptor);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return field(owner, name, descriptor);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            // a lambda: the name is the one method of the interface it makes
            String iface = Type.getReturnType(descriptor).getInternalName();
            Info i = info.get(iface);
            if (i == null || !i.game) return name;
            Set<String> found = new HashSet<>();
            for (var e : i.methods.entrySet()) if (e.getKey().startsWith(name + "(")) found.add(e.getValue());
            return found.size() == 1 ? found.iterator().next() : name;
        }
    };

    static final String GAME = "dev/skmods/emu/Game";
    static final Set<String> GAME_CALLS = Set.of("cls", "field", "method", "fieldName", "methodName");

    byte[] turn(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        cr.accept(node, 0);
        boolean changed = turnGameNames(node);
        ClassWriter cw = new ClassWriter(0);
        if (changed) node.accept(new ClassRemapper(cw, remapper));
        else cr.accept(new ClassRemapper(cw, remapper), 0);
        return cw.toByteArray();
    }

    /** The texts of the calls of Game: readable names to the game's. True when one was changed. */
    private boolean turnGameNames(org.objectweb.asm.tree.ClassNode node) {
        boolean any = false;
        if (node.name.equals(GAME)) return false; // the helper itself passes names it was given on
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode in : m.instructions.toArray()) {
                if (!(in instanceof org.objectweb.asm.tree.MethodInsnNode mi) || !mi.owner.equals(GAME) || !GAME_CALLS.contains(mi.name)) continue;
                // the text is the first argument: the constant pushed last before the call, or before the parameters of a method (an array made on the way)
                org.objectweb.asm.tree.LdcInsnNode ldc = null;
                int steps = 0;
                for (org.objectweb.asm.tree.AbstractInsnNode p = in.getPrevious(); p != null && steps < 60; p = p.getPrevious()) {
                    if (p.getOpcode() < 0) continue;
                    steps++;
                    if (p instanceof org.objectweb.asm.tree.LdcInsnNode l && l.cst instanceof String) {
                        ldc = l;
                        break;
                    }
                    if (!mi.name.equals("method")) break;
                }
                if (ldc == null) throw new Builder.BuildFailed(node.name + "." + m.name + ": Game." + mi.name + " needs its name written in the call, as a constant text (the build turns it into the game's)");
                ldc.cst = gameName(mi.name, (String) ldc.cst, node.name + "." + m.name);
                any = true;
            }
        }
        return any;
    }

    /** a readable text of a call of Game ({@code a.b.C}, {@code a.b.C#member}, {@code a.b.C#method(desc)}) as the game's */
    String gameName(String call, String text, String where) {
        int h = text.indexOf('#');
        String cls = (h < 0 ? text : text.substring(0, h)).replace('.', '/');
        if (!call.equals("cls") && h < 0) throw new Builder.BuildFailed(where + ": Game." + call + "(\"" + text + "\") needs Class#member");
        if (!info.containsKey(cls) || !info.get(cls).game) throw new Builder.BuildFailed(where + ": Game." + call + "(\"" + text + "\"): the game has no class " + cls.replace('/', '.') + " in the mapping");
        String obfCls = map.obfClass(cls).replace('/', '.');
        if (call.equals("cls")) return obfCls;
        String name = text.substring(h + 1), desc = null;
        int p = name.indexOf('(');
        if (p >= 0) {
            desc = name.substring(p);
            name = name.substring(0, p);
        }
        boolean method = call.startsWith("method");
        Set<String> found = new TreeSet<>();
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(cls);
        while (!q.isEmpty() && found.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            Info i = info.get(c);
            if (i == null) continue;
            if (i.game) {
                for (var e : (method ? i.methods : i.fields).entrySet()) {
                    String key = e.getKey();
                    boolean hit = method ? (desc == null ? key.startsWith(name + "(") : key.equals(name + desc)) : key.startsWith(name + " ");
                    if (hit) found.add(e.getValue());
                }
            }
            if (i.sup != null) q.add(i.sup);
            q.addAll(i.ifs);
        }
        if (found.isEmpty()) throw new Builder.BuildFailed(where + ": Game." + call + "(\"" + text + "\"): the game's " + cls.replace('/', '.') + " has no " + (method ? "method " : "field ") + name + " in the mapping (it was renamed, or never named)");
        if (found.size() > 1) throw new Builder.BuildFailed(where + ": Game." + call + "(\"" + text + "\") names several things the game calls " + found + ": add the descriptor, as Class#name(desc)");
        return obfCls + "#" + found.iterator().next();
    }

    /** every class under a folder or in a jar, written to the same paths in the output folder; other files are copied. */
    void run(Path in, Path out) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (Files.isDirectory(in)) {
            try (Stream<Path> w = Files.walk(in)) {
                for (Path p : w.filter(Files::isRegularFile).toList()) files.put(in.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        } else {
            try (ZipFile z = new ZipFile(in.toFile())) {
                for (var e : Collections.list(z.entries())) if (!e.isDirectory()) files.put(e.getName(), z.getInputStream(e).readAllBytes());
            }
        }
        for (var e : files.entrySet()) if (e.getKey().endsWith(".class")) addOwn(e.getValue());
        int turned = 0;
        for (var e : files.entrySet()) {
            byte[] b = e.getValue();
            String name = e.getKey();
            if (name.endsWith(".class")) {
                b = turn(b);
                turned++;
                String c = new ClassReader(b).getClassName();
                name = c + ".class";
            }
            Path target = out.resolve(name);
            Files.createDirectories(target.getParent());
            Files.write(target, b);
        }
        System.out.println("obfuscated " + turned + " classes into " + out);
    }
}
