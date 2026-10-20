package dev.skmods.editor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The window of the Tools menu: which tools the tool bar shows (tick them), and in what order (Move up, Move down). A change is made at once; the choice
 * is kept for the next time. Below, the signals the game sends by itself (what the Tags and Lint tools do not report as sent by nothing).
 */
final class ToolsDialog extends JDialog {
    private final Map<String, JToggleButton> buttons;
    private final List<String> order = new ArrayList<>();
    private final Set<String> visible = new LinkedHashSet<>();
    private final JTable table;
    private final AbstractTableModel model;
    private final JTextField signals = new JTextField(ServerSignals.patterns().isEmpty() ? "" : String.join(", ", ServerSignals.patterns()), 22);

    ToolsDialog(Frame owner, Map<String, JToggleButton> buttons) {
        super(owner, "Tools", false);
        this.buttons = buttons;
        order.addAll(ToolLayout.order(new ArrayList<>(buttons.keySet())));
        for (String id : order) if (ToolLayout.visible(id)) visible.add(id);
        visible.add("arrow");
        model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return order.size();
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public String getColumnName(int c) {
                return c == 0 ? "Show" : "Tool";
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : Object.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 0 && !order.get(r).equals("arrow");
            }

            @Override
            public Object getValueAt(int r, int c) {
                return c == 0 ? (Object) visible.contains(order.get(r)) : order.get(r);
            }

            @Override
            public void setValueAt(Object v, int r, int c) {
                if (c != 0) return;
                if ((Boolean) v) visible.add(order.get(r));
                else visible.remove(order.get(r));
                apply();
            }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(26);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                String id = (String) value;
                JToggleButton b = buttons.get(id);
                super.getTableCellRendererComponent(t, b != null && b.getText() != null && !b.getText().isEmpty() ? b.getText() : id, sel, focus, row, col);
                Icon icon = b == null ? null : b.getIcon();
                setIcon(icon);
                return this;
            }
        });

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttonsRow.add(button("Move up", () -> move(-1)));
        buttonsRow.add(button("Move down", () -> move(1)));
        buttonsRow.add(button("Show all", () -> {
            visible.addAll(order);
            apply();
        }));
        buttonsRow.add(button("Default", () -> {
            ToolLayout.reset();
            reload();
        }));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(300, 330));
        JLabel hint = new JLabel("<html><body style='width:280px'>Tick the tools the tool bar shows. The order here is the order there. Select is always shown.</body></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel top = new JPanel(new BorderLayout());
        top.add(hint, BorderLayout.NORTH);
        top.add(buttonsRow, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(4, 2));
        bottom.setBorder(BorderFactory.createTitledBorder("Signals the game sends by itself"));
        bottom.add(new JLabel("<html><body style='width:280px'>Patterns, comma separated, <b>*</b> for anything (for example capture:*). Tags and Lint do not say that nothing sends these.</body></html>"), BorderLayout.NORTH);
        JPanel field = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        field.add(signals);
        field.add(button("Apply", this::applySignals));
        bottom.add(field, BorderLayout.CENTER);

        getContentPane().setLayout(new BorderLayout(4, 4));
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setMargin(new java.awt.Insets(1, 8, 1, 8));
        b.addActionListener(ev -> r.run());
        return b;
    }

    private void move(int by) {
        int i = table.getSelectedRow();
        if (i < 0) return;
        int j = i + by;
        if (j < 0 || j >= order.size()) return;
        String id = order.remove(i);
        order.add(j, id);
        apply();
        table.setRowSelectionInterval(j, j);
    }

    private void apply() {
        ToolLayout.set(order, visible);
        model.fireTableDataChanged();
    }

    private void reload() {
        order.clear();
        order.addAll(ToolLayout.order(new ArrayList<>(buttons.keySet())));
        visible.clear();
        for (String id : order) if (ToolLayout.visible(id)) visible.add(id);
        model.fireTableDataChanged();
    }

    private void applySignals() {
        List<String> list = new ArrayList<>();
        for (String s : signals.getText().split(",")) if (!s.isBlank()) list.add(s.trim());
        ServerSignals.set(list);
    }
}
