package dev.skmods.build;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/**
 * Rewrites source code from the game's obfuscated names to the readable names of the mapping (the one-time move of this project's code), using the
 * compiler's own resolution, so a name is changed where it really refers to a class, field or method of the game and nowhere else.
 *
 * <pre>
 *   java SourceRemap.java MAPPING CLASSPATH SRCROOT...
 * </pre>
 *
 * Files are rewritten in place; a top-level class that is renamed gets its file renamed too. Strings are not changed: those that look like a
 * name of the game's are listed for a look.
 */
public final class SourceRemap {
    record Edit(int start, int end, String text) {
    }

    final Mapping map;
    JavacTask task;
    Trees trees;
    Elements elements;
    Types types;
    final Map<Element, String> cache = new HashMap<>();
    final Set<String> obfMemberNames = new HashSet<>();
    int renamedFiles, edits;
    final List<String> notes = new ArrayList<>();

    SourceRemap(Mapping map) {
        this.map = map;
        for (String k : map.fields.keySet()) obfMemberNames.add(k.substring(k.indexOf('.') + 1));
        for (String k : map.methods.keySet()) obfMemberNames.add(k.substring(k.indexOf('.') + 1, k.indexOf('(')));
    }

    String internal(TypeElement te) {
        return elements.getBinaryName(te).toString().replace('.', '/');
    }

    String desc(TypeMirror t) {
        t = types.erasure(t);
        switch (t.getKind()) {
            case BOOLEAN: return "Z";
            case BYTE: return "B";
            case SHORT: return "S";
            case INT: return "I";
            case LONG: return "J";
            case CHAR: return "C";
            case FLOAT: return "F";
            case DOUBLE: return "D";
            case VOID: return "V";
            case ARRAY: return "[" + desc(((ArrayType) t).getComponentType());
            case DECLARED: return "L" + internal((TypeElement) ((DeclaredType) t).asElement()) + ";";
            default: return "Ljava/lang/Object;";
        }
    }

    String methodDesc(ExecutableElement m) {
        StringBuilder b = new StringBuilder("(");
        for (VariableElement p : m.getParameters()) b.append(desc(p.asType()));
        return b.append(")").append(desc(m.getReturnType())).toString();
    }

    /** the readable simple name of a class, or null if it keeps its name */
    String newType(TypeElement te) {
        String nn = map.classToDeobf.get(internal(te));
        if (nn == null) return null;
        return nn.substring(Math.max(nn.lastIndexOf('/'), nn.lastIndexOf('$')) + 1);
    }

    String newMember(Element el) {
        if (cache.containsKey(el)) return cache.get(el);
        cache.put(el, null);
        String r = null;
        if (el instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
            TypeElement owner = (TypeElement) m.getEnclosingElement();
            r = map.methods.get(internal(owner) + "." + m.getSimpleName() + methodDesc(m));
            if (r == null && !m.getModifiers().contains(Modifier.STATIC) && !m.getModifiers().contains(Modifier.PRIVATE)) {
                // a method that overrides one of the game's takes its name
                for (TypeMirror sup : allSupertypes(owner.asType(), new HashSet<>())) {
                    TypeElement st = (TypeElement) ((DeclaredType) sup).asElement();
                    for (Element e2 : st.getEnclosedElements()) {
                        if (e2.getKind() == ElementKind.METHOD && e2.getSimpleName().equals(m.getSimpleName()) && elements.overrides(m, (ExecutableElement) e2, owner)) {
                            String n2 = newMember(e2);
                            if (n2 != null) r = n2;
                        }
                    }
                    if (r != null) break;
                }
            }
        } else if (el instanceof VariableElement v && (v.getKind() == ElementKind.FIELD || v.getKind() == ElementKind.ENUM_CONSTANT)) {
            TypeElement owner = (TypeElement) v.getEnclosingElement();
            r = map.fields.get(internal(owner) + "." + v.getSimpleName());
        }
        cache.put(el, r);
        return r;
    }

