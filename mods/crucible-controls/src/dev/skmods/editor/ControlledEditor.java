package dev.skmods.editor;

import com.threerings.opengl.GlCanvas;
import com.threerings.tudey.tools.EditorTool;
import java.awt.Component;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.JTextComponent;

/**
 * The game's scene editor ({@code projectx.tools.h}) with controls you can assign. A <b>Controls</b> menu next to File, Edit and View opens the
 * preferences window ({@link ControlsDialog}); what each profile binds is in {@link Controls}. Under the built-in profiles <b>Legacy</b> and <b>Modern</b> (the editor's own controls, with its "modern controls" preference off or on) this class
 * changes nothing: the editor's own camera, mouse and shortcuts. Under <b>New</b> (the default):
 * <ul>
 * <li>Left click: use the tool. <b>Ctrl + right click</b> (hold to drag): erase / the secondary action. A plain right click does nothing.</li>
 * <li><b>Turn the camera</b> on the spot: <b>Ctrl + left drag</b>, <b>middle button drag</b> or the <b>arrow keys</b> (with Shift: swing around the point it looks at). <b>W A S D</b> move it
 * (hold Shift for fast).</li>
 * <li><b>Mouse wheel</b>: zoom in and out (no modifier).</li>
 * <li>Orbit: Shift + middle drag, or Z / C. Q / E, Alt + wheel and Shift + wheel rotate what you are placing (the stamp); with Shift (Shift + wheel, Shift + Q / E) by 5 degrees.</li>
 * <li>Page Up / Page Down raise and lower the whole grid (the arrow keys' old job); Ctrl + Up / Down raise and lower what is selected.</li>
 * </ul>
 * The names of the methods overridden here ({@code b, c, aeX, aeY, afb, afc} and the mouse fields) are the game's obfuscated ones and change
 * with an update. Only the Knight Launcher mod for the Crucible editor uses this class; the full editor of launch-editor.bat has its own.
 */
public abstract class ControlledEditor extends com.threerings.projectx.tools.SceneEditorImpl {
    /** The keys held that mean something to the camera or the stamp, with what they mean. */
    private final Map<Integer, Controls.Act> down = new HashMap<>();
    private boolean announced;
    private final Mouse mouse = new Mouse();
    private final Cam cam = new Cam();
    /** What the mouse button now held does (a camera drag, a stroke of the tool), and which button it is. */
    private Controls.Act held;
    private int heldButton;
    /** The game's own camera listeners, taken off the canvas under New and put back under Legacy and Modern. */
    private final List<MouseMotionListener> removedMotion = new ArrayList<>();
    private final List<MouseWheelListener> removedWheel = new ArrayList<>();
    private boolean restored = true;

    protected ControlledEditor(String scene) {
        super(scene);
        Controls.onChange(() -> SwingUtilities.invokeLater(this::syncMenus));
    }

    private static boolean on() {
        return Controls.current().active;
    }

    /** The primary action: the tool. */
    @Override
    public boolean isMainAction(MouseEvent e) {
        if (!on()) return super.isMainAction(e);
        return !isAdjustingView() && Controls.mouseAct(e) == Controls.Act.USE_TOOL;
    }

    /** The secondary action (erase, in the brushes and definers). */
    @Override
    public boolean isDeleteAction(MouseEvent e) {
        if (!on()) return super.isDeleteAction(e);
        return !isAdjustingView() && Controls.mouseAct(e) == Controls.Act.ERASE;
    }

    /** Primary button held (dragging a stroke). */
    @Override
    public boolean isMainButtonDown() {
        return on() ? held == Controls.Act.USE_TOOL : super.isMainButtonDown();
    }

    /** Secondary stroke held. */
    @Override
    public boolean isDeleteButtonDown() {
        return on() ? held == Controls.Act.ERASE : super.isDeleteButtonDown();
    }

    /** "The camera has the mouse": only while turning it, so the tools hide their cursor and do nothing. */
    @Override
    public boolean isCameraDown() {
        return on() ? cameraDrag(held) : super.isCameraDown();
    }

    private static boolean cameraDrag(Controls.Act a) {
        return a == Controls.Act.TURN_CAMERA || a == Controls.Act.ORBIT_CAMERA || a == Controls.Act.PAN_CAMERA || a == Controls.Act.ZOOM_DRAG;
    }

