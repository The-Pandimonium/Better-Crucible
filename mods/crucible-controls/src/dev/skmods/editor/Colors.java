package dev.skmods.editor;

/**
 * The colours the editor's extra tools draw in the scene. Every kind of thing has a colour of its own, so what is drawn can be told apart at a glance;
 * the explanation of each tool names its colour.
 */
final class Colors {
    private Colors() {
    }

    /** red: the place an error is about (Errors) */
    static final float[] ERROR = {1f, 0.05f, 0.05f};
    /** orange: the thing being wired (Wire), and the rays out of it */
    static final float[] WIRE_SOURCE = {1f, 0.55f, 0.05f};
    /** cyan-blue: the rays that come into a thing (Wire) */
    static final float[] WIRE_INCOMING = {0.2f, 0.8f, 1f};
    /** green: what can be wired to (Wire) */
    static final float[] WIRE_TARGET = {0.2f, 1f, 0.3f};
    /** blue: what is selected (Select and Region) */
    static final float[] SELECTED = {0.25f, 0.45f, 1f};
    /** magenta: what the region being dragged would select (Region) */
    static final float[] REGION_PREVIEW = {1f, 0.2f, 1f};
    /** aqua: the results of a search (Find) */
    static final float[] FIND = {0.1f, 0.9f, 0.85f};
    /** violet: the things that have a tag (Tags) */
    static final float[] TAGS = {0.65f, 0.4f, 1f};
    /** amber: what Lint is about */
    static final float[] LINT = {1f, 0.75f, 0.1f};
    /** white: the points and the line of a measurement (Measure) */
    static final float[] MEASURE = {1f, 1f, 1f};
    /** coral: what Replace would change */
    static final float[] REPLACE = {1f, 0.45f, 0.35f};
    /** grey-blue: the tool bar's own selected tool is the editor's; the camera (Marks) has no colour in the scene */
    static final float[] NONE = {0.6f, 0.6f, 0.6f};

    static java.awt.Color awt(float[] c) {
        return new java.awt.Color(c[0], c[1], c[2]);
    }

    /** the colour as a CSS hex for the explanations ("#1ae6d9"), darkened a little so it reads on a light panel */
    static String css(float[] c) {
        return String.format("#%02x%02x%02x", Math.round(c[0] * 200), Math.round(c[1] * 200), Math.round(c[2] * 200));
    }
}
