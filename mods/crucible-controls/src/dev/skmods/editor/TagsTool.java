package dev.skmods.editor;

import com.threerings.tudey.data.TudeySceneModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The "Tags" tool: every tag of the scene. A tag opens to show what is behind it, split by what each thing is: the things that <b>have</b> the tag (one line for
 * each kind of thing, with how many), and the things that <b>send</b> to it (by kind of thing, with the signals). A tag nothing has (something sends to it) and a tag
 * that reacts to signals nobody sends are marked; signals the game sends by itself (capture:0 ...) are not counted as missing. <b>Rename</b> gives the tag a new name
 * everywhere, as one step to undo.
 */
final class TagsTool extends PanelTool {
    private final JPanel list = new JPanel();
    private final JLabel status = new JLabel("Press Refresh.");
    private final JCheckBox problems = new JCheckBox("Only the ones that look wrong");
    private final Map<String, Integer> next = new HashMap<>();
    private final Set<String> open = new HashSet<>();
    private List<SceneTools.TagInfo> tags = new ArrayList<>();

    TagsTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Tags", "Tags: every tag of the scene, split by what has it and what signals it; the ones that lead nowhere; rename a tag everywhere", Colors.TAGS,
            "Lists every tag in the scene. Click a tag to see what <b>has</b> it and what <b>sends</b> to it. <b>Go</b> moves the camera there and outlines them in <font color='" + Colors.css(Colors.TAGS) + "'><b>violet</b></font>, "
+ "and <b>Select</b> selects them. <font color='#b40000'>Red</font> means something sends to the tag but nothing has it. <font color='#b46400'>Orange</font> means the tag waits for a signal that nothing sends. "
+ "Signals the game sends itself (such as capture:0) are not reported. <b>Rename</b> changes a tag everywhere it is used.");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(button("Refresh", this::refresh, "Read the scene again"));
        top.add(problems);
        top.add(button("Open all", () -> {
            for (SceneTools.TagInfo t : tags) open.add(t.tag);
            render();
        }, "Open every tag"));
        top.add(button("Close all", () -> {
            open.clear();
            render();
        }, "Close every tag"));
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        add(north, BorderLayout.NORTH);
        add(scrolling(list, 360, 230), BorderLayout.CENTER);
        problems.addActionListener(ev -> render());
    }

    @Override
    public void activate() {
        super.activate();
        SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        TudeySceneModel scene = scene();
        if (scene == null) return;
        try {
            tags = SceneTools.tags(scene, cfg());
        } catch (RuntimeException ex) {
            say(status, "Reading the tags failed: " + ex, true);
            ex.printStackTrace();
            return;
        }
        render();
    }

    private void render() {
        list.removeAll();
        int shown = 0, wrong = 0;
        for (SceneTools.TagInfo t : tags) {
            boolean bad = t.dangling() || t.unused();
            if (bad) wrong++;
            if (problems.isSelected() && !bad) continue;
            shown++;
            list.add(headerFor(t, bad));
            if (open.contains(t.tag)) addDetails(t);
        }
        say(status, tags.size() + " tag" + (tags.size() == 1 ? "" : "s") + (wrong > 0 ? ", " + wrong + " that look wrong" : ", none that looks wrong") + (shown < tags.size() ? " (" + shown + " shown)" : ""), wrong > 0);
        list.revalidate();
        list.repaint();
    }

    private JPanel headerFor(SceneTools.TagInfo t, boolean bad) {
        boolean isOpen = open.contains(t.tag);
        String color = t.dangling() ? "#b40000" : t.unused() ? "#b46400" : "#000000";
        StringBuilder sb = new StringBuilder("<font color='" + color + "'><b>" + (isOpen ? "▾ " : "▸ ") + escape(t.tag) + "</b></font>  ");
        sb.append(t.holders.size()).append(t.holders.size() == 1 ? " thing" : " things");
        if (!t.sent.isEmpty()) sb.append(", signalled ").append(t.sent.values().stream().mapToInt(Integer::intValue).sum()).append('x');
        if (t.dangling()) sb.append("<br><font color='#b40000'>nothing has this tag, but something sends to it</font>");
        else if (t.unused()) sb.append("<br><font color='#b46400'>reacts to ").append(escape(String.join(", ", t.heard))).append(", and none is sent</font>");
        final List<TudeySceneModel.Entry> targets = t.holders.isEmpty() ? t.senders : t.holders;
        JButton rename = button("Rename", () -> rename(t), t.renamable ? "Give the tag a new name everywhere" : "Not possible: the tag is fixed in the configuration");
        rename.setEnabled(t.renamable);
        JPanel row = row(sb.toString(), 245,
            button("Go", () -> goNext(t.tag, targets), "Go to the things (again for the next one)"),
            button("Select", () -> select(targets), "Select them all in the editor"),
            rename);
        row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (open.contains(t.tag)) open.remove(t.tag);
                else open.add(t.tag);
                render();
            }
        });
        return row;
    }

    /** Under an open tag: a line for each kind of thing that has it, and for each kind that sends to it. */
    private void addDetails(SceneTools.TagInfo t) {
        if (!t.holderGroups.isEmpty()) list.add(section("has the tag"));
        for (SceneTools.Group g : t.holderGroups.values()) list.add(groupRow(g, "", t));
        if (!t.senderGroups.isEmpty()) list.add(section("sends to it"));
        for (SceneTools.Group g : t.senderGroups.values()) list.add(groupRow(g, g.signals.isEmpty() ? "" : "  <font color='#666666'>signals " + escape(String.join(", ", g.signals)) + "</font>", t));
        if (!t.heard.isEmpty() || !t.fromGame.isEmpty()) {
            StringBuilder r = new StringBuilder();
            if (!t.heard.isEmpty()) r.append("reacts to ").append(escape(String.join(", ", t.heard)));
            if (!t.fromGame.isEmpty()) r.append(r.length() > 0 ? "; " : "").append("from the game: ").append(escape(String.join(", ", t.fromGame)));
            JLabel l = new JLabel("<html><body style='width:300px'>" + r + "</body></html>");
            l.setBorder(BorderFactory.createEmptyBorder(1, 24, 3, 4));
            l.setAlignmentX(LEFT_ALIGNMENT);
            list.add(l);
        }
    }

    private JLabel section(String text) {
        JLabel l = new JLabel("<html><i>" + text + "</i></html>");
        l.setBorder(BorderFactory.createEmptyBorder(2, 18, 0, 4));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JPanel groupRow(SceneTools.Group g, String extra, SceneTools.TagInfo t) {
        int n = g.entries.size();
        String name = g.config.isEmpty() ? "(no configuration)" : g.config;
        JPanel row = row(n + " x " + escape(g.kind + ": " + name) + extra, 205,
            button("Go", () -> goNext(t.tag + "|" + g.kind + g.config, g.entries), "Go to them (again for the next one)"),
            button("Select", () -> select(g.entries), "Select them in the editor"));
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0), row.getBorder()));
        return row;
    }

    private void goNext(String key, List<TudeySceneModel.Entry> targets) {
        if (targets.isEmpty()) return;
        int i = next.merge(key, 1, Integer::sum) - 1;
        goTo(targets.get(i % targets.size()));
        mark(targets, 8f);
    }

    private void rename(SceneTools.TagInfo t) {
        Object answer = JOptionPane.showInputDialog(this, "New name for the tag " + t.tag + ":\n(it is changed on " + t.holders.size() + " thing" + (t.holders.size() == 1 ? "" : "s") + " and in everything that sends to it)", "Rename tag", JOptionPane.QUESTION_MESSAGE, null, null, t.tag);
        if (answer == null) return;
        String nw = answer.toString().trim();
        if (nw.isEmpty() || nw.equals(t.tag)) return;
        try {
            WireModel.Change c = SceneTools.renameTag(scene(), cfg(), t.tag, nw);
            if (!c.isEmpty()) host.apply(c);
            say(status, c.notes.isEmpty() ? "Done." : c.notes.get(c.notes.size() - 1), c.isEmpty());
            if (open.remove(t.tag)) open.add(nw);
        } catch (RuntimeException ex) {
            say(status, "Could not rename: " + ex, true);
            ex.printStackTrace();
        }
        SwingUtilities.invokeLater(this::refresh);
    }

    @SuppressWarnings("unused")
    private static JComponent[] none() {
        return new JComponent[0];
    }
}
