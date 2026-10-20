package dev.skmods.editor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/**
 * The preferences window of the editor's controls: the profile in use, and for the profile chosen every action (camera, tools, stamp) and every
 * menu command with what it is bound to. Click a binding to assign it, then press the key, click the mouse button or turn the wheel (with
 * the modifiers wanted held). The built-in profiles Legacy, Modern and New can be looked at but not changed: make a profile of your own from either.
 */
final class ControlsDialog extends JDialog {
    /** The editor's menu commands: by path ("Edit/Copy"), the item and the shortcut it has in the editor itself. */
    interface Menus {
        Map<String, JMenuItem> items();

        KeyStroke original(String path);
    }

    private final Menus menus;
    private final JComboBox<Controls.Profile> combo = new JComboBox<>();
    private final JButton add = new JButton("New...");
    private final JButton rename = new JButton("Rename...");
    private final JButton remove = new JButton("Delete");
    private final JLabel status = new JLabel(" ");
    private final ActionModel actions = new ActionModel();
    private final MenuModel commands = new MenuModel();
    private final JTable actionTable = new JTable(actions);
    private final JTable commandTable = new JTable(commands);
    private boolean updating;

    ControlsDialog(Frame owner, Menus menus) {
        super(owner, "Editor controls", false);
        this.menus = menus;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("Profile:"));
        top.add(combo);
        top.add(add);
        top.add(rename);
        top.add(remove);
        combo.addActionListener(e -> {
            if (updating) return;
            Controls.Profile p = (Controls.Profile) combo.getSelectedItem();
            if (p != null) Controls.use(p);
            refresh();
        });
        add.addActionListener(e -> addProfile());
        rename.addActionListener(e -> renameProfile());
        remove.addActionListener(e -> removeProfile());

