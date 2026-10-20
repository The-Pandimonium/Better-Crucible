package dev.skmods.modapi;

import dev.skmods.build.Mapping;
import dev.skmods.build.Remap;
import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Changes classes of the game for a mod (see {@link ModPatches}). Every class, method and descriptor is given by its readable name; the game's
 * class is read from its jar, changed, and written to the mod's classes under the game's name, so the Knight Launcher puts it in place of the
 * game's own. Several patches of one class are all made.
 *
 * <p>This is the whole of what a mod can do to a game class, and the places to add more: {@link #transform} takes any change at all (the
 * ClassFile API), and the others are built on it.
 */
public final class Patcher implements AutoCloseable {
    private final Mapping map;
    private final List<Path> gameJars = new ArrayList<>();
    private final Path out;
    private final URLClassLoader loader;
    private final ClassFile cf;
    private final Map<String, ClassTransform> pending = new LinkedHashMap<>();
    private final List<Runnable> checks = new ArrayList<>();

    /** @param game the game's {@code code} folder; @param out where the changed classes go; @param modClasses the classes of the mod (so the hierarchy of a class that is retargeted to one of them is known) */
    public Patcher(Mapping map, Path game, Path out, List<Path> modClasses) throws IOException {
        this.map = map;
        this.out = out;
        List<URL> urls = new ArrayList<>();
        // the game's own jars, and only those: a mod that is mounted in the game already has its (changed) classes in config.jar, and those are not what is patched
        for (String j : Remap.GAME_JARS) {
            gameJars.add(game.resolve(j));
            urls.add(game.resolve(j).toUri().toURL());
        }
        try (var ds = Files.newDirectoryStream(game, "*.jar")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.contains("deobfuscated") || n.equals("config.jar") || Remap.GAME_JARS.contains(n)) continue;
                urls.add(p.toUri().toURL());
            }
        }
        for (Path m : modClasses) urls.add(m.toUri().toURL());
        loader = new URLClassLoader(urls.toArray(new URL[0]), Patcher.class.getClassLoader());
        cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofClassLoading(loader))));
    }

    // ---- names ----

    /** the game's name (internal form, with slashes) of a class written with its readable name (dots, or slashes) */
    public String gameClass(String readable) {
        return map.obfClass(readable.replace('.', '/'));
    }

    private ClassDesc desc(String readable) {
        return ClassDesc.ofInternalName(gameClass(readable));
    }

    // ---- the patches ----

    /**
     * The class now extends {@code newSuper} instead of what it extended: its superclass and the calls of the old one's constructor are changed.
     * (This is how the Crucible editor is given its new controls: the game's editor class is made to extend the mod's.)
     */
    public Patcher retargetSuper(String gameClass, String newSuper) throws IOException {
        String cn = gameClass(gameClass);
        ClassModel model = cf.parse(original(cn));
        ClassDesc oldSuper = model.superclass().orElseThrow().asSymbol();
        ClassDesc nw = desc(newSuper);
        CodeTransform code = (b, e) -> {
            if (e instanceof InvokeInstruction i && i.opcode() == Opcode.INVOKESPECIAL && i.owner().asSymbol().equals(oldSuper) && i.name().equalsString("<init>")) {
                b.invokespecial(nw, "<init>", i.typeSymbol(), i.isInterface());
            } else {
                b.with((CodeElement) e);
            }
        };
        ClassTransform change = (b, e) -> {
            if (e instanceof Superclass) b.withSuperclass(nw);
            else b.with(e);
        };
        add(cn, change.andThen(ClassTransform.transformingMethodBodies(code)));
        return this;
    }

    /** The hook is called first thing in the method (the method and its descriptor are the readable ones). */
    public Patcher injectAtStart(String gameClass, String method, String descriptor, Hook hook) {
        String cn = gameClass(gameClass);
        String mn = map.obfMethod(gameClass.replace('.', '/'), method, descriptor);
        String md = map.desc(descriptor, false);
        MethodTypeDesc target = MethodTypeDesc.ofDescriptor(md);
        boolean[] done = {false};
        boolean isStatic = isStatic(cn, mn, md);
        addBody(cn, m -> m.methodName().equalsString(mn) && m.methodType().equalsString(md), (b, e) -> {
            if (!done[0] && e instanceof Instruction) {
                done[0] = true;
                call(b, hook, target, isStatic);
            }
            b.with((CodeElement) e);
        });
        checks.add(() -> {
            if (!done[0]) throw new IllegalStateException("no method " + method + descriptor + " in " + gameClass + " (the game's " + mn + md + ")");
        });
        return this;
    }

    /**
     * The hook is called before every return of the method. With {@link Arg#RETURNED} (as its first argument) it is given the value that is returned.
     */
    public Patcher injectAtEnd(String gameClass, String method, String descriptor, Hook hook) {
        String cn = gameClass(gameClass);
        String mn = map.obfMethod(gameClass.replace('.', '/'), method, descriptor);
        String md = map.desc(descriptor, false);
        MethodTypeDesc target = MethodTypeDesc.ofDescriptor(md);
        boolean isStatic = isStatic(cn, mn, md);
        boolean[] done = {false};
        addBody(cn, m -> m.methodName().equalsString(mn) && m.methodType().equalsString(md), (b, e) -> {
            if (e instanceof ReturnInstruction r) {
                done[0] = true;
                boolean returned = !hook.args.isEmpty() && hook.args.get(0).kind == Arg.Kind.RETURNED;
                if (returned) {
                    if (r.typeKind() == TypeKind.VOID) throw new IllegalStateException("Arg.RETURNED in a method that returns nothing: " + method);
                    if (r.typeKind().slotSize() == 2) b.dup2();
                    else b.dup();
                }
                call(b, hook, target, isStatic, returned ? 1 : 0);
            }
            b.with((CodeElement) e);
        });
        checks.add(() -> {
            if (!done[0]) throw new IllegalStateException("no method " + method + descriptor + " in " + gameClass + " (the game's " + mn + md + ")");
        });
        return this;
    }

    /**
     * The hook is called first thing in the method, and what it returns <b>replaces the parameter</b> {@code param} (counted from 0): the method goes on with the
     * hook's result. The hook returns a value of the parameter's type (or one that can take its place).
     */
    public Patcher wrapParam(String gameClass, String method, String descriptor, int param, Hook hook) {
        String cn = gameClass(gameClass);
        String mn = map.obfMethod(gameClass.replace('.', '/'), method, descriptor);
        String md = map.desc(descriptor, false);
        MethodTypeDesc target = MethodTypeDesc.ofDescriptor(md);
        boolean isStatic = isStatic(cn, mn, md);
        boolean[] done = {false};
        addBody(cn, m -> m.methodName().equalsString(mn) && m.methodType().equalsString(md), (b, e) -> {
            if (!done[0] && e instanceof Instruction) {
                done[0] = true;
                call(b, hook, target, isStatic, 0, true);
                b.storeLocal(TypeKind.from(target.parameterType(param)), slotOf(target, isStatic, param));
            }
            b.with((CodeElement) e);
        });
        checks.add(() -> {
            if (!done[0]) throw new IllegalStateException("no method " + method + descriptor + " in " + gameClass + " (the game's " + mn + md + ")");
        });
        return this;
    }

    /**
     * The hook is called right after the call of {@code owner.method} inside {@code inMethod} (a method of the class, with its readable descriptor). With
     * {@link Arg#RETURNED} as its first argument it is given the value the call returned (the call's own value stays on the stack for the method); the other
     * arguments are the parameters of {@code inMethod}. The call must be found exactly once.
     */
    public Patcher injectAfterCall(String gameClass, String inMethod, String inDescriptor, String owner, String method, String descriptor, Hook hook) {
        String cn = gameClass(gameClass);
        String mn = map.obfMethod(gameClass.replace('.', '/'), inMethod, inDescriptor);
        String md = map.desc(inDescriptor, false);
        String oo = gameClass(owner);
        String on = map.obfMethod(owner.replace('.', '/'), method, descriptor);
        String od = map.desc(descriptor, false);
        MethodTypeDesc target = MethodTypeDesc.ofDescriptor(md);
        boolean isStatic = isStatic(cn, mn, md);
        int[] found = {0};
        addBody(cn, m -> m.methodName().equalsString(mn) && m.methodType().equalsString(md), (b, e) -> {
            b.with((CodeElement) e);
            if (e instanceof InvokeInstruction i && i.owner().asInternalName().equals(oo) && i.name().equalsString(on) && i.type().equalsString(od)) {
                found[0]++;
                boolean returned = !hook.args.isEmpty() && hook.args.get(0).kind == Arg.Kind.RETURNED;
                if (returned) {
                    TypeKind r = TypeKind.from(i.typeSymbol().returnType());
                    if (r == TypeKind.VOID) throw new IllegalStateException("Arg.RETURNED after a call that returns nothing: " + method);
                    if (r.slotSize() == 2) b.dup2();
                    else b.dup();
                }
                call(b, hook, target, isStatic, returned ? 1 : 0, false);
            }
        });
        checks.add(() -> {
            if (found[0] != 1) throw new IllegalStateException("the call of " + owner + "." + method + descriptor + " in " + gameClass + "." + inMethod + inDescriptor + " was found " + found[0] + " times, not once");
        });
        return this;
    }

    /**
     * Every call of {@code owner.method} in the class (or only in {@code inMethod}, the readable name, when it is not null) is replaced by a call
     * of the hook, which is given what the call was (the object first for a method that is not static) and returns what it returned.
     */
    public Patcher redirectCalls(String gameClass, String inMethod, String owner, String method, String descriptor, Hook hook) {
        String cn = gameClass(gameClass);
        String oo = gameClass(owner);
        String on = map.obfMethod(owner.replace('.', '/'), method, descriptor);
        String od = map.desc(descriptor, false);
        ClassDesc hookOwner = ClassDesc.ofInternalName(hook.owner);
        MethodTypeDesc hookType = MethodTypeDesc.ofDescriptor(map.desc(hook.descriptor, false));
        boolean[] done = {false};
        Predicate<MethodModel> only = m -> inMethod == null || map.methods.getOrDefault(cn + "." + m.methodName().stringValue() + m.methodType().stringValue(), m.methodName().stringValue()).equals(inMethod);
        addBody(cn, only, (b, e) -> {
            if (e instanceof InvokeInstruction i && i.owner().asInternalName().equals(oo) && i.name().equalsString(on) && i.type().equalsString(od)) {
                done[0] = true;
                b.invokestatic(hookOwner, hook.name, hookType);
            } else {
                b.with((CodeElement) e);
            }
        });
        checks.add(() -> {
            if (!done[0]) throw new IllegalStateException("no call of " + owner + "." + method + descriptor + " in " + gameClass);
        });
        return this;
    }

    /** Any change to a class of the game at all: the ClassFile API ({@code java.lang.classfile}). The class is given by its readable name. */
    public Patcher transform(String gameClass, ClassTransform transform) {
        add(gameClass(gameClass), transform);
        return this;
    }

    // ---- the work ----

    private void add(String gameClassName, ClassTransform t) {
        pending.merge(gameClassName, t, ClassTransform::andThen);
    }

    /** the patches of method bodies of one class: all of them are made in one pass over the class (several of one class, even of one method, work together) */
    private final Map<String, List<BodyPatch>> bodies = new LinkedHashMap<>();

    private record BodyPatch(Predicate<MethodModel> method, CodeTransform code) {
    }

    private void addBody(String gameClassName, Predicate<MethodModel> method, CodeTransform code) {
        bodies.computeIfAbsent(gameClassName, k -> new ArrayList<>()).add(new BodyPatch(method, code));
    }

    private ClassTransform bodiesOf(String gameClassName) {
        List<BodyPatch> ps = bodies.get(gameClassName);
        if (ps == null) return null;
        return (cb, ce) -> {
            if (ce instanceof MethodModel mm && mm.code().isPresent()) {
                CodeTransform all = null;
                for (BodyPatch p : ps) {
                    if (!p.method.test(mm)) continue;
                    all = all == null ? p.code : all.andThen(p.code);
                }
                if (all != null) {
                    cb.transformMethod(mm, MethodTransform.transformingCode(all));
                    return;
                }
            }
            cb.with(ce);
        };
    }

    /** writes every changed class; says which patch found nothing to change */
    public void flush() throws IOException {
        Set<String> names = new LinkedHashSet<>(pending.keySet());
        names.addAll(bodies.keySet());
        for (String name : names) {
            ClassTransform t = pending.get(name), b = bodiesOf(name);
            ClassTransform whole = t == null ? b : b == null ? t : t.andThen(b);
            byte[] before = original(name);
            byte[] after = cf.transformClass(cf.parse(before), whole);
            Path target = out.resolve(name + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, after);
            System.out.println("patched " + name + " (" + before.length + " -> " + after.length + " bytes)");
        }
        for (Runnable c : checks) c.run();
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    private byte[] original(String internal) throws IOException {
        for (Path jar : gameJars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry(internal + ".class");
                if (e != null) return z.getInputStream(e).readAllBytes();
            }
        }
        throw new IOException("the class " + internal + " is not in the game's jars");
    }

    private boolean isStatic(String cn, String mn, String md) {
        try {
            for (MethodModel m : cf.parse(original(cn)).methods()) {
                if (m.methodName().equalsString(mn) && m.methodType().equalsString(md)) return (m.flags().flagsMask() & ClassFile.ACC_STATIC) != 0;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return false; // not found: the patch says so when it is made
    }

    private void call(CodeBuilder b, Hook hook, MethodTypeDesc target, boolean isStatic) {
        call(b, hook, target, isStatic, 0);
    }

    /** the arguments, then the call; skip: how many of the arguments are already on the stack */
    private void call(CodeBuilder b, Hook hook, MethodTypeDesc target, boolean isStatic, int skip) {
        call(b, hook, target, isStatic, skip, false);
    }

    private static int slotOf(MethodTypeDesc target, boolean isStatic, int param) {
        int slot = isStatic ? 0 : 1;
        for (int p = 0; p < param; p++) slot += TypeKind.from(target.parameterType(p)).slotSize();
        return slot;
    }

    private void call(CodeBuilder b, Hook hook, MethodTypeDesc target, boolean isStatic, int skip, boolean keep) {
        for (int i = skip; i < hook.args.size(); i++) {
            Arg a = hook.args.get(i);
            switch (a.kind) {
                case THIS:
                    if (isStatic) throw new IllegalStateException("Arg.THIS in a static method");
                    b.aload(0);
                    break;
                case PARAM: {
                    int slot = isStatic ? 0 : 1;
                    for (int p = 0; p < a.index; p++) slot += TypeKind.from(target.parameterType(p)).slotSize();
                    b.loadLocal(TypeKind.from(target.parameterType(a.index)), slot);
                    break;
                }
                case RETURNED:
                    throw new IllegalStateException("Arg.RETURNED can only be the first argument of a hook at the end of a method");
                case INT:
                    b.loadConstant((Integer) a.value);
                    break;
                case STRING:
                    b.loadConstant((String) a.value);
                    break;
                case NULL:
                    b.aconst_null();
                    break;
            }
        }
        MethodTypeDesc ht = MethodTypeDesc.ofDescriptor(map.desc(hook.descriptor, false));
        b.invokestatic(ClassDesc.ofInternalName(hook.owner), hook.name, ht);
        TypeKind r = TypeKind.from(ht.returnType());
        if (keep) {
            if (r == TypeKind.VOID) throw new IllegalStateException("the hook " + hook.name + " has to return something");
        } else if (r != TypeKind.VOID) {
            if (r.slotSize() == 2) b.pop2();
            else b.pop();
        }
    }
}
