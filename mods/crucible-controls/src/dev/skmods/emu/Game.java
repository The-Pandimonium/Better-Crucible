package dev.skmods.emu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reaching into the game's classes by name, without ever writing an obfuscated name. The game's code is obfuscated again with every update, so a name
 * in a string ({@code "blQ"}) is the one thing the mapping cannot follow: the code compiles, runs, and quietly does the wrong thing. A name given
 * here is the <b>readable</b> one (the one of {@code mappings/}): {@code Game.field("com.threerings.projectx.dungeon.util.PawnAdvancerImpl#weaponSlot")}.
 * The build ({@code Obfuscate}) rewrites the text of every call of these methods to the game's own names, from the mapping, and fails when a name is
 * not in it (or says two things) so a name that went away is found by the build, not in the middle of a game.
 *
 * <p>A text is {@code Class} or {@code Class#member}; for a method, an optional readable descriptor after the name
 * ({@code Class#advance(I)V}) tells overloads apart, and is only for the build: what is left at run time is the plain name.
 */
public final class Game {
    private Game() {
    }

    /** The class (readable name; the build makes it the game's). */
    public static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game has no class " + name + ": this program was not built for this build of the game (./sk mappings update, then ./sk all)", e);
        }
    }

    private static String owner(String spec) {
        int h = spec.indexOf('#');
        return h < 0 ? spec : spec.substring(0, h);
    }

    private static String member(String spec) {
        int h = spec.indexOf('#');
        if (h < 0) throw new IllegalArgumentException("no member in " + spec);
        String m = spec.substring(h + 1);
        int p = m.indexOf('(');
        return p < 0 ? m : m.substring(0, p);
    }

    /** The name the game's jar has for a field (the build turns the readable one into it). */
    public static String fieldName(String spec) {
        return member(spec);
    }

    /** The name the game's jar has for a method. */
    public static String methodName(String spec) {
        return member(spec);
    }

    /** The field, found in the class or above it, ready to be read and written. */
    public static Field field(String spec) {
        Class<?> c = cls(owner(spec));
        String n = member(spec);
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // the class above
            }
        }
        throw new IllegalStateException("the game's " + owner(spec) + " has no field " + n + ": this program was not built for this build of the game");
    }

    /** The method with these parameter types, found in the class or above it, ready to be called. */
    public static Method method(String spec, Class<?>... params) {
        Class<?> c = cls(owner(spec));
        String n = member(spec);
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // the class above
            }
        }
        throw new IllegalStateException("the game's " + owner(spec) + " has no method " + n + ": this program was not built for this build of the game");
    }
}
