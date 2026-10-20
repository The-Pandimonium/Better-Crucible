package dev.skmods.editor;

import com.threerings.editor.swing.BasePropertyEditor;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.HierarchyEvent;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * "Path" buttons for the config editor: every property editor of a configuration, at every level of nesting, gets a small button in its header row
 * beside the collapse/expand button (a property with no header, such as a number, gets it beside its label). Pressing it copies that property's path
 * the way a Direct parameter wants it ({@code implementation.hit_weight}, {@code implementation.cascades[0].behavior},
 * {@code implementation.target_action.attacks}) to the clipboard; hovering shows the path. The names are the editor's own (it builds the same path
 * for its own parameter dialog), only the leading dot it puts in front is dropped. Ctrl + Shift + C copies the path of the field that has the
 * keyboard focus (or, with none, the property under the mouse pointer).
 */
final class PathCopier {
    private PathCopier() {
    }

    private static final String MARK = "emu.pathcopier";
    private static boolean installed;
    private static final String BUTTON = "emu.pathbutton";
    private static Boolean shown;

    /** Whether the Path buttons are shown (View > Path Buttons); off until switched on, and remembered. */
    static synchronized boolean shown() {
        if (shown == null) {
            try {
                shown = ModConfig.settings().getBoolean("pathButtons", false);
            } catch (RuntimeException e) {
                shown = false;
            }
        }
        return shown;
    }

