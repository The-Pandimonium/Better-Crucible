package dev.skmods.editor;

import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.math.Rect;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.tools.EditorTool;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/**
 * The "Errors" tool of the Crucible editor's tool bar, next to the selection, the brushes and the eraser. It lists every validation error of the
 * scene (what the editor's Valid / INVALID button reports, one line each). An error that belongs to a place or a thing (a missing tile, a blocked
 * connector, a camera that is not allowed) has a <b>Move Camera</b> button next to it: it moves the camera there and marks the place in red for
 * about five seconds. General errors (no control points, too few entrances ...) belong nowhere in particular and have no button.
 *
 * <p>The errors are text only, so the place is read out of the text: coordinates ({@code [12, 40]}, {@code near 12.5, 40.0}) or the name or tag of
 * what is meant (the thing's config name, or a tag). The highlight is drawn by the editor ({@link ControlledEditor#compositeView()}) so it shows
 * whatever tool is selected.
 */
public final class ErrorsTool extends EditorTool {
    /** What the tool needs of the editor. */
    interface Host {
        /** Runs the editor's own validation: its errors, one text each (empty when the scene is valid). */
        List<String> validationErrors();

        TudeySceneModel scene();

        /** Moves the camera so it looks at the world point. */
        void moveCameraTo(float x, float y, float z);
    }

    /** Where an error is: the rectangle in the scene (world units) and its elevation step. */
    static final class Place {
        final float minX, minY, maxX, maxY;
        final int elevation;