        actionTable.setRowHeight(22);
        actionTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        actionTable.getColumnModel().getColumn(1).setPreferredWidth(360);
        actionTable.getColumnModel().getColumn(2).setPreferredWidth(190);
        actionTable.getColumnModel().getColumn(3).setPreferredWidth(190);
        actionTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = actionTable.rowAtPoint(e.getPoint()), col = actionTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col >= 2) assignAction(Controls.Act.values()[row], col - 2);
            }
        });
        commandTable.setRowHeight(22);
        commandTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        commandTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        commandTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = commandTable.rowAtPoint(e.getPoint());
                if (row >= 0 && commandTable.columnAtPoint(e.getPoint()) == 1) assignCommand(commands.paths.get(row));
            }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Camera, tools and placing", new JScrollPane(actionTable));
        tabs.addTab("Menu commands", new JScrollPane(commandTable));

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(close, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 6));

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(860, 600));
        pack();
        setLocationRelativeTo(owner);
        refresh();
    }

    private Controls.Profile selected() {
        return (Controls.Profile) combo.getSelectedItem();
    }

    private void refresh() {
        updating = true;
        combo.removeAllItems();
        for (Controls.Profile p : Controls.profiles()) combo.addItem(p);
        combo.setSelectedItem(Controls.current());
        updating = false;
        Controls.Profile p = selected();
        rename.setEnabled(p != null && !p.builtIn);
        remove.setEnabled(p != null && !p.builtIn);
        commands.reload();
        actions.fireTableDataChanged();
        commands.fireTableDataChanged();
        status.setText(p == null ? " " : p.builtIn
            ? (p.active ? "New is built in and cannot be changed. Make a profile of your own with New... to change controls."
                : p.name + " is the editor's own controls, untouched (its menu shortcuts are on the other tab). New... makes a profile of your own that starts as a copy of it.")
            : "Click a binding to change it. Changes apply at once.");
    }

    private void addProfile() {
        Controls.Profile from = selected();
        String name = JOptionPane.showInputDialog(this, "Name of the new profile (a copy of " + from.name + "):", "New profile", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        Controls.Profile p = Controls.add(name, from);
        if (p == null) {
            JOptionPane.showMessageDialog(this, "That name is empty or taken.", "New profile", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Controls.use(p);
        refresh();
    }

    private void renameProfile() {
        Controls.Profile p = selected();
        if (p == null || p.builtIn) return;
        String name = JOptionPane.showInputDialog(this, "New name:", p.name);
        if (name == null) return;
        if (!Controls.rename(p, name)) JOptionPane.showMessageDialog(this, "That name is empty or taken.", "Rename", JOptionPane.WARNING_MESSAGE);
        refresh();
    }

    private void removeProfile() {
        Controls.Profile p = selected();
        if (p == null || p.builtIn) return;
        if (JOptionPane.showConfirmDialog(this, "Delete the profile " + p.name + "?", "Delete profile", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Controls.remove(p);
        refresh();
    }

    private boolean editable() {
        Controls.Profile p = selected();
        if (p != null && !p.builtIn) return true;
        JOptionPane.showMessageDialog(this, "The built-in profiles cannot be changed.\nUse New... to make a profile of your own, then change that.", "Editor controls", JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    // ---- assigning ----

    private void assignAction(Controls.Act act, int slot) {
        if (!editable()) return;
        Controls.Profile p = selected();
        Controls.Binding b = capture(act);
        if (b == null) return;
        if (b == REMOVE) {
            List<Controls.Binding> list = p.of(act);
            if (slot < list.size()) list.remove(slot);
        } else {
            String took = null;
            for (Controls.Act other : Controls.Act.values()) {
                if (other.kind == act.kind && other != act && p.of(other).remove(b)) took = other.title;
            }
            List<Controls.Binding> list = p.of(act);
            list.remove(b);
            if (slot < list.size()) list.set(slot, b);
            else list.add(b);
            status.setText(took == null ? "Assigned " + b.text() + "." : "Assigned " + b.text() + " (taken from \"" + took + "\").");
        }
        Controls.save();
        Controls.changed();
        actions.fireTableDataChanged();
    }

    private void assignCommand(String path) {
        if (!editable()) return;
        Controls.Profile p = selected();
        KeyStroke k = captureKey(path);
        if (k == CLEAR) {
            p.accel.put(path, null);
        } else if (k == RESET) {
            p.accel.remove(path);
        } else if (k != null) {
            for (String other : commands.paths) {
                if (!other.equals(path) && k.equals(commands.shortcut(p, other))) p.accel.put(other, null);
            }
            p.accel.put(path, k);
        } else {
            return;
        }
        Controls.save();
        Controls.changed();
        commands.fireTableDataChanged();
    }

    private static final KeyStroke CLEAR = KeyStroke.getKeyStroke(KeyEvent.VK_UNDEFINED, 0);
    private static final KeyStroke RESET = KeyStroke.getKeyStroke(KeyEvent.VK_UNDEFINED, InputEvent.SHIFT_DOWN_MASK);

    /** Asks for the binding of an action: a key (with modifiers), a mouse button (with modifiers) or the wheel; REMOVE means "remove the binding". */
    private Controls.Binding capture(Controls.Act act) {
        final Controls.Binding[] result = {null};
        final JDialog d = new JDialog(this, act.title, true);
        String what = act.kind == Controls.Kind.KEY ? "Press the key (with Ctrl, Shift or Alt held if you want them)."
            : act.kind == Controls.Kind.MOUSE ? "Click here with the mouse button wanted (with Ctrl, Shift or Alt held if you want them)."
            : "Turn the mouse wheel here (with Ctrl, Shift or Alt held if you want them).";
        final JLabel area = new JLabel("<html><center>" + act.title + "<br><br>" + what + "</center></html>", JLabel.CENTER);
        area.setOpaque(true);
        area.setBackground(new java.awt.Color(235, 235, 245));
        area.setBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY));
        area.setPreferredSize(new Dimension(420, 130));
        area.setFocusable(true);
        final int[] heldMod = {0};
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (act.kind != Controls.Kind.KEY) return;
                int c = e.getKeyCode();
                if (isModifier(c)) {
                    heldMod[0] = c;
                    return;
                }
                heldMod[0] = 0;
                result[0] = new Controls.Binding(Controls.Kind.KEY, c, e.getModifiersEx());
                d.dispose();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                // a modifier pressed and let go on its own is itself the binding (hold Shift to move fast)
                if (act.kind == Controls.Kind.KEY && heldMod[0] == e.getKeyCode() && isModifier(e.getKeyCode())) {
                    result[0] = new Controls.Binding(Controls.Kind.KEY, e.getKeyCode(), 0);
                    d.dispose();
                }
            }
        });
        area.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                area.requestFocusInWindow();
                if (act.kind != Controls.Kind.MOUSE) return;
                result[0] = new Controls.Binding(Controls.Kind.MOUSE, e.getButton(), e.getModifiersEx());
                d.dispose();
            }
        });
        area.addMouseWheelListener((MouseWheelEvent e) -> {
            if (act.kind != Controls.Kind.WHEEL) return;
            result[0] = new Controls.Binding(Controls.Kind.WHEEL, 0, e.getModifiersEx());
            d.dispose();
        });
        JButton clear = new JButton("Remove this binding");
        clear.addActionListener(e -> {
            result[0] = REMOVE;
            d.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(clear);
        buttons.add(cancel);
        d.setLayout(new BorderLayout(6, 6));
        d.add(area, BorderLayout.CENTER);
        d.add(buttons, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(this);
        SwingUtilities.invokeLater(area::requestFocusInWindow);
        d.setVisible(true);
        return result[0];
    }

    /** What "Remove this binding" answers. */
    private static final Controls.Binding REMOVE = new Controls.Binding(Controls.Kind.KEY, -1, 0);

    private static boolean isModifier(int c) {
        return c == KeyEvent.VK_SHIFT || c == KeyEvent.VK_CONTROL || c == KeyEvent.VK_ALT || c == KeyEvent.VK_META || c == KeyEvent.VK_ALT_GRAPH;
    }

    private KeyStroke captureKey(String path) {
        final KeyStroke[] result = {null};
        final JDialog d = new JDialog(this, path, true);
        final JLabel area = new JLabel("<html><center>" + path + "<br><br>Press the shortcut wanted.</center></html>", JLabel.CENTER);
        area.setOpaque(true);
        area.setBackground(new java.awt.Color(235, 235, 245));
        area.setBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY));
        area.setPreferredSize(new Dimension(420, 120));
        area.setFocusable(true);
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (isModifier(e.getKeyCode())) return;
                result[0] = KeyStroke.getKeyStroke(e.getKeyCode(), e.getModifiersEx() & Controls.MASK);
                d.dispose();
            }
        });
        area.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                area.requestFocusInWindow();
            }
        });
        JButton reset = new JButton("The editor's own");
        reset.addActionListener(e -> {
            result[0] = RESET;
            d.dispose();
        });
        JButton clear = new JButton("No shortcut");
        clear.addActionListener(e -> {
            result[0] = CLEAR;
            d.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(reset);
        buttons.add(clear);
        buttons.add(cancel);
        d.setLayout(new BorderLayout(6, 6));
        d.add(area, BorderLayout.CENTER);
        d.add(buttons, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(this);
        SwingUtilities.invokeLater(area::requestFocusInWindow);
        d.setVisible(true);
        return result[0];
    }

    // ---- the tables ----

    private final class ActionModel extends AbstractTableModel {
        private final String[] cols = {"Group", "Action", "Binding", "Alternative"};

        @Override
        public int getRowCount() {
            return Controls.Act.values().length;
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            Controls.Act a = Controls.Act.values()[row];
            Controls.Profile p = selected();
            if (col == 0) return a.group;
            if (col == 1) return a.title;
            if (p == null) return "";
            List<Controls.Binding> l = p.of(a);
            int i = col - 2;
            return i < l.size() ? l.get(i).text() : "";
        }
    }

    private final class MenuModel extends AbstractTableModel {
        List<String> paths = new java.util.ArrayList<>();

        void reload() {
            paths = new java.util.ArrayList<>(menus.items().keySet());
        }

        KeyStroke shortcut(Controls.Profile p, String path) {
            if (p != null && p.active && p.accel.containsKey(path)) return p.accel.get(path);
            return menus.original(path);
        }

        @Override
        public int getRowCount() {
            return paths.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Menu command" : "Shortcut";
        }

        @Override
        public Object getValueAt(int row, int col) {
            String path = paths.get(row);
            if (col == 0) return path.replace("/", "  >  ");
            KeyStroke k = shortcut(selected(), path);
            if (k == null) return "";
            String m = InputEvent.getModifiersExText(k.getModifiers());
            String key = KeyEvent.getKeyText(k.getKeyCode());
            return m.isEmpty() ? key : m + "+" + key;
        }
    }
}