    static void setShown(boolean on) {
        synchronized (PathCopier.class) {
            shown = on;
        }
        try {
            ModConfig.settings().putBoolean("pathButtons", on);
        } catch (RuntimeException e) {
            System.err.println("[editor] could not save the Path buttons setting: " + e);
        }
        for (Window w : Window.getWindows()) {
            for (Component k : all(w)) {
                if (k instanceof JButton && ((JButton) k).getClientProperty(BUTTON) != null) {
                    k.setVisible(on);
                    if (k.getParent() != null) {
                        k.getParent().revalidate();
                        k.getParent().repaint();
                    }
                }
            }
        }
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e instanceof HierarchyEvent && e.getID() == HierarchyEvent.HIERARCHY_CHANGED) {
                HierarchyEvent h = (HierarchyEvent) e;
                if ((h.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0 && h.getComponent() instanceof BasePropertyEditor && h.getComponent().getParent() != null) {
                    final BasePropertyEditor be = (BasePropertyEditor) h.getComponent();
                    SwingUtilities.invokeLater(() -> decorate(be));
                }
            } else if (e instanceof KeyEvent && e.getID() == KeyEvent.KEY_PRESSED) {
                KeyEvent k = (KeyEvent) e;
                if (k.getKeyCode() == KeyEvent.VK_C && k.isControlDown() && k.isShiftDown() && !k.isAltDown()) {
                    k.consume();
                    SwingUtilities.invokeLater(() -> copy(null));
                }
            }
        }, AWTEvent.HIERARCHY_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        // editors that are already up (this may be installed after the editor started)
        SwingUtilities.invokeLater(() -> {
            for (Window w : Window.getWindows()) for (Component k : all(w)) if (k instanceof BasePropertyEditor) decorate((BasePropertyEditor) k);
        });
    }

    /**
     * Puts a small "Path" button on a property editor: in its header row beside the collapse/expand and highlight buttons when it has one (objects,
     * lists, strings, references), else right after its label (numbers, flags, choices). Every level of a config gets one.
     */
    private static void decorate(final BasePropertyEditor be) {
        if (be.getClientProperty(MARK) != null || be.getClass().getSimpleName().equals("EditorPanel")) return;
        Container host = null;
        int at = -1;
        for (Component ch : be.getComponents()) {
            if (!(ch instanceof javax.swing.JPanel)) continue;
            // the header row: the panel that holds the collapse button and its companions (all of them buttons)
            Component[] kids = ((Container) ch).getComponents();
            boolean allButtons = kids.length > 0;
            for (Component g : kids) if (!(g instanceof JButton)) allButtons = false;
            if (allButtons) {
                host = (Container) ch;
                break;
            }
        }
        if (host == null && be.getLayout() instanceof java.awt.FlowLayout) {
            host = be;
            at = 0;
            for (int k = 0; k < be.getComponentCount(); k++) if (be.getComponent(k) instanceof javax.swing.JLabel) {
                at = k + 1;
                break;
            }
        }
        if (host == null) return;
        be.putClientProperty(MARK, Boolean.TRUE);
        final JButton b = new JButton("Path") {
            @Override
            public String getToolTipText() {
                String path = pathOf(this);
                return path == null ? "no path for this property" : "Copy the path of this property: " + path;
            }
        };
        b.setToolTipText("path");
        b.putClientProperty(BUTTON, Boolean.TRUE);
        b.setVisible(shown());
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(0, 3, 0, 3));
        b.setFont(b.getFont().deriveFont(Math.max(9f, b.getFont().getSize2D() - 3f)));
        // sized to its label ("Path", or "Copied" while it says so) so the text is never cut off
        java.awt.FontMetrics fm = b.getFontMetrics(b.getFont());
        java.awt.Dimension size = new java.awt.Dimension(fm.stringWidth("Copied") + 12, Math.max(18, fm.getHeight() + 4));
        b.setPreferredSize(size);
        b.setMinimumSize(size);
        b.addActionListener(ev -> copyFrom(b));
        if (at >= 0) host.add(b, at);
        else host.add(b);
        host.revalidate();
        host.repaint();
    }

    private static java.util.List<Component> all(Container c) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (Component k : c.getComponents()) {
            out.add(k);
            if (k instanceof Container) out.addAll(all((Container) k));
        }
        return out;
    }

    /** The editor that holds the property control (the outermost one: the panel of the whole config), or null. */
    private static BasePropertyEditor rootOf(Component c) {
        BasePropertyEditor top = null;
        for (Container q = c instanceof Container ? (Container) c : c.getParent(); q != null; q = q.getParent()) {
            if (q instanceof BasePropertyEditor) top = (BasePropertyEditor) q;
        }
        if (top == null && c != null) for (Container q = c.getParent(); q != null; q = q.getParent()) if (q instanceof BasePropertyEditor) top = (BasePropertyEditor) q;
        return top;
    }

    /** The path of the property this component belongs to ("implementation.hit_weight"), or null when it is not inside a property editor. */
    static String pathOf(Component c) {
        if (c == null) return null;
        BasePropertyEditor top = rootOf(c);
        if (top == null) return null;
        String p = top.getComponentPath(c, false);
        if (p == null || p.isEmpty()) return null;
        return p.startsWith(".") ? p.substring(1) : p;
    }

    private static Component underPointer() {
        try {
            Point pt = MouseInfo.getPointerInfo().getLocation();
            for (Window w : Window.getWindows()) {
                if (!w.isShowing() || !w.getClass().getName().contains("ConfigEditor")) continue;
                Point at = new Point(pt.x - w.getLocationOnScreen().x, pt.y - w.getLocationOnScreen().y);
                if (at.x < 0 || at.y < 0 || at.x >= w.getWidth() || at.y >= w.getHeight()) continue;
                Component k = SwingUtilities.getDeepestComponentAt(w, at.x, at.y);
                if (k != null) return k;
            }
        } catch (RuntimeException ignored) {
            // no pointer (headless)
        }
        return null;
    }

    /** The button of one property editor was pressed: that editor's path. */
    static void copyFrom(final JButton b) {
        String path = pathOf(b);
        if (path == null) {
            b.setText("?");
        } else {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
            System.err.println("[editor] path copied: " + path);
            b.setText("Copied");
        }
        Timer t = new Timer(1500, ev -> b.setText("Path"));
        t.setRepeats(false);
        t.start();
        Container bar = b.getParent();
        if (bar != null) {
            bar.revalidate();
            bar.repaint();
        }
    }

    static void copy(AbstractButton shown) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        String path = pathOf(focus);
        if (path == null) path = pathOf(underPointer());
        final String text = path == null ? "no property: click a field first" : path;
        if (path != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
            System.err.println("[editor] path copied: " + path);
        }
        AbstractButton b = shown;
        if (b == null) {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                for (Component k : all(w)) if (k instanceof JButton && "Copy Path".equals(((JButton) k).getText())) b = (JButton) k;
            }
        }
        if (b != null) {
            final AbstractButton fb = b;
            fb.setText(path == null ? "Copy Path" : "Copied: " + path);
            fb.setToolTipText(text);
            Timer t = new Timer(5000, ev -> fb.setText("Copy Path"));
            t.setRepeats(false);
            t.start();
            Container bar = fb.getParent();
            if (bar != null) {
                bar.revalidate();
                bar.repaint();
            }
        }
    }
}
