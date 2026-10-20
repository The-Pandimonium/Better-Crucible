package dev.skmods.editor;

import com.threerings.tudey.data.TudeySceneModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** The "Stats" tool: what the scene holds (how many of what, the layers, the size and heights of the floor, the tags, the monsters, the most used configurations), to read or copy. */
final class StatsTool extends PanelTool {
    private final JTextArea text = new JTextArea();

    StatsTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Stats", "Stats: what the scene holds: counts, layers, size, tags, monsters, the configurations used most", Colors.NONE,
            "A summary of the scene: how many things of each kind, the layers, the size and heights of the floor, tags, signals, monsters and the most used configurations. "
+ "<b>Refresh</b> counts again after you edit, and <b>Copy</b> puts the report on the clipboard.");
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(button("Refresh", this::refresh, "Count again"));
        top.add(button("Copy", () -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()), null), "Copy the report to the clipboard"));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new java.awt.Dimension(340, 230));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
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
            text.setText(String.join("\n", SceneTools.stats(scene, cfg())));
        } catch (RuntimeException ex) {
            text.setText("Counting failed: " + ex);
            ex.printStackTrace();
        }
        text.setCaretPosition(0);
    }
}
