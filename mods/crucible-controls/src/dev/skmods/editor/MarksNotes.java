package dev.skmods.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Camera marks kept inside the notes of a scene (the text of the editor's Notes tool, which is saved in the scene file and which the game ignores), as a block
 * at the end:
 *
 * <pre>
 * --- camera marks (made by the Marks tool; delete the block to forget them) ---
 * Boss room | 12.00 40.00 0.00 | 0.50 0.60 14.00
 * --- end of camera marks ---
 * </pre>
 *
 * So the marks travel with the scene file, and a scene with them is as good as one without: it is the same file with a longer note. What is written in
 * the notes above the block is left as it is.
 */
final class MarksNotes {
    private MarksNotes() {
    }

    static final String BEGIN = "--- camera marks (made by the Marks tool; delete the block to forget them) ---";
    static final String END = "--- end of camera marks ---";

    static final class Mark {
        String name;
        /** The point the camera looks at (x, y, z), then azimuth, elevation and distance. */
        float[] state;

        @Override
        public String toString() {
            return name + "  (" + Math.round(state[0]) + ", " + Math.round(state[1]) + ")";
        }
    }

    static List<Mark> parse(String notes) {
        List<Mark> out = new ArrayList<>();
        if (notes == null) return out;
        int b = notes.indexOf(BEGIN);
        if (b < 0) return out;
        int e = notes.indexOf(END, b);
        String body = notes.substring(b + BEGIN.length(), e < 0 ? notes.length() : e);
        for (String line : body.split("\\r?\\n")) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            try {
                String[] at = p[1].trim().split("\\s+"), angles = p[2].trim().split("\\s+");
                Mark m = new Mark();
                m.name = p[0].trim();
                m.state = new float[] {Float.parseFloat(at[0]), Float.parseFloat(at[1]), Float.parseFloat(at[2]), Float.parseFloat(angles[0]), Float.parseFloat(angles[1]), Float.parseFloat(angles[2])};
                out.add(m);
            } catch (RuntimeException ex) {
                // a line that is not a mark is skipped
            }
        }
        return out;
    }

    /** The notes as the designer wrote them: without the block of marks. */
    static String strip(String notes) {
        if (notes == null) return "";
        if (!notes.contains(BEGIN)) return notes;
        return write(notes, new ArrayList<>());
    }

    /** What the designer's text is saved as: the text, with the block of marks the notes now have put back after it. */
    static String merge(String shown, String current) {
        if (current == null || !current.contains(BEGIN)) return shown;
        return write(shown, parse(current));
    }

    /** The notes with the block of marks made again from these (taken out when there are none); the rest of the notes is untouched. */
    static String write(String notes, List<Mark> marks) {
        String text = notes == null ? "" : notes;
        int b = text.indexOf(BEGIN);
        String before = text, after = "";
        if (b >= 0) {
            int e = text.indexOf(END, b);
            before = text.substring(0, b);
            after = e < 0 ? "" : text.substring(e + END.length());
        }
        before = before.replaceAll("\\s+$", "");
        after = after.replaceAll("^\\s+", "");
        StringBuilder sb = new StringBuilder(before);
        if (!marks.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(BEGIN).append('\n');
            for (Mark m : marks) {
                sb.append(m.name.replace('|', '/').replace('\n', ' ')).append(" | ");
                sb.append(String.format(Locale.ROOT, "%.2f %.2f %.2f | %.3f %.3f %.2f", m.state[0], m.state[1], m.state[2], m.state[3], m.state[4], m.state[5])).append('\n');
            }
            sb.append(END);
        }
        if (!after.isEmpty()) sb.append(sb.length() > 0 ? "\n\n" : "").append(after);
        return sb.toString();
    }
}
