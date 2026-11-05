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

    // Every setting's default is the middle step, so each can go either way. The defaults are
    // the looks the styles have always had.
    private static final double[] RING_OUTER = {0.42, 0.46, 0.50, 0.54, 0.58};   // past 0.5 beads start to fuse
    private static final double[] RING_HOLE = {0.11, 0.14, 0.17, 0.20, 0.23};
    private static final double[] SOLID_SIZE = {0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4};   // past 1.0 squares overlap

    public static final Knob[][] KNOBS = {
        /* BORDER  */ {new Knob("Border size", Kind.SLIDER, 2, 0.015, 0.03, 0.045, 0.065, 0.09)},
        /* ROUNDED */ {new Knob("Roundness", Kind.SLIDER, 2, 0.08, 0.14, 0.2, 0.28, 0.36)},
        /* JITTER  */ {new Knob("Jitter", Kind.SLIDER, 2, 0.02, 0.035, 0.05, 0.07, 0.09)},
        /* SOLID   */ {new Knob("Light squares", Kind.SLIDER, 4, SOLID_SIZE), new Knob("Dark squares", Kind.SLIDER, 4, SOLID_SIZE)},
        /* STITCH  */ {new Knob("Thickness", Kind.SLIDER, 2, 2, 3, 4, 5, 6)},   // twenty-fourths of a cell
        /* BEADS   */ {new Knob("Outer circle", Kind.SLIDER, 2, RING_OUTER), new Knob("Inner circle", Kind.SLIDER, 2, RING_HOLE)},
        /* MELTED  */ {new Knob("Outer circle", Kind.SLIDER, 2, RING_OUTER), new Knob("Inner circle", Kind.SLIDER, 2, RING_HOLE),
                       new Knob("Rotation", Kind.SLIDER, 3, -90, -60, -30, 0, 30, 60, 90),
                       new Knob("Meltiness", Kind.SLIDER, 2, 0.4, 0.7, 1.0, 1.3, 1.6)},
    };

    /** The 8 accent colours: the same bright family as the colour patch. */
    public static final int[] SWATCHES = {0xffe93e8f, 0xffff6a1c, 0xfff6d55c, 0xff44d468, 0xff3caea3, 0xff2278ff, 0xff773ca6, 0xffa77d50};
    public static final String[] SWATCH_NAMES = {"Pink", "Orange", "Yellow", "Green", "Teal", "Blue", "Purple", "Brown"};
    /** Accent amount, 5 steps: how wide a hue band, and how dull and dark a colour may be, and still take the accent. */
    public static final float[] RANGE_DEGREES = {10, 18, 26, 36, 50};
    public static final float[] ACCENT_MIN_SAT = {0.40f, 0.34f, 0.28f, 0.22f, 0.16f};
    public static final float[] ACCENT_MIN_VAL = {0.26f, 0.22f, 0.18f, 0.15f, 0.12f};
    public static final int ACCENT_DEFAULT = 2;

    /** Detail (pixels across the short side) and Pixel size (output pixels per cell); the defaults are in the middle. */
    public static final int[] DETAIL = {32, 48, 64, 80, 96, 128, 160, 192, 256};
    public static final int[] PIXEL = {4, 6, 8, 11, 15, 20, 25, 32, 40};

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
    public int cells = 96;          // pixels across the short side
    public int cellSize = 15;       // output pixels per cell
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

    int accentColor() { return SWATCHES[swatch]; }
    float accentHue() { return Engine.hsv(SWATCHES[swatch])[0]; }
}
