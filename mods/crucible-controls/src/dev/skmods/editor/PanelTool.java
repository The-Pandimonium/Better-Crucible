package dev.skmods.editor;

import com.threerings.config.ConfigManager;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.tools.EditorTool;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;

/**
 * What the editor's extra tools (Find, Tags, Lint, Stats, Marks, Measure, Replace) have in common: a button in the tool bar (an icon and a label of the
 * size the editor gives its own), a panel of their own, and ways to take the camera to a thing and mark it in the scene.
 */
abstract class PanelTool extends EditorTool {
    /** What the tools need of the editor. */
    interface Host {
        TudeySceneModel scene();

        /** Moves the camera so it looks at the world point. */
        void moveCameraTo(float x, float y, float z);

        /** Applies a change as one step that can be undone. */
        void apply(WireModel.Change change);

        /** Where the camera is: the point it looks at (x, y, z), then azimuth, elevation and distance; null when it cannot be told. */
        float[] cameraState();

        void setCameraState(float[] state);

        /** The camera's right and up directions in the world (right x, y, z, then up x, y, z), or null when they cannot be told. */
        default float[] cameraAxes() {
            return null;
        }

        /** The file of the scene, or null while it has none. */
        java.io.File sceneFile();

        /** The notes of the scene (the editor's Notes tool shows them). */
        String notes();

        /** Changes the notes of the scene, and what the Notes tool shows. */
        void setNotes(String notes);

        /** Selects things in the editor and chooses the Select tool (it moves and edits what is selected). */
        void select(TudeySceneModel.Entry... entries);

        TudeySceneModel.Entry[] selection();
    }

    protected final Host host;
    private final String label;
    private final String tip;
    private final float[] color;
    private final List<Marker> markers = new ArrayList<>();
    /** What the tool puts in its window goes here, under the explanation. */
    private final JPanel content = new JPanel(new BorderLayout(0, 4));
    private boolean built;

    PanelTool(com.threerings.tudey.tools.SceneEditor editor, Host host, String label, String tip, float[] color, String explanation) {
        super(editor);
        this.host = host;
        this.label = label;
        this.tip = tip;
        this.color = color;
        setLayout(new BorderLayout(0, 2));
        super.add(new Explainer(explanation), BorderLayout.NORTH);
        super.add(content, BorderLayout.CENTER);
        built = true;
    }

    /** What the tools add (north, centre, south) goes under the explanation. */
    @Override
    public void add(Component c, Object constraints) {
        if (built) content.add(c, constraints);
        else super.add(c, constraints);
    }

    @Override
    public void setButton(JToggleButton button) {
        super.setButton(button);
        // a label under the icon, in the size the editor gives its labelled tool buttons (56 x 56)
        button.setText(label);
        button.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        button.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
        button.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        button.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        button.setMargin(new Insets(2, 2, 2, 2));
        Dimension size = new Dimension(56, 56);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setPreferredSize(size);
        button.setToolTipText(tip);
    }

    // ---- helpers for what the tools show ----

    ConfigManager cfg() {
        return _editor.getConfigManager();
    }

    TudeySceneModel scene() {
        return host.scene();
    }

    static JButton button(String text, Runnable action, String tip) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(1, 6, 1, 6));
        b.setToolTipText(tip);
        b.addActionListener(ev -> action.run());
        return b;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** One line of a list: the text (HTML, wrapped), and buttons at its end. */
    static JPanel row(String html, int width, JComponent... buttons) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY), BorderFactory.createEmptyBorder(3, 4, 3, 4)));
        row.add(new JLabel("<html><body style='width:" + width + "px'>" + html + "</body></html>"), BorderLayout.CENTER);
        if (buttons.length > 0) {
            JPanel east = new JPanel();
            east.setLayout(new javax.swing.BoxLayout(east, javax.swing.BoxLayout.Y_AXIS));
            for (JComponent b : buttons) {
                b.setAlignmentX(Component.LEFT_ALIGNMENT);
                east.add(b);
                east.add(javax.swing.Box.createVerticalStrut(2));
            }
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.add(east, BorderLayout.NORTH);
            row.add(wrap, BorderLayout.EAST);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** A list of rows in a scroll pane. */
    static JScrollPane scrolling(JPanel list, int width, int height) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(list, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(width, height));
        return scroll;
    }

    static void say(JLabel status, String text, boolean problem) {
        status.setText(text);
        status.setForeground(problem ? new Color(180, 0, 0) : new Color(0, 110, 0));
    }

    // ---- going to a thing, and marking it ----

    /** The camera goes to the thing, and it is marked for five seconds. */
    void goTo(TudeySceneModel.Entry e) {
        try {
            ConfigManager cfg = cfg();
            float[] b = WireModel.bounds(e, cfg);
            int step = WireModel.elevationOf(scene(), e, cfg, _editor.getGrid().getElevation());
            host.moveCameraTo((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, com.threerings.tudey.util.TudeySceneMetrics.getTileZ(step));
            mark(List.of(e), 5f);
        } catch (RuntimeException ex) {
            System.err.println("[editor] could not go to the thing: " + ex);
        }
    }

    /** Marks things (the first twenty) in the colour of the tool for some seconds. */
    void mark(List<TudeySceneModel.Entry> things, float seconds) {
        ConfigManager cfg = cfg();
        int n = 0;
        for (TudeySceneModel.Entry e : things) {
            if (n >= 20) break;
            try {
                float[] b = WireModel.bounds(e, cfg);
                int step = WireModel.elevationOf(scene(), e, cfg, _editor.getGrid().getElevation());
                while (markers.size() <= n) markers.add(new Marker(_editor, color[0], color[1], color[2]));
                markers.get(n).show(b[0], b[1], b[2], b[3], step, seconds);
                n++;
            } catch (RuntimeException ex) {
                // not marked
            }
        }
        for (int i = n; i < markers.size(); i++) markers.get(i).hide();
    }

    /** Called by the editor on every frame it draws, whatever tool is chosen. */
    void compositeHighlight() {
        for (Marker m : markers) m.composite();
    }

    void select(List<TudeySceneModel.Entry> things) {
        if (things.isEmpty()) return;
        host.select(things.toArray(new TudeySceneModel.Entry[0]));
    }
}