    /** The tools are switched off (and what is held hides) while the profile's suspend control is held: the right button in New, Alt in Legacy, Meta in Modern. */
    @Override
    public boolean isGridAdjustDown() {
        return on() ? held == Controls.Act.SUSPEND_MOUSE || down.containsValue(Controls.Act.SUSPEND_KEY) : super.isGridAdjustDown();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!on() || typing()) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            Controls.Act act = Controls.keyAct(e);
            if (act != null) {
                boolean first = !down.containsKey(code);
                down.put(code, act);
                switch (act) {
                    case STAMP_LEFT: if (first) rotateStamp(-1, false); break;
                    case STAMP_RIGHT: if (first) rotateStamp(1, false); break;
                    case STAMP_LEFT_FINE: if (first) rotateStamp(-1, true); break;
                    case STAMP_RIGHT_FINE: if (first) rotateStamp(1, true); break;
                    default: break;
                }
                // a modifier key still goes on to the editor, which keeps its own count of them
                if (!isModifier(code)) {
                    e.consume();
                    return true;
                }
            } else if (menuShortcut(e)) {
                e.consume();
                return true;
            }
        } else if (e.getID() == KeyEvent.KEY_RELEASED) {
            if (down.remove(code) != null && !isModifier(code)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isModifier(int c) {
        return c == KeyEvent.VK_SHIFT || c == KeyEvent.VK_CONTROL || c == KeyEvent.VK_ALT || c == KeyEvent.VK_META;
    }

    /** True when a key belongs to a text box or the editor is not the active window. */
    private boolean typing() {
        Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f == null) return true;
        if (f instanceof JTextComponent) return true;
        Window w = SwingUtilities.getWindowAncestor(f);
        return w != _frame;
    }

    @Override
    protected void didInit() {
        super.didInit();
        _newKeys = Controls.isModern();
        PathCopier.install(); // the "Path" buttons of the config editors
        syncCamera();
        SwingUtilities.invokeLater(this::syncMenus);
    }

    private int frames;

    @Override
    protected void updateView(float dt) {
        // the editor's "modern controls" preference is the profile's business now: Modern turns it on, everything else off
        _newKeys = Controls.isModern();
        syncCamera();
        if (++frames % 60 == 0) SwingUtilities.invokeLater(this::syncMenus);
        if (on() && !down.isEmpty() && !typing() && cam.ok()) {
            boolean fast = down.containsValue(Controls.Act.MOVE_FAST);
            float speed = Math.max(3f, cam.distance() * 0.9f) * (fast ? 3f : 1f) * dt;
            float x = count(Controls.Act.MOVE_RIGHT) - count(Controls.Act.MOVE_LEFT);
            float y = count(Controls.Act.MOVE_FORWARD) - count(Controls.Act.MOVE_BACK);
            float yaw = count(Controls.Act.TURN_LEFT) - count(Controls.Act.TURN_RIGHT);
            float pitch = count(Controls.Act.TURN_DOWN) - count(Controls.Act.TURN_UP);
            if (yaw != 0f || pitch != 0f) cam.look(yaw * 1.6f * dt, pitch * 1.6f * dt);
            float oyaw = count(Controls.Act.ORBIT_LEFT) - count(Controls.Act.ORBIT_RIGHT);
            float opitch = count(Controls.Act.ORBIT_DOWN) - count(Controls.Act.ORBIT_UP);
            if (oyaw != 0f || opitch != 0f) cam.orbit(oyaw * 1.6f * dt, opitch * 1.6f * dt);
            if (x != 0f || y != 0f) cam.pan(x * speed, y * speed);
        } else if (!down.isEmpty() && typing()) {
            down.clear();
        }
        super.updateView(dt);
    }

    private float count(Controls.Act a) {
        return down.containsValue(a) ? 1f : 0f;
    }

    // ---- the Errors tool ----

    /** The Errors tool; set while the editor builds its tool bar (before this class's own fields are set up, so it has no initializer). */
    private ErrorsTool errors;
    private WireTool wire;
    /** The extra tools of the third row (set while the tool bar is built, so no initializer). */
    private java.util.List<PanelTool> panelTools;
    /** The tool bar, and its buttons by the name of the tool (set when the tool bar is complete). */
    private javax.swing.JComponent toolbarComponent;
    private java.util.List<String> toolNames;
    private java.util.Map<String, javax.swing.JToggleButton> toolButtons;
    private javax.swing.JLabel selectionLabel;
    private JMenu toolsMenu;
    private ToolsDialog toolsDialog;

    /** The tool bar: the editor's own tools, with the Wire tool after the notepad (the end of the first row) and the Errors tool after the last of them (the eraser). */
    @Override
    protected void addTool(javax.swing.JComponent toolbar, javax.swing.ButtonGroup group, String name, EditorTool tool) {
        record(name);
        // the Notes tool without the block of camera marks
        if ("notepad".equals(name) && tool.getClass() == com.threerings.tudey.tools.Notepad.class) tool = new HiddenMarksNotepad(this);
        explain(name, tool);
        super.addTool(toolbar, group, name, tool);
        if ("notepad".equals(name) && wire == null) {
            wire = new WireTool(this, new WireTool.Host() {
                @Override
                public com.threerings.tudey.data.TudeySceneModel scene() {
                    return _scene;
                }

                @Override
                public void moveCameraTo(float x, float y, float z) {
                    cam.moveTo(x, y, z);
                }

                @Override
                public void apply(WireModel.Change change) {
                    // one undo step for the whole change: the prop that sends, the one that receives, the relay that goes
                    _undoSupport.beginUpdate();
                    try {
                        if (!change.added.isEmpty()) ControlledEditor.this.handleEntrys(change.added.toArray(new com.threerings.tudey.data.TudeySceneModel.Entry[0]));
                        if (!change.updated.isEmpty()) ControlledEditor.this.updateEntries(change.updated.toArray(new com.threerings.tudey.data.TudeySceneModel.Entry[0]));
                        if (!change.removed.isEmpty()) ControlledEditor.this.removeEntries(change.removed.toArray());
                    } finally {
                        _undoSupport.endUpdate();
                    }
                }
            });
            record("wire");
            explain("wire", wire);
            super.addTool(toolbar, group, "wire", wire);
        }
        if ("eraser".equals(name) && errors == null) {
            errors = new ErrorsTool(this, new ErrorsTool.Host() {
                @Override
                public java.util.List<String> validationErrors() {
                    com.samskivert.util.Tuple<Boolean, java.util.List<String>> r = handleActionEvent();
                    return r.right == null ? new ArrayList<String>() : new ArrayList<String>(r.right);
                }

                @Override
                public com.threerings.tudey.data.TudeySceneModel scene() {
                    return _scene;
                }

                @Override
                public void moveCameraTo(float x, float y, float z) {
                    cam.moveTo(x, y, z);
                }
            });
            record("errors");
            explain("errors", errors);
            super.addTool(toolbar, group, "errors", errors);
            // the tools of the third row: Find, Tags, Lint, Stats, Marks, Measure, Replace
            panelTools = new java.util.ArrayList<>();
            PanelTool.Host panel = panelHost();
            addPanel(toolbar, group, "find", new FindTool(this, panel));
            addPanel(toolbar, group, "tags", new TagsTool(this, panel));
            addPanel(toolbar, group, "lint", new LintTool(this, panel));
            addPanel(toolbar, group, "stats", new StatsTool(this, panel));
            addPanel(toolbar, group, "marks", new BookmarksTool(this, panel));
            addPanel(toolbar, group, "measure", new MeasureTool(this, panel));
            addPanel(toolbar, group, "replace", new ReplaceTool(this, panel));
            captureToolbar(toolbar);
        }
    }

    /** The collapsible "How it works" at the top of a tool that has none of its own. */
    private static void explain(String name, EditorTool tool) {
        String text = ToolHelp.of(name);
        if (text == null || tool instanceof PanelTool) return;
        Explainer x = new Explainer(text);
        java.awt.LayoutManager layout = tool.getLayout();
        if (layout instanceof com.samskivert.swing.GroupLayout) {
            tool.add(x, com.samskivert.swing.GroupLayout.FIXED, 0); // fixed: the tool's layout would stretch it over the whole panel
        } else {
            // any other layout: what the tool has goes under the explanation, as it was
            javax.swing.JPanel inner = new javax.swing.JPanel(layout);
            for (Component c : tool.getComponents()) {
                Object constraint = layout instanceof java.awt.BorderLayout ? ((java.awt.BorderLayout) layout).getConstraints(c) : null;
                tool.remove(c);
                inner.add(c, constraint);
            }
            tool.setLayout(new java.awt.BorderLayout());
            tool.add(x, java.awt.BorderLayout.NORTH);
            tool.add(inner, java.awt.BorderLayout.CENTER);
        }
    }

    private void record(String name) {
        if (toolNames == null) toolNames = new ArrayList<>();
        toolNames.add(name);
    }

    /** The tool bar is complete: its buttons are kept by tool, and it is arranged as the Tools menu says. */
    private void captureToolbar(javax.swing.JComponent toolbar) {
        toolbarComponent = toolbar;
        toolButtons = new java.util.LinkedHashMap<>();
        int i = 0;
        for (Component c : toolbar.getComponents()) {
            if (c instanceof javax.swing.JToggleButton && i < toolNames.size()) toolButtons.put(toolNames.get(i++), (javax.swing.JToggleButton) c);
        }
        ToolLayout.onChange(() -> SwingUtilities.invokeLater(this::arrangeToolbar));
        arrangeToolbar();
    }

    /** Puts the buttons in the tool bar in the order of the Tools menu, the ones that are not shown left out (and the empty slot the editor leaves after Notes gone). */
    private void arrangeToolbar() {
        if (toolbarComponent == null || toolButtons == null) return;
        toolbarComponent.removeAll();
        for (String id : ToolLayout.order(new ArrayList<>(toolButtons.keySet()))) {
            if (ToolLayout.visible(id)) toolbarComponent.add(toolButtons.get(id));
        }
        toolbarComponent.revalidate();
        toolbarComponent.repaint();
        java.awt.Container up = toolbarComponent.getParent();
        if (up != null) {
            up.revalidate();
            up.repaint();
        }
    }

    private void addPanel(javax.swing.JComponent toolbar, javax.swing.ButtonGroup group, String name, PanelTool tool) {
        panelTools.add(tool);
        record(name);
        super.addTool(toolbar, group, name, tool);
    }

    /** What the extra tools ask of the editor. */
    private PanelTool.Host panelHost() {
        return new PanelTool.Host() {
            @Override
            public com.threerings.tudey.data.TudeySceneModel scene() {
                return _scene;
            }

            @Override
            public void moveCameraTo(float x, float y, float z) {
                cam.moveTo(x, y, z);
            }

            @Override
            public void apply(WireModel.Change change) {
                // one undo step for the whole change
                _undoSupport.beginUpdate();
                try {
                    if (!change.added.isEmpty()) ControlledEditor.this.handleEntrys(change.added.toArray(new com.threerings.tudey.data.TudeySceneModel.Entry[0]));
                    if (!change.updated.isEmpty()) ControlledEditor.this.updateEntries(change.updated.toArray(new com.threerings.tudey.data.TudeySceneModel.Entry[0]));
                    if (!change.removed.isEmpty()) ControlledEditor.this.removeEntries(change.removed.toArray());
                } finally {
                    _undoSupport.endUpdate();
                }
            }

            @Override
            public float[] cameraState() {
                return cam.state();
            }

            @Override
            public void setCameraState(float[] state) {
                cam.restore(state);
            }

            @Override
            public float[] cameraAxes() {
                return cam.axes();
            }

            @Override
            public java.io.File sceneFile() {
                return _file;
            }

            @Override
            public void select(com.threerings.tudey.data.TudeySceneModel.Entry... entries) {
                if (entries.length == 1) {
                    ControlledEditor.this.select(entries[0]); // the Select tool, with the thing to edit
                } else if (entries.length > 1) {
                    ControlledEditor.this.setActiveTool(_arrow); // the Select tool (not the Region), which moves what is selected
                    ControlledEditor.this.setSelection(entries);
                }
            }

            @Override
            public String notes() {
                return _scene == null || _scene.getNotes() == null ? "" : _scene.getNotes();
            }

            @Override
            public void setNotes(String notes) {
                if (_scene == null) return;
                _scene.setNotes(notes);
                EditorTool pad = (EditorTool) _tools.get("notepad"); // what the Notes tool shows must be what the scene has, or it would write its old text back
                if (pad != null) pad.sceneChanged(_scene);
            }

            @Override
            public com.threerings.tudey.data.TudeySceneModel.Entry[] selection() {
                return getSelection();
            }
        };
    }

    /** The Errors tool's icon is drawn, and so are those of the extra tools; everything else is the editor's. */
    @Override
    protected javax.swing.ImageIcon createIcon(String name) {
        javax.swing.ImageIcon extra = ToolIcons.of(name);
        if (extra != null) return extra;
        if ("errors".equals(name)) return ErrorsTool.icon();
        if ("wire".equals(name)) return WireTool.icon();
        return super.createIcon(name);
    }

    /** The red mark of the Errors tool shows whichever tool is chosen. */
    @Override
    protected void compositeView() {
        super.compositeView();
        if (errors != null) errors.compositeHighlight();
        if (wire != null) wire.compositeHighlight();
        if (panelTools != null) for (PanelTool t : panelTools) t.compositeHighlight();
    }

    /** What is selected is counted in the menu bar (the editor outlines it in the scene itself). */
    @Override
    public void setSelection(com.threerings.tudey.data.TudeySceneModel.Entry... entries) {
        super.setSelection(entries);
        if (selectionLabel != null) {
            final String text = describeSelection(entries);
            SwingUtilities.invokeLater(() -> selectionLabel.setText(text));
        }
    }

    private static String describeSelection(com.threerings.tudey.data.TudeySceneModel.Entry[] entries) {
        if (entries == null || entries.length == 0) return " nothing selected ";
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (com.threerings.tudey.data.TudeySceneModel.Entry e : entries) kinds.merge(SceneTools.kindOf(e), 1, Integer::sum);
        StringBuilder sb = new StringBuilder(" " + entries.length + " selected (");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> en : kinds.entrySet()) {
            sb.append(first ? "" : ", ").append(en.getValue()).append(' ').append(en.getKey()).append(en.getValue() == 1 ? "" : "s");
            first = false;
        }
        return sb.append(") ").toString();
    }

    // ---- the menus: the Controls menu, and the shortcuts of the profile ----

    private JMenu controlsMenu;
    private javax.swing.JCheckBoxMenuItem pathItem;
    private ControlsDialog dialog;
    private final Map<String, KeyStroke> originals = new HashMap<>();
    private final Map<String, JMenuItem> items = new LinkedHashMap<>();

    private JFrame frame() {
        try {
            Window w = SwingUtilities.getWindowAncestor((Component) getCanvas());
            return w instanceof JFrame ? (JFrame) w : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** On the Swing thread: puts the Controls menu in the bar (after View), finds the editor's commands and gives them the shortcuts of the profile in use. */
    private void syncMenus() {
        JFrame f = frame();
        if (f == null || f.getJMenuBar() == null) return;
        JMenuBar bar = f.getJMenuBar();
        if (controlsMenu == null) {
            controlsMenu = new JMenu("Controls");
            controlsMenu.addMenuListener(new MenuListener() {
                @Override
                public void menuSelected(MenuEvent e) {
                    // a click on the name itself opens the window (there is nothing to drop down)
                    SwingUtilities.invokeLater(() -> {
                        javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                        openDialog();
                    });
                }

                @Override
                public void menuDeselected(MenuEvent e) {
                }

                @Override
                public void menuCanceled(MenuEvent e) {
                }
            });
        }
        if (bar.getComponentIndex(controlsMenu) < 0) {
            int at = bar.getMenuCount();
            for (int i = 0; i < bar.getMenuCount(); i++) {
                JMenu m = bar.getMenu(i);
                if (m != null && "View".equals(m.getText())) at = i + 1;
            }
            bar.add(controlsMenu, at);
            bar.revalidate();
            bar.repaint();
        }
        if (toolsMenu == null) {
            toolsMenu = new JMenu("Tools");
            toolsMenu.addMenuListener(new MenuListener() {
                @Override
                public void menuSelected(MenuEvent e) {
                    // a click on the name itself opens the window (there is nothing to drop down)
                    SwingUtilities.invokeLater(() -> {
                        javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                        openToolsDialog();
                    });
                }

                @Override
                public void menuDeselected(MenuEvent e) {
                }

                @Override
                public void menuCanceled(MenuEvent e) {
                }
            });
        }
        if (bar.getComponentIndex(toolsMenu) < 0) {
            bar.add(toolsMenu, Math.min(bar.getMenuCount(), bar.getComponentIndex(controlsMenu) + 1));
            bar.revalidate();
            bar.repaint();
        }
        if (selectionLabel == null) {
            selectionLabel = new javax.swing.JLabel(describeSelection(getSelection()));
            selectionLabel.setForeground(Colors.awt(Colors.SELECTED).darker());
            selectionLabel.setToolTipText("What is selected");
        }
        if (selectionLabel.getParent() != bar) {
            bar.add(javax.swing.Box.createHorizontalGlue());
            bar.add(selectionLabel);
            bar.revalidate();
        }
        if (pathItem == null) {
            pathItem = new javax.swing.JCheckBoxMenuItem("Path Buttons", PathCopier.shown());
            pathItem.setToolTipText("Show a Path button on every property of the config editors (copies the property's path)");
            pathItem.addActionListener(ev -> PathCopier.setShown(pathItem.isSelected()));
        }
        if (pathItem.getParent() == null) {
            for (int i = 0; i < bar.getMenuCount(); i++) {
                JMenu m = bar.getMenu(i);
                if (m != null && "View".equals(m.getText())) {
                    m.addSeparator();
                    m.add(pathItem);
                    break;
                }
            }
        }
        items.clear();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu m = bar.getMenu(i);
            if (m != null && m != controlsMenu && m != toolsMenu) scan(m, m.getText());
        }
        Controls.Profile p = Controls.current();
        for (Map.Entry<String, JMenuItem> en : items.entrySet()) {
            KeyStroke want = p.active && p.accel.containsKey(en.getKey()) ? p.accel.get(en.getKey()) : originals.get(en.getKey());
            if (!Objects.equals(en.getValue().getAccelerator(), want)) en.getValue().setAccelerator(want);
        }
    }

    private void scan(JMenu menu, String path) {
        for (Component c : menu.getMenuComponents()) {
            if (c instanceof JMenu) {
                scan((JMenu) c, path + "/" + ((JMenu) c).getText());
            } else if (c == pathItem) {
                continue; // ours, not a command of the editor
            } else if (c instanceof JMenuItem && ((JMenuItem) c).getText() != null && !((JMenuItem) c).getText().isEmpty()) {
                String p = path + "/" + ((JMenuItem) c).getText();
                if (items.containsKey(p)) continue;
                items.put(p, (JMenuItem) c);
                if (!originals.containsKey(p)) originals.put(p, ((JMenuItem) c).getAccelerator());
            }
        }
    }

    private void openToolsDialog() {
        if (toolButtons == null) return;
        if (toolsDialog != null) {
            if (toolsDialog.isShowing()) {
                toolsDialog.toFront();
                return;
            }
            toolsDialog.dispose(); // closed with the window's button: made again
        }
        toolsDialog = new ToolsDialog(frame(), toolButtons);
        toolsDialog.setVisible(true);
    }

    private void openDialog() {
        if (dialog != null) {
            if (dialog.isShowing()) {
                dialog.toFront();
                return;
            }
            dialog.dispose();
        }
        JFrame f = frame();
        syncMenus();
        dialog = new ControlsDialog(f, new ControlsDialog.Menus() {
            @Override
            public Map<String, JMenuItem> items() {
                return items;
            }

            @Override
            public KeyStroke original(String path) {
                return originals.get(path);
            }
        });
        dialog.setVisible(true);
    }

    /**
     * A key that is a menu shortcut of the profile's own (one it changed, or the selection's Raise / Lower): does the command. Raise and Lower are
     * only allowed while something is selected, so the window's title says why a press did nothing. Shortcuts the profile left as the editor has
     * them are the editor's.
     */
    private boolean menuShortcut(KeyEvent e) {
        Controls.Profile p = Controls.current();
        int mods = e.getModifiersEx() & Controls.MASK;
        for (Map.Entry<String, JMenuItem> en : items.entrySet()) {
            JMenuItem item = en.getValue();
            KeyStroke k = item.getAccelerator();
            if (k == null || k.getKeyCode() != e.getKeyCode() || (k.getModifiers() & Controls.MASK) != mods) continue;
            String text = item.getText();
            boolean raise = "Raise".equals(text) || "Lower".equals(text);
            if (!raise && !p.accel.containsKey(en.getKey())) continue;
            if (item.isEnabled()) {
                item.doClick(0);
                if (raise) hint("Selection " + text.toLowerCase() + "ed");
            } else if (raise) {
                System.err.println("[editor] " + text + ": the editor has nothing selected");
                hint("Nothing is selected to " + text.toLowerCase() + ": select an object first (Select tool)");
            }
            return true;
        }
        return false;
    }

    private String plainTitle;
    private javax.swing.Timer titleTimer;

    /** Says something in the editor's window title for a few seconds. */
    private void hint(String text) {
        final JFrame f = frame();
        if (f == null) return;
        if (plainTitle == null) plainTitle = f.getTitle();
        f.setTitle(plainTitle + "   -   " + text);
        if (titleTimer != null) titleTimer.stop();
        titleTimer = new javax.swing.Timer(3000, ev -> {
            f.setTitle(plainTitle);
            plainTitle = null;
        });
        titleTimer.setRepeats(false);
        titleTimer.start();
    }

    /** One notch of the wheel for the tool: the stamp turns a step (with {@code fine}, Shift held, the small step: 5 degrees). */
    private void rotateStamp(int dir, boolean fine) {
        if (_activeTool == null) return;
        Component c = (Component) getCanvas();
        _activeTool.mouseWheelMoved(new MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), fine ? InputEvent.SHIFT_DOWN_MASK : 0, 0, 0, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, dir));
    }

    // ---- the camera listeners ----

    private void syncCamera() {
        if (on()) {
            restored = false;
            takeOverCamera();
        } else if (!restored) {
            restoreCamera();
        }
    }

    /** Legacy, Modern: the game's own camera listeners back, ours off. */
    private void restoreCamera() {
        restored = true;
        held = null;
        down.clear();
        try {
            Component awt = awtCanvas();
            if (awt == null) return;
            awt.removeMouseListener(mouse);
            awt.removeMouseMotionListener(mouse);
            awt.removeMouseWheelListener(mouse);
            for (MouseMotionListener l : removedMotion) awt.addMouseMotionListener(l);
            for (MouseWheelListener l : removedWheel) awt.addMouseWheelListener(l);
            removedMotion.clear();
            removedWheel.clear();
            announced = false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[editor] could not restore the editor's own camera controls: " + e);
        }
    }

    private Component awtCanvas() throws ReflectiveOperationException {
        Field f = GlCanvas.class.getDeclaredField("_awtCanvas");
        f.setAccessible(true);
        return (Component) f.get(getCanvas());
    }

    /**
     * The game's camera controller (class {@code tudey.tools.o}) wants a modifier for everything and orbits on the left button: take it
     * off the canvas, keep the tools from seeing the wheel (it zooms now), and put {@link Mouse} on instead. Tools add their listeners
     * whenever one is selected, so this looks again on every frame.
     */
    private void takeOverCamera() {
        try {
            Component awt = awtCanvas();
            if (awt == null) return;
            boolean have = false;
            for (MouseMotionListener l : awt.getMouseMotionListeners()) {
                if (l == mouse) {
                    have = true;
                } else if (l.getClass().getName().equals("com.threerings.tudey.tools.o")) {
                    awt.removeMouseMotionListener(l);
                    if (!removedMotion.contains(l)) removedMotion.add(l);
                }
            }
            for (MouseWheelListener l : awt.getMouseWheelListeners()) {
                if (l == mouse) continue;
                if (l.getClass().getName().equals("com.threerings.tudey.tools.o") || l instanceof EditorTool) {
                    awt.removeMouseWheelListener(l);
                    if (!removedWheel.contains(l)) removedWheel.add(l);
                }
            }
            if (!have) {
                awt.addMouseListener(mouse);
                awt.addMouseMotionListener(mouse);
                awt.addMouseWheelListener(mouse);
                if (!announced) {
                    announced = true;
                    System.err.println("[editor] controls: profile " + Controls.current().name + " (Controls menu: assign them)");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!announced) {
                announced = true;
                System.err.println("[editor] could not take over the camera controls: " + e);
            }
        }
    }

    /**
     * The game's orbit camera ({@code opengl.a.d}), called by reflection: {@code com.threerings.opengl.a} is both a class and a package
     * in the obfuscated jar, so javac cannot spell the camera's type name.
     */
    private final class Cam {
        private java.lang.reflect.Method panXY, orbit, zoom, getCoords, getTarget, getEye, isAdded, updatePosition;
        private Field distance;
        private Class<?> type;

        boolean ok() {
            Object h = _camhand;
            if (h == null) return false;
            try {
                if (type == null) {
                    type = dev.skmods.emu.Game.cls("com.threerings.opengl.a.Camhand");
                    panXY = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#panXY"), float.class, float.class);
                    orbit = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#orbit"), float.class, float.class);
                    zoom = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#zoom"), float.class);
                    getCoords = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#getCoords"));
                    getTarget = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#getTarget"));
                    getEye = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#getViewerTranslation"));
                    isAdded = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#isAdded"));
                    updatePosition = type.getMethod(dev.skmods.emu.Game.methodName("com.threerings.opengl.a.Camhand#updatePosition"));
                    distance = getCoords.getReturnType().getField("distance");
                }
                return type.isInstance(h);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        float distance() {
            try {
                return distance.getFloat(getCoords.invoke(_camhand));
            } catch (ReflectiveOperationException e) {
                return 10f;
            }
        }

        void pan(float x, float y) {
            call(panXY, x, y);
        }

        void orbit(float az, float el) {
            call(orbit, az, el);
        }

        /**
         * Turns the camera on the spot, as a head turns: the game's camera hangs on a point it looks at, so turning it with orbit swings it around
         * that point; here the point moves the other way so the camera itself stays where it is.
         */
        void look(float az, float el) {
            try {
                Object target = getTarget.invoke(_camhand);
                float x = (Float) target.getClass().getField("x").get(target), y = (Float) target.getClass().getField("y").get(target), z = (Float) target.getClass().getField("z").get(target);
                float[] before = eye();
                orbit.invoke(_camhand, az, el);
                float[] after = eye();
                setTarget(target, x + before[0] - after[0], y + before[1] - after[1], z + before[2] - after[2]);
            } catch (ReflectiveOperationException e) {
                call(orbit, az, el); // at least it turns
            }
        }

        /**
         * The directions the camera's right and up point in, in the world: the camera's own rotation turns its right (+x) and up (+y) to them. It is the same looking
         * straight down as from the side; null when it cannot be told.
         */
        float[] axes() {
            if (!ok()) return null;
            try {
                Object q = type.getMethod("getViewerRotation").invoke(_camhand);
                Class<?> c = q.getClass();
                float qx = (Float) c.getField("x").get(q), qy = (Float) c.getField("y").get(q), qz = (Float) c.getField("z").get(q), qw = (Float) c.getField("w").get(q);
                float[] r = rotate(qx, qy, qz, qw, 1f, 0f, 0f), u = rotate(qx, qy, qz, qw, 0f, 1f, 0f);
                return new float[] {r[0], r[1], r[2], u[0], u[1], u[2]};
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return null;
            }
        }

        private float[] rotate(float qx, float qy, float qz, float qw, float vx, float vy, float vz) {
            // v + w t + q x t, with t = 2 (q x v)
            float tx = 2f * (qy * vz - qz * vy), ty = 2f * (qz * vx - qx * vz), tz = 2f * (qx * vy - qy * vx);
            return new float[] {vx + qw * tx + (qy * tz - qz * ty), vy + qw * ty + (qz * tx - qx * tz), vz + qw * tz + (qx * ty - qy * tx)};
        }

        /** Where the camera stands (the game's own answer). */
        private float[] eye() throws ReflectiveOperationException {
            Object v = getEye.invoke(_camhand);
            Class<?> c = v.getClass();
            return new float[]{(Float) c.getField("x").get(v), (Float) c.getField("y").get(v), (Float) c.getField("z").get(v)};
        }

        private void setTarget(Object target, float x, float y, float z) throws ReflectiveOperationException {
            target.getClass().getField("x").set(target, x);
            target.getClass().getField("y").set(target, y);
            target.getClass().getField("z").set(target, z);
        }

        void zoom(float d) {
            call(zoom, d);
        }

        /** Where the camera is: the point it looks at, then azimuth, elevation and distance; null when it cannot be told. */
        float[] state() {
            if (!ok()) return null;
            try {
                Object t = getTarget.invoke(_camhand), c = getCoords.invoke(_camhand);
                return new float[] {field(t, "x"), field(t, "y"), field(t, "z"), field(c, "azimuth"), field(c, "elevation"), field(c, "distance")};
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        /** Puts the camera as {@link #state} said. */
        void restore(float[] s) {
            if (!ok() || s == null || s.length < 6) return;
            try {
                setTarget(getTarget.invoke(_camhand), s[0], s[1], s[2]);
                Object c = getCoords.invoke(_camhand);
                c.getClass().getField("azimuth").set(c, s[3]);
                c.getClass().getField("elevation").set(c, s[4]);
                c.getClass().getField("distance").set(c, s[5]);
                refresh();
            } catch (ReflectiveOperationException e) {
                System.err.println("[editor] could not put the camera back: " + e);
            }
        }

        private float field(Object o, String name) throws ReflectiveOperationException {
            return (Float) o.getClass().getField(name).get(o);
        }

        /** The camera looks at this point now (keeping its direction; a camera too far away comes in to a distance where a tile can be seen). */
        void moveTo(float x, float y, float z) {
            if (!ok()) return;
            try {
                setTarget(getTarget.invoke(_camhand), x, y, z);
                float d = distance();
                if (d > 24f) {
                    zoom(24f - d);
                    if (distance() > d) zoom(2f * (d - 24f)); // zoom takes the other sign than assumed
                }
                refresh();
            } catch (ReflectiveOperationException e) {
                System.err.println("[editor] could not move the camera: " + e);
            }
        }

        void refresh() {
            try {
                if ((Boolean) isAdded.invoke(_camhand)) updatePosition.invoke(_camhand);
            } catch (ReflectiveOperationException e) {
                // the next frame updates it anyway
            }
        }

        private void call(java.lang.reflect.Method m, Object... a) {
            try {
                m.invoke(_camhand, a);
            } catch (ReflectiveOperationException e) {
                // ignored: the camera simply does not move
            }
        }
    }

    /** The mouse of the profile in use: turning and swinging the camera (drags), zoom and the stamp (the wheel). */
    private final class Mouse implements MouseListener, MouseMotionListener, MouseWheelListener {
        private int lx, ly;

        @Override
        public void mouseDragged(MouseEvent e) {
            int dx = e.getX() - lx, dy = e.getY() - ly;
            lx = e.getX();
            ly = e.getY();
            if (!cam.ok()) return;
            if (held == Controls.Act.TURN_CAMERA) cam.look(-dx * 0.0031415927f, dy * 0.0031415927f);
            else if (held == Controls.Act.ORBIT_CAMERA) cam.orbit(-dx * 0.0031415927f, dy * 0.0031415927f);
            else if (held == Controls.Act.PAN_CAMERA) cam.pan(-dx * 0.02f, dy * 0.02f);
            else if (held == Controls.Act.ZOOM_DRAG) cam.zoom(dy * 0.02f);
            else return;
            cam.refresh();
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            lx = e.getX();
            ly = e.getY();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            lx = e.getX();
            ly = e.getY();
            if (held == null) {
                held = Controls.mouseAct(e);
                heldButton = e.getButton();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (held != null && e.getButton() == heldButton) held = null;
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            Controls.Act act = Controls.wheelAct(e);
            if (act == Controls.Act.ZOOM) {
                if (cam.ok()) {
                    cam.zoom(e.getWheelRotation() * Math.max(0.3f, cam.distance() * 0.06f));
                    cam.refresh();
                }
            } else if (act == Controls.Act.GRID_WHEEL && _grid != null) {
                _grid.setElevation(_grid.getElevation() + e.getWheelRotation());
            } else if ((act == Controls.Act.STAMP_WHEEL || act == Controls.Act.STAMP_WHEEL_FINE) && _activeTool != null) {
                // the tool's own wheel: it turns what is being placed (Shift: by 5 degrees, as in the game's own editor)
                _activeTool.mouseWheelMoved(new MouseWheelEvent((Component) e.getSource(), e.getID(), e.getWhen(), act == Controls.Act.STAMP_WHEEL_FINE ? InputEvent.SHIFT_DOWN_MASK : 0,
                    e.getX(), e.getY(), e.getClickCount(), false, e.getScrollType(), e.getScrollAmount(), e.getWheelRotation()));
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
        }

        @Override
        public void mouseEntered(MouseEvent e) {
        }

        @Override
        public void mouseExited(MouseEvent e) {
        }
    }
}
