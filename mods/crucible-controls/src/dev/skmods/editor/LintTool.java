package dev.skmods.editor;

import com.threerings.tudey.data.TudeySceneModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The "Lint" tool: the checks the editor's own validation (the Errors tool) does not make. A signal sent to a tag nothing has; a thing that reacts to a
 * signal nobody sends; identical props at one place; areas and paths with no points; configurations that are missing; props over no floor. Each is an
 * error, a warning or a note, with <b>Go</b> and <b>Select</b> (which selects everything the line is about, such as all the stacked props).
 */
final class LintTool extends PanelTool {
    private final JPanel list = new JPanel();
    private final JLabel status = new JLabel("Press Check.");
    private final JCheckBox errors = new JCheckBox("Errors", true), warnings = new JCheckBox("Warnings", true), notes = new JCheckBox("Notes", true);
    private List<SceneTools.Issue> issues = new ArrayList<>();

    LintTool(com.threerings.tudey.tools.SceneEditor editor, Host host) {
        super(editor, host, "Lint", "Lint: more checks than the editor's validation (signals that lead nowhere, stacked props, empty shapes ...)", Colors.LINT,
            "Finds problems that the Errors tool misses: signals sent to a tag nothing has, things waiting for a signal nothing sends, identical props at one place, empty areas or paths, "
+ "missing configurations, and props with no floor tile beneath them. Signals the game sends itself (such as capture:0) and props the game does not draw (such as markers and triggers) are ignored. "
+ "<b>Go</b> moves the camera to the item, <b>Select</b> selects it, and <b>Show</b> outlines it in <font color='" + Colors.css(Colors.LINT) + "'><b>amber</b></font>.");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(button("Check", this::check, "Read the whole scene and list what looks wrong"));
        top.add(errors);
        top.add(warnings);
        top.add(notes);
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        add(north, BorderLayout.NORTH);
        add(scrolling(list, 340, 210), BorderLayout.CENTER);
        errors.addActionListener(ev -> render());
        warnings.addActionListener(ev -> render());
        notes.addActionListener(ev -> render());
    }

    @Override
    public void activate() {
        super.activate();
        SwingUtilities.invokeLater(this::check);
    }

    private void check() {
        TudeySceneModel scene = scene();
        if (scene == null) return;
        try {
            issues = SceneTools.lint(scene, cfg());
        } catch (RuntimeException ex) {
            issues = new ArrayList<>();
            say(status, "The check failed: " + ex, true);
            ex.printStackTrace();
            return;
        }
        render();
    }

    private void render() {
        list.removeAll();
        int e = 0, w = 0, n = 0, shown = 0;
        for (SceneTools.Issue i : issues) {
            switch (i.severity) {
                case ERROR:
                    e++;
                    if (!errors.isSelected()) continue;
                    break;
                case WARNING:
                    w++;
                    if (!warnings.isSelected()) continue;
                    break;
                default:
                    n++;
                    if (!notes.isSelected()) continue;
                    break;
            }
            shown++;
            String color = i.severity == SceneTools.Severity.ERROR ? "#b40000" : i.severity == SceneTools.Severity.WARNING ? "#b46400" : "#555555";
            String word = i.severity == SceneTools.Severity.ERROR ? "Error" : i.severity == SceneTools.Severity.WARNING ? "Warning" : "Note";
            final SceneTools.Issue issue = i;
            List<javax.swing.JComponent> buttons = new ArrayList<>();
            if (i.entry != null) buttons.add(button("Go", () -> goTo(issue.entry), "Move the camera there and mark it"));
            if (i.related != null && !i.related.isEmpty()) {
                buttons.add(button(i.related.size() > 1 ? "Select " + i.related.size() : "Select", () -> select(issue.related), "Select what the line is about"));
                if (i.related.size() > 1) buttons.add(button("Show", () -> mark(issue.related, 8f), "Mark them in the scene"));
            }
            list.add(row("<font color='" + color + "'><b>" + word + "</b></font> " + escape(i.text), 225, buttons.toArray(new javax.swing.JComponent[0])));
        }
        say(status, issues.isEmpty() ? "Nothing found." : e + " error" + (e == 1 ? "" : "s") + ", " + w + " warning" + (w == 1 ? "" : "s") + ", " + n + " note" + (n == 1 ? "" : "s"), e + w > 0);
        list.revalidate();
        list.repaint();
    }
}
