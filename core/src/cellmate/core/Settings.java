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

    private static final double[] RING_OUTER = {0.36, 0.40, 0.44, 0.47, 0.50};
    private static final double[] RING_HOLE = {0.08, 0.11, 0.14, 0.17, 0.20, 0.23, 0.26};
    private static final double[] SOLID_SIZE = {0.55, 0.65, 0.75, 0.85, 0.93, 1.0};

    public static final Knob[][] KNOBS = {
        /* BORDER  */ {new Knob("Border size", Kind.SLIDER, 2, 0.015, 0.03, 0.045, 0.065, 0.09, 0.12)},
        /* ROUNDED */ {new Knob("Roundness", Kind.SLIDER, 2, 0.06, 0.12, 0.2, 0.28, 0.36, 0.44)},
        /* JITTER  */ {new Knob("Jitter", Kind.SLIDER, 2, 0.015, 0.03, 0.05, 0.07, 0.09, 0.11)},
        /* SOLID   */ {new Knob("Light squares", Kind.SLIDER, 5, SOLID_SIZE), new Knob("Dark squares", Kind.SLIDER, 5, SOLID_SIZE)},
        /* STITCH  */ {new Knob("Thickness", Kind.SLIDER, 3, 1, 2, 3, 4, 5, 6)},   // twenty-fourths of a cell
        /* BEADS   */ {new Knob("Outer circle", Kind.SLIDER, 4, RING_OUTER), new Knob("Inner circle", Kind.SLIDER, 3, RING_HOLE)},
        /* MELTED  */ {new Knob("Outer circle", Kind.SLIDER, 4, RING_OUTER), new Knob("Inner circle", Kind.SLIDER, 3, RING_HOLE),
                       new Knob("Rotation", Kind.DIAL, 0, 0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330),
                       new Knob("Melt", Kind.BUTTON, 0, 1, 2, 3, 4, 5)},
    };

    /** 24 accent swatches: muted and pastel colours around the colour wheel. */
    public static final int[] SWATCHES = new int[24];
    static {
        for (int i = 0; i < 24; i++) {
            float h = i * 15f;
            boolean pastel = i % 2 == 0;
            SWATCHES[i] = Engine.fromHsv(h, pastel ? 0.34f : 0.46f, pastel ? 0.93f : 0.72f);
        }
    }
    public static final String[] RANGE_NAMES = {"Narrow", "Medium", "Wide"};
    public static final float[] RANGE_DEGREES = {14, 26, 42};

    public boolean color = false;   // false = greyscale
    public int greyTones = 3;       // 2 (black and white) .. 8
    public int colors = 12;         // 4..32, colour mode
    public boolean accent = false;  // greyscale only
    public int swatch = 0;          // 0..23
    public int accentRange = 1;     // index into RANGE_NAMES
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
        s.color = color; s.greyTones = greyTones; s.colors = colors; s.accent = accent; s.swatch = swatch;
        s.accentRange = accentRange; s.cells = cells; s.cellSize = cellSize;
        for (int i = 0; i < knob.length; i++) s.knob[i] = knob[i].clone();
        return s;
    }

    int accentColor() { return SWATCHES[swatch]; }
    float accentHue() { return Engine.hsv(SWATCHES[swatch])[0]; }
}
