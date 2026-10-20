import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.ZipFile;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/**
 * Names the obfuscated classes, fields and methods of the game's code jar from the open-source libraries it was built with (samskivert, Clyde,
 * Narya, Nenya): a class of the jar is matched to a source class of the same package by the shape of its members (kind, static, the types in
 * the signatures, declaration order), then the members are lined up one by one. The names, and the parameter names, come from the source.
 *
 * <pre>
 *   java SourceMatch.java JAR OUT.mapping REPORT.md SRCDIR...
 *   java SourceMatch.java --previous OLD-DEOBF.jar JAR OUT.mapping REPORT.md
 * </pre>
 *
 * The second form carries the names of an older build over to a newer one: the older build's classes, with the readable names the mapping gave them
 * (OLD-DEOBF.jar, made from the old jar and its mapping), take the place of the open-source sources, and are matched to the new jar's classes by
 * the same shape comparison. What comes out is a mapping of the new jar's names with the old build's names, for the classes and members that were found again.
 */
public final class SourceMatch {
    // ---- source side ----
    static final class SMember {
        char kind; // F field, M method
        String name;
        boolean isStatic;
        String ret; // method: return token; field: type token
        List<String> params = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
    }

    static final class SType {
        String fqn; // internal, pkg/Outer$Inner
        String simple;
        boolean isInterface, isEnum, isAbstract, isStaticNested = true;
        String sup;
        List<String> ifs = new ArrayList<>();
        /** an older build's class: the full names of its superclass and interfaces (to match classes through what they extend and implement) */
        String supFqn;
        List<String> ifFqns = new ArrayList<>();
        List<SMember> fields = new ArrayList<>(), methods = new ArrayList<>();
        List<SType> nested = new ArrayList<>();
        SType outer;
        String origin;
    }

    /** the sources are the classes of an older build (see the top): names that are still obfuscated there are not carried */
    boolean carry;
    final Map<String, SType> src = new LinkedHashMap<>();
    final Map<String, List<SType>> srcByPkg = new HashMap<>();

    // ---- jar side ----
    final Map<String, ClassModel> cls = new TreeMap<>();
    /** the older build's classes as they were read (their code is compared with the jar's: what an obfuscator cannot change) */
    final Map<String, ClassModel> prevModels = new TreeMap<>();
    final Map<String, SType> match = new LinkedHashMap<>(); // jar class -> source class
    final Set<SType> used = new HashSet<>();
    final Map<String, String> why = new HashMap<>();

    static String simple(String internal) {
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        int d = s.lastIndexOf('$');
        return d >= 0 ? s.substring(d + 1) : s;
    }

    static String pkg(String c) {
        int i = c.lastIndexOf('/');
        return i < 0 ? "" : c.substring(0, i);
    }

    static boolean obfSimple(String s) {
        return s.length() <= 3 && !s.isEmpty() && !s.chars().allMatch(Character::isDigit);
    }

    static boolean obfClass(String internal) {
        return obfSimple(simple(internal));
    }

    static boolean obfMember(String name) {
        if (name.startsWith("<") || name.startsWith("lambda$") || name.startsWith("access$") || name.contains("$")) return false;
        return name.length() <= 3 && !Set.of("id", "get", "set", "put", "add", "run", "map", "key", "log", "has", "len", "max", "min", "pop", "of", "is", "to", "x", "y", "z", "w", "h", "r", "g", "b", "u", "v", "s", "t", "i", "j", "k", "n", "d", "e").contains(name) || name.length() == 1 && !Set.of("x", "y", "z", "w", "h").contains(name);
    }

    // ---- reading the sources ----
    void readSources(List<Path> roots) throws IOException {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        for (Path root : roots) {
            List<Path> files;
            try (var w = Files.walk(root)) {
                files = w.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/test/") && !p.toString().contains("\\test\\") && !p.getFileName().toString().equals("package-info.java") && !p.getFileName().toString().equals("module-info.java")).toList();
            }
            StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
            for (int off = 0; off < files.size(); off += 200) {
                var units = fm.getJavaFileObjectsFromPaths(files.subList(off, Math.min(files.size(), off + 200)));
                JavacTask task = (JavacTask) jc.getTask(null, fm, d -> {
                }, List.of("-proc:none"), null, units);
                for (CompilationUnitTree cu : task.parse()) {
                    String pk = cu.getPackageName() == null ? "" : cu.getPackageName().toString().replace('.', '/');
                    for (Tree t : cu.getTypeDecls()) if (t instanceof ClassTree ct) readType(ct, pk, null, root.getFileName().toString());
                }
            }
        }
    }

