import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * Makes an Enigma mapping (enigma_file format) for the obfuscated names in the Spiral Knights code jar, from evidence in the bytecode itself:
 *
 * <pre>
 *   java Mapper.java GAMEDIR OUT.mapping REPORT.md [--carry CARRIED.mapping] [--personal DIR] [--source SOURCE.mapping]
 * </pre>
 *
 * The layers are loaded in this order and a later one wins: the names carried over from an older build (see SourceMatch --previous), the hand-made
 * ones (a folder of Enigma .mapping files), the ones from the open-source libraries; the names read out of the code fill in the rest.
 *
 * Enigma cannot find meaningful names; this does. A name is only given when something in the code supports it, and each is a best reading, not
 * a fact: the report says how many came from which kind of evidence. Order: classes, fields, methods (each uses the names before it).
 */
public final class Mapper {
    // ---- the jar ----
    final Map<String, ClassModel> cls = new TreeMap<>();
    final Map<String, String> sup = new HashMap<>();
    final Map<String, List<String>> ifs = new HashMap<>();
    final Map<String, List<String>> direct = new HashMap<>(); // direct supertypes in the jar
    final Map<String, Set<String>> subs = new HashMap<>();
    final URLClassLoader loader;
    final Map<String, Set<String>> external = new HashMap<>();

    // ---- results ----
    final Map<String, String> className = new HashMap<>(); // obf internal name -> new internal name
    final Map<String, String> classWhy = new HashMap<>();
    final Map<String, String> fieldName = new HashMap<>(); // owner.name -> new
    final Map<String, String> fieldWhy = new HashMap<>();
    final Map<String, String> methodName = new HashMap<>(); // owner.name+desc -> new
    final Map<String, String> methodWhy = new HashMap<>();
    // names from the personal investigations: fixed, they win over everything found here
    final Map<String, String> pClass = new LinkedHashMap<>(), pField = new HashMap<>(), pMethod = new HashMap<>();
    final Map<String, List<String>> pArgs = new HashMap<>();
    final List<String> personalNotes = new ArrayList<>();
    int personalFiles;

    /** reads every .mapping file under a folder (Enigma's directory format); only entries that give a new name count, and the parameter names. */
    final Map<String, String> pTag = new HashMap<>();
    final List<String> conflicts = new ArrayList<>();

    void loadPersonal(Path dir) throws IOException {
        load(dir, "personal");
    }

    void setPreset(Map<String, String> m, String key, String val, String tag, String kind) {
        String old = m.put(key, val);
        if (old != null && !old.equals(val)) conflicts.add(kind + " | " + key + " | " + old + " (" + pTag.get(key) + ") | " + val + " (" + tag + ")");
        pTag.put(key, tag);
    }

