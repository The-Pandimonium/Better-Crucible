package dev.skmods.build;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * The Enigma mapping of the game's code jar (mappings/projectx-pcode.mapping), read into lookups both ways. Obfuscated names ("obf") are
 * what the game's jars hold; readable names ("deobf") are what the code of this project is written in.
 */
public final class Mapping {
    /** class: internal names, with $ for inner classes */
    public final Map<String, String> classToDeobf = new HashMap<>(), classToObf = new HashMap<>();
    /** obf owner + "." + obf name -> readable name */
    public final Map<String, String> fields = new HashMap<>();
    /** obf owner + "." + obf name + obf descriptor -> readable name */
    public final Map<String, String> methods = new HashMap<>();

    public static Mapping load(Path file) throws IOException {
        Mapping m = new Mapping();
        List<String> stack = new ArrayList<>();
        List<String> newStack = new ArrayList<>(); // the readable name of each class of the stack
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int depth = 0;
            while (depth < line.length() && line.charAt(depth) == '\t') depth++;
            String[] t = line.trim().split("\\s+");
            switch (t[0]) {
                case "CLASS": {
                    String obf = depth == 0 ? t[1] : stack.get(depth - 1) + "$" + t[1];
                    String nw;
                    if (t.length > 2) nw = depth == 0 ? t[2] : newStack.get(depth - 1) + "$" + t[2];
                    else nw = depth == 0 ? obf : newStack.get(depth - 1) + "$" + t[1];
                    while (stack.size() > depth) {
                        stack.remove(stack.size() - 1);
                        newStack.remove(newStack.size() - 1);
                    }
                    stack.add(obf);
                    newStack.add(nw);
                    if (!nw.equals(obf)) {
                        m.classToDeobf.put(obf, nw);
                        m.classToObf.put(nw, obf);
                    }
                    break;
                }
                case "FIELD":
                    if (t.length > 3 && !t[1].equals(t[2])) m.fields.put(stack.get(depth - 1) + "." + t[1], t[2]);
                    break;
                case "METHOD":
                    if (t.length > 3 && !t[1].equals(t[2])) m.methods.put(stack.get(depth - 1) + "." + t[1] + t[t.length - 1], t[2]);
                    break;
                default:
                    break;
            }
        }
        return m;
    }

    /** the methods of each class by their game name: {game name, game descriptor, readable name} */
    private Map<String, List<String[]>> byOwner;

    /**
     * The name in the game's jar of a method given by the readable name of its class, its readable name and readable descriptor, or the name
     * unchanged when it has none (the game's jar names it itself).
     */
    public String obfMethod(String readableClass, String name, String readableDesc) {
        if (byOwner == null) {
            byOwner = new HashMap<>();
            for (var e : methods.entrySet()) {
                String k = e.getKey();
                int dot = k.indexOf('.');
                int par = k.indexOf('(');
                byOwner.computeIfAbsent(k.substring(0, dot), x -> new ArrayList<>()).add(new String[] {k.substring(dot + 1, par), k.substring(par), e.getValue()});
            }
        }
        String owner = obfClass(readableClass);
        String obfDesc = desc(readableDesc, false);
        for (String[] m : byOwner.getOrDefault(owner, List.of())) if (m[1].equals(obfDesc) && m[2].equals(name)) return m[0];
        return name;
    }

    public String deobfClass(String obf) {
        return classToDeobf.getOrDefault(obf, obf);
    }

    public String obfClass(String deobf) {
        return classToObf.getOrDefault(deobf, deobf);
    }

    /** a descriptor or signature with the class names turned into the readable names (obf = false: the other way). */
    public String desc(String d, boolean toDeobf) {
        if (d.indexOf('L') < 0) return d;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c == 'L') {
                int j = i + 1;
                while (j < d.length() && d.charAt(j) != ';' && d.charAt(j) != '<') j++;
                String n = d.substring(i + 1, j);
                b.append('L').append(toDeobf ? deobfClass(n) : obfClass(n));
                i = j;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }
}