    static String typeTok(Tree t, Set<String> vars) {
        if (t == null) return "V";
        if (t instanceof PrimitiveTypeTree p) {
            switch (p.getPrimitiveTypeKind()) {
                case INT: return "I";
                case LONG: return "J";
                case FLOAT: return "F";
                case DOUBLE: return "D";
                case BOOLEAN: return "Z";
                case BYTE: return "B";
                case SHORT: return "S";
                case CHAR: return "C";
                default: return "V";
            }
        }
        if (t instanceof ArrayTypeTree a) return "[" + typeTok(a.getType(), vars);
        if (t instanceof ParameterizedTypeTree pt) return typeTok(pt.getType(), vars);
        if (t instanceof AnnotatedTypeTree at) return typeTok(at.getUnderlyingType(), vars);
        if (t instanceof WildcardTree) return "L:?";
        String n = t.toString();
        int d = n.lastIndexOf('.');
        if (d >= 0) n = n.substring(d + 1);
        if (vars.contains(n)) return "L:?";
        return "L:" + n;
    }

    void readType(ClassTree ct, String pk, SType outer, String origin) {
        SType st = new SType();
        String name = ct.getSimpleName().toString();
        if (name.isEmpty()) return;
        st.simple = name;
        st.outer = outer;
        st.origin = origin;
        st.fqn = outer == null ? (pk.isEmpty() ? name : pk + "/" + name) : outer.fqn + "$" + name;
        Tree.Kind k = ct.getKind();
        st.isInterface = k == Tree.Kind.INTERFACE || k == Tree.Kind.ANNOTATION_TYPE;
        st.isEnum = k == Tree.Kind.ENUM;
        st.isAbstract = ct.getModifiers().getFlags().contains(Modifier.ABSTRACT);
        st.isStaticNested = outer == null || ct.getModifiers().getFlags().contains(Modifier.STATIC) || st.isInterface || st.isEnum || outer.isInterface;
        Set<String> vars = new HashSet<>();
        for (TypeParameterTree tp : ct.getTypeParameters()) vars.add(tp.getName().toString());
        if (outer != null && !st.isStaticNested) vars.addAll(outerVars.getOrDefault(outer.fqn, Set.of()));
        outerVars.put(st.fqn, vars);
        if (ct.getExtendsClause() != null) st.sup = simpleName(ct.getExtendsClause());
        for (Tree i : ct.getImplementsClause()) st.ifs.add(simpleName(i));
        if (st.isInterface && k == Tree.Kind.INTERFACE) {
            // an interface lists what it extends in the extends clause too
            for (Tree i : ct.getImplementsClause()) {
            }
        }
        for (Tree m : ct.getMembers()) {
            if (m instanceof VariableTree vt) {
                SMember f = new SMember();
                f.kind = 'F';
                f.name = vt.getName().toString();
                Set<Modifier> fl = vt.getModifiers().getFlags();
                f.isStatic = fl.contains(Modifier.STATIC) || st.isInterface;
                boolean enumConst = st.isEnum && vt.getInitializer() instanceof NewClassTree && vt.getType() != null && vt.getType().toString().equals(name) && !fl.contains(Modifier.STATIC) && vt.getModifiers().getFlags().isEmpty();
                if (enumConst) f.isStatic = true;
                f.ret = typeTok(vt.getType(), vars);
                st.fields.add(f);
            } else if (m instanceof MethodTree mt) {
                String mn = mt.getName().toString();
                if (mn.equals("<init>")) continue;
                SMember f = new SMember();
                f.kind = 'M';
                f.name = mn;
                f.isStatic = mt.getModifiers().getFlags().contains(Modifier.STATIC);
                Set<String> mv = new HashSet<>(vars);
                for (TypeParameterTree tp : mt.getTypeParameters()) mv.add(tp.getName().toString());
                f.ret = typeTok(mt.getReturnType(), mv);
                for (VariableTree p : mt.getParameters()) {
                    String tk = typeTok(p.getType(), mv);
                    f.params.add(tk);
                    f.paramNames.add(p.getName().toString());
                }
                st.methods.add(f);
            } else if (m instanceof ClassTree nt) {
                readType(nt, pk, st, origin);
            }
        }
        if (outer != null) outer.nested.add(st);
        if (src.putIfAbsent(st.fqn, st) == null) srcByPkg.computeIfAbsent(pkg(st.fqn.contains("$") ? st.fqn.substring(0, st.fqn.indexOf('$')) : st.fqn), x -> new ArrayList<>()).add(st);
    }

    final Map<String, Set<String>> outerVars = new HashMap<>();