    /** a folder of .mapping files or one file; the later a load, the more it counts (a name of the same entry is replaced and the difference noted). */
    void load(Path dir, String tag) throws IOException {
        List<Path> files;
        try (var w = Files.walk(dir)) {
            files = w.filter(x -> x.toString().endsWith(".mapping")).sorted().toList();
        }
        for (Path f : files) {
            personalFiles++;
            List<String> stack = new ArrayList<>();
            String lastMethod = null;
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                int depth = 0;
                while (depth < line.length() && line.charAt(depth) == '\t') depth++;
                String[] t = line.trim().split("\\s+");
                switch (t[0]) {
                    case "CLASS": {
                        String full = depth == 0 ? t[1] : stack.get(depth - 1) + "$" + t[1];
                        while (stack.size() > depth) stack.remove(stack.size() - 1);
                        stack.add(full);
                        if (t.length > 2 && !t[2].equals(t[1])) {
                            String nn = depth == 0 ? t[2] : full.substring(0, full.lastIndexOf('$') + 1) + t[2];
                            setPreset(pClass, full, nn, tag, "class");
                        }
                        break;
                    }
                    case "FIELD":
                        if (t.length > 3 && !t[1].equals(t[2])) setPreset(pField, stack.get(depth - 1) + "." + t[1], t[2], tag, "field");
                        break;
                    case "METHOD": {
                        String owner = stack.get(depth - 1);
                        String desc = t[t.length - 1];
                        lastMethod = owner + "." + t[1] + desc;
                        if (t.length > 3 && !t[1].equals(t[2])) setPreset(pMethod, lastMethod, t[2], tag, "method");
                        break;
                    }
                    case "ARG":
                        if (lastMethod != null && t.length > 2) {
                            if (!tag.equals(pTag.get("arg:" + lastMethod))) {
                                pArgs.remove(lastMethod);
                                pTag.put("arg:" + lastMethod, tag);
                            }
                            pArgs.computeIfAbsent(lastMethod, k -> new ArrayList<>()).add(line.trim());
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }


    static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_");
    static final Set<String> SHORT_REAL = new HashSet<>(Arrays.asList("id", "get", "set", "put", "add", "run", "map", "key", "log", "has", "len", "max", "min", "pop", "of", "is", "to", "eq", "or", "and", "not", "xor", "out", "in", "at", "by", "x", "y", "z", "w", "h", "r", "g", "b", "u", "v", "s", "t", "i", "j", "k", "n", "d", "e", "lo", "hi", "now", "key", "len", "end", "top", "row", "col", "cmp", "ptr", "buf", "src", "dst", "tag", "arg", "val", "red", "dir", "sum", "avg", "int", "dec", "inc", "dot", "abs", "exp", "pow", "cos", "sin", "tan", "max", "min", "mul", "div", "sub", "neg", "inv", "fix", "uid", "url", "uri", "ip", "ui"));

    Mapper(Path game) throws IOException {
        List<URL> urls = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(game.resolve("code"), "*.jar")) {
            for (Path p : ds) if (!p.getFileName().toString().contains("deobfuscated")) urls.add(p.toUri().toURL());
        }
        loader = new URLClassLoader(urls.toArray(new URL[0]), Mapper.class.getClassLoader());
        try (ZipFile z = new ZipFile(game.resolve("code/projectx-pcode.jar").toFile())) {
            for (var e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                ClassModel cm = ClassFile.of().parse(z.getInputStream(e).readAllBytes());
                cls.put(cm.thisClass().asInternalName(), cm);
            }
        }
        for (var e : cls.entrySet()) {
            ClassModel cm = e.getValue();
            String s = cm.superclass().map(c -> c.asInternalName()).orElse(null);
            sup.put(e.getKey(), s);
            List<String> il = cm.interfaces().stream().map(c -> c.asInternalName()).toList();
            ifs.put(e.getKey(), il);
            List<String> d = new ArrayList<>();
            if (s != null && cls.containsKey(s)) d.add(s);
            for (String i : il) if (cls.containsKey(i)) d.add(i);
            direct.put(e.getKey(), d);
            for (String p : d) subs.computeIfAbsent(p, k -> new HashSet<>()).add(e.getKey());
        }
    }

    // ---- what counts as obfuscated ----
    static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    static boolean obfSimple(String s) {
        return s.length() <= 3 && !s.isEmpty() && !s.chars().allMatch(Character::isDigit);
    }

    /** The last segment of a class name (after any $) is a made-up one. */
    static boolean obfClass(String internal) {
        String s = simple(internal);
        int d = s.lastIndexOf('$');
        if (d >= 0) s = s.substring(d + 1);
        return obfSimple(s);
    }

    static boolean obfMember(String name, boolean inObfClass, boolean field) {
        if (name.startsWith("<") || name.startsWith("lambda$") || name.startsWith("access$") || name.startsWith("$")) return false;
        if (name.length() > 3) return false;
        if (name.length() == 1 && !inObfClass && field && SHORT_REAL.contains(name)) return false;
        if (name.length() >= 2 && !Character.isUpperCase(name.charAt(0)) && name.chars().skip(1).allMatch(Character::isLowerCase) && SHORT_REAL.contains(name)) return false;
        return true;
    }

    // ---- helpers ----
    static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                b.append(up && b.length() > 0 ? Character.toUpperCase(c) : c);
                up = false;
            } else up = true;
        }
        return b.toString();
    }

    /** a name without the underscores in front of it */
    static String plain(String s) {
        String t = s.replaceFirst("^_+", "");
        return t.isEmpty() ? s : t;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String uncap(String s) {
        if (s.isEmpty()) return s;
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) return s; // URL, UI...
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String ident(String s) {
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) s = "n" + s;
        return KEYWORDS.contains(s) ? s + "_" : s;
    }

    String simpleNew(String internal) {
        String n = className.getOrDefault(internal, internal);
        String s = simple(n);
        return s;
    }

    /** the readable simple name of a type (the new name if it has one), for building member names. */
    String typeWord(String desc) {
        int dims = 0;
        while (desc.startsWith("[")) {
            dims++;
            desc = desc.substring(1);
        }
        String w;
        switch (desc.charAt(0)) {
            case 'I': w = "Int"; break;
            case 'J': w = "Long"; break;
            case 'F': w = "Float"; break;
            case 'D': w = "Double"; break;
            case 'Z': w = "Flag"; break;
            case 'B': w = "Byte"; break;
            case 'S': w = "Short"; break;
            case 'C': w = "Char"; break;
            case 'V': w = "Void"; break;
            default:
                String in = desc.substring(1, desc.length() - 1);
                String s = simpleNew(in);
                int d = s.lastIndexOf('$');
                if (d >= 0) s = s.substring(d + 1);
                w = ident(s);
                if (obfSimple(w) && !className.containsKey(in)) w = "Obj";
        }
        return dims > 0 ? w + "s" : w;
    }

    // ---- hierarchy ----
    Set<String> ancestors(String c) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>(direct.getOrDefault(c, List.of()));
        while (!q.isEmpty()) {
            String p = q.poll();
            if (out.add(p)) q.addAll(direct.getOrDefault(p, List.of()));
        }
        return out;
    }

    /** every method (name+desc) declared by supertypes of this class that are not in the jar: whatever they are called, they keep their name. */
    Set<String> externalMethodsOf(String c) {
        return external.computeIfAbsent(c, k -> {
            Set<String> out = new HashSet<>();
            Deque<String> q = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            String s = sup.get(c);
            if (s != null) q.add(s);
            q.addAll(ifs.getOrDefault(c, List.of()));
            while (!q.isEmpty()) {
                String p = q.poll();
                if (!seen.add(p)) continue;
                if (cls.containsKey(p)) {
                    String ps = sup.get(p);
                    if (ps != null) q.add(ps);
                    q.addAll(ifs.getOrDefault(p, List.of()));
                    continue;
                }
                try {
                    Class<?> k2 = Class.forName(p.replace('/', '.'), false, loader);
                    for (var m : k2.getDeclaredMethods()) if (!Modifier.isPrivate(m.getModifiers())) out.add(m.getName() + pd(descOf(m)));
                    if (k2.getSuperclass() != null) q.add(k2.getSuperclass().getName().replace('.', '/'));
                    for (Class<?> i : k2.getInterfaces()) q.add(i.getName().replace('.', '/'));
                } catch (Throwable t) {
                    // not loadable: assume it declares nothing we rename
                }
            }
            return out;
        });
    }

    /** the part of a method descriptor that tells overloads apart: the parameters */
    static String pd(String desc) {
        return desc.substring(0, desc.indexOf(')') + 1);
    }

    static String descOf(java.lang.reflect.Method m) {
        StringBuilder b = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) b.append(p.descriptorString());
        return b.append(")").append(m.getReturnType().descriptorString()).toString();
    }

    // ---- 1. classes ----
    final Map<String, Map<String, Integer>> cand = new HashMap<>();
    final Map<String, Map<String, String>> candWhy = new HashMap<>();

    void vote(String cls, String name, int score, String why) {
        name = ident(cap(name));
        if (name.length() < 3 || name.equals("Obj")) return;
        cand.computeIfAbsent(cls, k -> new HashMap<>()).merge(name, score, Integer::sum);
        candWhy.computeIfAbsent(cls, k -> new HashMap<>()).putIfAbsent(name, why);
    }

    /** the class an object descriptor names, if it is an obfuscated class of the jar. */
    String obfTypeOf(String desc) {
        while (desc.startsWith("[")) desc = desc.substring(1);
        if (!desc.startsWith("L")) return null;
        String in = desc.substring(1, desc.length() - 1);
        return cls.containsKey(in) && obfClass(in) ? in : null;
    }

    static String singular(String s) {
        if (s.endsWith("ies") && s.length() > 4) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 3) return s.substring(0, s.length() - 1);
        return s;
    }

    void classEvidence() {
        for (var e : cls.entrySet()) {
            ClassModel cm = e.getValue();
            String owner = e.getKey();
            boolean ownerObf = obfClass(owner);
            for (FieldModel f : cm.fields()) {
                String n = f.fieldName().stringValue();
                if (obfMember(n, ownerObf, true) || n.contains("$")) continue;
                String t = obfTypeOf(f.fieldType().stringValue());
                if (t != null && n.length() > 2) vote(t, f.fieldType().stringValue().startsWith("[") ? singular(camel(n)) : camel(n), 8, "field " + owner + "." + n);
            }
            for (MethodModel m : cm.methods()) {
                String n = m.methodName().stringValue();
                if (n.startsWith("<") || obfMember(n, ownerObf, false)) continue;
                var md = m.methodTypeSymbol();
                String ret = obfTypeOf(md.returnType().descriptorString());
                if (ret != null) {
                    String base = n;
                    for (String pre : new String[] {"get", "create", "new", "find", "make", "build"}) if (n.startsWith(pre) && n.length() > pre.length() && Character.isUpperCase(n.charAt(pre.length()))) base = n.substring(pre.length());
                    if (base.length() > 2 && !base.equals("self")) vote(ret, camel(base), 7, "method " + owner + "." + n);
                }
                if (md.parameterCount() == 1 && n.startsWith("set") && n.length() > 3) {
                    String p = obfTypeOf(md.parameterType(0).descriptorString());
                    if (p != null) vote(p, camel(n.substring(3)), 6, "setter " + owner + "." + n);
                }
            }
            // ui files and other config names this class loads
            for (PoolEntry pe : cm.constantPool()) {
                if (pe instanceof StringEntry se && obfClass(owner)) {
                    String s = se.stringValue();
                    Matcher mm = Pattern.compile("^ui/[a-z_0-9/]*?interface_([A-Za-z0-9_-]+)\\.dat$").matcher(s);
                    if (mm.matches()) vote(owner, camel(mm.group(1)) + "Ui", 6, "loads " + s);
                }
            }
        }
    }

    void nameClasses() {
        classEvidence();
        Map<String, Set<String>> used = new HashMap<>(); // package -> lower-case names in use
        // two classes may not get one name: the source wins over a personal name, and either gives way to a class that already has the name
        Map<String, String> owner = new HashMap<>();
        for (String c : cls.keySet()) if (!pClass.containsKey(c)) owner.put(c.toLowerCase(), c);
        List<String> order0 = new ArrayList<>(pClass.keySet());
        order0.sort(Comparator.comparing((String c) -> pTag.getOrDefault(c, "personal").equals("source") ? 0 : 1));
        for (String c : order0) {
            String nn = pClass.get(c).toLowerCase();
            String other = owner.get(nn);
            if (other != null && !other.equals(c)) {
                personalNotes.add("name " + pClass.get(c) + " for " + c + " (" + pTag.getOrDefault(c, "personal") + ") is taken by " + other + ": not used");
                pClass.remove(c);
            } else owner.put(nn, c);
        }
        for (var e : pClass.entrySet()) {
            if (!cls.containsKey(e.getKey())) {
                personalNotes.add("class not in the jar any more: " + e.getKey());
                continue;
            }
            className.put(e.getKey(), e.getValue());
            classWhy.put(e.getKey(), pTag.getOrDefault(e.getKey(), "personal"));
            String sn = simple(e.getValue());
            int dd = sn.lastIndexOf('$');
            used.computeIfAbsent(scopeOf(e.getKey()), k -> new HashSet<>()).add((dd >= 0 ? sn.substring(dd + 1) : sn).toLowerCase());
        }
        for (String c : cls.keySet()) {
            if (!obfClass(c)) {
                String sc = simple(c);
                int dd = sc.lastIndexOf('$');
                used.computeIfAbsent(scopeOf(c), k -> new HashSet<>()).add((dd >= 0 ? sc.substring(dd + 1) : sc).toLowerCase());
            }
        }
        // evidence-based names, outermost classes first so inner ones know their outer
        List<String> order = cls.keySet().stream().filter(Mapper::obfClass).sorted(Comparator.comparingInt((String c) -> c.length() - c.replace("$", "").length()).thenComparing(c -> c)).toList();
        Map<String, Integer> fallbackN = new HashMap<>();
        for (int pass = 0; pass < 3; pass++) {
            for (String c : order) {
                if (className.containsKey(c)) continue;
                String name = null, why = null;
                Map<String, Integer> cs = cand.get(c);
                if (cs != null) {
                    var best = cs.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).get();
                    name = best.getKey();
                    why = candWhy.get(c).get(name);
                } else if (pass >= 1) {
                    String s = sup.get(c);
                    String base = null;
                    if (s != null && !s.equals("java/lang/Object") && !s.equals("java/lang/Enum") && !s.startsWith("java/")) {
                        base = (cls.containsKey(s) ? (className.containsKey(s) ? simple(className.get(s)) : (obfClass(s) ? null : simple(s))) : simple(s));
                        if (base != null) {
                            int d = base.lastIndexOf('$');
                            if (d >= 0) base = base.substring(d + 1);
                        }
                        if (base != null) {
                            name = stem(base) + "Impl";
                            why = "extends " + s;
                        }
                    }
                    if (name == null) {
                        for (String i : ifs.getOrDefault(c, List.of())) {
                            String b = cls.containsKey(i) && obfClass(i) ? (className.containsKey(i) ? simple(className.get(i)) : null) : simple(i);
                            if (b != null && !b.endsWith("Codes") && !b.equals("Streamable") && !i.startsWith("java/") && !i.equals("java/lang/Comparable") && !i.equals("java/lang/Runnable")) {
                                int d = b.lastIndexOf('$');
                                if (d >= 0) b = b.substring(d + 1);
                                name = stem(b) + "Impl";
                                why = "implements " + i;
                                break;
                            }
                        }
                    }
                }
                if (name == null) continue;
                assign(c, ident(name), why, used);
            }
        }
        // the rest
        for (String c : order) {
            if (className.containsKey(c)) continue;
            String p = pkg(c);
            String last = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
            String base = simple(c).contains("$") ? "Inner" : cap(last) + "Type";
            int n = fallbackN.merge(p + "/" + base, 1, Integer::sum);
            assign(c, base + n, "no evidence", used);
        }
    }

    static String stem(String b) {
        String o;
        do {
            o = b;
            b = b.replaceAll("(Impl|Sub)[0-9]*$", "");
        } while (!b.equals(o) && !b.isEmpty());
        return b.isEmpty() ? o : b;
    }

    static String pkg(String c) {
        int i = c.lastIndexOf('/');
        return i < 0 ? "" : c.substring(0, i);
    }

    static String scopeOf(String c) {
        String s = simple(c);
        int d = s.lastIndexOf('$');
        return d >= 0 ? pkg(c) + "/" + s.substring(0, d) : pkg(c);
    }

    void assign(String c, String name, String why, Map<String, Set<String>> used) {
        Set<String> u = used.computeIfAbsent(scopeOf(c), k -> new HashSet<>());
        String final0 = name;
        int n = 2;
        while (u.contains(final0.toLowerCase())) final0 = name + n++;
        u.add(final0.toLowerCase());
        String s = simple(c);
        int d = s.lastIndexOf('$');
        className.put(c, d >= 0 ? c.substring(0, c.lastIndexOf('$') + 1) + final0 : (pkg(c).isEmpty() ? final0 : pkg(c) + "/" + final0));
        classWhy.put(c, why);
    }

    // ---- 2. fields ----
    void nameFields() {
        for (var e : cls.entrySet()) {
            String owner = e.getKey();
            ClassModel cm = e.getValue();
            boolean ownerObf = obfClass(owner);
            Map<String, String> found = new HashMap<>(); // field -> name, from the code
            Map<String, String> foundWhy = new HashMap<>();
            for (MethodModel m : cm.methods()) {
                CodeAttribute code = m.findAttribute(Attributes.code()).orElse(null);
                if (code == null) continue;
                String mn = m.methodName().stringValue();
                List<CodeElement> els = code.elementList().stream().filter(x -> x instanceof Instruction).toList();
                String lastTag = null;
                String lastGetterName = null;
                for (int i = 0; i < els.size(); i++) {
                    CodeElement el = els.get(i);
                    if (el instanceof ConstantInstruction.LoadConstantInstruction lc && lc.constantEntry() instanceof StringEntry se) lastTag = se.stringValue();
                    if (el instanceof InvokeInstruction inv) {
                        String in = inv.name().stringValue();
                        if (in.equals("getComponent") || in.equals("setVisible") || in.equals("setText") || in.equals("setEnabled")) {
                            // tag use: a getComponent("tag") whose result is stored
                        } else if (!obfMember(in, false, false) && (in.startsWith("get") || in.startsWith("is") || in.startsWith("create") || in.startsWith("new")) && in.length() > 3) {
                            lastGetterName = in;
                        } else if (!inv.name().stringValue().equals("<init>")) lastGetterName = lastGetterName;
                        if (in.equals("getComponent") && lastTag != null) {
                            // look ahead for checkcast / putfield on this class
                            for (int j = i + 1; j < Math.min(els.size(), i + 4); j++) {
                                if (els.get(j) instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD && fi.owner().asInternalName().equals(owner)) {
                                    String fn = fi.name().stringValue();
                                    if (obfMember(fn, ownerObf, true)) {
                                        String t = typeWord(fi.type().stringValue());
                                        String base = uncap(camel(lastTag));
                                        found.putIfAbsent(fn, ident(base + (t.equals("Obj") || base.toLowerCase().endsWith(t.toLowerCase()) ? "" : t)));
                                        foundWhy.putIfAbsent(fn, "stored from getComponent(\"" + lastTag + "\")");
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    if (el instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD && fi.owner().asInternalName().equals(owner) && lastGetterName != null && i > 0 && els.get(i - 1) instanceof InvokeInstruction prev && prev.name().stringValue().equals(lastGetterName)) {
                        String fn = fi.name().stringValue();
                        if (obfMember(fn, ownerObf, true) && !found.containsKey(fn)) {
                            String base = lastGetterName.replaceFirst("^(get|is|create|new)", "");
                            if (base.length() > 1) {
                                found.put(fn, ident(uncap(base)));
                                foundWhy.put(fn, "assigned from " + lastGetterName + "()");
                            }
                        }
                    }
                    // getter of a named method: name() { return this.f }
                    if (!obfMember(mn, ownerObf, false) && !mn.startsWith("<") && els.size() <= 4 && el instanceof FieldInstruction fi && fi.opcode() == Opcode.GETFIELD && fi.owner().asInternalName().equals(owner)) {
                        String fn = fi.name().stringValue();
                        if (obfMember(fn, ownerObf, true) && !m.methodTypeSymbol().returnType().descriptorString().equals("V") && m.methodTypeSymbol().parameterCount() == 0) {
                            String base = mn.replaceFirst("^(get|is|has)", "");
                            if (base.length() > 1) {
                                found.putIfAbsent(fn, ident(uncap(base)));
                                foundWhy.putIfAbsent(fn, "returned by " + mn + "()");
                            }
                        }
                    }
                }
            }
            Set<String> taken = new HashSet<>();
            for (FieldModel f : cm.fields()) if (!obfMember(f.fieldName().stringValue(), ownerObf, true)) taken.add(f.fieldName().stringValue());
            for (FieldModel f : cm.fields()) {
                String pf = pField.get(owner + "." + f.fieldName().stringValue());
                if (pf != null) {
                    taken.add(pf);
                    fieldName.put(owner + "." + f.fieldName().stringValue(), pf);
                    fieldWhy.put(owner + "." + f.fieldName().stringValue(), pTag.getOrDefault(owner + "." + f.fieldName().stringValue(), "personal"));
                }
            }
            for (FieldModel f : cm.fields()) {
                String fn = f.fieldName().stringValue();
                if (fieldName.containsKey(owner + "." + fn)) continue;
                if (!obfMember(fn, ownerObf, true)) continue;
                String name = found.get(fn), why = foundWhy.get(fn);
                if (name == null) {
                    // a constant's own text
                    var cv = f.findAttribute(Attributes.constantValue()).orElse(null);
                    if (cv != null && cv.constant() instanceof StringEntry se && se.stringValue().length() > 1 && se.stringValue().length() < 30 && Pattern.matches("[A-Za-z0-9_ .:/-]+", se.stringValue())) {
                        name = ident(camel(se.stringValue()).toUpperCase().isEmpty() ? "VALUE" : se.stringValue().replaceAll("[^A-Za-z0-9]+", "_").toUpperCase());
                        why = "constant text";
                    } else {
                        String d = f.fieldType().stringValue();
                        boolean list = f.findAttribute(Attributes.signature()).isPresent();
                        name = uncap(typeWord(d));
                        if (list && !d.startsWith("[")) {
                            String sg = f.findAttribute(Attributes.signature()).get().signature().stringValue();
                            Matcher gm = Pattern.compile("<L([^;<]+)").matcher(sg);
                            String elem = null;
                            Matcher all = Pattern.compile("L([^;<]+)[;<]").matcher(sg);
                            List<String> ts = new ArrayList<>();
                            while (all.find()) ts.add(all.group(1));
                            if (!ts.isEmpty()) {
                                String t0 = ts.get(0);
                                String base = simple(className.getOrDefault(t0, t0));
                                int dd = base.lastIndexOf('$');
                                if (dd >= 0) base = base.substring(dd + 1);
                                if (t0.equals("java/util/List") || t0.equals("java/util/Set") || t0.equals("java/util/Collection") || t0.equals("java/lang/Iterable")) {
                                    if (ts.size() > 1) {
                                        String el = simple(className.getOrDefault(ts.get(1), ts.get(1)));
                                        int d2 = el.lastIndexOf('$');
                                        if (d2 >= 0) el = el.substring(d2 + 1);
                                        name = uncap(ident(el)) + "s";
                                    }
                                } else if (t0.equals("java/util/Map") && ts.size() > 2) {
                                    String k = simple(className.getOrDefault(ts.get(1), ts.get(1))), v = simple(className.getOrDefault(ts.get(2), ts.get(2)));
                                    name = uncap(ident(v)) + "By" + ident(k);
                                }
                            }
                        }
                        why = "type";
                        if (name.equals("obj") || name.length() < 2) name = (name.equals("flag") ? "flag" : "value");
                        if (f.fieldType().stringValue().length() == 1) name += "Value";
                    }
                }
                name = ident(name);
                if (name.length() > 40) name = name.substring(0, 40);
                String fin = name;
                int n = 2;
                while (taken.contains(fin)) fin = name + n++;
                taken.add(fin);
                fieldName.put(owner + "." + fn, fin);
                fieldWhy.put(owner + "." + fn, why);
            }
        }
    }

    // ---- 3. methods ----
    final Map<String, String> uf = new HashMap<>();

    String find(String x) {
        uf.putIfAbsent(x, x);
        String r = uf.get(x);
        if (!r.equals(x)) {
            r = find(r);
            uf.put(x, r);
        }
        return r;
    }

    void union(String a, String b) {
        uf.put(find(a), find(b));
    }

    void nameMethods() {
        // nodes: class|name|desc for every non-private, non-static, non-constructor method
        Map<String, List<String>> groupMembers = new HashMap<>();
        for (var e : cls.entrySet()) {
            for (MethodModel m : e.getValue().methods()) {
                String n = m.methodName().stringValue();
                if (n.startsWith("<")) continue;
                find(e.getKey() + "|" + n + "|" + m.methodType().stringValue());
            }
        }
        for (String c : cls.keySet()) {
            Map<String, List<String>> byKey = new HashMap<>();
            List<String> chain = new ArrayList<>();
            chain.add(c);
            chain.addAll(ancestors(c));
            for (String k : chain) {
                for (MethodModel m : cls.get(k).methods()) {
                    int fl = m.flags().flagsMask();
                    if ((fl & ClassFile.ACC_PRIVATE) != 0 || m.methodName().stringValue().startsWith("<") || m.methodName().stringValue().startsWith("lambda$") || m.methodName().stringValue().startsWith("access$")) continue;
                    if ((fl & ClassFile.ACC_STATIC) != 0 && !k.equals(c)) continue;
                    byKey.computeIfAbsent(m.methodName().stringValue() + m.methodType().stringValue(), x -> new ArrayList<>()).add(k + "|" + m.methodName().stringValue() + "|" + m.methodType().stringValue());
                }
            }
            for (var l : byKey.values()) for (int i = 1; i < l.size(); i++) union(l.get(0), l.get(i));
        }
        for (var e : cls.entrySet()) {
            for (MethodModel m : e.getValue().methods()) {
                if ((m.flags().flagsMask() & (ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC)) == 0 || m.methodName().stringValue().startsWith("<") || m.methodName().stringValue().startsWith("lambda$") || m.methodName().stringValue().startsWith("access$")) continue;
                CodeAttribute code = m.findAttribute(Attributes.code()).orElse(null);
                if (code == null) continue;
                for (CodeElement ce : code.elementList()) {
                    if (ce instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(e.getKey()) && !inv.name().stringValue().startsWith("<")) {
                        String target = e.getKey() + "|" + inv.name().stringValue() + "|" + inv.type().stringValue();
                        if (uf.containsKey(target)) union(e.getKey() + "|" + m.methodName().stringValue() + "|" + m.methodType().stringValue(), target);
                        break;
                    }
                }
            }
        }
        Map<String, List<String>> groups = new TreeMap<>();
        for (String node : new ArrayList<>(uf.keySet())) groups.computeIfAbsent(find(node), k -> new ArrayList<>()).add(node);

        // names used in a class (to keep each (name,desc) unique per class)
        Map<String, Set<String>> takenIn = new HashMap<>();
        for (var e : cls.entrySet()) {
            Set<String> t = takenIn.computeIfAbsent(e.getKey(), k -> new HashSet<>());
            for (MethodModel m : e.getValue().methods()) {
                String n = m.methodName().stringValue();
                if (!obfMember(n, obfClass(e.getKey()), false)) t.add(n + pd(m.methodType().stringValue()));
            }
        }
        // a bridge method with a name of its own (clone() calling the real clone, which is called Vt) gives that name to its target: Enigma links them
        Map<String, String> bridgeFixed = new HashMap<>();
        for (var e : cls.entrySet()) {
            for (MethodModel m : e.getValue().methods()) {
                String bn = m.methodName().stringValue();
                if ((m.flags().flagsMask() & (ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC)) == 0 || bn.startsWith("<") || bn.startsWith("lambda$") || bn.startsWith("access$") || obfMember(bn, obfClass(e.getKey()), false)) continue;
                CodeAttribute code = m.findAttribute(Attributes.code()).orElse(null);
                if (code == null) continue;
                for (CodeElement ce : code.elementList()) {
                    if (ce instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(e.getKey()) && !inv.name().stringValue().equals(bn + "$")) {
                        String tn = inv.name().stringValue();
                        if (obfMember(tn, obfClass(e.getKey()), false)) bridgeFixed.put(e.getKey() + "|" + tn + "|" + inv.type().stringValue(), bn);
                        break;
                    }
                }
            }
        }
        Set<String> done = new HashSet<>();
        for (var g : groups.values()) {
            String fixed = null, fixedTag = null;
            for (String node : g) {
                String[] p = node.split("\\|");
                String pk = p[0] + "." + p[1] + p[2];
                String pn = pMethod.get(pk);
                String tg = pTag.getOrDefault(pk, "personal");
                if (bridgeFixed.containsKey(node)) {
                    pn = bridgeFixed.get(node);
                    tg = "bridge";
                }
                if (pn == null) continue;
                if (fixed == null || (tg.equals("source") && !fixedTag.equals("source"))) {
                    if (fixed != null && !fixed.equals(pn)) personalNotes.add("two names in one group of overriding methods: " + fixed + " (" + fixedTag + ") / " + pn + " (" + tg + "): kept " + pn);
                    fixed = pn;
                    fixedTag = tg;
                } else if (!pn.equals(fixed)) personalNotes.add("two names in one group of overriding methods: " + fixed + " (" + fixedTag + ") / " + pn + " (" + tg + "): kept " + fixed);
            }
            if (fixed == null) continue;
            boolean outside = false;
            for (String node : g) {
                String[] p = node.split("\\|");
                if (externalMethodsOf(p[0]).contains(p[1] + pd(p[2]))) outside = true;
            }
            if (outside && !"bridge".equals(fixedTag)) {
                personalNotes.add("not applied, it overrides something outside the jar: " + g.get(0) + " -> " + fixed);
                done.add(g.get(0));
                continue;
            }
            for (String node : g) {
                String[] p = node.split("\\|");
                takenIn.get(p[0]).add(fixed + pd(p[2]));
                methodName.put(p[0] + "." + p[1] + p[2], fixed);
                methodWhy.put(p[0] + "." + p[1] + p[2], fixedTag);
            }
            done.add(g.get(0));
        }
        for (var g : groups.values()) {
            if (done.contains(g.get(0))) continue;
            // all members must be obfuscated and none may override an outside method
            boolean ok = true;
            for (String node : g) {
                String[] p = node.split("\\|");
                if (!obfMember(p[1], obfClass(p[0]), false)) {
                    ok = false;
                    break;
                }
                if (externalMethodsOf(p[0]).contains(p[1] + pd(p[2]))) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            String name = null, why = null;
            // a body that tells what it is
            for (String node : g) {
                String[] p = node.split("\\|");
                String[] r = describe(p[0], p[1], p[2]);
                if (r != null) {
                    name = r[0];
                    why = r[1];
                    break;
                }
            }
            if (name == null) {
                String[] p = g.get(0).split("\\|");
                var md = java.lang.constant.MethodTypeDesc.ofDescriptor(p[2]);
                String ret = md.returnType().descriptorString();
                String firstParam = md.parameterCount() > 0 ? typeWord(md.parameterType(0).descriptorString()) : null;
                switch (ret.charAt(0)) {
                    case 'V':
                        name = firstParam == null ? "update" : (md.parameterCount() == 1 ? "handle" + cap(firstParam) : "apply" + cap(firstParam));
                        break;
                    case 'Z':
                        name = firstParam == null ? "isActive" : "accepts" + cap(firstParam);
                        break;
                    default:
                        String rw = typeWord(ret);
                        name = firstParam == null ? "get" + cap(rw) : "compute" + cap(rw) + "From" + cap(firstParam);
                }
                why = "signature";
            }
            name = ident(name);
            if (name.length() > 48) name = name.substring(0, 48);
            // unique in every member class
            String fin = name;
            int n = 2;
            boolean clash;
            do {
                clash = false;
                for (String node : g) {
                    String[] p = node.split("\\|");
                    if (takenIn.get(p[0]).contains(fin + pd(p[2])) || externalMethodsOf(p[0]).contains(fin + pd(p[2]))) clash = true;
                    for (String s : allSubs(p[0])) if (externalMethodsOf(s).contains(fin + pd(p[2]))) clash = true;
                    // also any class in the subtree may inherit names: check subtypes' members
                    for (String s : allSubs(p[0])) if (takenIn.getOrDefault(s, Set.of()).contains(fin + pd(p[2]))) clash = true;
                    for (String s : ancestors(p[0])) if (takenIn.getOrDefault(s, Set.of()).contains(fin + pd(p[2])) && !g.contains(s + "|" + p[1] + "|" + p[2])) clash = true;
                }
                if (clash) fin = name + n++;
            } while (clash);
            for (String node : g) {
                String[] p = node.split("\\|");
                takenIn.get(p[0]).add(fin + pd(p[2]));
                methodName.put(p[0] + "." + p[1] + p[2], fin);
                methodWhy.put(p[0] + "." + p[1] + p[2], why);
            }
        }
    }

    boolean isBridge(String node) {
        String[] p = node.split("\\|");
        ClassModel cm = cls.get(p[0]);
        if (cm == null) return false;
        for (MethodModel m : cm.methods()) {
            if (m.methodName().stringValue().equals(p[1]) && m.methodType().stringValue().equals(p[2])) return (m.flags().flagsMask() & (ClassFile.ACC_BRIDGE)) != 0;
        }
        return false;
    }

    Set<String> allSubs(String c) {
        Set<String> out = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(subs.getOrDefault(c, Set.of()));
        while (!q.isEmpty()) {
            String s = q.poll();
            if (out.add(s)) q.addAll(subs.getOrDefault(s, Set.of()));
        }
        return out;
    }

    /** names a method from what its body does, when the body is one of a few simple shapes. */
    String[] describe(String owner, String name, String desc) {
        ClassModel cm = cls.get(owner);
        MethodModel mm = cm.methods().stream().filter(m -> m.methodName().stringValue().equals(name) && m.methodType().stringValue().equals(desc)).findFirst().orElse(null);
        if (mm == null) return null;
        CodeAttribute code = mm.findAttribute(Attributes.code()).orElse(null);
        if (code == null) return null;
        List<Instruction> ins = code.elementList().stream().filter(x -> x instanceof Instruction).map(x -> (Instruction) x).toList();
        var md = java.lang.constant.MethodTypeDesc.ofDescriptor(desc);
        boolean stat = (mm.flags().flagsMask() & ClassFile.ACC_STATIC) != 0;
        // getter: aload_0 getfield xreturn
        if (!stat && ins.size() == 3 && ins.get(0).opcode() == Opcode.ALOAD_0 && ins.get(1) instanceof FieldInstruction fi && fi.opcode() == Opcode.GETFIELD && fi.owner().asInternalName().equals(owner) && md.parameterCount() == 0) {
            String f = fieldName.getOrDefault(owner + "." + fi.name().stringValue(), fi.name().stringValue());
            String r = md.returnType().descriptorString();
            return new String[] {(r.equals("Z") ? "is" : "get") + cap(plain(f)), "returns field " + f};
        }
        // setter: aload_0 load putfield return
        if (!stat && ins.size() == 4 && ins.get(0).opcode() == Opcode.ALOAD_0 && ins.get(2) instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD && fi.owner().asInternalName().equals(owner) && md.parameterCount() == 1 && md.returnType().descriptorString().equals("V")) {
            String f = fieldName.getOrDefault(owner + "." + fi.name().stringValue(), fi.name().stringValue());
            return new String[] {"set" + cap(plain(f)), "sets field " + f};
        }
        // a factory: new X, dup, args, invokespecial, areturn
        for (Instruction i : ins) {
            if (i instanceof NewObjectInstruction no && ins.get(ins.size() - 1).opcode() == Opcode.ARETURN && ins.size() < 16) {
                String t = no.className().asInternalName();
                String w = simple(className.getOrDefault(t, t));
                int d = w.lastIndexOf('$');
                if (d >= 0) w = w.substring(d + 1);
                if (!obfSimple(w)) return new String[] {"create" + cap(ident(w)), "creates " + t};
            }
        }
        // a method that names a tag ("health_ui"): what it does with it, and the tag
        for (Instruction i : ins) {
            if (i instanceof ConstantInstruction.LoadConstantInstruction lc && lc.constantEntry() instanceof StringEntry se && se.stringValue().matches("[a-z][a-z0-9_]{3,30}") && se.stringValue().contains("_") || (i instanceof ConstantInstruction.LoadConstantInstruction lc2 && lc2.constantEntry() instanceof StringEntry se2 && se2.stringValue().matches("[a-z]{4,20}") && ins.size() < 60)) {
                String tag = ((StringEntry) ((ConstantInstruction.LoadConstantInstruction) i).constantEntry()).stringValue();
                String r = md.returnType().descriptorString();
                String verb = r.equals("V") ? "update" : r.equals("Z") ? "is" : "get";
                return new String[] {verb + cap(camel(tag)), "uses the text \"" + tag + "\""};
            }
        }
        // a single call to something named, then return: named like it (a wrapper)
        List<InvokeInstruction> calls = ins.stream().filter(x -> x instanceof InvokeInstruction).map(x -> (InvokeInstruction) x).toList();
        if (calls.size() == 1 && ins.size() <= 10) {
            InvokeInstruction c = calls.get(0);
            String cn = c.name().stringValue();
            String co = c.owner().asInternalName();
            boolean named = !cn.startsWith("<") && (!cls.containsKey(co) || !obfMember(cn, obfClass(co), false)) && !cn.equals("valueOf") && !cn.equals("intValue") && !cn.equals("booleanValue") && !cn.equals("floatValue");
            if (named && cn.length() > 3) {
                String key = cn.startsWith("get") || cn.startsWith("set") || cn.startsWith("is") ? cn : cn;
                return new String[] {"call" + cap(key), "wraps " + simple(co) + "." + cn};
            }
        }
        return null;
    }

    // ---- output ----
    void write(Path out, Path report) throws IOException {
        Map<String, List<String>> inners = new TreeMap<>();
        for (String c : cls.keySet()) {
            int d = c.lastIndexOf('$');
            if (d >= 0) inners.computeIfAbsent(c.substring(0, d), k -> new ArrayList<>()).add(c);
        }
        StringBuilder sb = new StringBuilder();
        for (String c : cls.keySet()) {
            if (c.contains("$") && cls.containsKey(c.substring(0, c.lastIndexOf('$')))) continue;
            emit(sb, c, 0, inners);
        }
        Files.writeString(out, sb.toString());
        // report
        Map<String, Long> cw = classWhy.values().stream().map(Mapper::kind).collect(Collectors.groupingBy(x -> x, TreeMap::new, Collectors.counting()));
        Map<String, Long> fw = fieldWhy.values().stream().map(Mapper::kind).collect(Collectors.groupingBy(x -> x, TreeMap::new, Collectors.counting()));
        Map<String, Long> mw = methodWhy.values().stream().map(Mapper::kind).collect(Collectors.groupingBy(x -> x, TreeMap::new, Collectors.counting()));
        StringBuilder r = new StringBuilder("# Mapping report\n\n");
        r.append("Classes named: ").append(className.size()).append("\n\nFields named: ").append(fieldName.size()).append("\n\nMethods named: ").append(methodName.size()).append("\n\n");
        if (personalFiles > 0) {
            long pc = classWhy.values().stream().filter(x -> x.equals("personal")).count(), pf = fieldWhy.values().stream().filter(x -> x.equals("personal")).count(), pm = methodWhy.values().stream().filter(x -> x.equals("personal")).count();
            long sc = classWhy.values().stream().filter(x -> x.equals("source")).count(), sf = fieldWhy.values().stream().filter(x -> x.equals("source")).count(), sm = methodWhy.values().stream().filter(x -> x.equals("source")).count();
            r.append("From the open-source libraries (samskivert, Clyde, Narya, Nenya; they win over personal names): ").append(sc).append(" classes, ").append(sf).append(" fields, ").append(sm).append(" methods.\n\n");
            r.append("Where a personal name and the source disagreed: ").append(conflicts.size()).append(" (listed in conflicts.md)\n\n");
            r.append("Personal mappings merged: ").append(personalFiles).append(" files; ").append(pc).append(" classes, ").append(pf).append(" fields, ").append(pm).append(" methods, ").append(pArgs.size()).append(" methods with parameter names (they win over every other name).\n\n");
            int missingF = 0, missingM = 0;
            for (String k : pField.keySet()) if (!fieldName.containsKey(k)) missingF++;
            for (String k : pMethod.keySet()) if (!methodName.containsKey(k)) missingM++;
            r.append("Not found in the jar (the game changed, or it is another jar): ").append(missingF).append(" fields, ").append(missingM).append(" methods.\n\n");
            for (String n : personalNotes) r.append("* ").append(n).append("\n");
            r.append("\n");
        }
        r.append("## By kind of evidence\n\n### Classes\n").append(table(cw)).append("\n### Fields\n").append(table(fw)).append("\n### Methods\n").append(table(mw));
        Files.writeString(report, r.toString());
        StringBuilder cf = new StringBuilder("# Where the personal names and the open-source names differ\n\nThe source name was used.\n\n| kind | entry | personal | source |\n|---|---|---|---|\n");
        for (String c : conflicts) {
            String[] x = c.split(" \\| ");
            if (x.length == 4) cf.append("| ").append(x[0]).append(" | ").append(x[1]).append(" | ").append(x[2]).append(" | ").append(x[3]).append(" |\n");
        }
        Files.writeString(report.resolveSibling("conflicts.md"), cf.toString());
    }

    static String kind(String why) {
        if (why == null) return "none";
        for (String k : new String[] {"field ", "method ", "setter ", "loads ", "extends ", "implements ", "no evidence", "stored from", "assigned from", "returned by", "constant text", "type", "returns field", "sets field", "creates ", "wraps ", "signature", "uses the text"}) if (why.startsWith(k)) return k.trim();
        return why;
    }

    static String table(Map<String, Long> m) {
        StringBuilder b = new StringBuilder("| evidence | count |\n|---|---|\n");
        m.forEach((k, v) -> b.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        return b.toString();
    }

    void emit(StringBuilder sb, String c, int depth, Map<String, List<String>> inners) {
        String tabs = "\t".repeat(depth);
        String nn = className.get(c);
        sb.append(tabs).append("CLASS ").append(depth == 0 ? c : c.substring(c.lastIndexOf('$') + 1));
        if (nn != null) {
            String shown = depth == 0 ? nn : nn.substring(nn.lastIndexOf('$') + 1);
            if (depth > 0) {
                String outer = c.substring(0, c.lastIndexOf('$'));
                String on = className.getOrDefault(outer, outer);
                on = on.substring(Math.max(on.lastIndexOf('/'), on.lastIndexOf('$')) + 1);
                if (shown.equalsIgnoreCase(on)) shown = shown + "Inner";
            }
            sb.append(' ').append(shown);
        }
        sb.append('\n');
        ClassModel cm = cls.get(c);
        for (FieldModel f : cm.fields()) {
            String n = fieldName.get(c + "." + f.fieldName().stringValue());
            if (n != null) sb.append(tabs).append("\tFIELD ").append(f.fieldName().stringValue()).append(' ').append(n).append(' ').append(f.fieldType().stringValue()).append('\n');
        }
        for (MethodModel m : cm.methods()) {
            String key = c + "." + m.methodName().stringValue() + m.methodType().stringValue();
            String n = methodName.get(key);
            List<String> args = pArgs.get(key);
            if (n != null) sb.append(tabs).append("\tMETHOD ").append(m.methodName().stringValue()).append(' ').append(n).append(' ').append(m.methodType().stringValue()).append('\n');
            else if (args != null) sb.append(tabs).append("\tMETHOD ").append(m.methodName().stringValue()).append(' ').append(m.methodType().stringValue()).append('\n');
            if (args != null) for (String ag : args) sb.append(tabs).append("\t\t").append(ag).append('\n');
        }
        for (String in : inners.getOrDefault(c, List.of())) emit(sb, in, depth + 1, inners);
    }

    public static void main(String[] a) throws Exception {
        Mapper m = new Mapper(Path.of(a[0]));
        Map<String, Path> layers = new LinkedHashMap<>();
        for (int i = 3; i + 1 < a.length; i += 2) {
            if (!a[i].startsWith("--")) throw new IllegalArgumentException("expected --carry, --personal or --source, found " + a[i]);
            layers.put(a[i].substring(2), Path.of(a[i + 1]));
        }
        for (String tag : List.of("carry", "personal", "source")) if (layers.containsKey(tag)) m.load(layers.get(tag), tag.equals("carry") ? "carried" : tag);
        m.nameClasses();
        System.out.println("classes " + m.className.size());
        m.nameFields();
        System.out.println("fields " + m.fieldName.size());
        m.nameMethods();
        System.out.println("methods " + m.methodName.size());
        m.write(Path.of(a[1]), Path.of(a[2]));
    }
}
