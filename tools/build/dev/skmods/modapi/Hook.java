package dev.skmods.modapi;

import java.util.List;

/**
 * A call to a static method of the mod, put into a method of the game: {@code Hook.of("my.Class", "method", "(descriptor)V", Arg.THIS, Arg.param(0))}.
 * The descriptor is written with the readable names; the method's result, if it returns one, is dropped.
 */
public final class Hook {
    final String owner;
    final String name;
    final String descriptor;
    final List<Arg> args;

    private Hook(String owner, String name, String descriptor, List<Arg> args) {
        this.owner = owner.replace('.', '/');
        this.name = name;
        this.descriptor = descriptor;
        this.args = args;
    }

    public static Hook of(String ownerClass, String method, String descriptor, Arg... args) {
        return new Hook(ownerClass, method, descriptor, List.of(args));
    }
}
