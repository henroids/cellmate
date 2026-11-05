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

    /** Accent presets for greyscale mode: name, hue (degrees), drawn colour. */
    public static final String[] ACCENT_NAMES = {"Off", "Red", "Orange", "Yellow", "Green", "Teal", "Blue", "Purple", "Pink", "Picked"};
    public static final float[] ACCENT_HUES = {0, 0, 28, 52, 120, 175, 215, 275, 325, 0};
    public static final int[] ACCENT_COLORS = {0, 0xffd8333a, 0xffef7d22, 0xfff2c230, 0xff3fae4a,
            0xff1fa7a0, 0xff3570d8, 0xff8a4fd0, 0xffe35d9c, 0};
    public static final String[] RANGE_NAMES = {"Narrow", "Medium", "Wide"};
    public static final float[] RANGE_DEGREES = {14, 26, 42};

    public boolean color = false;   // false = greyscale
    public int greyTones = 3;       // 2..8
    public int colors = 12;         // 2..32, colour mode
    public int accent = 0;          // index into ACCENT_NAMES, greyscale mode
    public int pickedColor = 0;     // ARGB, used when accent is "Picked"
    public int accentRange = 1;     // index into RANGE_NAMES
    public int cells = 96;          // pixels across the short side
    public int cellSize = 15;       // output pixels per cell

    public Settings copy() {
        Settings s = new Settings();
        s.color = color; s.greyTones = greyTones; s.colors = colors; s.accent = accent;
        s.pickedColor = pickedColor; s.accentRange = accentRange; s.cells = cells; s.cellSize = cellSize;
        return s;
    }

    int accentColor() { return accent == ACCENT_NAMES.length - 1 ? pickedColor | 0xff000000 : ACCENT_COLORS[accent]; }

    float accentHue() {
        if (accent != ACCENT_NAMES.length - 1) return ACCENT_HUES[accent];
        float[] hsv = Engine.hsv(pickedColor);
        return hsv[0];
    }
}
