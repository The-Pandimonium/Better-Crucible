package dev.skmods.editor;

import com.threerings.tudey.data.TudeySceneModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The "Replace" tool, in three steps that read top to bottom. <b>1 Replace this</b>: choose what to replace from what the scene uses (type to narrow the list),
 * or click a thing in the scene, or use what is selected. <b>2 With this</b>: choose what to put there from the configurations of the same kind (type to narrow
 * the list), or click a thing in the scene to use its configuration. <b>3 Where</b>: everywhere, or only in the selection. The things that would change are
 * outlined in coral as soon as both are chosen, and a line says how many; <b>Replace</b> makes the change (one step to undo).
 */
final class ReplaceTool extends PanelTool {
    private static final class Item {
        final SceneTools.Used used;
        final TudeySceneModel.Entry sample;

        Item(SceneTools.Used used, TudeySceneModel.Entry sample) {
            this.used = used;
            this.sample = sample;
        }

        @Override
        public String toString() {
            return used.kind + ":  " + used.name + "   (" + used.count + ")";
        }
    }

    /** A list that says why it is empty. */
    private static final class HintList<T> extends JList<T> {
        private final java.util.function.Supplier<String> hint;

        HintList(javax.swing.ListModel<T> model, java.util.function.Supplier<String> hint) {
            super(model);
            this.hint = hint;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (getModel().getSize() > 0) return;
            g.setColor(java.awt.Color.GRAY);
            int y = 18;
            for (String line : hint.get().split("\n")) {
                g.drawString(line, 8, y);
                y += 16;
            }
        }
    }

    private final JTextField fromFilter = new JTextField(14);
    private final DefaultListModel<Item> fromModel = new DefaultListModel<>();
    private final JList<Item> fromList = new HintList<>(fromModel, () -> "The scene has nothing that can be replaced.");
    /** The picker of step 2: the editor's own configuration tree (as in the Place and Tiles tools) for the kind of thing chosen in step 1. */
    private final JPanel toHost = new JPanel(new BorderLayout());
    private com.threerings.config.swing.ConfigTree toTree;
    private String toChosen;
    private final JRadioButton everywhere = new JRadioButton("Everywhere in the scene", true), inSelection = new JRadioButton("Only in the selection");
    private final JCheckBox keep = new JCheckBox("Keep the tag and the settings of each thing", true);
    private final JLabel preview = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton replaceButton;
    private final JButton pickFrom, pickTo;
    private List<Item> allFrom = new ArrayList<>();
    private Consumer<TudeySceneModel.Entry> picking;
    private boolean loading;

    ReplaceTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Replace", "Replace: give everything that uses one configuration another one", Colors.REPLACE,
            "<b>1.</b> Choose what to replace: pick it from the list, click it in the scene, or use the selection. "
+ "<b>2.</b> Choose the replacement in the same configuration tree that the Place and Tiles tools use (it always shows the kind of thing chosen in step 1: props, tiles, actors, areas and so on), or click a thing in the scene. "
+ "<b>3.</b> Choose where: everywhere, or only in the selection. The things that would change are outlined in <font color='" + Colors.css(Colors.REPLACE) + "'><b>coral</b></font>, with a count. "
+ "<b>Replace</b> applies the change and Undo reverses it. Positions and rotations stay the same.");
        fromList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fromList.addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting() && !loading) fromChosen();
        });
        listen(fromFilter, this::filterFrom);
        showHint("First choose what to replace in step 1,<br>or click a thing in the scene.");
        ButtonGroup scope = new ButtonGroup();
        scope.add(everywhere);
        scope.add(inSelection);
        everywhere.addActionListener(ev -> refreshPreview());
        inSelection.addActionListener(ev -> refreshPreview());
        keep.addActionListener(ev -> refreshPreview());
        pickFrom = button("Click a thing", () -> pick(this::pickedFrom, pickFromButton()), "Click a thing in the scene: what it uses is what is replaced");
        pickTo = button("Click a thing", () -> pick(this::pickedTo, pickToButton()), "Click a thing in the scene: what it uses is what it is replaced with");
        replaceButton = button("Replace", this::replace, "Make the replacement (it can be undone)");
        replaceButton.setEnabled(false);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(step("1  Replace this", fromFilter, fromList, 110, pickFrom, button("Use the selection", this::useSelection, "Replace what the selected thing uses")));
        body.add(toStep());
        JPanel where = new JPanel();
        where.setLayout(new BoxLayout(where, BoxLayout.Y_AXIS));
        where.setBorder(BorderFactory.createTitledBorder("3  Where"));
        where.add(everywhere);
        where.add(inSelection);
        where.add(keep);
        body.add(where);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bottom.add(replaceButton);
        bottom.add(button("Show what changes", () -> refreshPreview(), "Outline the things that would change"));
        bottom.add(button("Refresh", this::load, "Read what the scene uses again"));
        JPanel south = new JPanel(new BorderLayout());
        south.add(preview, BorderLayout.NORTH);
        south.add(bottom, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new java.awt.Dimension(350, 330));
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        for (JLabel l : new JLabel[] {preview, status}) l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
    }

    private JButton pickFromButton() {
        return pickFrom;
    }

    private JButton pickToButton() {
        return pickTo;
    }

    private JPanel toStep() {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("2  With this"), BorderFactory.createEmptyBorder(0, 2, 2, 2)));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(pickTo);
        p.add(top, BorderLayout.NORTH);
        toHost.setPreferredSize(new java.awt.Dimension(320, 240));
        p.add(toHost, BorderLayout.CENTER);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private void showHint(String html) {
        toHost.removeAll();
        JLabel l = new JLabel("<html>" + html + "</html>");
        l.setForeground(java.awt.Color.GRAY);
        l.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        l.setVerticalAlignment(JLabel.TOP);
        toHost.add(l, BorderLayout.CENTER);
        toHost.revalidate();
        toHost.repaint();
    }

    /** The picker for the kind of thing that is replaced: every configuration of that kind, in the tree the editor uses, with its filter. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildTo(Item from) {
        if (toTree != null) {
            toTree.dispose();
            toTree = null;
        }
        toChosen = null;
        if (from == null) {
            showHint("First choose what to replace in step 1,<br>or click a thing in the scene.");
            return;
        }
        try {
            Class type = WireModel.cls(from.sample);
            com.threerings.config.ConfigGroup[] groups = cfg().getGroups(type);
            toTree = new com.threerings.config.swing.ConfigTree(groups);
            com.threerings.config.swing.ConfigTreeFilterPanel filter = new com.threerings.config.swing.ConfigTreeFilterPanel(_editor.getMessageManager());
            com.google.common.base.ChoosingFilter hidden = _editor.getChoosingFilter(type);
            if (hidden != null) filter.addHiddenConstraint(hidden);
            filter.setTree(toTree);
            final com.threerings.config.swing.ConfigTree tree = toTree;
            tree.addTreeSelectionListener(ev -> {
                com.threerings.config.swing.ConfigTreeNode node = tree.getSelectedNode();
                toChosen = node == null || node.getConfig() == null ? null : node.getName();
                if (!loading) refreshPreview();
            });
            JScrollPane pane = new JScrollPane(tree);
            toHost.removeAll();
            toHost.add(filter, BorderLayout.NORTH);
            toHost.add(pane, BorderLayout.CENTER);
            toHost.revalidate();
            toHost.repaint();
        } catch (RuntimeException | LinkageError ex) {
            System.err.println("[editor] could not build the configuration picker: " + ex);
            showHint("There are no configurations of this kind to choose from.");
        }
    }

    private JPanel step(String title, JTextField filter, JList<?> list, int height, JButton pick, JButton extra) {
            JPanel p = new JPanel(new BorderLayout(0, 2));
            p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), BorderFactory.createEmptyBorder(0, 2, 2, 2)));
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            top.add(new JLabel("Filter:"));
            top.add(filter);
            top.add(pick);
            if (extra != null) top.add(extra);
            JScrollPane s = new JScrollPane(list);
            s.setPreferredSize(new java.awt.Dimension(320, height));
            p.add(top, BorderLayout.NORTH);
            p.add(s, BorderLayout.CENTER);
            p.setAlignmentX(LEFT_ALIGNMENT);
            return p;
        }

        private static void listen(JTextField f, Runnable r) {
            f.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    r.run();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    r.run();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    r.run();
                }
            });
        }

        @Override
        public void activate() {
            super.activate();
            SwingUtilities.invokeLater(this::load);
        }

        @Override
        public void deactivate() {
            super.deactivate();
            picking = null;
            pickFrom.setText("Click a thing");
            pickTo.setText("Click a thing");
        }

        // ---- choosing in the scene ----

        private void pick(Consumer<TudeySceneModel.Entry> then, JButton button) {
            if (picking != null) { // pressed again: cancelled
                picking = null;
                pickFrom.setText("Click a thing");
                pickTo.setText("Click a thing");
                say(status, " ", false);
                return;
            }
            picking = then;
            button.setText("Cancel");
            say(status, "Click a thing in the scene ...", false);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (picking == null || !_editor.isMainAction(e)) return;
            TudeySceneModel.Entry thing = _editor.getMouseEntry();
            if (thing == null || !WireModel.hasConfig(thing)) return;
            Consumer<TudeySceneModel.Entry> c = picking;
            picking = null;
            pickFrom.setText("Click a thing");
            pickTo.setText("Click a thing");
            c.accept(thing);
        }

        private void pickedFrom(TudeySceneModel.Entry thing) {
            selectFrom(SceneTools.kindOf(thing), SceneTools.configName(thing));
        }

        private void pickedTo(TudeySceneModel.Entry thing) {
            Item from = fromList.getSelectedValue();
            if (from == null) {
                say(status, "Choose what to replace first (step 1).", true);
                return;
            }
            if (!sameType(from.sample, thing)) {
                say(status, "That is not the same kind of thing as what is replaced.", true);
                return;
            }
            selectTo(SceneTools.configName(thing));
        }

        private static boolean sameType(TudeySceneModel.Entry a, TudeySceneModel.Entry b) {
            try {
                return WireModel.cls(a) == WireModel.cls(b);
            } catch (RuntimeException ex) {
                return false;
            }
        }

        private void useSelection() {
            TudeySceneModel.Entry[] sel = host.selection();
            for (TudeySceneModel.Entry e : sel) {
                if (WireModel.hasConfig(e)) {
                    selectFrom(SceneTools.kindOf(e), SceneTools.configName(e));
                    inSelection.setSelected(true);
                    refreshPreview();
                    return;
                }
            }
            say(status, "Nothing that can be replaced is selected.", true);
        }

        private void selectFrom(String kind, String name) {
            fromFilter.setText("");
            for (int i = 0; i < fromModel.size(); i++) {
                Item it = fromModel.get(i);
                if (it.used.kind.equals(kind) && it.used.name.equals(name)) {
                    fromList.setSelectedIndex(i);
                    fromList.ensureIndexIsVisible(i);
                    return;
                }
            }
        }

        private void selectTo(String name) {
            toChosen = name;
        if (toTree != null) toTree.setSelectedNode(name);
        refreshPreview();
        }

        // ---- the lists ----

        private void load() {
            TudeySceneModel scene = scene();
            if (scene == null) return;
            loading = true;
            try {
                allFrom = new ArrayList<>();
                for (SceneTools.Used u : SceneTools.usedConfigs(scene)) {
                    TudeySceneModel.Entry sample = null;
                    for (TudeySceneModel.Entry e : scene.getEntries()) {
                        if (u.kind.equals(SceneTools.kindOf(e)) && u.name.equals(SceneTools.configName(e))) {
                            sample = e;
                            break;
                        }
                    }
                    if (sample != null) allFrom.add(new Item(u, sample));
                }
                filterFrom();
            } finally {
                loading = false;
            }
            fromChosen();
        }

        private void filterFrom() {
            Item keepChosen = fromList.getSelectedValue();
            boolean was = loading;
            loading = true;
            try {
                fromModel.clear();
                for (Item it : allFrom) if (matches(it.used.kind + " " + it.used.name, fromFilter.getText())) fromModel.addElement(it);
                if (keepChosen != null) fromList.setSelectedValue(keepChosen, true);
            } finally {
                loading = was;
            }
        }

        private static boolean matches(String text, String filter) {
            String t = text.toLowerCase(Locale.ROOT);
            for (String w : filter.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!w.isEmpty() && !t.contains(w)) return false;
            return true;
        }

        /** What is to be replaced was chosen: the list of what it could be replaced with is that of its kind. */
        private void fromChosen() {
            rebuildTo(fromList.getSelectedValue());
            refreshPreview();
        }

        // ---- what would change ----

        private Set<Object> scopeKeys() {
            return inSelection.isSelected() ? SceneTools.keysOf(host.selection()) : null;
        }

        private void refreshPreview() {
            if (loading) return;
            Item from = fromList.getSelectedValue();
            String to = toChosen != null && from != null && toChosen.equals(from.used.name) ? null : toChosen;
            replaceButton.setEnabled(false);
            if (from == null) {
                say(preview, "Choose what to replace (step 1).", false);
                mark(new ArrayList<>(), 1f);
                return;
            }
            List<TudeySceneModel.Entry> users = SceneTools.usersOf(scene(), from.used.kind, from.used.name, scopeKeys());
            if (to == null) {
                say(preview, users.size() + " " + from.used.kind + (users.size() == 1 ? "" : "s") + " use " + from.used.name + ". Choose what to put there (step 2).", false);
            } else if (users.isEmpty()) {
                say(preview, inSelection.isSelected() ? "Nothing in the selection uses " + from.used.name + "." : "Nothing uses it.", true);
            } else {
                say(preview, users.size() + " " + from.used.kind + (users.size() == 1 ? "" : "s") + " will change from  " + from.used.name + "  to  " + to + ".", false);
                replaceButton.setEnabled(true);
            }
            mark(users, 120f);
        }

        private void replace() {
            Item from = fromList.getSelectedValue();
            String to = toChosen;
            if (from == null || to == null || (from.used.name.equals(to))) return;
            Set<Object> only = scopeKeys();
            int count = SceneTools.usersOf(scene(), from.used.kind, from.used.name, only).size();
            if (count == 0) return;
            if (count > 500 && JOptionPane.showConfirmDialog(this, "Replace " + from.used.name + "\nby " + to + "\nin " + count + " things?", "Replace", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                WireModel.Change c = SceneTools.replace(scene(), cfg(), from.used.name, to, keep.isSelected(), only);
                if (!c.isEmpty()) host.apply(c);
                say(status, c.notes.isEmpty() ? "Done." : c.notes.get(c.notes.size() - 1) + " (Undo takes it back)", c.isEmpty());
            } catch (RuntimeException ex) {
                say(status, "Could not replace: " + ex, true);
                ex.printStackTrace();
            }
            SwingUtilities.invokeLater(this::load);
        }
    }
