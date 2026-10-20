package dev.skmods.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The explanation at the top of each extra tool: a line that opens and closes it ("How it works"). It is one setting for all the tools: opening or
 * closing the explanation in one opens or closes it in every one, and the editor remembers which it was.
 */
final class Explainer extends JPanel {
    private static final String NODE = "dev/skmods/crucible-controls";
    private static final List<WeakReference<Explainer>> ALL = new ArrayList<>();
    private static boolean open = load();

    private final JLabel header = new JLabel();
    private final JLabel body;

    Explainer(String html) {
        super(new BorderLayout());
        body = new JLabel("<html><body style='width:320px'>" + html + "</body></html>");
        body.setBorder(BorderFactory.createEmptyBorder(2, 14, 4, 4));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        header.setForeground(new Color(60, 90, 160));
        header.setToolTipText("Show or hide the explanation (in all the tools)");
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        synchronized (ALL) {
            ALL.add(new WeakReference<>(this));
        }
        refresh();
    }

    private void refresh() {
        header.setText((open ? "▾ " : "▸ ") + "How it works");
        body.setVisible(open);
        revalidate();
        repaint();
    }

    /** Opens the explanation of every tool, or closes it. */
    static void toggle() {
        open = !open;
        try {
            ModConfig.settings().putBoolean("explainersOpen", open);
        } catch (RuntimeException e) {
            // kept for this session
        }
        synchronized (ALL) {
            ALL.removeIf(w -> w.get() == null);
            for (WeakReference<Explainer> w : ALL) {
                Explainer x = w.get();
                if (x != null) x.refresh();
            }
        }
    }

    private static boolean load() {
        try {
            return ModConfig.settings().getBoolean("explainersOpen", true);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
