package dev.skmods.editor;

import com.threerings.config.ConfigManager;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.tools.EditorTool;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The "Wire" tool of the Crucible editor's tool bar: joins a thing that sends a signal (a switch, a pressure plate, a handling marker, a trigger, an
 * area ...) to the things that react to it (doors, gates, traps ...), the way Hammer's I/O tab joins an entity's outputs to other entities' inputs.
 *
 * <p>With the tool chosen, click anything that has a tag or a signal (a prop, a tile, an area ...). Everything it sends is listed in <b>slots</b>,
 * and each slot has its <b>connections</b>: one line per thing it signals, each with its own signal. <b>New connection</b> adds some (several can be
 * picked at once); <b>Pick in the scene</b> next to it chooses the target by clicking it, with everything that can be wired to lit up. A connection
 * can be changed or removed one by one. The tool writes them the way that takes the fewest parts: a single connection is set straight on the sender;
 * several go through a handling marker the tool makes (and takes away again when it is no longer needed). A path through a marker can be shared:
 * pick the marker as the target and any number of senders use the same one. Striped rays in the scene join the thing to everything it is wired to.
 */
public final class WireTool extends EditorTool {
    /** What the tool needs of the editor. */
    interface Host {
        TudeySceneModel scene();

        /** Moves the camera so it looks at the world point. */
        void moveCameraTo(float x, float y, float z);

        /** Puts a change in the scene as one undoable step. */
        void apply(WireModel.Change change);
    }

    private final Host host;
    private final JLabel title = new JLabel(" ");
    private final JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel rows = new JPanel();
    private final JLabel status = new JLabel(" ");
    private Object sourceKey;
    private Marker preview;
    /** The orange mark on the thing selected as the sender. */
    private Marker selected;
    private Rays rays, lit;
    /** When set, the next click on a thing is the answer (the target to connect to) instead of choosing a source. */
    private Consumer<TudeySceneModel.Entry> picking;

    WireTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor);
        this.host = host;
        setLayout(new BorderLayout(0, 4));
        title.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.CENTER);
        top.add(head, BorderLayout.SOUTH);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(rows, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(400, 190));
        status.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        rebuild();
    }

    @Override
    public void setButton(JToggleButton button) {
        super.setButton(button);
        button.setToolTipText("Wire: join a switch (or anything that sends a signal) to the doors and things it controls");
        button.setText("Wire");
        button.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        button.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
        button.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        button.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        button.setMargin(new Insets(2, 2, 2, 2));
        Dimension size = new Dimension(56, 56);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setPreferredSize(size);
    }

    @Override
    public void activate() {
        super.activate();
        stopPicking();
        SwingUtilities.invokeLater(this::rebuild);
    }

    @Override
    public void deactivate() {
        super.deactivate();
        stopPicking();
        if (preview != null) preview.hide();
        if (selected != null) selected.hide();
        if (rays != null) rays.clear();
    }

    private void stopPicking() {
        picking = null;
        if (lit != null) lit.clear();
    }

    /** Clicking a thing makes it the one to wire from (or, after "Pick in the scene", the one to wire to). */
    @Override
    public void mousePressed(MouseEvent e) {
        if (!_editor.isMainAction(e)) return;
        TudeySceneModel.Entry p = _editor.getMouseEntry();
        ConfigManager cfg = _editor.getConfigManager();
        if (picking != null) {
            Consumer<TudeySceneModel.Entry> c = picking;
            stopPicking();
            if (p == null || !WireModel.valid(p, cfg)) {
                say(p == null ? "Cancelled: nothing was clicked." : "Cancelled: that has no tag or signal to wire to.");
            } else {
                final TudeySceneModel.Entry chosen = p;
                SwingUtilities.invokeLater(() -> c.accept(chosen));
            }
            return;
        }
        if (p == null) return;
        if (!WireModel.valid(p, cfg)) {
            say(WireModel.describe(p, cfg) + " has no tag or signal, so there is nothing to wire.");
            return;
        }
        sourceKey = p.getKey();
        _editor.setSelection(p);
        say(" ");
        rebuild();
    }

    /** The scene changed under us (an edit, an undo): the lists show what is there now. */
    @Override
    public void entryUpdated(TudeySceneModel.Entry oldEntry, TudeySceneModel.Entry newEntry) {
        if (sourceKey != null) SwingUtilities.invokeLater(this::rebuild);
    }

    @Override
    public void entryAdded(TudeySceneModel.Entry entry) {
        if (sourceKey != null) SwingUtilities.invokeLater(this::rebuild);
    }

    @Override
    public void entryRemoved(TudeySceneModel.Entry entry) {
        if (sourceKey != null && sourceKey.equals(entry.getKey())) sourceKey = null;
        SwingUtilities.invokeLater(this::rebuild);
    }

    void compositeHighlight() {
        if (preview != null) preview.composite();
        if (selected != null) selected.composite();
        if (rays != null) rays.composite();
        if (lit != null) lit.composite();
    }

    private void say(String text) {
        status.setText("<html><body style='width:360px'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>");
    }

    private TudeySceneModel.Entry current() {
        TudeySceneModel scene = host.scene();
        if (scene == null || sourceKey == null) return null;
        // bK looks an entry up by its key (bJ removes it)
        return scene.getEntry(sourceKey);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- the lists ----

    private void rebuild() {
        rows.removeAll();
        head.removeAll();
        TudeySceneModel.Entry src = current();
        ConfigManager cfg = _editor.getConfigManager();
        TudeySceneModel scene = host.scene();
        List<Rays.Ray> drawn = new ArrayList<>();
        if (src == null || cfg == null || scene == null) {
            title.setText("<html>Click anything with a tag or a signal to wire from it (a switch, a pressure plate, a handling marker, a trigger, an area ...).</html>");
        } else {
            try {
                title.setText("<html><b>" + escape(WireModel.describe(src, cfg)) + "</b></html>");
                List<WireModel.Slot> slots = WireModel.slots(src, cfg);
                if (slots.isEmpty()) rows.add(note("It sends no signals. Click another thing."));
                for (WireModel.Slot s : slots) {
                    rows.add(section(src, s, scene, cfg));
                    addRays(drawn, src, s, scene, cfg);
                }
                if (WireModel.isMarker(src)) head.add(button("New handler...", () -> newHandler(src, scene, cfg), "Make the marker hear another signal, or do something when the scene starts"));
                addIncoming(drawn, src, scene, cfg);
                rows.add(inputs(src, scene, cfg));
            } catch (RuntimeException ex) {
                System.err.println("[editor] the wire list failed: " + ex);
                ex.printStackTrace();
                rows.add(note("Could not read this: " + ex));
            }
        }
        showRays(src, drawn, scene, cfg);
        markSelected(src, cfg);
        rows.revalidate();
        rows.repaint();
        head.revalidate();
        head.repaint();
    }

    /** An orange mark on the selected thing, for as long as it is selected. */
    private void markSelected(TudeySceneModel.Entry src, ConfigManager cfg) {
        if (selected == null) selected = new Marker(_editor, 1f, 0.55f, 0.05f);
        if (src == null || cfg == null) {
            selected.hide();
            return;
        }
        try {
            float[] b = WireModel.bounds(src, cfg);
            selected.show(b[0], b[1], b[2], b[3], WireModel.elevationOf(host.scene(), src, cfg, _editor.getGrid().getElevation()), 100000f);
        } catch (RuntimeException ex) {
            selected.hide();
        }
    }

    private JLabel note(String text) {
        JLabel l = new JLabel("<html><body style='width:360px'>" + escape(text) + "</body></html>");
        l.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JButton button(String text, Runnable action, String tip) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(1, 6, 1, 6));
        b.setToolTipText(tip);
        b.addActionListener(ev -> action.run());
        return b;
    }

    private JPanel section(final TudeySceneModel.Entry src, final WireModel.Slot slot, final TudeySceneModel scene, final ConfigManager cfg) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2), BorderFactory.createTitledBorder(slot.title())));
        List<WireModel.Link> links = slot.links(scene, cfg);
        String other = slot.other(scene, cfg);
        if (other != null) box.add(note("It " + other + "."));
        if (links.isEmpty() && slot.editable()) box.add(note("Not connected."));
        boolean relayed = false;
        for (WireModel.Link l : links) {
            box.add(linkRow(src, slot, l, scene, cfg));
            if (l.via != null) relayed = true;
        }
        if (slot.editable()) {
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            buttons.add(button("New connection...", () -> connect(slot, null, null), "Wire this to one or more things"));
            buttons.add(button("Pick in the scene", () -> pick(slot, null), "Click the thing to wire this to; everything that can be wired to is lit up"));
            if (relayed) buttons.add(button("Simplify", () -> done(WireModel.simplify(scene, cfg, slot)), "Write the connections the shortest way: a path through a marker that sends one signal becomes a direct connection and the marker goes"));
            if (slot.removable()) {
                buttons.add(button("Remove handler", () -> {
                    WireModel.Change c = new WireModel.Change();
                    WireModel.removeHandler(scene, cfg, c, src, (WireModel.HandlerSlot) slot);
                    done(c);
                }, "Take this handler away from the marker"));
            }
            buttons.setAlignmentX(LEFT_ALIGNMENT);
            box.add(buttons);
        }
        box.setAlignmentX(LEFT_ALIGNMENT);
        return box;
    }

    private JPanel linkRow(final TudeySceneModel.Entry src, final WireModel.Slot slot, final WireModel.Link link, final TudeySceneModel scene, final ConfigManager cfg) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        final List<TudeySceneModel.Entry> hit = targets(scene, cfg, link.hop.tag, src);
        String to;
        if (hit.isEmpty()) to = "<font color='#b00000'>nothing has the tag " + escape(link.hop.tag) + "</font>";
        else to = escape(WireModel.describe(hit.get(0), cfg)) + (hit.size() > 1 ? " <i>and " + (hit.size() - 1) + " more</i>" : "") + " <font color='#707070'>[" + escape(link.hop.tag) + "]</font>";
        String via = "";
        if (link.via != null) via = "<br><i><font color='#505050'>through " + escape(link.via) + (link.sharedWith > 0 ? "; shared with " + link.sharedWith + " other" + (link.sharedWith == 1 ? "" : "s") + ": changing it changes them" : "") + "</font></i>";
        JLabel label = new JLabel("<html><body style='width:250px'><b>" + escape(link.hop.signal) + "</b> &rarr; " + to + via + "</body></html>");
        row.add(label, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        if (slot.editable()) {
            b.add(button("Edit", () -> connect(slot, link, null), "Change this connection"));
            b.add(button("Remove", () -> done(WireModel.remove(scene, cfg, slot, link)), "Take this connection away (what is no longer needed goes with it)"));
        }
        JButton show = button("Show", () -> showTarget(hit.get(0), cfg, 5f), "Move the camera to the thing this goes to");
        show.setEnabled(!hit.isEmpty());
        b.add(show);
        row.add(b, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
        return row;
    }

    /** What answers to a tag: props, tiles, areas ..., not the sender itself. */
    private static List<TudeySceneModel.Entry> targets(TudeySceneModel scene, ConfigManager cfg, String tag, TudeySceneModel.Entry except) {
        List<TudeySceneModel.Entry> out = new ArrayList<>();
        for (TudeySceneModel.Entry r : WireModel.tagged(scene, cfg, tag)) {
            if (except != null && r.getKey().equals(except.getKey())) continue;
            out.add(r);
        }
        return out;
    }

    /** What the clicked thing itself answers to, and who sends to it: the other half of the wiring. */
    private JPanel inputs(TudeySceneModel.Entry p, TudeySceneModel scene, ConfigManager cfg) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        String tag = WireModel.tagOf(p, cfg);
        Set<String> signals = WireModel.signalsOf(p, cfg);
        StringBuilder sb = new StringBuilder();
        if (tag == null) sb.append(WireModel.tagControl(p, cfg) != null ? "It has no tag yet: wiring something to it gives it one." : "It has no tag, so nothing can be wired to it.");
        else sb.append("Answers to the tag ").append(tag).append(signals.isEmpty() ? "." : ", reacts to: " + String.join(", ", signals) + ".");
        box.add(note(sb.toString()));
        if (tag != null) {
            int n = 0;
            for (TudeySceneModel.Entry s : scene.getEntries()) {
                if (!WireModel.hasConfig(s) || s.getKey().equals(p.getKey())) continue;
                for (WireModel.Slot slot : WireModel.slots(s, cfg)) {
                    for (WireModel.Hop h : slot.sends(scene, cfg)) {
                        if (tag.equals(h.tag)) {
                            box.add(note("Heard from " + WireModel.describe(s, cfg) + " (" + slot.title() + "): " + h.signal));
                            n++;
                        }
                    }
                }
            }
            if (n == 0) box.add(note("Nothing sends to it yet."));
        }
        box.setAlignmentX(LEFT_ALIGNMENT);
        return box;
    }

    private void showTarget(TudeySceneModel.Entry target, ConfigManager cfg, float seconds) {
        float[] b = WireModel.bounds(target, cfg);
        host.moveCameraTo((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, com.threerings.tudey.util.TudeySceneMetrics.getTileZ((int) b[4]));
        if (preview == null) preview = new Marker(_editor, 0.1f, 0.9f, 0.2f);
        preview.show(b[0], b[1], b[2], b[3], (int) b[4], seconds);
    }

    private void done(WireModel.Change c) {
        if (c.isEmpty()) {
            say(c.notes.isEmpty() ? "Nothing to change." : String.join("; ", c.notes) + ".");
            return;
        }
        host.apply(c);
        say(String.join("; ", c.notes) + ".");
        rebuild();
    }

    // ---- the striped rays ----

    private static final float[] ORANGE = {1f, 0.55f, 0.1f}, CYAN = {0.2f, 0.8f, 1f}, GREEN = {0.2f, 1f, 0.3f};

    private static float[] centre(TudeySceneModel.Entry e, ConfigManager cfg) {
        float[] b = WireModel.bounds(e, cfg);
        return new float[]{(b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f};
    }

    /** The rays of one slot: from the thing to each thing it signals; through a marker, to the marker and from there on. */
    private void addRays(List<Rays.Ray> into, TudeySceneModel.Entry src, WireModel.Slot slot, TudeySceneModel scene, ConfigManager cfg) {
        float[] from = centre(src, cfg);
        TudeySceneModel.Entry via = slot.relay(scene, cfg);
        boolean toMarker = false;
        for (WireModel.Link l : slot.links(scene, cfg)) {
            float[] start = from;
            if (l.via != null && via != null) {
                float[] m = centre(via, cfg);
                if (!toMarker) into.add(new Rays.Ray(from[0], from[1], m[0], m[1], CYAN[0], CYAN[1], CYAN[2], true));
                toMarker = true;
                start = m;
            }
            int drawnTargets = 0;
            for (TudeySceneModel.Entry t : targets(scene, cfg, l.hop.tag, src)) {
                if (++drawnTargets > 40) break; // a floor of trap tiles shares one tag: the first forty are enough to see where it is
                float[] to = centre(t, cfg);
                into.add(new Rays.Ray(start[0], start[1], to[0], to[1], ORANGE[0], ORANGE[1], ORANGE[2], true));
            }
        }
    }

    /** The rays that come in: from every thing that sends to the tag of the selected one (a marker sees the switches that feed it). */
    private void addIncoming(List<Rays.Ray> into, TudeySceneModel.Entry dst, TudeySceneModel scene, ConfigManager cfg) {
        String tag = WireModel.tagOf(dst, cfg);
        if (tag == null) return;
        float[] to = centre(dst, cfg);
        for (TudeySceneModel.Entry s : scene.getEntries()) {
            if (!WireModel.hasConfig(s) || s.getKey().equals(dst.getKey())) continue;
            boolean sends = false;
            for (WireModel.Slot slot : WireModel.slots(s, cfg)) for (WireModel.Hop h : slot.sends(scene, cfg)) if (tag.equals(h.tag)) sends = true;
            if (!sends) continue;
            float[] from = centre(s, cfg);
            into.add(new Rays.Ray(from[0], from[1], to[0], to[1], CYAN[0], CYAN[1], CYAN[2], true));
        }
    }

    private void showRays(TudeySceneModel.Entry src, List<Rays.Ray> drawn, TudeySceneModel scene, ConfigManager cfg) {
        if (rays == null) rays = new Rays(_editor);
        if (src == null || drawn.isEmpty()) {
            rays.clear();
            return;
        }
        rays.show(drawn, com.threerings.tudey.util.TudeySceneMetrics.getTileZ(WireModel.elevationOf(scene, src, cfg, _editor.getGrid().getElevation())) + 1.2f);
    }

    /** Lights up every thing in the scene that can be wired to (it has a tag or a signal): a green outline round each. */
    private void lightUp() {
        TudeySceneModel scene = host.scene();
        ConfigManager cfg = _editor.getConfigManager();
        if (lit == null) lit = new Rays(_editor);
        List<Rays.Ray> outlines = new ArrayList<>();
        if (scene != null) {
            for (TudeySceneModel.Entry e : scene.getEntries()) {
                if (!WireModel.valid(e, cfg)) continue;
                float[] b = WireModel.bounds(e, cfg);
                outlines.add(new Rays.Ray(b[0], b[1], b[2], b[1], GREEN[0], GREEN[1], GREEN[2], false, true));
                outlines.add(new Rays.Ray(b[2], b[1], b[2], b[3], GREEN[0], GREEN[1], GREEN[2], false, true));
                outlines.add(new Rays.Ray(b[2], b[3], b[0], b[3], GREEN[0], GREEN[1], GREEN[2], false, true));
                outlines.add(new Rays.Ray(b[0], b[3], b[0], b[1], GREEN[0], GREEN[1], GREEN[2], false, true));
            }
        }
        lit.show(outlines, com.threerings.tudey.util.TudeySceneMetrics.getTileZ(_editor.getGrid().getElevation()) + 0.15f);
    }

    // ---- connecting ----

    /** What the connection dialog answered. */
    private static final class Choice {
        final List<WireModel.Target> targets = new ArrayList<>();
        boolean pick;
    }

    /** Waits for a click on a thing to connect to, with everything that can be wired to lit up. */
    private void pick(final WireModel.Slot slot, final WireModel.Link edit) {
        picking = chosen -> connect(slot, edit, Collections.singletonList(chosen));
        lightUp();
        say("Click the thing to connect to (everything outlined in green can be); click anywhere else to cancel.");
    }

    /** Opens the window for a new connection of a slot, or (with {@code edit}) to change one. */
    private void connect(final WireModel.Slot slot, final WireModel.Link edit, final List<TudeySceneModel.Entry> preselect) {
        ConfigManager cfg = _editor.getConfigManager();
        TudeySceneModel scene = host.scene();
        Choice c = new ConnectDialog(SwingUtilities.getWindowAncestor(this), slot, edit, scene, cfg, preselect).ask();
        if (c == null) return;
        if (c.pick) {
            pick(slot, edit);
            return;
        }
        if (edit == null) done(WireModel.add(scene, cfg, slot, c.targets));
        else done(WireModel.replace(scene, cfg, slot, edit, c.targets.get(0)));
    }

    private void newHandler(TudeySceneModel.Entry marker, TudeySceneModel scene, ConfigManager cfg) {
        Object[] options = {"When it receives a signal...", "When the scene starts", "Cancel"};
        int pick = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(this), "What should the new handler react to?", "New handler", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (pick == 0) {
            String name = JOptionPane.showInputDialog(SwingUtilities.getWindowAncestor(this), "The name of the signal it hears:", "New handler", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) done(WireModel.addHandler(scene, cfg, marker, false, name.trim()));
        } else if (pick == 1) {
            done(WireModel.addHandler(scene, cfg, marker, true, null));
        }
    }

    /** The window that asks which things, and which signal. */
    private final class ConnectDialog extends JDialog {
        private final TudeySceneModel scene;
        private final ConfigManager cfg;
        private final DefaultListModel<Item> model = new DefaultListModel<>();
        private final JList<Item> list = new JList<>(model);
        private final JTextField filter = new JTextField();
        private final JTextField tag = new JTextField(14);
        private final JComboBox<String> signal = new JComboBox<>();
        private final JLabel info = new JLabel(" ");
        private final JButton ok;
        private final List<Item> all = new ArrayList<>();
        private final String keepSignal;
        private final TudeySceneModel.Entry slotOwner;
        private Choice result;

        ConnectDialog(java.awt.Window owner, WireModel.Slot slot, WireModel.Link edit, TudeySceneModel scene, ConfigManager cfg, List<TudeySceneModel.Entry> preselect) {
            super(owner, (edit == null ? "New connection from " : "Change the connection from ") + slot.title() + " of " + WireModel.describe(slot.owner(scene), cfg), ModalityType.APPLICATION_MODAL);
            this.scene = scene;
            this.cfg = cfg;
            this.keepSignal = edit == null ? null : edit.hop.signal;
            this.slotOwner = slot.owner(scene);
            ok = new JButton(edit == null ? "Add" : "Change");
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            TudeySceneModel.Entry srcEntry = slot.owner(scene);
            float[] sb = WireModel.bounds(srcEntry, cfg);
            for (TudeySceneModel.Entry r : WireModel.receivers(scene, cfg)) {
                if (r.getKey().equals(srcEntry.getKey())) continue;
                float[] b = WireModel.bounds(r, cfg);
                float dx = (b[0] + b[2]) / 2f - (sb[0] + sb[2]) / 2f, dy = (b[1] + b[3]) / 2f - (sb[1] + sb[3]) / 2f;
                all.add(new Item(r, r, WireModel.describe(r, cfg), WireModel.tagOf(r, cfg), dx * dx + dy * dy, WireModel.isMarker(r), null));
            }
            for (java.util.Map.Entry<String, List<TudeySceneModel.Entry>> g : WireModel.tileGroups(scene, cfg).entrySet()) {
                TudeySceneModel.Entry first = g.getValue().get(0);
                float[] b = WireModel.bounds(first, cfg);
                float dx = (b[0] + b[2]) / 2f - (sb[0] + sb[2]) / 2f, dy = (b[1] + b[3]) / 2f - (sb[1] + sb[3]) / 2f;
                all.add(new Item(null, first, g.getValue().size() + " x " + WireModel.describe(first, cfg).replaceAll(" at \\(.*", ""), g.getKey(), dx * dx + dy * dy, false, g.getValue()));
            }
            all.sort(Comparator.comparingDouble(i -> i.distance));
            fill();
            list.setSelectionMode(edit == null ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
            list.addListSelectionListener(ev -> {
                if (!ev.getValueIsAdjusting()) chosen();
            });
            filter.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    fill();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    fill();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    fill();
                }
            });
            signal.setEditable(true);

            JPanel left = new JPanel(new BorderLayout(0, 4));
            left.add(new JLabel(edit == null ? "Things to signal (pick several with Ctrl or Shift), nearest first:" : "The thing to signal, nearest first:"), BorderLayout.NORTH);
            left.add(new JScrollPane(list), BorderLayout.CENTER);
            left.add(filter, BorderLayout.SOUTH);
            JPanel form = new JPanel();
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            form.add(new JLabel("Tag it answers to:"));
            form.add(tag);
            form.add(Box.createVerticalStrut(8));
            form.add(new JLabel("Signal to send:"));
            form.add(signal);
            form.add(Box.createVerticalStrut(8));
            form.add(info);
            form.add(Box.createVerticalStrut(8));
            JButton go = new JButton("Move Camera to it");
            go.addActionListener(ev -> {
                Item i = list.getSelectedValue();
                if (i != null) showTarget(i.rep, cfg, 60f);
            });
            form.add(go);
            for (Component k : form.getComponents()) if (k instanceof JComponent) ((JComponent) k).setAlignmentX(LEFT_ALIGNMENT);
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, form);
            split.setResizeWeight(0.55);

            JButton pickButton = new JButton("Pick in the scene");
            pickButton.addActionListener(ev -> {
                result = new Choice();
                result.pick = true;
                dispose();
            });
            ok.addActionListener(ev -> accept());
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(ev -> dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(pickButton);
            buttons.add(ok);
            buttons.add(cancel);
            setLayout(new BorderLayout(6, 6));
            ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            add(split, BorderLayout.CENTER);
            add(buttons, BorderLayout.SOUTH);
            setPreferredSize(new Dimension(700, 400));
            pack();
            setLocationRelativeTo(owner);
            List<TudeySceneModel.Entry> want = new ArrayList<>();
            if (preselect != null) want.addAll(preselect);
            else if (edit != null) want.addAll(targets(scene, cfg, edit.hop.tag, srcEntry));
            for (TudeySceneModel.Entry w : want) {
                for (int i = 0; i < model.size(); i++) {
                    Item it = model.get(i);
                    boolean inGroup = it.group != null && it.group.stream().anyMatch(g -> g.getKey().equals(w.getKey()));
                    if (it.rep.getKey().equals(w.getKey()) || inGroup) list.addSelectionInterval(i, i);
                }
                if (edit != null) break;
            }
            if (list.getSelectedIndex() < 0 && !model.isEmpty()) list.setSelectedIndex(0);
            chosen();
        }

        Choice ask() {
            setVisible(true);
            if (preview != null) preview.hide();
            return result;
        }

        private void accept() {
            List<Item> picked = list.getSelectedValuesList();
            if (picked.isEmpty()) return;
            Object s = signal.getEditor().getItem();
            String sig = s == null ? "" : s.toString().trim();
            if (sig.isEmpty()) {
                info.setText("<html><body style='width:200px'><font color='#b00000'>Choose or type a signal first.</font></body></html>");
                return;
            }
            result = new Choice();
            for (Item i : picked) {
                // the tag typed for a single thing that has none; otherwise the model settles it (a tag made for the connection, or the thing's own)
                String t = picked.size() == 1 && i.tag == null ? tag.getText().trim() : (i.tag != null ? i.tag : "");
                result.targets.add(new WireModel.Target(i.entry, i.group != null ? i.tag : t, sig));
            }
            dispose();
        }

        private void fill() {
            String f = filter.getText().trim().toLowerCase();
            List<Item> keep = list.getSelectedValuesList();
            model.clear();
            for (Item i : all) if (f.isEmpty() || i.toString().toLowerCase().contains(f)) model.addElement(i);
            for (int k = 0; k < model.size(); k++) if (keep.contains(model.get(k))) list.addSelectionInterval(k, k);
        }

        /** A selection was made: its tag (or a fresh one), what they react to, and a marker on the first. */
        private void chosen() {
            List<Item> picked = list.getSelectedValuesList();
            ok.setEnabled(!picked.isEmpty());
            if (picked.isEmpty()) return;
            Item i = picked.get(0);
            boolean one = picked.size() == 1;
            String existing = i.tag;
            tag.setEnabled(one);
            tag.setText(one ? (existing != null ? existing : i.entry == null ? "" : WireModel.connectionTag(slotOwner, i.entry, cfg, scene, null)) : "(made for each connection)");
            tag.setEditable(one && existing == null && i.entry != null && WireModel.tagControl(i.entry, cfg) != null);
            String about;
            if (!one) about = picked.size() + " targets: each gets its own tag if it has none.";
            else if (i.group != null) about = "Things with this tag: the signal reaches all " + i.group.size() + " of them.";
            else if (i.marker) about = "A handling marker: sending to its tag shares the path it relays.";
            else about = existing != null ? "It answers to this tag already (a tag shared with other things is replaced by one of its own, unless something else sends to it)." : "It has no tag: this one, made for the connection, will be given to it.";
            info.setText("<html><body style='width:200px'>" + escape(about) + "</body></html>");
            Set<String> signals = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Item p : picked) signals.addAll(p.group != null ? WireModel.groupSignals(p.group, cfg) : WireModel.signalsOf(p.entry, cfg));
            DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
            for (String s : signals) m.addElement(s);
            if (keepSignal != null && signals.stream().noneMatch(keepSignal::equalsIgnoreCase)) m.addElement(keepSignal);
            signal.setModel(m);
            String pick = keepSignal != null ? keepSignal : signals.stream().filter(s -> s.equalsIgnoreCase("open")).findFirst().orElse(m.getSize() > 0 ? m.getElementAt(0) : "");
            m.setSelectedItem(pick);
            float[] b = WireModel.bounds(i.rep, cfg);
            if (preview == null) preview = new Marker(_editor, 0.1f, 0.9f, 0.2f);
            preview.show(b[0], b[1], b[2], b[3], (int) b[4], 120f);
        }
    }

    private static final class Item {
        /** The thing, or null for a group of tiles that share a tag. */
        final TudeySceneModel.Entry entry;
        /** What stands for it in the scene (the first tile of a group), for its place. */
        final TudeySceneModel.Entry rep;
        final String text, tag;
        final double distance;
        final boolean marker;
        final List<TudeySceneModel.Entry> group;

        Item(TudeySceneModel.Entry entry, TudeySceneModel.Entry rep, String text, String tag, double distance, boolean marker, List<TudeySceneModel.Entry> group) {
            this.entry = entry;
            this.rep = rep;
            this.group = group;
            this.text = text;
            this.tag = tag;
            this.distance = distance;
            this.marker = marker;
        }

        @Override
        public String toString() {
            return (marker ? "path: " : group != null ? "tiles: " : "") + text + (tag == null ? "   (no tag)" : "   [" + tag + "]");
        }
    }

    // ---- the tool bar icon ----

    /** A drawn icon: two connectors joined by a wire. */
    static ImageIcon icon() {
        BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(230, 150, 30));
        g.draw(new CubicCurve2D.Float(6, 17, 6, 6, 18, 18, 18, 7));
        g.setColor(new Color(60, 60, 70));
        g.fillRoundRect(2, 15, 8, 6, 3, 3);
        g.fillRoundRect(14, 3, 8, 6, 3, 3);
        g.setColor(new Color(220, 220, 230));
        g.fillOval(4, 17, 2, 2);
        g.fillOval(7, 17, 2, 2);
        g.fillOval(16, 5, 2, 2);
        g.fillOval(19, 5, 2, 2);
        g.dispose();
        return new ImageIcon(img);
    }
}
