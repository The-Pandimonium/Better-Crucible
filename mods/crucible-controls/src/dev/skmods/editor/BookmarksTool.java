package dev.skmods.editor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * The "Marks" tool: camera bookmarks. The top line shows where the camera is now (the point it looks at, its angles and its distance). <b>Add</b> keeps that under a
 * name; <b>Go</b> (or a double click) brings the camera back; <b>Update</b> puts the camera's present place in the chosen mark. The marks are kept in the notes of
 * the scene ({@link MarksNotes}), so they are saved in the scene file with everything else, and the scene is as good as any other to upload.
 */
final class BookmarksTool extends PanelTool {
    private final DefaultListModel<MarksNotes.Mark> model = new DefaultListModel<>();
    private final JList<MarksNotes.Mark> list = new JList<>(model);
    private final JLabel camera = new JLabel("camera: ...");
    private final JLabel status = new JLabel(" ");
    private String lastShown = "";

    BookmarksTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Marks", "Marks: camera bookmarks, saved in the scene's notes", Colors.NONE,
            "Saves camera positions. The top line shows where the camera is now. <b>Add</b> saves it under a name, <b>Go</b> (or a double click) returns to it, "
+ "and <b>Update</b> replaces the chosen mark with the current position. Marks are stored in the scene's notes (hidden in the Notes tool), so save the scene to keep them.");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) go();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(button("Add", this::add, "Keep the camera as it is, under a name"));
        buttons.add(button("Go", this::go, "Bring the camera back to the chosen mark"));
        buttons.add(button("Update", this::update, "Put the camera's present place in the chosen mark"));
        buttons.add(button("Rename", this::rename, "Give the chosen mark another name"));
        buttons.add(button("Delete", this::delete, "Forget the chosen mark"));
        camera.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
        camera.setFont(camera.getFont().deriveFont(java.awt.Font.BOLD));
        JPanel north = new JPanel(new BorderLayout());
        north.add(camera, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(340, 170));
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    @Override
    public void activate() {
        super.activate();
        SwingUtilities.invokeLater(this::load);
    }

    @Override
    public void sceneChanged(com.threerings.tudey.data.TudeySceneModel scene) {
        super.sceneChanged(scene);
        SwingUtilities.invokeLater(this::load);
    }

    /** Every frame while the tool is chosen: where the camera is. */
    @Override
    public void tick(float elapsed) {
        float[] s = host.cameraState();
        String text = s == null ? "camera: not available" : format(s);
        if (!text.equals(lastShown)) {
            lastShown = text;
            SwingUtilities.invokeLater(() -> camera.setText(text));
        }
    }

    private static String format(float[] s) {
        return String.format(Locale.ROOT, "camera: looks at (%.1f, %.1f, %.1f)   angle %.0f° / %.0f°   distance %.1f", s[0], s[1], s[2], Math.toDegrees(s[3]), Math.toDegrees(s[4]), s[5]);
    }

    /** Reads the marks out of the scene's notes (and the file an earlier version kept them in, once). */
    private void load() {
        String notes = host.notes() == null ? "" : host.notes();
        List<MarksNotes.Mark> marks = MarksNotes.parse(notes);
        boolean fromFile = false;
        if (marks.isEmpty() && !notes.contains(MarksNotes.BEGIN)) {
            marks = legacy();
            fromFile = !marks.isEmpty();
        }
        model.clear();
        for (MarksNotes.Mark m : marks) model.addElement(m);
        if (fromFile) save();
    }

    /** The file next to the scene that held the marks before they were kept in the scene (read, not removed). */
    private List<MarksNotes.Mark> legacy() {
        List<MarksNotes.Mark> out = new ArrayList<>();
        File scene = host.sceneFile();
        if (scene == null) return out;
        String n = scene.getName();
        int dot = n.lastIndexOf('.');
        File f = new File(scene.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".bookmarks");
        if (!f.exists()) return out;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length < 7) continue;
                MarksNotes.Mark m = new MarksNotes.Mark();
                m.name = p[0];
                m.state = new float[6];
                for (int i = 0; i < 6; i++) m.state[i] = Float.parseFloat(p[i + 1]);
                out.add(m);
            }
        } catch (IOException | RuntimeException ex) {
            say(status, "Could not read the old file " + f.getName() + ": " + ex, true);
        }
        return out;
    }

    private void save() {
        List<MarksNotes.Mark> marks = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) marks.add(model.get(i));
        host.setNotes(MarksNotes.write(host.notes(), marks));
        say(status, "Saved in the scene's notes (save the scene to keep it).", false);
    }

    private void add() {
        float[] s = host.cameraState();
        if (s == null) {
            say(status, "The camera cannot be read.", true);
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name of the mark:", "Add mark", JOptionPane.QUESTION_MESSAGE, null, null, "Mark " + (model.size() + 1));
        if (name == null || name.toString().isBlank()) return;
        MarksNotes.Mark m = new MarksNotes.Mark();
        m.name = name.toString().trim();
        m.state = s;
        model.addElement(m);
        list.setSelectedIndex(model.size() - 1);
        save();
    }

    private void go() {
        MarksNotes.Mark m = list.getSelectedValue();
        if (m != null) host.setCameraState(m.state);
    }

    private void update() {
        MarksNotes.Mark m = list.getSelectedValue();
        float[] s = host.cameraState();
        if (m == null || s == null) return;
        m.state = s;
        list.repaint();
        save();
    }

    private void rename() {
        MarksNotes.Mark m = list.getSelectedValue();
        if (m == null) return;
        Object name = JOptionPane.showInputDialog(this, "New name:", "Rename mark", JOptionPane.QUESTION_MESSAGE, null, null, m.name);
        if (name == null || name.toString().isBlank()) return;
        m.name = name.toString().trim();
        list.repaint();
        save();
    }

    private void delete() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        model.remove(i);
        save();
    }
}