    static String simpleName(Tree t) {
        if (t instanceof ParameterizedTypeTree p) return simpleName(p.getType());
        String n = t.toString();
        int d = n.lastIndexOf('.');
        return d >= 0 ? n.substring(d + 1) : n;
    }

/** the older build's classes as the "sources": names, kinds and member shapes read from the class files (types are the simple names of the classes) */
void readPrevious(Path jar) throws IOException {
    carry = true;
    Map<String, ClassModel> pc = prevModels;
    try (ZipFile z = new ZipFile(jar.toFile())) {
        for (var e : Collections.list(z.entries())) {
            if (!e.getName().endsWith(".class")) continue;
            ClassModel cm = ClassFile.of().parse(z.getInputStream(e).readAllBytes());
            pc.put(cm.thisClass().asInternalName(), cm);
        }
    }
    Map<String, SType> made = new HashMap<>();
    for (var e : pc.entrySet()) {
        String c = e.getKey();
        ClassModel cm = e.getValue();
        int fl = cm.flags().flagsMask();
        SType st = new SType();
        st.fqn = c;
        st.simple = simple(c);
        st.origin = "previous";
        st.isInterface = (fl & ClassFile.ACC_INTERFACE) != 0;
        st.isEnum = (fl & ClassFile.ACC_ENUM) != 0;
        st.isAbstract = (fl & ClassFile.ACC_ABSTRACT) != 0;
        String sup = cm.superclass().map(x -> x.asInternalName()).orElse("java/lang/Object");
        if (!sup.equals("java/lang/Object") && !st.isInterface) st.sup = simple(sup);
        st.supFqn = sup;
        for (var i : cm.interfaces()) {
            st.ifs.add(simple(i.asInternalName()));
            st.ifFqns.add(i.asInternalName());
        }
        boolean hasOuterRef = false;
        for (FieldModel f : cm.fields()) {
            String fn = f.fieldName().stringValue();
            int ff = f.flags().flagsMask();
            if (fn.startsWith("this$")) hasOuterRef = true;
            if ((ff & ClassFile.ACC_SYNTHETIC) != 0 || fn.contains("$")) continue;
            SMember m = new SMember();
            m.kind = 'F';
            m.name = fn;
            m.isStatic = (ff & ClassFile.ACC_STATIC) != 0;
            m.ret = prevTok(f.fieldType().stringValue());
            st.fields.add(m);
        }
        for (MethodModel mm : cm.methods()) {
            int mf = mm.flags().flagsMask();
            String n = mm.methodName().stringValue();
            if ((mf & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0 || n.startsWith("<") || n.startsWith("lambda$") || n.startsWith("access$")) continue;
            if (st.isEnum && (n.equals("values") || n.equals("valueOf"))) continue;
            SMember m = new SMember();
            m.kind = 'M';
            m.name = n;
            m.isStatic = (mf & ClassFile.ACC_STATIC) != 0;
            MethodTypeDesc md = mm.methodTypeSymbol();
            m.ret = prevTok(md.returnType().descriptorString());
            for (var pd : md.parameterList()) {
                m.params.add(prevTok(pd.descriptorString()));
                m.paramNames.add("arg");
            }
            st.methods.add(m);
        }
        st.isStaticNested = !hasOuterRef;
        made.put(c, st);
    }
    for (var e : made.entrySet()) {
        SType st = e.getValue();
        int d = e.getKey().lastIndexOf('$');
        SType outer = d >= 0 ? made.get(e.getKey().substring(0, d)) : null;
        if (outer != null) {
            st.outer = outer;
            outer.nested.add(st);
        }
        src.put(st.fqn, st);
        if (outer == null) srcByPkg.computeIfAbsent(pkg(st.fqn), k -> new ArrayList<>()).add(st);
    }
}

/**
 * What the code of a class says that an obfuscator cannot change: the texts and the larger numbers it loads, what it calls and reads of the world outside
 * the game's code (the JDK, the libraries: their names are not obfuscated), and which already matched classes it uses. {@code key} gives an owner class
 * its feature key, or null for a class that is of the game's and not matched (its name means nothing to the other build).
 */
static Set<String> features(ClassModel cm, java.util.function.Function<String, String> key) {
    Set<String> out = new HashSet<>();
    cm.superclass().ifPresent(s -> {
        String k = key.apply(s.asInternalName());
        if (k != null) out.add("X:" + k);
    });
    for (var i : cm.interfaces()) {
        String k = key.apply(i.asInternalName());
        if (k != null) out.add("X:" + k);
    }
    for (MethodModel mm : cm.methods()) {
        Optional<CodeModel> code = mm.code();
        if (code.isEmpty()) continue;
        for (CodeElement el : code.get()) {
            if (el instanceof ConstantInstruction.LoadConstantInstruction lc) {
                Object v = lc.constantValue();
                if (v instanceof String s && s.length() >= 3) out.add("S:" + s);
                else if (v instanceof Integer n && Math.abs(n) > 255 || v instanceof Long || v instanceof Float || v instanceof Double) out.add("N:" + v);
            } else if (el instanceof InvokeInstruction in) {
                String k = key.apply(in.owner().asInternalName());
                if (k == null) continue;
                out.add(k.startsWith("G:") ? k : "I:" + k + "." + in.name().stringValue() + "/" + in.typeSymbol().parameterCount());
            } else if (el instanceof FieldInstruction fi) {
                String k = key.apply(fi.owner().asInternalName());
                if (k == null) continue;
                out.add(k.startsWith("G:") ? k : "F:" + k + "." + fi.name().stringValue());
            }
        }
    }
    return out;
}

private static double jaccard(Set<String> x, Set<String> y) {
    int common = 0;
    for (String s : x) if (y.contains(s)) common++;
    return (double) common / (x.size() + y.size() - common);
}

/**
 * Classes the shape of the members does not settle (they changed, or are alike) are told apart by their code: two classes are the same when what they say
 * (see {@link #features}) is nearly the same, with the matched classes they use as part of it. A pair is taken only when it is clearly the best for both
 * sides, so a doubtful one is left for a person (or the guess) rather than made wrong. Returns how many classes it matched.
 */
int matchContents() {
    java.util.function.Function<String, String> jarKey = o -> cls.containsKey(o) ? (match.containsKey(o) ? "G:" + match.get(o).fqn : null) : "E:" + o;
    java.util.function.Function<String, String> oldKey = o -> prevModels.containsKey(o) ? (used.contains(src.get(o)) ? "G:" + o : null) : "E:" + o;
    Map<String, Set<String>> jf = new HashMap<>(), of = new HashMap<>();
    for (String jc : cls.keySet()) {
        if (match.containsKey(jc) || !(obfClass(jc) || carry)) continue;
        Set<String> f = features(cls.get(jc), jarKey);
        if (f.size() >= 6) jf.put(jc, f);
    }
    for (var e : src.entrySet()) {
        if (used.contains(e.getValue()) || !prevModels.containsKey(e.getKey())) continue;
        Set<String> f = features(prevModels.get(e.getKey()), oldKey);
        if (f.size() >= 6) of.put(e.getKey(), f);
    }
    Map<String, String> bestOld = new HashMap<>();
    Map<String, Double> bestScore = new HashMap<>(), nextScore = new HashMap<>();
    for (String jc : jf.keySet()) {
        double b = 0, n = 0;
        String bo = null;
        boolean iface = (cls.get(jc).flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0;
        for (String oc : of.keySet()) {
            if (!pkg(jc).equals(pkg(oc)) || iface != src.get(oc).isInterface) continue;
            double sim = jaccard(jf.get(jc), of.get(oc));
            if (sim > b) {
                n = b;
                b = sim;
                bo = oc;
            } else if (sim > n) {
                n = sim;
            }
        }
        if (bo != null) {
            bestOld.put(jc, bo);
            bestScore.put(jc, b);
            nextScore.put(jc, n);
        }
    }
    // the same from the older class's side: the pair must be each other's best
    int added = 0;
    for (var e : bestOld.entrySet()) {
        String jc = e.getKey(), oc = e.getValue();
        double b = bestScore.get(jc), n = nextScore.get(jc);
        if (b < 0.55 || b - n < 0.12) continue;
        boolean mutual = true;
        for (String other : jf.keySet()) {
            if (other.equals(jc) || !pkg(other).equals(pkg(oc))) continue;
            if (jaccard(jf.get(other), of.get(oc)) >= b) {
                mutual = false;
                break;
            }
        }
        SType st = src.get(oc);
        if (!mutual || st == null || used.contains(st) || match.containsKey(jc)) continue;
        match.put(jc, st);
        used.add(st);
        why.put(jc, String.format("content %.2f", b));
        added++;
    }
    System.out.println("by what their code says: " + added + " classes matched (" + match.size() + " in all)");
    return added;
}

/** how many of the matched classes are the class the mapping says they are (a check made with a jar whose names are known) */
void check(Path mapping, String stage) throws IOException {
    Map<String, String> truth = new HashMap<>();
    for (String l : Files.readAllLines(mapping)) {
        if (!l.startsWith("CLASS ")) continue;
        String[] t = l.split(" ");
        if (t.length > 2) truth.put(t[1], t[2]);
    }
    int right = 0, wrong = 0, unknown = 0;
    Map<String, Integer> byWhy = new TreeMap<>();
    for (var e : match.entrySet()) {
        String want = truth.get(e.getKey());
        if (want == null) {
            unknown++;
            continue;
        }
        boolean ok = want.equals(e.getValue().fqn);
        if (ok) right++;
        else wrong++;
        String w = why.getOrDefault(e.getKey(), "shape");
        if (w.startsWith("content")) w = "content";
        byWhy.merge(w + (ok ? " right" : " WRONG"), 1, Integer::sum);
    }
    System.out.println("check " + stage + ": " + right + " right, " + wrong + " wrong, " + unknown + " not top-level classes of the mapping " + byWhy);
}

/** a descriptor as the matcher's type token: a primitive as it is, a class by its simple name */
static String prevTok(String d) {
    int dims = 0;
    while (d.startsWith("[")) {
        dims++;
        d = d.substring(1);
    }
    String base = d.length() == 1 ? d : "L:" + simple(d.substring(1, d.length() - 1));
    return "[".repeat(dims) + base;
}

// ---- the jar ----
final class JMember {
        char kind;
        String name, desc;
        boolean isStatic;
        String ret;
        List<String> params = new ArrayList<>();
    }

    final Map<String, List<JMember>> jFields = new HashMap<>(), jMethods = new HashMap<>();

    static String descTok(String d) {
        int dims = 0;
        while (d.startsWith("[")) {
            dims++;
            d = d.substring(1);
        }
        String base = d.length() == 1 ? d : "L:" + d.substring(1, d.length() - 1);
        return "[".repeat(dims) + base;
    }

    void readJar(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                ClassModel cm = ClassFile.of().parse(z.getInputStream(e).readAllBytes());
                cls.put(cm.thisClass().asInternalName(), cm);
            }
        }
        for (var e : cls.entrySet()) {
            ClassModel cm = e.getValue();
            boolean isEnum = (cm.flags().flagsMask() & ClassFile.ACC_ENUM) != 0;
            List<JMember> fs = new ArrayList<>(), ms = new ArrayList<>();
            for (FieldModel f : cm.fields()) {
                int fl = f.flags().flagsMask();
                if ((fl & ClassFile.ACC_SYNTHETIC) != 0 || f.fieldName().stringValue().contains("$")) continue;
                JMember m = new JMember();
                m.kind = 'F';
                m.name = f.fieldName().stringValue();
                m.desc = f.fieldType().stringValue();
                m.isStatic = (fl & ClassFile.ACC_STATIC) != 0;
                m.ret = descTok(m.desc);
                fs.add(m);
            }
            for (MethodModel mm : cm.methods()) {
                int fl = mm.flags().flagsMask();
                String n = mm.methodName().stringValue();
                if ((fl & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0 || n.startsWith("<") || n.startsWith("lambda$") || n.startsWith("access$")) continue;
                if (isEnum && (n.equals("values") || n.equals("valueOf"))) continue;
                JMember m = new JMember();
                m.kind = 'M';
                m.name = n;
                m.desc = mm.methodType().stringValue();
                m.isStatic = (fl & ClassFile.ACC_STATIC) != 0;
                MethodTypeDesc md = mm.methodTypeSymbol();
                m.ret = descTok(md.returnType().descriptorString());
                for (var p : md.parameterList()) m.params.add(descTok(p.descriptorString()));
                ms.add(m);
            }
            jFields.put(e.getKey(), fs);
            jMethods.put(e.getKey(), ms);
        }
    }

    // ---- comparing ----
    /** 0 = no; 1 = could be; 3 = the same. */
    int tokScore(String s, String j) {
        if (s.length() == 1 || j.length() == 1) return s.equals(j) ? 3 : 0;
        if (s.startsWith("[") != j.startsWith("[")) return s.equals("L:?") && !j.startsWith("[") ? 1 : 0;
        if (s.startsWith("[")) return tokScore(s.substring(1), j.substring(1));
        if (s.length() == 1 || j.length() == 1) return 0;
        String sn = s.substring(2), jn = j.substring(2);
        if (sn.equals("?")) return 1;
        SType m = match.get(jn);
        if (m != null) return m.simple.equals(sn) ? 3 : 0;
        if (cls.containsKey(jn) && (obfClass(jn) || carry && !src.containsKey(jn))) return 1; // not matched yet
        String js = simple(jn);
        if (js.equals(sn)) return 3;
        // a type of the jar that has no match and a name: the same simple name needed, with one exception: Object stands for a type variable
        return js.equals("Object") && false ? 1 : 0;
    }

    /** the score of lining up a source member with a jar member; 0 if they cannot be the same. */
    int memScore(SMember s, JMember j) {
        if (s.isStatic != j.isStatic) return 0;
        if (!obfMember(j.name) && !j.name.equals(s.name)) return 0;
        int sc = 1 + (obfMember(j.name) ? 0 : 3);
        int r = tokScore(s.ret, j.ret);
        if (r == 0) return 0;
        sc += r;
        if (s.kind == 'M') {
            if (s.params.size() != j.params.size()) return 0;
            for (int i = 0; i < s.params.size(); i++) {
                int p = tokScore(s.params.get(i), j.params.get(i));
                if (p == 0) return 0;
                sc += p;
            }
        }
        return sc;
    }

    /** the best way to line up two sequences in order; returns pairs and total score. */
    static final class Align {
        int score;
        List<int[]> pairs = new ArrayList<>();
    }

    <A, B> Align align(List<A> a, List<B> b, java.util.function.ToIntBiFunction<A, B> sc) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        int[][] cell = new int[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) cell[i][j] = sc.applyAsInt(a.get(i), b.get(j));
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                int best = Math.max(dp[i + 1][j], dp[i][j + 1]);
                if (cell[i][j] > 0) best = Math.max(best, dp[i + 1][j + 1] + cell[i][j]);
                dp[i][j] = best;
            }
        }
        Align al = new Align();
        al.score = dp[0][0];
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (cell[i][j] > 0 && dp[i][j] == dp[i + 1][j + 1] + cell[i][j]) {
                al.pairs.add(new int[] {i, j});
                i++;
                j++;
            } else if (dp[i][j] == dp[i + 1][j]) i++;
            else j++;
        }
        return al;
    }

    /** how well a source class fits a jar class, 0..1, or -1 if they cannot be the same. */
    double fit(SType s, String jc) {
        ClassModel cm = cls.get(jc);
        int fl = cm.flags().flagsMask();
        if (((fl & ClassFile.ACC_INTERFACE) != 0) != s.isInterface) return -1;
        if (((fl & ClassFile.ACC_ENUM) != 0) != s.isEnum) return -1;
        // the superclass: a named one must be the same
        String js = cm.superclass().map(c -> c.asInternalName()).orElse("java/lang/Object");
        if (!s.isInterface && !s.isEnum) {
            String want = s.sup == null ? "Object" : s.sup;
            if (!js.equals("java/lang/Object") || s.sup == null) {
                SType m = match.get(js);
                if (m != null) {
                    if (!m.simple.equals(want)) return -1;
                } else if (!(cls.containsKey(js) && obfClass(js))) {
                    if (!simple(js).equals(want)) return -1;
                }
            } else if (s.sup != null && !s.sup.equals("Object")) {
                // the jar says Object, the source something else: a source class with an unknown super (a type of another library) is not excluded
            }
        }
        List<SMember> sf = s.fields, sm = s.methods;
        List<JMember> jf = jFields.get(jc), jm = jMethods.get(jc);
        Align af = align(sf, jf, this::memScore), am = align(sm, jm, this::memScore);
        int total = Math.max(sf.size(), jf.size()) + Math.max(sm.size(), jm.size());
        if (total == 0) return -1;
        int matched = af.pairs.size() + am.pairs.size();
        if (matched == 0) return -1;
        // unmatched jar members that are named are a sign this is another class
        return (double) matched / total;
    }

    // ---- matching classes ----
    void matchClasses() {
        // 1. the same name
        for (String jc : cls.keySet()) {
            SType s = src.get(jc);
            if (s != null && !used.contains(s)) {
                match.put(jc, s);
                used.add(s);
                why.put(jc, "same name");
            }
        }
        // 2. the rest, round after round
        for (int round = 1; round <= 12; round++) {
            List<Object[]> cands = new ArrayList<>();
            Map<String, List<SType>> byJar = new HashMap<>();
            for (String jc : cls.keySet()) {
                if (match.containsKey(jc) || !(obfClass(jc) || carry)) continue; // (an older build's hand-made renames can be of classes that were never obfuscated)
                List<SType> pool;
                int d = jc.lastIndexOf('$');
                if (d >= 0) {
                    SType outer = match.get(jc.substring(0, d));
                    if (outer == null) continue;
                    pool = outer.nested;
                } else pool = srcByPkg.getOrDefault(pkg(jc), List.of());
                for (SType s : pool) {
                    if (used.contains(s)) continue;
                    double f = fit(s, jc);
                    if (f >= 0.6) cands.add(new Object[] {f, jc, s});
                }
            }
            cands.sort((x, y) -> Double.compare((double) y[0], (double) x[0]));
            // a candidate is taken when nothing else is nearly as good for the same class or the same source class
            Map<String, Double> bestJ = new HashMap<>(), secondJ = new HashMap<>();
            Map<SType, Double> bestS = new HashMap<>(), secondS = new HashMap<>();
            for (Object[] c : cands) {
                double f = (double) c[0];
                String jc = (String) c[1];
                SType s = (SType) c[2];
                if (!bestJ.containsKey(jc)) bestJ.put(jc, f);
                else secondJ.putIfAbsent(jc, f);
                if (!bestS.containsKey(s)) bestS.put(s, f);
                else secondS.putIfAbsent(s, f);
            }
            int added = 0;
            for (Object[] c : cands) {
                double f = (double) c[0];
                String jc = (String) c[1];
                SType s = (SType) c[2];
                if (match.containsKey(jc) || used.contains(s)) continue;
                double margin = 0.08;
                if (f < bestJ.get(jc) || f < bestS.get(s)) continue;
                if (secondJ.containsKey(jc) && f - secondJ.get(jc) < margin) continue;
                if (secondS.containsKey(s) && f - secondS.get(s) < margin) continue;
                // little to go on (one or two members) needs a perfect fit
                int size = jFields.get(jc).size() + jMethods.get(jc).size();
                if (size <= 2 && f < 0.99) continue;
                if (f < 0.75 && size < 6) continue;
                match.put(jc, s);
                used.add(s);
                why.put(jc, String.format("shape %.2f round %d", f, round));
                added++;
            }
            System.out.println("round " + round + ": " + added + " classes matched (" + match.size() + " in all)");
            if (added == 0) break;
        }
    }

