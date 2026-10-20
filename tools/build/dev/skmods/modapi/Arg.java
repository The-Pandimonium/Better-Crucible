package dev.skmods.modapi;

/** What is passed to a {@link Hook}: the object the patched method is on, one of its parameters, the value it returns, or a constant. */
public final class Arg {
    enum Kind {THIS, PARAM, RETURNED, INT, STRING, NULL}

    final Kind kind;
    final int index;
    final Object value;

    private Arg(Kind kind, int index, Object value) {
        this.kind = kind;
        this.index = index;
        this.value = value;
    }

    /** {@code this} (the patched method must not be static). */
    public static final Arg THIS = new Arg(Kind.THIS, 0, null);
    /** {@code null} */
    public static final Arg NULL = new Arg(Kind.NULL, 0, null);
    /** The value the method is about to return: for {@link Patcher#injectAtEnd}, and only as the first argument (it is a copy of what is on the stack). */
    public static final Arg RETURNED = new Arg(Kind.RETURNED, 0, null);

    /** The parameter of the method, counted from 0 (the receiver is not counted; a long or a double is one parameter). */
    public static Arg param(int index) {
        return new Arg(Kind.PARAM, index, null);
    }

    public static Arg of(int value) {
        return new Arg(Kind.INT, 0, value);
    }

    public static Arg of(String value) {
        return new Arg(Kind.STRING, 0, value);
    }
}
