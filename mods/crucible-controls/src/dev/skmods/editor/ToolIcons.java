package dev.skmods.editor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/** The tool bar icons of the extra tools. The editor's own come from its resources; these are drawn, 24 by 24, in the same flat style. */
final class ToolIcons {
    private ToolIcons() {
    }

    /** The icon of a tool by the name it is added to the tool bar with, or null when it is not one of these. */
    static ImageIcon of(String name) {
        switch (name) {
            case "find": return draw(ToolIcons::find);
            case "tags": return draw(ToolIcons::tags);
            case "lint": return draw(ToolIcons::lint);
            case "stats": return draw(ToolIcons::stats);
            case "marks": return draw(ToolIcons::marks);
            case "measure": return draw(ToolIcons::measure);
            case "replace": return draw(ToolIcons::replace);
            default: return null;
        }
    }

    private static ImageIcon draw(java.util.function.Consumer<Graphics2D> painter) {
        BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        painter.accept(g);
        g.dispose();
        return new ImageIcon(img);
    }

    /** a magnifying glass */
    private static void find(Graphics2D g) {
        g.setColor(new Color(220, 238, 252));
        g.fill(new Ellipse2D.Float(3, 3, 12, 12));
        g.setColor(new Color(40, 110, 190));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Float(3, 3, 12, 12));
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(14, 14, 21, 21);
    }

    /** a price tag */
    private static void tags(Graphics2D g) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(4, 12);
        p.lineTo(12, 3.5);
        p.lineTo(20.5, 3.5);
        p.lineTo(20.5, 12);
        p.lineTo(12, 20.5);
        p.closePath();
        g.setColor(new Color(110, 200, 120));
        g.fill(p);
        g.setColor(new Color(30, 110, 50));
        g.draw(p);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(15, 6, 3.6f, 3.6f));
    }

    /** a shield with a check mark */
    private static void lint(Graphics2D g) {
        Path2D.Float s = new Path2D.Float();
        s.moveTo(12, 2.5);
        s.lineTo(20.5, 5.5);
        s.lineTo(20.5, 12);
        s.curveTo(20.5, 17, 16.5, 20, 12, 22);
        s.curveTo(7.5, 20, 3.5, 17, 3.5, 12);
        s.lineTo(3.5, 5.5);
        s.closePath();
        g.setColor(new Color(255, 205, 70));
        g.fill(s);
        g.setColor(new Color(170, 110, 0));
        g.draw(s);
        g.setColor(new Color(60, 70, 0));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(8, 12, 11, 15);
        g.drawLine(11, 15, 17, 8);
    }

    /** bars of a chart */
    private static void stats(Graphics2D g) {
        int[] heights = {8, 14, 11, 18};
        Color[] colors = {new Color(150, 120, 240), new Color(110, 90, 220), new Color(170, 140, 250), new Color(90, 70, 200)};
        for (int i = 0; i < 4; i++) {
            g.setColor(colors[i]);
            g.fill(new RoundRectangle2D.Float(3 + i * 5, 21 - heights[i], 4, heights[i], 2, 2));
        }
        g.setColor(new Color(70, 60, 130));
        g.drawLine(2, 21, 22, 21);
    }

    /** a bookmark */
    private static void marks(Graphics2D g) {
        Path2D.Float b = new Path2D.Float();
        b.moveTo(6, 2.5);
        b.lineTo(18, 2.5);
        b.lineTo(18, 21.5);
        b.lineTo(12, 16.5);
        b.lineTo(6, 21.5);
        b.closePath();
        g.setColor(new Color(240, 100, 170));
        g.fill(b);
        g.setColor(new Color(150, 30, 90));
        g.draw(b);
    }

    /** a ruler across the icon */
    private static void measure(Graphics2D g) {
        java.awt.geom.AffineTransform saved = g.getTransform();
        g.rotate(-Math.PI / 4, 12, 12);
        g.setColor(new Color(255, 225, 90));
        g.fill(new RoundRectangle2D.Float(1, 8, 22, 8, 2, 2));
        g.setColor(new Color(150, 110, 0));
        g.draw(new RoundRectangle2D.Float(1, 8, 22, 8, 2, 2));
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 6; i++) g.drawLine(4 + i * 3, 8, 4 + i * 3, i % 2 == 0 ? 13 : 11);
        g.setTransform(saved);
    }

    /** two arrows that swap */
    private static void replace(Graphics2D g) {
        g.setColor(new Color(235, 100, 60));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(4, 8, 19, 8);
        g.drawLine(15, 4, 19, 8);
        g.drawLine(15, 12, 19, 8);
        g.setColor(new Color(60, 130, 210));
        g.drawLine(20, 16, 5, 16);
        g.drawLine(9, 12, 5, 16);
        g.drawLine(9, 20, 5, 16);
    }
}
