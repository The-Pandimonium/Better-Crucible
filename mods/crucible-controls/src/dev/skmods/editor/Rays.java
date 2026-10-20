package dev.skmods.editor;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL11;

/**
 * Striped rays drawn over the scene: from a prop to what it is wired to. It is the editor's own cursor element ({@code tudey.a.c.e}, which draws a
 * {@code Shape} with the game's drawing state), given a shape of our own that draws lines instead of a polygon. Used by reflection, since
 * {@code tudey.a} is both a class and a package in the obfuscated jar. The editor draws it on every frame ({@link ControlledEditor#compositeView()}).
 */
final class Rays {
    /** One ray: a line from (x1, y1) to (x2, y2) in the scene, in a colour, with an arrow head when {@code arrow}. */
    static final class Ray {
        final float x1, y1, x2, y2, r, g, b;
        final boolean arrow;
        /** A plain line in its colour, not stripes. */
        final boolean solid;
        /** The height above the level the whole is drawn at (0: on it). */
        float z;
        /** The height of the far end, when it is not that of the near one. */
        float z2 = Float.NaN;

        Ray(float x1, float y1, float x2, float y2, float r, float g, float b, boolean arrow) {
            this(x1, y1, x2, y2, r, g, b, arrow, false);
        }

        /** The same ray, drawn this high above the level the rays are drawn at. */
        /** The same ray, rising or falling from one height to another. */
        Ray between(float from, float to) {
            this.z = from;
            this.z2 = to;
            return this;
        }

        Ray at(float height) {
            this.z = height;
            return this;
        }

        Ray(float x1, float y1, float x2, float y2, float r, float g, float b, boolean arrow, boolean solid) {
            this.solid = solid;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.r = r;
            this.g = g;
            this.b = b;
            this.arrow = arrow;
        }
    }

    /** The shape the cursor element draws: the rays as stripes. Its polygon vertices only give the element its bounds. */
    static final class RayShape extends com.threerings.tudey.shape.Polygon {
        private List<Ray> rays = new ArrayList<>();

        RayShape() {
            super(4);
        }

        void set(List<Ray> rays) {
            this.rays = new ArrayList<>(rays);
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Ray r : rays) {
                minX = Math.min(minX, Math.min(r.x1, r.x2));
                maxX = Math.max(maxX, Math.max(r.x1, r.x2));
                minY = Math.min(minY, Math.min(r.y1, r.y2));
                maxY = Math.max(maxY, Math.max(r.y1, r.y2));
            }
            if (rays.isEmpty()) minX = minY = maxX = maxY = 0f;
            getVertex(0).set(minX - 1f, minY - 1f);
            getVertex(1).set(maxX + 1f, minY - 1f);
            getVertex(2).set(maxX + 1f, maxY + 1f);
            getVertex(3).set(minX - 1f, maxY + 1f);
            updateBounds();
        }

        /** Draws every ray as alternating stripes of its colour and white. */
        @Override
        public void draw(boolean filled) {
            final float stripe = 0.4f;
            // the colour set here must not stay set: everything drawn after the rays (the scene itself) would be tinted with the last one
            GL11.glPushAttrib(GL11.GL_CURRENT_BIT);
            GL11.glBegin(GL11.GL_LINES);
            for (Ray r : new ArrayList<>(rays)) {
                if (r.solid && !Float.isNaN(r.z2)) {
                    // a plain line that rises or falls (it may stand straight up, so its length on the ground is not what counts)
                    GL11.glColor4f(r.r, r.g, r.b, 1f);
                    GL11.glVertex3f(r.x1, r.y1, r.z);
                    GL11.glVertex3f(r.x2, r.y2, r.z2);
                    continue;
                }
                float dx = r.x2 - r.x1, dy = r.y2 - r.y1;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.01f) continue;
                dx /= len;
                dy /= len;
                int n = r.solid ? 1 : (int) Math.ceil(len / stripe);
                for (int i = 0; i < n; i++) {
                    if (i % 2 == 0) GL11.glColor4f(r.r, r.g, r.b, 1f);
                    else GL11.glColor4f(1f, 1f, 1f, 1f);
                    float a = r.solid ? 0f : i * stripe, b = r.solid ? len : Math.min(len, (i + 1) * stripe);
                    GL11.glVertex3f(r.x1 + dx * a, r.y1 + dy * a, Float.isNaN(r.z2) ? r.z : r.z + (r.z2 - r.z) * (a / len));
                    GL11.glVertex3f(r.x1 + dx * b, r.y1 + dy * b, Float.isNaN(r.z2) ? r.z : r.z + (r.z2 - r.z) * (b / len));
                }
                if (r.arrow) {
                    GL11.glColor4f(r.r, r.g, r.b, 1f);
                    for (int side = -1; side <= 1; side += 2) {
                        // two short strokes back from the tip, 28 degrees either side
                        double ang = Math.toRadians(28) * side;
                        float cx = (float) (-dx * Math.cos(ang) + dy * Math.sin(ang));
                        float cy = (float) (-dx * Math.sin(ang) - dy * Math.cos(ang));
                        GL11.glVertex3f(r.x2, r.y2, r.z);
                        GL11.glVertex3f(r.x2 + cx * 0.6f, r.y2 + cy * 0.6f, r.z);
                    }
                }
            }
            GL11.glEnd();
            GL11.glPopAttrib();
        }
    }

    private final com.threerings.tudey.tools.SceneEditor editor;
    private Object cursor;
    private java.lang.reflect.Method cursorShape, cursorTransform, cursorComposite;
    private final RayShape shape = new RayShape();
    private boolean shown;

    Rays(com.threerings.tudey.tools.SceneEditor editor) {
        this.editor = editor;
    }

    /** Shows these rays, at this height above the scene's floor (in world units), until {@link #clear}. */
    void show(List<Ray> rays, float z) {
        try {
            if (cursor == null) {
                Class<?> type = dev.skmods.emu.Game.cls("com.threerings.tudey.a.c.SelectionElement");
                java.lang.reflect.Constructor<?> ctor = type.getConstructors()[0];
                cursor = ctor.newInstance(editor, true);
                cursorShape = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.tudey.a.c.SelectionElement#callUpdateBounds"), com.threerings.tudey.shape.Shape.class);
                cursorTransform = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.tudey.a.c.SelectionElement#getTransform"));
                cursorComposite = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.tudey.a.c.SelectionElement#composite"));
            }
            shape.set(rays);
            ((com.threerings.math.Transform3D) cursorTransform.invoke(cursor)).getTranslation().z = z;
            cursorShape.invoke(cursor, shape);
            shown = !rays.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[editor] could not draw the rays: " + ex);
        }
    }

    void clear() {
        shown = false;
    }

    /** Called on every frame the editor draws. */
    void composite() {
        if (!shown || cursor == null) return;
        try {
            cursorComposite.invoke(cursor);
        } catch (ReflectiveOperationException ex) {
            shown = false;
        }
    }
}