        Place(float minX, float minY, float maxX, float maxY, int elevation) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.elevation = elevation;
        }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[(-?\\d+(?:\\.\\d+)?), (-?\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern NEAR = Pattern.compile("(?:near|at) (-?\\d+(?:\\.\\d+)?), (-?\\d+(?:\\.\\d+)?)");

    private final Host host;
    private final JPanel list = new JPanel();
    private final JLabel status = new JLabel("Press Check to look for errors.");
    private Marker marker;

    ErrorsTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor);
        this.host = host;
        setLayout(new BorderLayout(0, 4));
        JButton check = new JButton("Check for errors");
        check.addActionListener(ev -> refresh());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(check);
        top.add(status);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(list, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(320, 160));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    @Override
    public void setButton(JToggleButton button) {
        super.setButton(button);
        // a label under the icon, in the size the editor gives its labelled tool buttons (56 x 56)
        button.setText("Errors");
        button.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        button.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
        button.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        button.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        Dimension size = new Dimension(56, 56);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setPreferredSize(size);
        button.setToolTipText("Errors: list what the editor's validation complains about, and go to where it is");
    }

    /** The tool was chosen: look again, since the scene has changed since last time. */
    @Override
    public void activate() {
        super.activate();
        SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        List<String> errors;
        try {
            errors = host.validationErrors();
        } catch (RuntimeException ex) {
            errors = new ArrayList<>();
            errors.add("The validation itself failed: " + ex);
        }
        list.removeAll();
        int n = 0;
        for (String text : errors) {
            n++;
            list.add(row(n, text));
        }
        status.setText(errors.isEmpty() ? "No errors." : errors.size() + (errors.size() == 1 ? " error" : " errors"));
        status.setForeground(errors.isEmpty() ? new Color(0, 120, 0) : new Color(180, 0, 0));
        list.revalidate();
        list.repaint();
    }

    private JPanel row(int n, String text) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(3, 4, 3, 4)));
        JLabel label = new JLabel("<html><body style='width:210px'>" + n + ". " + escape(text) + "</body></html>");
        row.add(label, BorderLayout.CENTER);
        final Place place = locate(text);
        if (place != null) {
            JButton move = new JButton("Move Camera");
            move.setMargin(new java.awt.Insets(2, 6, 2, 6));
            move.addActionListener(ev -> goTo(place));
            JPanel east = new JPanel(new BorderLayout());
            east.add(move, BorderLayout.NORTH);
            row.add(east, BorderLayout.EAST);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- where an error is ----

    /** The place the text of an error is about, or null when it is a general error (or the place cannot be told). */
    Place locate(String text) {
        try {
            TudeySceneModel scene = host.scene();
            ConfigManager cfg = _editor.getConfigManager();
            if (scene == null) return null;
            Matcher m = BRACKET.matcher(text);
            boolean bracket = m.find();
            if (!bracket) {
                m = NEAR.matcher(text);
                if (!m.find()) return byThing(scene, cfg, text);
            }
            float x = Float.parseFloat(m.group(1)), y = Float.parseFloat(m.group(2));
            boolean whole = bracket && !m.group(1).contains(".") && !m.group(2).contains(".");
            int step = _editor.getGrid().getElevation();
            if (whole) {
                TudeySceneModel.TileEntry tile = scene.getTileEntry((int) x, (int) y);
                if (tile != null) step = tile.getElevation();
                return new Place(x, y, x + 1f, y + 1f, step);
            }
            return new Place(x - 0.5f, y - 0.5f, x + 0.5f, y + 0.5f, step);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** "Invalid camera found: Camera/Foo", "Reused cp tag: CP1": what comes after the last colon (or the last sentence) names a thing or a tag of the scene. */
    private Place byThing(TudeySceneModel scene, ConfigManager cfg, String text) {
        String tail = text;
        int i = Math.max(text.lastIndexOf(": "), text.lastIndexOf(". "));
        if (i >= 0) tail = text.substring(i + 2);
        tail = tail.trim();
        if (tail.length() > 1 && tail.startsWith("'") && tail.endsWith("'")) tail = tail.substring(1, tail.length() - 1);
        if (tail.isEmpty()) return null;
        TudeySceneModel.Entry found = null;
        for (TudeySceneModel.Entry en : scene.getEntries()) {
            ConfigReference<?> ref = en.getReference();
            if (ref != null && tail.equals(ref.getName())) {
                found = en;
                if (!(en instanceof TudeySceneModel.TileEntry)) break;
            }
        }
        if (found == null) {
            List<TudeySceneModel.Entry> tagged = scene.getTaggedEntries(tail);
            if (tagged != null && !tagged.isEmpty()) found = tagged.get(0);
        }
        return found == null ? null : of(found, cfg);
    }

    private Place of(TudeySceneModel.Entry en, ConfigManager cfg) {
        Rect r = new Rect();
        en.getBounds(cfg, r);
        float minX = r.getMinimumExtent().x, minY = r.getMinimumExtent().y, maxX = r.getMaximumExtent().x, maxY = r.getMaximumExtent().y;
        if (!(maxX - minX > 0.01f) || !(maxY - minY > 0.01f)) {
            com.threerings.math.Vector2f at = en.getTranslation(cfg);
            if (at == null) return null;
            minX = at.x - 0.5f;
            maxX = at.x + 0.5f;
            minY = at.y - 0.5f;
            maxY = at.y + 0.5f;
        }
        return new Place(minX, minY, maxX, maxY, en.getElevation());
    }

    // ---- moving there, and the red mark ----

    private void goTo(Place p) {
        float z = com.threerings.tudey.util.TudeySceneMetrics.getTileZ(p.elevation);
        host.moveCameraTo((p.minX + p.maxX) / 2f, (p.minY + p.maxY) / 2f, z);
        mark(p);
    }

    /** Marks the place in red for about five seconds. */
    private void mark(Place p) {
        if (marker == null) marker = new Marker(_editor, 1f, 0.05f, 0.05f);
        marker.show(p.minX, p.minY, p.maxX, p.maxY, p.elevation, 5f);
    }

    /** Called by the editor on every frame it draws, whatever tool is chosen. */
    void compositeHighlight() {
        if (marker != null) marker.composite();
    }

    // ---- the tool bar icon ----

    /** The tool's icon (the editor's own icons come from its resources; this one is drawn): a red warning triangle with an exclamation mark. */
    static ImageIcon icon() {
        BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        java.awt.Polygon tri = new java.awt.Polygon(new int[]{12, 22, 2}, new int[]{2, 21, 21}, 3);
        g.setColor(new Color(214, 40, 40));
        g.fillPolygon(tri);
        g.setColor(new Color(120, 0, 0));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolygon(tri);
        g.setColor(Color.WHITE);
        g.fillRoundRect(11, 8, 3, 7, 2, 2);
        g.fillOval(11, 16, 3, 3);
        g.dispose();
        return new ImageIcon(img);
    }
}
