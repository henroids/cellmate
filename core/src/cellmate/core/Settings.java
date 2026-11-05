package cellmate.core;

/** Everything that changes how an image is converted. Plain fields; copy() before handing to a worker. */
public final class Settings {
    public enum Style {
        BORDER("Border"), ROUNDED("Rounded"), JITTER("Jitter"), SOLID("Solid"),
        STITCH("Stitch"), BEADS("Beads"), MELTED("Melted");
        public final String label;
        Style(String label) { this.label = label; }
        /** Folder / suffix name, e.g. "melted". */
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** What kind of control a knob is drawn as. */
    public enum Kind { SLIDER, DIAL, BUTTON }

    /**
     * One adjustable value of a style. Only a few sensible steps each, so no setting can
     * swamp the picture; the default step is the look the styles had from the start.
     */
    public static final class Knob {
        public final String name;
        public final Kind kind;
        public final double[] values;
        public final int def;
        Knob(String name, Kind kind, int def, double... values) { this.name = name; this.kind = kind; this.def = def; this.values = values; }
        public int steps() { return values.length; }
    }

    // Every setting's default is the middle step, with a wide range either side of it. The
    // defaults are the looks the styles have always had.
    private static final double[] RING_OUTER = {0.30, 0.34, 0.38, 0.42, 0.46, 0.50, 0.54, 0.58, 0.62, 0.66, 0.70};   // past 0.5 beads fuse
    private static final double[] RING_HOLE = {0.02, 0.05, 0.08, 0.11, 0.14, 0.17, 0.20, 0.23, 0.26, 0.29, 0.32};   // kept smaller than the bead
    private static final double[] SOLID_SIZE = {0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7};   // past 1.0 squares overlap
    private static final double[] ROTATION = {-180, -150, -120, -90, -60, -30, 0, 30, 60, 90, 120, 150, 180};   // all the way round

    public static final Knob[][] KNOBS = {
        /* BORDER  */ {new Knob("Border size", Kind.SLIDER, 5, 0.004, 0.008, 0.015, 0.022, 0.03, 0.045, 0.06, 0.08, 0.1, 0.125, 0.15)},
        /* ROUNDED */ {new Knob("Roundness", Kind.SLIDER, 5, 0.02, 0.05, 0.08, 0.11, 0.14, 0.2, 0.26, 0.32, 0.38, 0.44, 0.5)},
        /* JITTER  */ {new Knob("Jitter", Kind.SLIDER, 5, 0.005, 0.01, 0.02, 0.03, 0.04, 0.05, 0.065, 0.08, 0.1, 0.12, 0.15)},
        /* SOLID   */ {new Knob("Light squares", Kind.SLIDER, 7, SOLID_SIZE), new Knob("Dark squares", Kind.SLIDER, 7, SOLID_SIZE)},
        /* STITCH  */ {new Knob("Thickness", Kind.SLIDER, 5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 7, 8, 10)},   // twenty-fourths of a cell
        /* BEADS   */ {new Knob("Outer circle", Kind.SLIDER, 5, RING_OUTER), new Knob("Inner circle", Kind.SLIDER, 5, RING_HOLE)},
        /* MELTED  */ {new Knob("Outer circle", Kind.SLIDER, 5, RING_OUTER), new Knob("Inner circle", Kind.SLIDER, 5, RING_HOLE),
                       new Knob("Rotation", Kind.SLIDER, 6, ROTATION),
                       new Knob("Meltiness", Kind.SLIDER, 5, 0.25, 0.4, 0.55, 0.7, 0.85, 1.0, 1.3, 1.6, 1.9, 2.2, 2.5)},
    };

    /** The 8 accent colours: the same bright family as the colour patch. */
    public static final int[] SWATCHES = {0xffe93e8f, 0xffff6a1c, 0xfff6d55c, 0xff44d468, 0xff3caea3, 0xff2278ff, 0xff773ca6, 0xffa77d50};
    public static final String[] SWATCH_NAMES = {"Pink", "Orange", "Yellow", "Green", "Teal", "Blue", "Purple", "Brown"};
    /**
     * Accent amount, 5 steps: more takes bigger and fainter highlights, and more of them. Per
     * step: the largest spot (as a share of the picture's short side), how much brighter than its
     * surroundings a spot must be (0-255), and the most of the picture that can take the colour.
     */
    public static final double[] ACCENT_SPOT = {1 / 48.0, 1 / 32.0, 1 / 24.0, 1 / 16.0, 1 / 12.0};
    public static final int[] ACCENT_LIFT = {44, 34, 24, 18, 12};
    public static final double[] ACCENT_SHARE = {0.01, 0.02, 0.035, 0.055, 0.08};
    public static final int ACCENT_DEFAULT = 2;

    /** Detail (pixels across the short side) and Pixel size (output pixels per cell); the defaults are in the middle. */
    // Built around the defaults (64 across, 30 px = 1920 px on the short side): coarse enough to
    // still read as a picture at the low end, and no bigger than a phone can draw at the high end.
    public static final int[] DETAIL = {24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 96, 112, 128, 144, 160};
    public static final int[] PIXEL = {12, 14, 16, 18, 21, 24, 27, 30, 33, 36, 40, 44, 48, 52, 56};
    /** The longest side an output image may have (bigger runs phones and PCs out of memory). */
    public static final int MAX_SIDE = 12000;

    /** Index of the closest value (for values saved before the sliders had fixed steps). */
    public static int nearest(int[] values, int v) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) best = i;
        return best;
    }

    public boolean color = false;   // false = greyscale
    public int greyTones = 3;       // 2 (black and white) .. 8
    public int colors = 12;         // 4..32, colour mode
    public boolean accent = false;  // greyscale only
    public int swatch = 0;          // 0..7
    public int accentRange = ACCENT_DEFAULT;   // accent amount, 0..4
    public int cells = 64;          // pixels across the short side
    public int cellSize = 30;       // output pixels per cell
    public final int[][] knob = defaults();

    public static int[][] defaults() {
        int[][] k = new int[KNOBS.length][];
        for (int s = 0; s < KNOBS.length; s++) {
            k[s] = new int[KNOBS[s].length];
            for (int i = 0; i < k[s].length; i++) k[s][i] = KNOBS[s][i].def;
        }
        return k;
    }

    public double value(Style st, int i) { return KNOBS[st.ordinal()][i].values[knob[st.ordinal()][i]]; }

    public Settings copy() {
        Settings s = new Settings();
        s.set(this);
        return s;
    }

    /** Takes every value from o. */
    public void set(Settings o) {
        color = o.color; greyTones = o.greyTones; colors = o.colors; accent = o.accent; swatch = o.swatch;
        accentRange = o.accentRange; cells = o.cells; cellSize = o.cellSize;
        for (int i = 0; i < knob.length; i++) knob[i] = o.knob[i].clone();
    }

    /** Pixel size, made smaller if needed so an image of cols x rows cells stays within MAX_SIDE. */
    public int cellSizeFor(int cols, int rows) { return Math.max(1, Math.min(cellSize, MAX_SIDE / Math.max(1, Math.max(cols, rows)))); }
}
