package dev.skmods.editor;

import com.threerings.opengl.renderer.Color4f;

/**
 * A coloured rectangle laid over a place in the scene for a while, to show where something is. It is the editor's own cursor element
 * ({@code tudey.a.c.e}, the thing the selection tool draws its box with), so it is a class the compiler cannot spell ({@code a} is also a
 * package in the obfuscated jar) and is used by reflection. The editor draws it on every frame ({@link ControlledEditor#compositeView()}), whatever
 * tool is chosen.
 */
final class Marker {
    private final com.threerings.tudey.tools.SceneEditor editor;
    private final float r, g, b;
    private Object cursor;
    private java.lang.reflect.Method cursorShape, cursorColor, cursorTransform, cursorComposite;
    private com.threerings.tudey.shape.Polygon shape;
    private long until;
    private boolean colored;

    Marker(com.threerings.tudey.tools.SceneEditor editor, float r, float g, float b) {
        this.editor = editor;
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /** Marks the rectangle (world units, at the elevation step) for {@code seconds}. */
    void show(float minX, float minY, float maxX, float maxY, int elevation, float seconds) {
        try {
            if (cursor == null) {
                Class<?> type = dev.skmods.emu.Game.cls("com.threerings.tudey.a.c.SelectionElement");
                java.lang.reflect.Constructor<?> ctor = type.getConstructors()[0];
                cursor = ctor.newInstance(editor, true);
                cursorShape = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.tudey.a.c.SelectionElement#callUpdateBounds"), com.threerings.tudey.shape.Shape.class);
                cursorColor = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.tudey.a.c.SelectionElement#getColor"));
                cursorTransform = type.getMethod("getTransform");
                cursorComposite = type.getMethod("composite");
                shape = new com.threerings.tudey.shape.Polygon(4);
                cursorShape.invoke(cursor, shape);
            }
            float pad = 0.1f;
            shape.getVertex(0).set(minX - pad, minY - pad);
            shape.getVertex(1).set(maxX + pad, minY - pad);
            shape.getVertex(2).set(maxX + pad, maxY + pad);
            shape.getVertex(3).set(minX - pad, maxY + pad);
            shape.updateBounds();
            ((com.threerings.math.Transform3D) cursorTransform.invoke(cursor)).getTranslation().z = com.threerings.tudey.util.TudeySceneMetrics.getTileZ(elevation) + 0.02f;
            cursorShape.invoke(cursor, shape);
            colored = false;
            until = System.nanoTime() + (long) (seconds * 1_000_000_000L);
            applyColor();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[editor] could not mark the place: " + ex);
        }
    }

    void hide() {
        until = 0;
    }

    /** The colour: its draw state exists only once it has been drawn, so this is tried again on each frame until it takes. */
    private void applyColor() {
        try {
            Color4f c = (Color4f) cursorColor.invoke(cursor);
            c.r = r;
            c.g = g;
            c.b = b;
            c.floatValue = 1f;
            colored = true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // not yet
        }
    }

    /** Called on every frame the editor draws. */
    void composite() {
        if (cursor == null || System.nanoTime() > until) return;
        try {
            if (!colored) applyColor();
            cursorComposite.invoke(cursor);
            if (!colored) applyColor();
        } catch (ReflectiveOperationException ex) {
            until = 0;
        }
    }
}
