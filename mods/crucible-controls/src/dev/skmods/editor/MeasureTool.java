package dev.skmods.editor;

import com.threerings.math.Vector3f;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The "Measure" tool: <b>left click</b> sets the start of the measurement and <b>right click</b> the end. The distance between them is shown in tiles (straight,
 * along each axis, and in steps), a line is drawn between the points, and along it a ruler: a tick on both sides at every tile, longer at every fifth and longest at
 * every tenth, counted from the start. It does not change the scene.
 */
final class MeasureTool extends PanelTool {
    private final JLabel first = new JLabel("start (left click): click a point in the scene");
    private final JLabel second = new JLabel("end (right click): click another");
    private final JLabel result = new JLabel(" ");
    private final JCheckBox snap = new JCheckBox("Snap to the middle of tiles", true);
    private final Vector3f hit = new Vector3f();
    private float[] a, b;
    /** The start is green and the end is red; the line, the ruler and its numbers change from one to the other. */
    static final float[] START = {0.25f, 0.9f, 0.3f}, END = {1f, 0.3f, 0.25f};
    private Rays rays;
    private Marker markA, markB;

    MeasureTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Measure", "Measure: left click the start, right click the end, to see the distance and a ruler between them", Colors.MEASURE,
            "<b>Left click</b> sets the start and <b>right click</b> sets the end. The distance is shown here, and the line is drawn with a ruler beside it: "
            + "a tick at every tile, a longer one at every 5 tiles and the longest at every 10, with white numbers showing the distance from the start, which always face the camera. "
            + "The start is <font color='#3ccc4c'><b>green</b></font> and the end is <font color='#ff4d40'><b>red</b></font>; the line and the ruler change colour between them.  Nothing in the scene changes.");
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(snap);
        top.add(button("Clear", this::clear, "Forget the points"));
        body.add(top);
        body.add(first);
        body.add(second);
        body.add(result);
        JPanel north = new JPanel(new BorderLayout());
        north.add(body, BorderLayout.NORTH);
        add(north, BorderLayout.NORTH);
        for (JLabel l : new JLabel[] {first, second, result}) l.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
    }

    private int downX, downY;
    private boolean rightDown;

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON3) {
            // the right button also turns the camera when dragged, so the end point is set when it is let go without having moved
            downX = e.getX();
            downY = e.getY();
            rightDown = true;
            return;
        }
        if (_editor.isAdjustingView() || e.getButton() != MouseEvent.BUTTON1 || !_editor.isMainAction(e)) return;
        place(true);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON3 || !rightDown) return;
        rightDown = false;
        if (Math.abs(e.getX() - downX) <= 4 && Math.abs(e.getY() - downY) <= 4) place(false);
    }

    private void place(boolean start) {
        if (!getMousePlaneIntersection(hit)) return;
        float x = hit.x, y = hit.y;
        if (snap.isSelected()) {
            x = (float) Math.floor(x) + 0.5f;
            y = (float) Math.floor(y) + 0.5f;
        }
        if (start) a = new float[] {x, y};
        else b = new float[] {x, y};
        update();
    }

    private void clear() {
        a = null;
        b = null;
        update();
    }

    private static String point(String name, float[] p) {
        return String.format(Locale.ROOT, "%s: (%.1f, %.1f)", name, p[0], p[1]);
    }

    private void update() {
        first.setText(a == null ? "start (left click): click a point in the scene" : point("start", a));
        second.setText(b == null ? "end (right click): click another" : point("end", b));
        int step = _editor.getGrid().getElevation();
        float z = com.threerings.tudey.util.TudeySceneMetrics.getTileZ(step);
        if (markA == null) markA = new Marker(_editor, START[0], START[1], START[2]);
        if (markB == null) markB = new Marker(_editor, END[0], END[1], END[2]);
        if (a != null) markA.show(a[0] - 0.2f, a[1] - 0.2f, a[0] + 0.2f, a[1] + 0.2f, step, 100000f);
        else markA.hide();
        if (b != null) markB.show(b[0] - 0.2f, b[1] - 0.2f, b[0] + 0.2f, b[1] + 0.2f, step, 100000f);
        else markB.hide();
        if (rays == null) rays = new Rays(_editor);
        if (a != null && b != null) {
            float dx = b[0] - a[0], dy = b[1] - a[1];
            double d = Math.sqrt(dx * dx + dy * dy);
            result.setText(String.format(Locale.ROOT, "<html>distance <b>%.2f</b> tiles<br>across %.1f, along %.1f<br>%d steps (diagonals count as one), %d without diagonals</html>", d, Math.abs(dx), Math.abs(dy), Math.round(Math.max(Math.abs(dx), Math.abs(dy))), Math.round(Math.abs(dx) + Math.abs(dy))));
        } else {
            result.setText(" ");
        }
        rebuild();
    }

    private float[] seen;

    /** Makes the line, the ruler and the numbers again (the numbers face the camera, so this is done whenever the camera has turned). */
    private void rebuild() {
        if (rays == null) rays = new Rays(_editor);
        if (a == null || b == null) {
            rays.clear();
            return;
        }
        float z = com.threerings.tudey.util.TudeySceneMetrics.getTileZ(_editor.getGrid().getElevation()) + 0.3f;
        seen = host.cameraAxes();
        List<Rays.Ray> all = line(a, b, z);
        all.addAll(numbers(a, b, z, seen));
        rays.show(all, 0f);
    }

    private static boolean same(float[] p, float[] q) {
        if (p == null || q == null) return p == q;
        for (int i = 0; i < p.length; i++) if (Math.abs(p[i] - q[i]) > 0.002f) return false;
        return true;
    }

    /** The line, and the ruler along it: a tick across it at every tile, longer at every fifth, longest at every tenth, counted from the start. */
    static List<Rays.Ray> line(float[] a, float[] b, float z) {
        List<Rays.Ray> out = new ArrayList<>();
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        // the line, in pieces that change from the colour of the start to that of the end
        int pieces = 16;
        for (int i = 0; i < pieces; i++) {
            float t0 = (float) i / pieces, t1 = (float) (i + 1) / pieces;
            float[] c = blend(t0 + 0.5f / pieces);
            out.add(new Rays.Ray(a[0] + dx * t0, a[1] + dy * t0, a[0] + dx * t1, a[1] + dy * t1, c[0], c[1], c[2], i == pieces - 1, true).at(z));
        }
        if (len < 0.01f) return out;
        float ux = dx / len, uy = dy / len, nx = -uy, ny = ux;
        int every = len > 400f ? 10 : len > 100f ? 5 : 1;
        for (int i = 0; i * every <= len; i++) {
            int n = i * every;
            float half = n % 10 == 0 ? 0.9f : n % 5 == 0 ? 0.6f : 0.3f;
            float px = a[0] + ux * n, py = a[1] + uy * n;
            float[] w = blend(n / len);
            out.add(new Rays.Ray(px - nx * half, py - ny * half, px + nx * half, py + ny * half, w[0], w[1], w[2], false, true).at(z));
        }
        return out;
    }

    private static float[] blend(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new float[] {START[0] + (END[0] - START[0]) * t, START[1] + (END[1] - START[1]) * t, START[2] + (END[2] - START[2]) * t};
    }

    /** Segments of a digit as in a seven-segment display: top, upper right, lower right, bottom, lower left, upper left, middle. */
    private static final int[] DIGITS = {0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110, 0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111};

    /**
     * The numbers of the ruler, as lines in white on both sides of it: the distance from the start at every labelled tick (as many as fit), and the whole length past
     * the end. They are billboards: they stand up facing the camera, whose right and up directions in the world are {@code axes} (right x, y, z, up x, y, z); with
     * none given they face the way the camera usually does.
     */
    static List<Rays.Ray> numbers(float[] a, float[] b, float z, float[] axes) {
        List<Rays.Ray> out = new ArrayList<>();
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return out;
        float ux = dx / len, uy = dy / len, nx = -uy, ny = ux;
        float[] ax = axes != null ? axes : new float[] {1f, 0f, 0f, 0f, 0f, 1f};
        int step = len > 400f ? 100 : len > 100f ? 25 : len > 40f ? 10 : len > 15f ? 5 : 1;
        for (int n = 0; n <= len; n += step) {
            float half = n % 10 == 0 ? 0.9f : n % 5 == 0 ? 0.6f : 0.3f;
            float off = half + 0.2f + 0.45f; // clear of the tick, on each side
            float px = a[0] + ux * n, py = a[1] + uy * n;
            text(out, String.valueOf(n), px + nx * off, py + ny * off, z + 0.4f, ax);
            text(out, String.valueOf(n), px - nx * off, py - ny * off, z + 0.4f, ax);
        }
        text(out, String.format(Locale.ROOT, "%.1f", len), b[0] + ux * 1.6f, b[1] + uy * 1.6f, z + 0.4f, ax);
        return out;
    }

    /** One text, centred on a point, standing in the plane the camera looks at. */
    private static void text(List<Rays.Ray> out, String text, float px, float py, float pz, float[] ax) {
        float size = 0.6f, advance = 0.48f, width = 0.34f;
        float total = text.length() * advance - (advance - width);
        float x0 = -total / 2f;
        float base = -size / 2f;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            float gx = x0 + i * advance;
            if (ch == '.') {
                segment(out, gx + width / 2f, base, gx + width / 2f, base + 0.05f, px, py, pz, ax);
                continue;
            }
            if (ch < '0' || ch > '9') continue;
            int m = DIGITS[ch - '0'];
            float l = gx, r = gx + width, y0 = base, ym = base + size / 2f, y1 = base + size;
            if ((m & 1) != 0) segment(out, l, y1, r, y1, px, py, pz, ax);
            if ((m & 2) != 0) segment(out, r, ym, r, y1, px, py, pz, ax);
            if ((m & 4) != 0) segment(out, r, y0, r, ym, px, py, pz, ax);
            if ((m & 8) != 0) segment(out, l, y0, r, y0, px, py, pz, ax);
            if ((m & 16) != 0) segment(out, l, y0, l, ym, px, py, pz, ax);
            if ((m & 32) != 0) segment(out, l, ym, l, y1, px, py, pz, ax);
            if ((m & 64) != 0) segment(out, l, ym, r, ym, px, py, pz, ax);
        }
    }

    private static void segment(List<Rays.Ray> out, float x1, float y1, float x2, float y2, float px, float py, float pz, float[] ax) {
        out.add(new Rays.Ray(px + ax[0] * x1 + ax[3] * y1, py + ax[1] * x1 + ax[4] * y1, px + ax[0] * x2 + ax[3] * y2, py + ax[1] * x2 + ax[4] * y2, 1f, 1f, 1f, false, true)
            .between(pz + ax[2] * x1 + ax[5] * y1, pz + ax[2] * x2 + ax[5] * y2));
    }

    @Override
    void compositeHighlight() {
        super.compositeHighlight();
        if (a != null && b != null && !same(seen, host.cameraAxes())) rebuild(); // the camera has turned: the numbers turn with it
        if (markA != null) markA.composite();
        if (markB != null) markB.composite();
        if (rays != null) rays.composite();
    }
}
