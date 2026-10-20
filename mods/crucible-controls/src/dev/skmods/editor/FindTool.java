package dev.skmods.editor;

import com.threerings.tudey.data.TudeySceneModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The "Find" tool: type what you look for (words of a configuration's name, a tag, a signal, a layer, a place such as "(12, 40)") and it lists every
 * thing of the scene that has all of them. The search runs a little at a time, so the editor stays alive while it looks through a large scene, and the
 * list is a plain list, so thousands of results cost nothing. What is chosen in the list is marked in the scene as it is chosen.
 */
final class FindTool extends PanelTool {
    private static final String[] KINDS = {"Everything", "Tiles", "Props", "Areas", "Paths", "Globals"};
    private static final String[] KEYS = {"", "tile", "prop", "area", "path", "global"};

    private final JTextField query = new JTextField(14);
    private final JComboBox<String> kind = new JComboBox<>(KINDS);
    private final DefaultListModel<SceneTools.Hit> model = new DefaultListModel<>();
    private final JList<SceneTools.Hit> list = new JList<>(model);
    private final JLabel status = new JLabel("Type what to look for and press Enter.");
    private SceneTools.Cache cache = new SceneTools.Cache();
    private SceneTools.Search search;
    private final Timer worker = new Timer(1, ev -> work());

    FindTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Find", "Find: search the scene by name, tag, signal or place; go to the results or select them", Colors.FIND,
            "Type one or more words and press Enter. The list shows every thing that matches <b>all</b> of the words in its kind, configuration, tags, signals, layer or place (for example \"door 12\"). "
+ "Pick a result to outline it in <font color='" + Colors.css(Colors.FIND) + "'><b>aqua</b></font>. <b>Go</b> moves the camera there. <b>Select</b> selects it, and <b>Select all</b> selects every result.");
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
                SceneTools.Hit h = (SceneTools.Hit) value;
                return super.getListCellRendererComponent(l, h.kind + ": " + h.text, index, sel, focus);
            }
        });
        list.setFixedCellHeight(18);
        list.addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) showChosen();
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) go();
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(query);
        top.add(kind);
        top.add(button("Find", this::start, "Look for the words in the scene"));
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bottom.add(button("Go", this::go, "Move the camera to the chosen result (or double click it)"));
        bottom.add(button("Select", () -> select(chosen()), "Select the chosen results in the editor"));
        bottom.add(button("Select all", () -> select(all()), "Select every result in the editor"));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(340, 200));
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        query.addActionListener(ev -> start());
        kind.addActionListener(ev -> start());
        worker.setRepeats(true);
    }

    @Override
    public void activate() {
        super.activate();
        cache = new SceneTools.Cache(); // the configurations may have been edited
        SwingUtilities.invokeLater(() -> {
            if (!query.getText().isBlank()) start();
            query.requestFocusInWindow();
        });
    }

    @Override
    public void deactivate() {
        super.deactivate();
        worker.stop();
    }

    @Override
    public void entryAdded(TudeySceneModel.Entry e) {
        cache = new SceneTools.Cache();
    }

    @Override
    public void entryUpdated(TudeySceneModel.Entry a, TudeySceneModel.Entry b) {
        cache = new SceneTools.Cache();
    }

    @Override
    public void entryRemoved(TudeySceneModel.Entry e) {
        cache = new SceneTools.Cache();
    }

    private List<TudeySceneModel.Entry> all() {
        List<TudeySceneModel.Entry> out = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) out.add(model.get(i).entry);
        return out;
    }

    private List<TudeySceneModel.Entry> chosen() {
        List<TudeySceneModel.Entry> out = new ArrayList<>();
        for (SceneTools.Hit h : list.getSelectedValuesList()) out.add(h.entry);
        return out;
    }

    private void go() {
        SceneTools.Hit h = list.getSelectedValue();
        if (h != null) goTo(h.entry);
    }

    private void showChosen() {
        List<TudeySceneModel.Entry> c = chosen();
        if (!c.isEmpty()) mark(c, 60f);
    }

    private void start() {
        TudeySceneModel scene = scene();
        if (scene == null) return;
        worker.stop();
        model.clear();
        search = new SceneTools.Search(scene, cfg(), query.getText(), KEYS[Math.max(0, kind.getSelectedIndex())], cache);
        say(status, "Looking ...", false);
        worker.start();
    }

    /** A few milliseconds of the search, then the window gets its turn. */
    private void work() {
        SceneTools.Search s = search;
        if (s == null) {
            worker.stop();
            return;
        }
        boolean done;
        try {
            done = s.step(6_000_000L);
        } catch (RuntimeException ex) {
            worker.stop();
            say(status, "The search failed: " + ex, true);
            ex.printStackTrace();
            return;
        }
        if (!done) {
            say(status, "Looking ... " + (100L * s.done() / Math.max(1, s.total())) + "%  (" + s.hits.size() + " so far)", false);
            return;
        }
        worker.stop();
        model.addAll(s.hits); // in one go: a large list is one event, not thousands
        say(status, s.hits.isEmpty() ? "Nothing found." : s.hits.size() + (s.hits.size() == 1 ? " result" : " results"), s.hits.isEmpty());
        if (!s.hits.isEmpty()) list.setSelectedIndex(0);
    }
}