/**
 * Classes with next to nothing in them (a marker interface) cannot be told apart by their members. Once their neighbours are matched they can: the
 * class that implements or extends one has the same interfaces, in the same order, in both builds. Returns how many classes it matched.
 */
int matchRelations() {
    int added = 0;
    for (Map.Entry<String, SType> e : new ArrayList<>(match.entrySet())) {
        String jc = e.getKey();
        SType s = e.getValue();
        ClassModel cm = cls.get(jc);
        List<String> ji = cm.interfaces().stream().map(c -> c.asInternalName()).toList();
        if (ji.size() == s.ifFqns.size()) {
            for (int k = 0; k < ji.size(); k++) added += pair(ji.get(k), s.ifFqns.get(k));
        }
        String js = cm.superclass().map(c -> c.asInternalName()).orElse(null);
        if (js != null && s.supFqn != null) added += pair(js, s.supFqn);
    }
    System.out.println("through what they implement and extend: " + added + " classes matched (" + match.size() + " in all)");
    return added;
}

/** a jar class and an older class that stand in the same place of two matched classes are the same class, when neither is matched yet and they agree in kind and package */
int pair(String jc, String sf) {
    SType st = src.get(sf);
    if (st == null || match.containsKey(jc) || used.contains(st) || !cls.containsKey(jc)) return 0;
    if (!pkg(jc).equals(pkg(sf)) || ((cls.get(jc).flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0) != st.isInterface) return 0;
    match.put(jc, st);
    used.add(st);
    why.put(jc, "implements/extends");
    return 1;
}

// ---- output ----
final Map<String, String> fieldNames = new HashMap<>(), methodNames = new HashMap<>();
    final Map<String, List<String>> args = new HashMap<>();
    int fieldsMatched, methodsMatched, conflictsInGroup;

    void matchMembers() {
        for (var e : match.entrySet()) {
            String jc = e.getKey();
            SType s = e.getValue();
            List<JMember> jf = jFields.get(jc), jm = jMethods.get(jc);
            Align af = align(s.fields, jf, this::memScore), am = align(s.methods, jm, this::memScore);
            for (int[] p : af.pairs) {
                JMember j = jf.get(p[1]);
                SMember sm = s.fields.get(p[0]);
                if (obfMember(j.name) && !j.name.equals(sm.name) && !(carry && obfMember(sm.name))) {
                    fieldNames.put(jc + "." + j.name, sm.name);
                    fieldsMatched++;
                }
            }
            for (int[] p : am.pairs) {
                JMember j = jm.get(p[1]);
                SMember sm = s.methods.get(p[0]);
                String key = jc + "." + j.name + j.desc;
                if (obfMember(j.name) && !j.name.equals(sm.name) && !(carry && obfMember(sm.name))) {
                    methodNames.put(key, sm.name);
                    methodsMatched++;
                }
                // parameter names, by slot
                if (!sm.paramNames.isEmpty() && !carry) {
                    List<String> as = new ArrayList<>();
                    int slot = j.isStatic ? 0 : 1;
                    for (int i = 0; i < j.params.size(); i++) {
                        as.add("ARG " + slot + " " + sm.paramNames.get(i));
                        slot += j.params.get(i).equals("J") || j.params.get(i).equals("D") ? 2 : 1;
                    }
                    args.put(key, as);
                }
            }
        }
    }

    String newClassName(String jc) {
        SType s = match.get(jc);
        if (s == null) return null;
        if (jc.equals(s.fqn)) return null;
        if (carry && obfSimple(s.simple)) return null; // still obfuscated in the older build
        int d = jc.lastIndexOf('$');
        if (d >= 0) return jc.substring(0, d + 1) + s.simple;
        return pkg(jc).isEmpty() ? s.simple : pkg(jc) + "/" + s.simple;
    }

    void write(Path out, Path report) throws IOException {
        Map<String, List<String>> inners = new TreeMap<>();
        for (String c : cls.keySet()) {
            int d = c.lastIndexOf('$');
            if (d >= 0 && cls.containsKey(c.substring(0, d))) inners.computeIfAbsent(c.substring(0, d), k -> new ArrayList<>()).add(c);
        }
        StringBuilder sb = new StringBuilder();
        for (String c : cls.keySet()) {
            int d = c.lastIndexOf('$');
            if (d >= 0 && cls.containsKey(c.substring(0, d))) continue;
            emit(sb, c, 0, inners);
        }
        Files.writeString(out, sb.toString());
        long renamed = match.keySet().stream().filter(c -> newClassName(c) != null).count();
        long exact = why.values().stream().filter(w -> w.equals("same name")).count();
        StringBuilder r = new StringBuilder("# Names from the open-source libraries\n\n");
        r.append("Source classes read: ").append(src.size()).append("\n\nJar classes matched to a source class: ").append(match.size()).append(" (").append(exact).append(" by the same name, ").append(match.size() - exact).append(" by shape)\n\n");
        r.append("Classes renamed: ").append(renamed).append("\n\nFields named: ").append(fieldNames.size()).append("\n\nMethods named: ").append(methodNames.size()).append("\n\nMethods given parameter names: ").append(args.size()).append("\n\n");
        long obfLeft = cls.keySet().stream().filter(c -> obfClass(c) && (c.startsWith("com/samskivert/") || c.startsWith("com/threerings/")) && !match.containsKey(c)).count();
        r.append("Obfuscated classes in the library packages with no match: ").append(obfLeft).append("\n\n");
        r.append("## Matches by origin\n\n");
        Map<String, Long> byOrigin = match.values().stream().collect(Collectors.groupingBy(s -> s.origin, TreeMap::new, Collectors.counting()));
        byOrigin.forEach((k, v) -> r.append("* ").append(k).append(": ").append(v).append("\n"));
        r.append("\n## Unmatched obfuscated classes by package (top 25)\n\n");
        Map<String, Long> un = cls.keySet().stream().filter(c -> obfClass(c) && (c.startsWith("com/samskivert/") || c.startsWith("com/threerings/")) && !c.startsWith("com/threerings/projectx") && !match.containsKey(c)).collect(Collectors.groupingBy(SourceMatch::pkg, Collectors.counting()));
        un.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(25).forEach(x -> r.append("* ").append(x.getKey()).append(": ").append(x.getValue()).append("\n"));
        Files.writeString(report, r.toString());
    }

    void emit(StringBuilder sb, String c, int depth, Map<String, List<String>> inners) {
        String tabs = "\t".repeat(depth);
        String nn = newClassName(c);
        sb.append(tabs).append("CLASS ").append(depth == 0 ? c : c.substring(c.lastIndexOf('$') + 1));
        if (nn != null) sb.append(' ').append(depth == 0 ? nn : nn.substring(nn.lastIndexOf('$') + 1));
        sb.append('\n');
        ClassModel cm = cls.get(c);
        for (FieldModel f : cm.fields()) {
            String n = fieldNames.get(c + "." + f.fieldName().stringValue());
            if (n != null) sb.append(tabs).append("\tFIELD ").append(f.fieldName().stringValue()).append(' ').append(n).append(' ').append(f.fieldType().stringValue()).append('\n');
        }
        for (MethodModel m : cm.methods()) {
            String key = c + "." + m.methodName().stringValue() + m.methodType().stringValue();
            String n = methodNames.get(key);
            List<String> as = args.get(key);
            if (n != null) sb.append(tabs).append("\tMETHOD ").append(m.methodName().stringValue()).append(' ').append(n).append(' ').append(m.methodType().stringValue()).append('\n');
            else if (as != null) sb.append(tabs).append("\tMETHOD ").append(m.methodName().stringValue()).append(' ').append(m.methodType().stringValue()).append('\n');
            if (as != null) for (String a : as) sb.append(tabs).append("\t\t").append(a).append('\n');
        }
        for (String in : inners.getOrDefault(c, List.of())) emit(sb, in, depth + 1, inners);
    }

    public static void main(String[] a) throws Exception {
        SourceMatch sm = new SourceMatch();
        Path truth = null;
        if (a.length > 2 && a[a.length - 2].equals("--truth")) { // a check, for a jar whose names are known: --truth MAPPING at the end
            truth = Path.of(a[a.length - 1]);
            a = Arrays.copyOf(a, a.length - 2);
        }
        if (a.length > 0 && a[0].equals("--previous")) {
            // --previous OLD-DEOBF.jar JAR OUT REPORT
            sm.readPrevious(Path.of(a[1]));
            System.out.println("classes of the older build " + sm.src.size());
            sm.readJar(Path.of(a[2]));
            boolean noShape = System.getProperty("sm.noshape") != null; // a test of the code matching alone, on a jar whose names are known
            if (!noShape) {
                sm.matchClasses();
                for (int again = 0; again < 4 && sm.matchRelations() > 0; again++) sm.matchClasses();
            }
            if (truth != null) sm.check(truth, "by shape");
            // what the shape could not settle: the code
            for (int again = 0; again < 4; again++) {
                if (sm.matchContents() == 0) break;
                for (int r = 0; r < 4 && sm.matchRelations() > 0; r++) sm.matchClasses();
            }
            if (truth != null) sm.check(truth, "with the code");
            sm.matchMembers();
            System.out.println("carried: " + sm.match.size() + " classes, fields " + sm.fieldNames.size() + " methods " + sm.methodNames.size());
            sm.write(Path.of(a[3]), Path.of(a[4]));
            return;
        }
        List<Path> roots = new ArrayList<>();
        for (int i = 3; i < a.length; i++) roots.add(Path.of(a[i]));
        sm.readSources(roots);
        System.out.println("source classes " + sm.src.size());
        sm.readJar(Path.of(a[0]));
        sm.matchClasses();
        sm.matchMembers();
        System.out.println("fields " + sm.fieldNames.size() + " methods " + sm.methodNames.size() + " args " + sm.args.size());
        sm.write(Path.of(a[1]), Path.of(a[2]));
    }
}