    List<TypeMirror> allSupertypes(TypeMirror t, Set<String> seen) {
        List<TypeMirror> out = new ArrayList<>();
        for (TypeMirror s : types.directSupertypes(t)) {
            if (s.getKind() != TypeKind.DECLARED) continue;
            String n = ((TypeElement) ((DeclaredType) s).asElement()).getQualifiedName().toString();
            if (!seen.add(n)) continue;
            out.add(s);
            out.addAll(allSupertypes(s, seen));
        }
        return out;
    }

    void run(List<Path> roots, String classpath) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path r : roots) {
            try (Stream<Path> w = Files.walk(r)) {
                w.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        task = (JavacTask) jc.getTask(null, fm, d -> {
        }, List.of("-proc:none", "-Xlint:none", "-classpath", classpath), null, fm.getJavaFileObjectsFromPaths(files));
        Iterable<? extends CompilationUnitTree> units = task.parse();
        task.analyze();
        trees = Trees.instance(task);
        elements = task.getElements();
        types = task.getTypes();
        for (CompilationUnitTree cu : units) process(cu);
        System.out.println("rewrote " + edits + " names in " + files.size() + " files; renamed " + renamedFiles + " files");
        for (String n : notes) System.out.println("note: " + n);
    }

    void process(CompilationUnitTree cu) throws IOException {
        Path file = Path.of(cu.getSourceFile().toUri());
        String text = cu.getSourceFile().getCharContent(true).toString();
        SourcePositions sp = trees.getSourcePositions();
        List<Edit> es = new ArrayList<>();
        Map<String, TypeElement> usedTypes = new LinkedHashMap<>();
        new TreePathScanner<Void, Void>() {
            void rename(int start, int end, String old, String nw) {
                if (start < 0 || end > text.length() || start >= end) return;
                if (!text.substring(start, end).equals(old)) return;
                es.add(new Edit(start, end, nw));
            }

            Element el(TreePath p) {
                try {
                    return trees.getElement(p);
                } catch (RuntimeException e) {
                    return null;
                }
            }

            void ref(Tree t, Name name, int end) {
                Element e = el(getCurrentPath());
                if (e == null) return;
                if (name.contentEquals("super") || name.contentEquals("this")) return;
                if (e instanceof ExecutableElement ee && ee.getKind() == ElementKind.CONSTRUCTOR) e = ee.getEnclosingElement();
                String nn = null;
                if (e instanceof TypeElement te) {
                    nn = newType(te);
                    if (nn != null) usedTypes.put(te.getQualifiedName().toString(), te);
                } else nn = newMember(e);
                if (nn != null) rename(end - name.length(), end, name.toString(), nn);
            }

            @Override
            public Void visitIdentifier(IdentifierTree t, Void v) {
                int end = (int) sp.getEndPosition(cu, t);
                ref(t, t.getName(), end);
                return super.visitIdentifier(t, v);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void v) {
                int end = (int) sp.getEndPosition(cu, t);
                ref(t, t.getIdentifier(), end);
                return super.visitMemberSelect(t, v);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree t, Void v) {
                int end = (int) sp.getEndPosition(cu, t);
                ref(t, t.getName(), end);
                return super.visitMemberReference(t, v);
            }

            @Override
            public Void visitClass(ClassTree t, Void v) {
                Element e = el(getCurrentPath());
                if (e instanceof TypeElement te) {
                    String nn = newType(te);
                    if (nn != null) {
                        int s = (int) sp.getStartPosition(cu, t);
                        Matcher m = Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+(" + Pattern.quote(t.getSimpleName().toString()) + ")\\b").matcher(text);
                        if (s >= 0 && m.find(s)) es.add(new Edit(m.start(2), m.end(2), nn));
                    }
                }
                return super.visitClass(t, v);
            }

            @Override
            public Void visitMethod(MethodTree t, Void v) {
                Element e = el(getCurrentPath());
                if (e instanceof ExecutableElement ce && ce.getKind() == ElementKind.CONSTRUCTOR && ce.getEnclosingElement() instanceof TypeElement owner) {
                    String nn = newType(owner);
                    if (nn != null) {
                        String old = owner.getSimpleName().toString();
                        Matcher m = Pattern.compile("\\b" + Pattern.quote(old) + "\\s*\\(").matcher(text);
                        int from = (int) sp.getStartPosition(cu, t);
                        if (from >= 0 && m.find(from)) es.add(new Edit(m.start(), m.start() + old.length(), nn));
                    }
                }
                if (e != null && t.getReturnType() != null) {
                    String nn = newMember(e);
                    if (nn != null) {
                        int from = (int) sp.getEndPosition(cu, t.getReturnType());
                        Matcher m = Pattern.compile("\\b" + Pattern.quote(t.getName().toString()) + "\\s*\\(").matcher(text);
                        if (from >= 0 && m.find(from)) es.add(new Edit(m.start(), m.start() + t.getName().length(), nn));
                    }
                }
                return super.visitMethod(t, v);
            }

            @Override
            public Void visitVariable(VariableTree t, Void v) {
                Element e = el(getCurrentPath());
                if (e != null && e.getKind() == ElementKind.FIELD) {
                    String nn = newMember(e);
                    if (nn != null && t.getType() != null) {
                        int from = (int) sp.getEndPosition(cu, t.getType());
                        Matcher m = Pattern.compile("\\b" + Pattern.quote(t.getName().toString()) + "\\b").matcher(text);
                        if (from >= 0 && m.find(from)) es.add(new Edit(m.start(), m.end(), nn));
                    }
                }
                return super.visitVariable(t, v);
            }

            @Override
            public Void visitLiteral(LiteralTree t, Void v) {
                if (t.getValue() instanceof String s && s.length() >= 1 && s.length() <= 4 && obfMemberNames.contains(s) && !s.equals("a") && !s.equals("b") && !s.equals("x") && !s.equals("y")) {
                    notes.add(file.getFileName() + ": the string \"" + s + "\" is a name in the mapping: check it");
                }
                return super.visitLiteral(t, v);
            }
        }.scan(cu, null);
        // one edit per place (a select inside a select can be seen twice)
        Map<Integer, Edit> byStart = new TreeMap<>(Comparator.reverseOrder());
        for (Edit e : es) byStart.put(e.start(), e);
        StringBuilder sb = new StringBuilder(text);
        for (Edit e : byStart.values()) sb.replace(e.start(), e.end(), e.text());
        String out = sb.toString();
        edits += byStart.size();
        out = fixImportClashes(out, usedTypes);
        Path target = file;
        // a top-level class renamed: its file too
        for (Tree t : cu.getTypeDecls()) {
            if (t instanceof ClassTree ct) {
                Element e = trees.getElement(trees.getPath(cu, ct));
                if (e instanceof TypeElement te) {
                    String nn = newType(te);
                    if (nn != null && file.getFileName().toString().equals(ct.getSimpleName() + ".java")) {
                        target = file.resolveSibling(nn + ".java");
                        renamedFiles++;
                    }
                }
            }
        }
        if (!out.equals(text) || !target.equals(file)) {
            Files.writeString(target, out, StandardCharsets.UTF_8);
            if (!target.equals(file)) Files.delete(file);
        }
    }

    /** two imports with one simple name (a class of the game now called Component next to java.awt.Component): reported, to be written in full by hand */
    String fixImportClashes(String out, Map<String, TypeElement> usedTypes) {
        Matcher m = Pattern.compile("(?m)^import ((?:[a-z_0-9]+[.])+)([A-Za-z_][A-Za-z_0-9]*);").matcher(out);
        Map<String, List<String>> bySimple = new HashMap<>();
        while (m.find()) bySimple.computeIfAbsent(m.group(2), k -> new ArrayList<>()).add(m.group(1) + m.group(2));
        for (var e : bySimple.entrySet()) if (e.getValue().size() > 1) notes.add("imports clash on " + e.getKey() + ": " + e.getValue());
        return out;
    }

    public static void main(String[] a) throws Exception {
        Mapping m = Mapping.load(Path.of(a[0]));
        List<Path> roots = new ArrayList<>();
        for (int i = 2; i < a.length; i++) roots.add(Path.of(a[i]));
        new SourceRemap(m).run(roots, a[1]);
    }
}
