package cellmate.ui;

import cellmate.core.Engine;
import cellmate.core.Grid;
import cellmate.core.Picture;
import cellmate.core.Settings;
import cellmate.core.Settings.Knob;
import cellmate.core.Settings.Style;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cellmate's whole interface, drawn by hand in the Black Mesa Tennis start-screen style: black,
 * pixel font, one light grey and one dark grey. The desktop and Android apps only forward drawing
 * and input.
 *
 * Settings rows are sliders. A chosen row is a dark grey bar that fills like a ragged, molten
 * progress bar, split into one section per setting (L/R moves between sections, left/right or a
 * drag changes the value). A tap chooses or un-chooses a style. Greyscale, Color and Accent show
 * XOXO marks on the right; Accent's marks are tapped to pick its colour. One preview shows a style
 * at a time, with arrows at the bottom to page through the chosen styles and the original photo
 * as a small picture in the top-left corner.
 */
public final class Ui {
    public enum Pick { INPUT, INPUT_FOLDER, INPUT_IMAGE, OUTPUT }
    public enum Key { UP, DOWN, LEFT, RIGHT, A, B, PREV, NEXT, PAGE_PREV, PAGE_NEXT, SAVE, RESET, START }

    /** The Start button: the accent palette's green. */
    static final int START_GREEN = 0xff44d468;

    /** Background of a chosen row (and the empty part of its progress bar). */
    static final int CHOSEN = 0xff222222;
    static final int CLEAR_RED = 0xffe53935;   // the X on the Clear button
    /** The one light grey: the selection bar, text, fills and the patches' light squares (no pure white anywhere). */
    static final int HILITE = 0xffbcbcbc;
    /** The scroll bar's handle: the light grey, 30% darker. */
    static final int THUMB = 0xff848484;

    static final int BLACK = 0xff000000, WHITE = HILITE, GREY = 0xff9a9a9a, DIM = 0xff4a4a4a, ICON = 0xff7a7a7a;

    private final Host host;
    private final Settings s = new Settings();
    private final boolean[] styleOn = new boolean[Style.values().length];
    private boolean replace;

    private Object input, output;
    private boolean inputFolder;
    private String inputName = "", outputName = "";
    private boolean startWhenSavePicked;   // the next folder picked for saving starts the run
    private List<String> items;            // images in the input (null while scanning)
    private int previewIndex;
    private String message = "";
    // a small window over the menu: a title, a few lines of information and the options to pick from
    private String[] popup;                // the options (null = no window)
    private String popupTitle;
    private String[] popupInfo;
    private Runnable[] popupActions;
    private int popupSel;

    // ---- preview: one style at a time, plus the original as a picture-in-picture
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "preview"));
    private final AtomicLong previewSeq = new AtomicLong();
    private Style page = Style.BORDER;     // the style the preview shows
    private Object previewImg, beforeImg;
    private int previewImgW, previewImgH, beforeW, beforeH;
    // preview zoom (pinch on phones): 1 = fit; zx, zy = the point of the picture at the canvas centre (0..1)
    private double zoom = 1, zx = 0.5, zy = 0.5, previewImgZoom = 1;
    private int boxX, boxY;          // where the canvas was last drawn on the main screen
    private boolean panning;         // one finger is dragging the zoomed picture
    private Style previewImgStyle;
    private Picture picture;
    private String pictureKey;
    // the preview's grid, kept while only the style sliders change (they don't affect it)
    private Grid gridCache;
    private String gridKey;
    private Engine.Image beforeSmall;
    private Object beforeObject;        // beforeSmall, ready to draw   // the preview's "before" picture, kept while the photo and its box stay the same
    private int beforeBoxW, beforeBoxH;
    private int boxW, boxH, pipW, pipH;

    // ---- batch
    private enum Screen { MENU, RUNNING, DONE }
    private Screen screen = Screen.MENU;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile int done, total, written, skipped, failed;
    private volatile String current = "", firstError = "";
    private long started;

    // ---- menu
    private String selectedKey = "Input";
    private final Map<String, Integer> section = new HashMap<>();   // focused section of a slider box
    private int scroll;
    private boolean followSelection = true;   // scroll the selected row into view on the next draw (keys), not after the wheel
    private int barX, barY, barW, barH, visibleRows, rowCount;   // the menu's scroll bar, for dragging it
    private boolean previewOnOtherScreen;

    // ---- layout of the last frame, for touch and mouse
    private int k = 1, t = 20, rowH, menuX, menuY, menuW, menuH, previewX, previewY, previewW, previewH;
    private final List<Hit> mainHits = new ArrayList<>();     // what's tappable on the main screen
    private List<Hit> hits = mainHits;                        // the list being drawn into (the preview screen swaps in its own)
    private List<Hit> previewHits = new ArrayList<>();        // what's tappable on the second screen's preview

    public Ui(Host host) {
        this.host = host;
        styleOn[Style.BORDER.ordinal()] = true;
        loadPrefs();
    }

    private static Thread daemon(Runnable r, String name) { Thread th = new Thread(r, "cellmate-" + name); th.setDaemon(true); return th; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ================================================================== rows

    /** One section of a slider bar. */
    private interface Part {
        int steps();
        int get();
        void set(int v);
        String name();
        default String full() { return name(); }
    }

    private Part knobPart(Style st, int i) {
        Knob kn = Settings.KNOBS[st.ordinal()][i];
        return new Part() {
            public int steps() { return kn.steps(); }
            public int get() { return s.knob[st.ordinal()][i]; }
            public void set(int v) {
                s.knob[st.ordinal()][i] = clamp(v, 0, kn.steps() - 1);
                int now = s.knob[st.ordinal()][i];
                int mid = kn.def;
                message = st.label + "  " + kn.name + "  " + (kn.name.equals("Rotation") ? (int) kn.values[now] + " deg" : fromDefault(now, mid));
                changed();
            }
            public String name() { return SHORT.getOrDefault(kn.name, kn.name); }
            public String full() { return kn.name; }
        };
    }

    /** Words shown inside the slider sections (short, so they fit). */
    private static final Map<String, String> SHORT = new HashMap<>();
    static {
        String[] pairs = {"Border size", "Border", "Roundness", "Round", "Jitter", "Jitter", "Light squares", "Light",
                "Dark squares", "Dark", "Thickness", "Thick", "Outer circle", "Outer", "Inner circle", "Inner",
                "Rotation", "Turn", "Meltiness", "Melt"};
        for (int i = 0; i < pairs.length; i += 2) SHORT.put(pairs[i], pairs[i + 1]);
    }

    /** A slider section over 0..steps-1; describe says what the new value is, for the status line. */
    private Part part(String name, int steps, java.util.function.IntSupplier get, java.util.function.IntConsumer set,
                      java.util.function.IntFunction<String> describe) {
        return new Part() {
            public int steps() { return steps; }
            public int get() { return get.getAsInt(); }
            public void set(int v) { int now = clamp(v, 0, steps - 1); set.accept(now); message = name + "  " + describe.apply(now); changed(); }
            public String name() { return name; }
        };
    }

    private static String fromDefault(int now, int def) {
        return now == def ? "default" : now < def ? (def - now) + " below default" : (now - def) + " above default";
    }

    /** A menu line: a plain row with a value, or a "box" row (styles, Greyscale, Color, Accent). */
    private abstract class Row {
        final String key, label;
        Row(String key, String label) { this.key = key; this.label = label; }
        boolean box() { return false; }
        String value() { return ""; }
        boolean changeable() { return false; }
        void change(int d) { }
        void activate() { }
        // box rows
        boolean available() { return true; }
        boolean chosen() { return false; }
        void choose() { }
        List<Part> parts() { return Collections.emptyList(); }
        Object patch(int w, int h) { return null; }
        Style style() { return null; }
        /** Colours laid along the bar's fill instead of white (Greyscale tones, Color palette). */
        int[] bands() { return null; }
        /** The 8 XOXO marks' colours, for rows that show marks instead of a patch. */
        int[] marks() { return null; }
        /** Always chosen (Detail, Pixel size): a tap on the bar sets the value instead of toggling. */
        boolean fixed() { return false; }
        /** The longest value shown on the right of a plain slider (null for rows with a patch). */
        String widestValue() { return null; }
        /** Accent: the marks themselves are tapped to pick, and the bar fills just past the label. */
        boolean picks() { return false; }
    }

    private final class ValueRow extends Row {
        final java.util.function.Supplier<String> value; final java.util.function.IntConsumer change; final Runnable activate;
        ValueRow(String key, String label, java.util.function.Supplier<String> value, java.util.function.IntConsumer change, Runnable activate) {
            super(key, label); this.value = value; this.change = change; this.activate = activate;
        }
        String value() { return value.get(); }
        boolean changeable() { return change != null; }
        void change(int d) { if (change != null) change.accept(d); }
        void activate() { if (activate != null) activate.run(); else if (change != null) change.accept(1); }
    }

    private final class StyleRow extends Row {
        final Style st; final List<Part> parts = new ArrayList<>();
        StyleRow(Style st) {
            super("style:" + st.name(), st.label);
            this.st = st;
            for (int i = 0; i < Settings.KNOBS[st.ordinal()].length; i++) parts.add(knobPart(st, i));
        }
        boolean box() { return true; }
        boolean chosen() { return styleOn[st.ordinal()]; }
        void choose() { styleOn[st.ordinal()] = !styleOn[st.ordinal()]; if (styleOn[st.ordinal()]) page = st; changed(); }
        List<Part> parts() { return parts; }
        Object patch(int w, int h) { return stylePatch(st, w, h); }
        Style style() { return st; }
    }

    /** A setting that's always on: a dark grey bar that's only ever slid, with its value on the right. */
    private final class SliderRow extends Row {
        final List<Part> parts; final java.util.function.Supplier<String> value; final String widest;
        /** widest: the longest value text, so the bar's end doesn't move as the value changes. */
        SliderRow(String key, Part part, java.util.function.Supplier<String> value, String widest) {
            super(key, part.name()); this.parts = Collections.singletonList(part); this.value = value; this.widest = widest;
        }
        boolean box() { return true; }
        boolean chosen() { return true; }
        boolean fixed() { return true; }
        List<Part> parts() { return parts; }
        String value() { return value.get(); }
        String widestValue() { return widest; }
    }

    private List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(new SliderRow("Detail", part("Detail", Settings.DETAIL.length, () -> Settings.nearest(Settings.DETAIL, s.cells),
                v -> s.cells = Settings.DETAIL[v], v -> Settings.DETAIL[v] + " across"), () -> s.cells + " across", "160 across"));
        r.add(new SliderRow("Pixel", part("Pixel size", Settings.PIXEL.length, () -> Settings.nearest(Settings.PIXEL, s.cellSize),
                v -> s.cellSize = Settings.PIXEL[v], v -> Settings.PIXEL[v] + " px  =  " + (s.cells * Settings.PIXEL[v]) + " px pictures"), () -> s.cellSize + " px", "56 px"));
        for (Style st : Style.values()) r.add(new StyleRow(st));
        r.add(new Row("Greyscale", "Greyscale") {
            final List<Part> p = Collections.singletonList(part("Grey tones", 7, () -> s.greyTones - 2, v -> s.greyTones = v + 2, v -> (v + 2) + " tones"));
            boolean box() { return true; }
            boolean chosen() { return !s.color; }
            void choose() { if (s.color) { s.color = false; changed(); } }
            List<Part> parts() { return p; }
            int[] bands() { return greyColors(); }
            int[] marks() { return pad8(greyColors()); }
        });
        r.add(new Row("Accent", "Accent") {
            // the bar is the amount; the colour is picked on the marks (or with L/R after switching section)
            final List<Part> p = Arrays.asList(
                    part("Accent amount", Settings.ACCENT_SHARE.length, () -> s.accentRange, v -> s.accentRange = v, v -> fromDefault(v, Settings.ACCENT_DEFAULT)),
                    part("Accent color", Settings.SWATCHES.length, () -> s.swatch, v -> s.swatch = v, v -> Settings.SWATCH_NAMES[v]));
            boolean box() { return true; }
            boolean available() { return !s.color; }
            boolean chosen() { return s.accent && !s.color; }
            void choose() {
                if (s.color) { message = "Accent works with Greyscale."; return; }
                s.accent = !s.accent; changed();
            }
            List<Part> parts() { return p; }
            int[] marks() { return Settings.SWATCHES; }
            boolean picks() { return true; }
        });
        r.add(new Row("Color", "Color") {
            final List<Part> p = Collections.singletonList(part("Colors", 8, () -> (s.colors - 4) / 4, v -> s.colors = 4 + 4 * v, v -> (4 + 4 * v) + " colors"));
            boolean box() { return true; }
            boolean chosen() { return s.color; }
            void choose() { if (!s.color) { s.color = true; changed(); } }
            List<Part> parts() { return p; }
            int[] bands() { return paletteByLight(); }
            int[] marks() { return colorMarks(); }
        });
        if (items != null && items.size() > 1)
            r.add(new ValueRow("Preview image", "Preview image", () -> (previewIndex + 1) + " / " + items.size(), d -> {
                previewIndex = Math.floorMod(previewIndex + d, items.size()); schedulePreview();
            }, null));
        r.add(new ValueRow("Existing", "Existing files", () -> replace ? "Replace" : "Skip", d -> { replace = !replace; savePrefs(); }, null));
        r.add(new ValueRow("Quit", "Quit", () -> "", null, host::quit));
        return r;
    }

    private String startSummary() {
        int n = 0;
        for (boolean b : styleOn) if (b) n++;
        if (input == null) return "";
        String imgs = items == null ? "scanning" : items.size() + (items.size() == 1 ? " image" : " images");
        return imgs + " x " + n + (n == 1 ? " style" : " styles");
    }

    private int selectedIndex(List<Row> r) {
        for (int i = 0; i < r.size(); i++) if (r.get(i).key.equals(selectedKey)) return i;
        return 0;
    }

    private void select(List<Row> r, int i) {
        Row row = r.get(i);
        selectedKey = row.key;
        followSelection = true;
        if (row.style() != null && row.style() != page) { page = row.style(); schedulePreview(); }
    }

    private void changed() { savePrefs(); schedulePreview(); host.repaint(); }

    /** The clear button on the preview: asks first, then forgets the pictures. */
    private void confirmClear() {
        String count = items == null ? "" : "  (" + items.size() + (items.size() == 1 ? " image)" : " images)");
        openPopup("Clear the pictures?", new String[]{inputName + count},
                new String[]{"Clear", "Keep them"}, () -> {
                    input = null; inputName = ""; items = null; previewIndex = 0;
                    picture = null; pictureKey = null; previewImg = beforeImg = null;
                    message = "";
                    savePrefs();
                }, () -> { });
        popupSel = 1;   // "Keep them" until the user moves to "Clear"
        popupOverCanvas = true;
    }

    private void pickInput() {
        if (host.canPickEither()) { host.pick(Pick.INPUT); return; }
        openPopup("Add pictures", new String[0], new String[]{"A folder of images", "One image"},
                () -> host.pick(Pick.INPUT_FOLDER), () -> host.pick(Pick.INPUT_IMAGE));
        popupOverCanvas = true;
    }

    private boolean popupOverCanvas;

    private static final List<String> YES = Arrays.asList("Clear", "Choose where to save", "Start", "OK"),
            NO = Arrays.asList("Keep them", "Go back", "Cancel");

    /** The colour of a window's choice: green to go ahead, red to back out, 0 for neither. */
    private static int toneOf(String choice) { return YES.contains(choice) ? START_GREEN : NO.contains(choice) ? CLEAR_RED : 0; }   // the window is about the pictures, so it opens over the canvas

    private void openPopup(String title, String[] info, String[] options, Runnable... actions) {
        popupTitle = title; popupInfo = info; popup = options; popupActions = actions; popupSel = 0;
        popupOverCanvas = false;
    }

    /** Chosen styles in menu order (what the preview pages through). */
    private List<Style> chosenStyles() {
        List<Style> out = new ArrayList<>();
        for (Style st : Style.values()) if (styleOn[st.ordinal()]) out.add(st);
        return out;
    }

    private void turnPage(int d) {
        List<Style> list = chosenStyles();
        if (!list.contains(page)) list.add(page);
        list.sort(Comparator.comparingInt(Enum::ordinal));
        int i = list.indexOf(page);
        page = list.get(Math.floorMod(i + d, list.size()));
        schedulePreview();
        host.repaint();
    }

    // ================================================================== keys

    public void key(Key key) {
        if (screen == Screen.RUNNING) { if (key == Key.B) { cancel.set(true); message = "Cancelling..."; } host.repaint(); return; }
        if (screen == Screen.DONE) { if (key == Key.A || key == Key.B) screen = Screen.MENU; host.repaint(); return; }
        if (popup != null) { popupKey(key); host.repaint(); return; }
        List<Row> r = rows();
        int i = selectedIndex(r);
        Row row = r.get(i);
        int sec = section.getOrDefault(row.key, 0);
        switch (key) {
            case UP: select(r, (i - 1 + r.size()) % r.size()); break;
            case DOWN: select(r, (i + 1) % r.size()); break;
            case PAGE_PREV: turnPage(-1); break;
            case PAGE_NEXT: turnPage(1); break;
            case PREV: case NEXT:
                if (row.box() && row.parts().size() > 1) section.put(row.key, Math.floorMod(sec + (key == Key.NEXT ? 1 : -1), row.parts().size()));
                break;
            case LEFT: case RIGHT: {
                int d = key == Key.RIGHT ? 1 : -1;
                if (!row.box()) { row.change(d); break; }
                if (!row.available()) { message = "Accent works with Greyscale."; break; }
                if (!row.chosen()) { message = "Press A to choose " + row.label + " first."; break; }
                Part p = row.parts().get(Math.min(sec, row.parts().size() - 1));
                p.set(p.get() + d);
                break;
            }
            case A: if (row.box()) row.choose(); else row.activate(); break;
            case B: if (i != 0) select(r, 0); break;
            case SAVE: if (row.style() != null) save(row.style()); break;
            case RESET: resetAll(); break;
            case START: start(); break;
        }
        host.repaint();
    }

    private void save(Style st) {
        StringBuilder b = new StringBuilder();
        for (int v : s.knob[st.ordinal()]) b.append(v).append(',');
        savedKnobs.put(st.name(), b.toString());
        savePrefs();
        message = st.label + " saved for next time.";
    }

    /** Y: every setting back to its default (the chosen styles, folders and preview stay as they are). */
    private void resetAll() {
        s.set(new Settings());
        savedKnobs.clear();
        savePrefs();
        message = "Everything is back to its defaults.";
        changed();
    }

    private void popupKey(Key key) {
        switch (key) {
            case UP: case LEFT: popupSel = Math.floorMod(popupSel - 1, popup.length); break;
            case DOWN: case RIGHT: popupSel = Math.floorMod(popupSel + 1, popup.length); break;
            case A: popupChoose(popupSel); break;
            case B: popup = null; break;
            default: break;
        }
    }

    private void popupChoose(int i) { Runnable r = popupActions[i]; popup = null; r.run(); }

    // ================================================================== pointer (mouse and touch)

    /** Something drawn last frame that can be tapped or dragged. */
    private static final class Hit {
        final int x, y, w, h; final String row; final Object what; final int index;
        Hit(int x, int y, int w, int h, String row, Object what, int index) { this.x = x; this.y = y; this.w = w; this.h = h; this.row = row; this.what = what; this.index = index; }
        boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }

    private static final Object ROW = new Object(), START_BUTTON = new Object(), SWATCH = new Object(), SCROLLBAR = new Object(), CANVAS = new Object(), CLEAR = new Object(), PAGE_LEFT = new Object(), PAGE_RIGHT = new Object(), HELP = new Object();
    private Hit down;
    private int downX, downY, lastY;
    private boolean dragging, sliding;

    public void pointerDown(int x, int y) {
        downX = x; downY = lastY = y; dragging = false; sliding = false; down = null;
        if (screen != Screen.MENU || popup != null) return;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) { down = hits.get(i); break; }
    }

    public void pointerMove(int x, int y) {
        if (screen != Screen.MENU || popup != null) return;
        if (down != null && down.what == SCROLLBAR) { scrollTo(y); return; }
        if (sliding) { slide(down, x, y); return; }
        // one finger dragging a zoomed-in picture moves around it
        if (down != null && down.what == CANVAS && zoom > 1) {
            if (!panning && Math.hypot(x - downX, y - downY) > 6 * k) { panning = true; panX = downX; panY = downY; }
            if (panning) { pan(x - panX, y - panY); panX = x; panY = y; }
            return;
        }
        // a sideways drag on a chosen row's bar moves its setting (a plain tap chooses or un-chooses the row)
        if (!dragging && down != null && down.what instanceof Part && Math.abs(x - downX) > 6 * k && Math.abs(x - downX) > Math.abs(y - downY)) {
            sliding = true; slide(down, x, y); return;
        }
        if (!dragging && Math.abs(y - downY) > rowH / 2) dragging = true;
        if (dragging && rowH > 0) {
            int moved = (lastY - y) / (rowH + rowGap);
            if (moved != 0) { scrollBy(moved); lastY -= moved * (rowH + rowGap); }
        }
    }

    /** A second finger came down: whatever the first one started isn't a tap or a drag. */
    public void pointerCancel() { down = null; dragging = sliding = panning = false; }

    /** Pinch on the preview (phones): scale around the fingers' centre (fx, fy). */
    public void pinch(float fx, float fy, float scale) {
        if (screen != Screen.MENU || popup != null || previewImg == null || boxW <= 0) return;
        int dw = (int) Math.round(previewImgW / previewImgZoom), dh = (int) Math.round(previewImgH / previewImgZoom);   // size at zoom 1
        double before = zoom, max = Math.max(1, Math.min(8, 2400.0 / Math.max(boxW, boxH)));
        zoom = Math.max(1, Math.min(max, zoom * scale));
        // keep the point under the fingers where it is
        double w0 = dw * before, h0 = dh * before;
        int left = place(boxX, boxW, (int) w0, zx), top = place(boxY, boxH, (int) h0, zy);
        double u = (fx - left) / w0, v = (fy - top) / h0;
        double w1 = dw * zoom, h1 = dh * zoom;
        zx = clamp01((boxX + boxW / 2.0 - (fx - u * w1)) / w1);
        zy = clamp01((boxY + boxH / 2.0 - (fy - v * h1)) / h1);
        host.repaint();
    }

    /** The pinch is over: draw the picture sharp at its new size. */
    public void pinchEnd() { schedulePreview(); }

    private int panX, panY;

    private void pan(int dx, int dy) {
        double dw = previewImgW * zoom / previewImgZoom, dh = previewImgH * zoom / previewImgZoom;
        zx = clamp01(zx - dx / dw); zy = clamp01(zy - dy / dh);
        host.repaint();
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    public void pointerUp(int x, int y) {
        tapUp(x, y);
        host.repaint();   // whatever the tap did (a window opening, a choice), show it now
    }

    private void tapUp(int x, int y) {
        if (screen == Screen.RUNNING) { cancel.set(true); message = "Cancelling..."; host.repaint(); return; }
        if (screen == Screen.DONE) { screen = Screen.MENU; host.repaint(); return; }
        if (popup != null) { tapPopup(x, y); host.repaint(); return; }
        if (panning) { panning = false; return; }
        if (sliding || dragging) { sliding = dragging = false; host.repaint(); return; }
        if (down == null) return;
        if (down.what == SCROLLBAR) { scrollTo(y); return; }
        if (down.what == CANVAS) { pickInput(); return; }
        if (down.what == START_BUTTON) { start(); return; }   // a tap on the preview adds (or changes) the pictures
        if (down.what == CLEAR) { confirmClear(); return; }
        if (down.what == PAGE_LEFT) { turnPage(-1); return; }
        if (down.what == PAGE_RIGHT) { turnPage(1); return; }
        if (down.what == HELP) { key((Key) helpKeys.get(down.index)); return; }
        if (down.what == SWATCH) { pickSwatch(down.index); return; }
        if (down.what instanceof Part && rowFor(down.row) instanceof SliderRow) { slide(down, x, y); return; }
        List<Row> r = rows();
        int idx = -1;
        for (int i = 0; i < r.size(); i++) if (r.get(i).key.equals(down.row)) idx = i;
        if (idx < 0) return;
        Row row = r.get(idx);
        boolean wasSelected = row.key.equals(selectedKey);
        select(r, idx);
        if (row.box()) row.choose();   // one tap chooses, the next un-chooses
        else if (wasSelected && row.changeable() && x >= valueLeft && x < valueMid) row.change(-1);
        else if (wasSelected && row.changeable()) row.change(1);
        else if (!row.changeable()) row.activate();
        host.repaint();
    }

    /** A tap on one of Accent's marks: that colour becomes the accent (and turns it on). */
    private void pickSwatch(int i) {
        List<Row> r = rows();
        for (int j = 0; j < r.size(); j++) if (r.get(j).key.equals("Accent")) select(r, j);
        if (s.color) { message = "Accent works with Greyscale."; host.repaint(); return; }
        if (s.accent && s.swatch == i) { s.accent = false; message = "Accent off."; }   // tapping the picked one again turns it off
        else { s.swatch = i; s.accent = true; message = "Accent  " + Settings.SWATCH_NAMES[i]; }
        changed();
        host.repaint();
    }

    private Row rowFor(String key) { for (Row row : rows()) if (row.key.equals(key)) return row; return null; }

    /** Old single-tap entry point (tests). */
    public void tap(int x, int y) { pointerDown(x, y); pointerUp(x, y); }

    private void slide(Hit h, int x, int y) {
        Part p = (Part) h.what;
        List<Row> r = rows();
        for (int i = 0; i < r.size(); i++) if (r.get(i).key.equals(h.row)) select(r, i);
        section.put(h.row, h.index);
        int v = clamp((x - h.x) * p.steps() / Math.max(1, h.w), 0, p.steps() - 1);
        if (v != p.get()) p.set(v);
        host.repaint();
    }

    private int popupX, popupY, popupW, popupRowH;

    private void tapPopup(int x, int y) {
        if (x < popupX || x >= popupX + popupW) { popup = null; return; }
        int i = (y - popupY) / Math.max(1, popupRowH);
        if (i >= 0 && i < popup.length) popupChoose(i); else popup = null;
    }

    /** Swipe or drag, in rows (positive = down). The selection stays where it is. */
    public void scrollBy(int rowsDelta) {
        scroll = clamp(scroll + rowsDelta, 0, Math.max(0, rowCount - visibleRows));
        followSelection = false;
        host.repaint();
    }

    private double wheelRest;

    /** Mouse wheel or touchpad (in rows, may be fractional), scrolling the menu when the pointer is over it. */
    public void wheel(int x, int y, double rowsDelta) {
        if (screen != Screen.MENU || popup != null) return;
        if (x < menuX || x >= barX + barW || y < menuY || y >= menuY + menuH) return;
        wheelRest += rowsDelta;
        int whole = (int) wheelRest;
        if (whole != 0) { wheelRest -= whole; scrollBy(whole); }
    }

    /** Dragging or tapping the scroll bar: centre the thumb on the pointer. */
    private void scrollTo(int y) {
        if (rowCount <= visibleRows || barH <= 0) return;
        int thumb = Math.max(barH * visibleRows / rowCount, 3 * k);
        double f = (y - barY - thumb / 2.0) / Math.max(1, barH - thumb);
        scroll = clamp((int) Math.round(f * (rowCount - visibleRows)), 0, rowCount - visibleRows);
        followSelection = false;
        host.repaint();
    }

    /** A tap on the second screen's preview: the arrows page through the styles. */
    /** A tap on the second screen's preview: the page arrows, Clear, or the canvas (add or change the pictures). */
    public void tapPreviewScreen(int x, int y) {
        if (screen != Screen.MENU || popup != null) return;
        List<Hit> list = previewHits;
        for (int i = list.size() - 1; i >= 0; i--) {
            Hit h = list.get(i);
            if (!h.contains(x, y)) continue;
            if (h.what == PAGE_LEFT || h.what == PAGE_RIGHT) turnPage(h.what == PAGE_LEFT ? -1 : 1);
            else if (h.what == CLEAR) confirmClear();
            else if (h.what == CANVAS) pickInput();
            host.repaint();
            return;
        }
    }

    // ================================================================== picking

    public void picked(Pick kind, Object place, String name) {
        if (place == null) return;
        if (kind == Pick.OUTPUT) {
            if (!startWhenSavePicked) return;
            startWhenSavePicked = false;
            if (inputFolder && host.isInside(place, input)) { message = "Choose somewhere outside the folder of pictures."; host.repaint(); return; }
            output = place; outputName = name;
            begin();
            host.repaint();
            return;
        } else {
            input = place; inputName = name; inputFolder = kind == Pick.INPUT_FOLDER;
            items = null; previewIndex = 0; picture = null; pictureKey = null; previewImg = beforeImg = null;
            zoom = 1; zx = zy = 0.5;
            scan();
        }
        message = "";
        savePrefs();
        host.repaint();
    }

    private void scan() {
        final Object in = input; final boolean folder = inputFolder;
        daemon(() -> {
            List<String> found;
            try { found = host.listImages(in, folder); } catch (Exception e) { found = new ArrayList<>(); say("Couldn't read the input: " + e.getMessage()); }
            final List<String> f = found;
            host.runOnUi(() -> {
                if (in != input) return;
                items = f;
                if (f.isEmpty()) message = "No images found in " + inputName;
                schedulePreview();
                host.repaint();
            });
        }, "scan").start();
    }

    private void say(String text) { host.runOnUi(() -> { message = text; host.repaint(); }); }

    // ================================================================== preview

    /** Tells the UI that the preview is shown on a second screen (the Thor's lower display). */
    public void setPreviewOnOtherScreen(boolean other) { previewOnOtherScreen = other; host.repaint(); }

    private void schedulePreview() {
        if (input == null || items == null || items.isEmpty() || boxW <= 0) return;
        final long seq = previewSeq.incrementAndGet();
        final Settings set = s.copy();
        final Style style = page;
        final String rel = items.get(Math.min(previewIndex, items.size() - 1));
        final Object in = input; final boolean folder = inputFolder;
        final int pw = pipW, ph = pipH;
        final double z = zoom;   // drawn at the zoomed size, so zooming in shows the real pixels
        final int bw = (int) Math.round(boxW * z), bh = (int) Math.round(boxH * z);
        previewWorker.execute(() -> {
            if (seq != previewSeq.get()) return;
            try {
                String key = (folder ? "d:" : "f:") + in + "|" + rel;
                Picture p = picture;
                boolean newPicture = p == null || !key.equals(pictureKey);
                if (newPicture) { p = host.decode(in, folder, rel); picture = p; pictureKey = key; }
                // the original photo for the corner, made once per photo and size
                Engine.Image photo = beforeSmall;
                if (newPicture || photo == null || pw != beforeBoxW || ph != beforeBoxH) {
                    photo = beforeSmall = Engine.fit(new Engine.Image(p.width, p.height, p.argb), pw, ph);
                    beforeBoxW = pw; beforeBoxH = ph;
                    beforeObject = host.makeImage(photo.argb, photo.width, photo.height);
                }
                final int photoW = photo.width, photoH = photo.height;
                final Object before = beforeObject;
                if (seq != previewSeq.get()) return;
                // drawn at the size it's shown, not the full output size: the same look, many times faster
                Grid grid = previewGrid(p, key, set);
                int onScreen = (int) Math.ceil(Math.min(bw / (double) grid.cols, bh / (double) grid.rows));
                int cell = Math.max(2, Math.min(set.cellSizeFor(grid.cols, grid.rows), onScreen));
                Engine.Image shown = Engine.fit(Engine.draw(grid, style, cell, set), bw, bh);
                Object img = host.makeImage(shown.argb, shown.width, shown.height);
                host.runOnUi(() -> {
                    if (seq != previewSeq.get()) return;
                    previewImg = img; previewImgW = shown.width; previewImgH = shown.height; previewImgStyle = style; previewImgZoom = z;
                    beforeImg = before; beforeW = photoW; beforeH = photoH;
                    host.repaint();
                });
            } catch (Throwable e) {
                host.runOnUi(() -> { if (seq == previewSeq.get()) { message = "Preview failed: " + rel; host.repaint(); } });
            }
        });
    }

    // ================================================================== batch

    private void start() {
        List<Style> chosen = chosenStyles();
        if (input == null) { pickInput(); return; }
        if (chosen.isEmpty()) { message = "Choose at least one style."; return; }
        if (items == null) { message = "Still looking for images..."; return; }
        if (items.isEmpty()) { message = "No images to convert."; return; }
        List<String> names = new ArrayList<>();
        for (Style st : chosen) names.add(st.label);
        String look = s.color ? s.colors + " colors" : s.greyTones + " grey tones" + (s.accent ? ", " + Settings.SWATCH_NAMES[s.swatch] + " accent" : "");
        openPopup("Start?", new String[]{
                startSummary(),
                String.join(", ", names),
                look + ",  " + s.cells + " across,  " + s.cellSize + " px",
                replace ? "Existing files will be replaced" : "Existing files will be skipped"},
                new String[]{"Choose where to save", "Go back"},
                () -> { startWhenSavePicked = true; host.pick(Pick.OUTPUT); }, () -> { });
    }

    private synchronized Grid previewGrid(Picture p, String pictureKey, Settings set) {
        String key = pictureKey + "|" + set.color + "|" + set.greyTones + "|" + set.colors + "|" + set.accent + "|"
                + set.swatch + "|" + set.accentRange + "|" + set.cells;
        if (!key.equals(gridKey)) { gridCache = Engine.grid(p, set); gridKey = key; }
        return gridCache;
    }

    /** Converts everything, once the start window is confirmed. */
    private void begin() {
        List<Style> chosen = chosenStyles();
        final List<String> list = new ArrayList<>(items);
        final List<String> outs = Plan.outputs(list);
        final Settings set = s.copy();
        final Object in = input, out = output; final boolean folder = inputFolder; final boolean rep = replace;
        final String name = inputName;
        cancel.set(false);
        done = 0; written = 0; skipped = 0; failed = 0; total = list.size() * chosen.size(); current = ""; firstError = "";
        started = System.currentTimeMillis();
        screen = Screen.RUNNING;
        message = "";
        // several pictures at once, one per spare processor core (each is small: the photo,
        // its grid and one finished picture at a time)
        final int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        daemon(() -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "convert"); t.setDaemon(true); return t;
            });
            for (int n = 0; n < list.size(); n++) {
                final int i = n;
                pool.execute(() -> convertOne(list.get(i), outs.get(i), chosen, set, in, out, folder, rep, name));
            }
            pool.shutdown();
            try { pool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            host.runOnUi(() -> { screen = Screen.DONE; host.repaint(); });
        }, "batch").start();
    }

    /** One picture in every chosen style (styles already saved are skipped unless replacing). */
    private void convertOne(String rel, String outName, List<Style> chosen, Settings set, Object in, Object out,
                            boolean folder, boolean rep, String name) {
        if (cancel.get()) return;
        current = rel;
        List<Style> todo = new ArrayList<>();
        for (Style st : chosen) {
            if (!rep && host.exists(out, Plan.styleFolder(name, folder, st) + "/" + outName)) count(0, 1, 0);
            else todo.add(st);
        }
        if (todo.isEmpty()) { host.repaint(); return; }
        try {
            Picture p = host.decode(in, folder, rel);
            Grid g = Engine.grid(p, set);
            for (Style st : todo) {
                if (cancel.get()) return;
                host.writePng(out, Plan.styleFolder(name, folder, st) + "/" + outName, Engine.draw(g, st, set.cellSizeFor(g.cols, g.rows), set));
                count(1, 0, 0);
                host.repaint();
            }
        } catch (Throwable e) {
            count(0, 0, todo.size());
            synchronized (this) { if (firstError.isEmpty()) firstError = rel + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()); }
        }
        host.repaint();
    }

    /** Progress, from any of the converting threads. */
    private synchronized void count(int wrote, int skip, int fail) {
        written += wrote; skipped += skip; failed += fail; done += wrote + skip + fail;
    }

    // ================================================================== drawing

    private int valueLeft, valueMid, rowGap;

    /** Draws everything. */
    public void draw(Surface g, int w, int h) {
        k = Math.max(1, Math.min(Math.round(h / 540f), Math.round(w / 640f)));
        t = 20 * k;          // pixel font size (one font pixel = k screen pixels)
        rowH = t + 8 * k;
        rowGap = 8 * k;
        hits.clear();
        g.fill(0, 0, w, h, BLACK);

        title(g, 14 * k, 8 * k);
        String top = input == null ? "" : inputName + (items == null ? "" : "  " + items.size() + (items.size() == 1 ? " image" : " images"));
        top = fitText(g, top, w / 2, t);
        g.text(top, w - 14 * k - g.textWidth(top, t), 8 * k, t, GREY);

        // upright screens put the status message on its own line above the button hints
        boolean tall = w < h;
        int topH = t + 18 * k, bottomH = t + 20 * k + (tall ? t + 8 * k : 0);
        int midY = topH, midH = h - topH - bottomH;

        if (screen != Screen.MENU) {
            drawJob(g, w, midY, midH);
            drawHelp(g, w, h, screen == Screen.RUNNING ? new Object[]{"B", "Cancel", Key.B} : new Object[]{"A", "OK", Key.A});
            return;
        }

        // the green Start button: a big bar along the bottom on tall screens; on wide ones it sits
        // under the settings, as wide as them
        int startX, startY, startW, startH;
        if (previewOnOtherScreen) {
            startH = rowH + 12 * k; startY = midY + midH - startH;
            menuX = w / 8; menuY = midY; menuW = w - w / 4; menuH = midH - startH - 12 * k;
            startX = menuX; startW = menuW;
            previewW = previewH = 0;
        } else if (w >= h) {
            startH = rowH + 12 * k; startY = midY + midH - startH;
            menuX = 0; menuY = midY; menuW = (int) (w * 0.46); menuH = midH - startH - 12 * k;
            startX = menuX; startW = menuW;
            previewX = menuW + 16 * k; previewY = midY; previewW = w - previewX - 14 * k; previewH = midH;
        } else {
            startH = 2 * t + 8 * k; startY = midY + midH - startH;
            startX = 14 * k; startW = w - 28 * k;
            midH -= startH + 12 * k;
            previewX = 14 * k; previewY = midY; previewW = w - 28 * k; previewH = (int) (midH * 0.42);
            menuX = 0; menuY = previewY + previewH + 10 * k; menuW = w; menuH = midH - previewH - 10 * k;
        }
        menuW -= 14 * k;   // room for the scroll bar
        drawMenu(g);
        if (!previewOnOtherScreen) drawPreview(g, previewX, previewY, previewW, previewH);
        drawStart(g, startX, startY, startW, startH);

        List<Row> r = rows();
        Row row = r.get(selectedIndex(r));
        List<Object> help = new ArrayList<>();
        if (!row.fixed()) help.addAll(Arrays.asList("A", row.box() ? (row.chosen() && row.style() != null ? "Remove" : "Choose") : "Select", Key.A));
        if (row.box() && row.parts().size() > 1 && row.chosen()) help.addAll(Arrays.asList("LR", "Section", Key.NEXT));
        boolean saves = row.style() != null && row.chosen();
        if (saves) help.addAll(Arrays.asList("X", "Save", Key.SAVE));
        help.addAll(Arrays.asList("Y", "Reset", Key.RESET));
        if (!saves) help.addAll(Arrays.asList("B", "Back", Key.B));
        int helpLeft = drawHelp(g, w, h, help.toArray());
        // the status message: beside the hints on wide screens, on its own line above them on tall ones
        int room = tall ? w - 28 * k : helpLeft - 14 * k - 24 * k;
        g.text(fitText(g, message, room, t), 14 * k, h - bottomH + 6 * k, t, GREY);
        if (popup != null) drawPopup(g, w, h);
    }

    /** Draws only the preview, for a second screen. */
    public void drawPreviewScreen(Surface g, int w, int h) {
        int saveK = k, saveT = t;
        k = Math.max(1, Math.round(h / 540f)); t = 20 * k;
        g.fill(0, 0, w, h, BLACK);
        // its own tap areas, in its own coordinates (not mixed in with the main screen's)
        List<Hit> main = hits, mine = new ArrayList<>();
        hits = mine;
        try { drawPreview(g, 14 * k, 10 * k, w - 28 * k, h - 20 * k); }
        finally { hits = main; previewHits = mine; k = saveK; t = saveT; }
    }

    private void drawMenu(Surface g) {
        List<Row> r = rows();
        int sel = selectedIndex(r);
        int pitch = rowH + rowGap, visible = Math.max(1, (menuH + rowGap) / pitch);
        if (followSelection) {
            if (sel < scroll) scroll = sel;
            if (sel >= scroll + visible) scroll = sel - visible + 1;
            followSelection = false;
        }
        scroll = clamp(scroll, 0, Math.max(0, r.size() - visible));
        visibleRows = visible; rowCount = r.size();
        g.clip(menuX, menuY, menuW, menuH);
        for (int i = scroll; i < r.size() && i < scroll + visible; i++) {
            Row row = r.get(i);
            int y = menuY + (i - scroll) * pitch;
            boolean on = i == sel;
            if (on && !row.box()) g.fill(menuX, y, menuW, rowH, HILITE);   // slider rows are never light grey: see drawBoxRow
            hits.add(new Hit(menuX, y, menuW, rowH, row.key, ROW, 0));
            if (row.box()) drawBoxRow(g, row, y, on); else drawValueRow(g, row, y, on);
        }
        g.unclip();
        // scroll bar: dark grey track, mid grey thumb, just right of the rows
        barW = 6 * k; barX = menuX + menuW + 8 * k; barY = menuY; barH = Math.min(menuH, visible * pitch - rowGap);
        if (r.size() > visible) {
            int thumb = Math.max(barH * visible / r.size(), 3 * k);
            int ty = barY + (barH - thumb) * scroll / (r.size() - visible);
            g.fill(barX, barY, barW, barH, CHOSEN);
            g.fill(barX, ty, barW, thumb, THUMB);
            hits.add(new Hit(barX - 6 * k, barY, barW + 12 * k, barH, "", SCROLLBAR, 0));
        }
    }

    private void drawValueRow(Surface g, Row row, int y, boolean on) {
        int ty = y + (rowH - t) / 2, right = menuX + menuW - (on && row.changeable() ? 4 * k : 0);   // values end where the patches end
        g.text(row.label, menuX + 10 * k, ty, t, on ? BLACK : WHITE);
        boolean arrows = on && row.changeable();
        int arrowW = arrows ? 6 * k : 0, gap = arrows ? 6 * k : 0;
        int labelEnd = menuX + 10 * k + g.textWidth(row.label, t) + 16 * k;
        String v = fitText(g, row.value(), right - labelEnd - 2 * (arrowW + gap), t);
        int vw = g.textWidth(v, t), vx = right - vw - (arrowW + gap);
        g.text(v, vx, ty, t, on ? BLACK : GREY);
        if (on) { valueLeft = vx - gap - arrowW - 6 * k; valueMid = vx + vw / 2; }
        if (arrows) {
            int cy = y + rowH / 2;
            triangle(g, vx - gap - arrowW, cy, arrowW, true, BLACK);
            triangle(g, vx + vw + gap, cy, arrowW, false, BLACK);
        }
    }

    /**
     * Label on the left, patch (or XOXO marks, or a value) on the right. Selected or chosen, the row
     * is the dark grey bar; a chosen row's bar is its slider, one section per setting, filling with
     * molten pixels, and the words inside flip to black where the fill passes under them.
     */
    private void drawBoxRow(Surface g, Row row, int y, boolean on) {
        boolean avail = row.available(), chosen = row.chosen();
        List<Part> parts = row.parts();
        int focusSec = clamp(section.getOrDefault(row.key, 0), 0, Math.max(0, parts.size() - 1));
        // the patch fills the bar's height and ends where the bar ends
        int ph = rowH, pw = ph * 5 / 2;
        if (row.widestValue() != null) pw = Math.max(pw, g.textWidth(row.widestValue(), t) + 12 * k);   // room for the value
        int px = menuX + menuW - pw;
        int labelX = menuX + 10 * k, ty = y + (rowH - t) / 2;
        boolean slider = chosen && avail && !parts.isEmpty() && !row.picks();   // a chosen row always shows its setting

        if (row.picks() && chosen && avail) {
            // Accent: the bar is the amount, filled in the picked colour; the least reaches just past the name
            int fillColor = Settings.SWATCHES[s.swatch];
            g.fill(menuX, y, menuW, rowH, CHOSEN);   // the empty part: the chosen grey, like the other sliders
            int x1 = px, start = labelX + g.textWidth(row.label, t) + 14 * k, steps = Settings.ACCENT_SHARE.length;
            int end = start + s.accentRange * (x1 - start) / (steps - 1);
            List<int[]> fills = new ArrayList<>();
            magma(g, menuX, y, x1 - menuX, rowH, end - menuX, fillColor, null, s.swatch * 7 + s.accentRange * 13 + 3, fills);
            // drag or tap along the bar to set the amount (each step owns an equal stretch around its fill end)
            int span = (x1 - start) / (steps - 1);
            hits.add(new Hit(start - span / 2, y, span * steps, rowH, row.key, parts.get(0), 0));
            g.text(row.label, labelX, ty, t, WHITE);
            for (int[] f : fills) { g.clip(f[0], f[1], f[2], f[3]); g.text(row.label, labelX, ty, t, light(f[4]) ? BLACK : WHITE); g.unclip(); }
        } else if (slider) {
            g.fill(menuX, y, menuW, rowH, CHOSEN);                        // the empty part: the chosen grey
            int x0 = menuX, x1 = px;   // the bar runs right up to the patch
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) idx.add(i);
            int n = idx.size(), gap = n > 1 ? 3 * k : 0, secW = n == 0 ? 0 : (x1 - x0 - gap * (n - 1)) / n;
            List<int[]> fills = new ArrayList<>();   // {x, y, w, h} pieces of fill, for the text flip
            for (int j = 0; j < n; j++) {
                Part p = parts.get(idx.get(j));
                int sx = x0 + j * (secW + gap);
                // empty at the lowest step, half way at the default (the middle), full at the top
                int fw = row.bands() != null
                        ? (int) Math.round((p.get() + 1) * secW / (double) p.steps())   // a count: even the lowest shows its colours
                        : (int) Math.round(p.get() * secW / (double) Math.max(1, p.steps() - 1));
                int fillColor = n == 1 || !on || idx.get(j) == focusSec ? WHITE : ICON;
                magma(g, sx, y, secW, rowH, fw, fillColor, row.bands(), p.get() * 7 + j * 31 + row.key.length(), fills);
                if (j > 0) g.fill(sx - gap, y, gap, rowH, BLACK);   // a thin black cut between sections
                hits.add(new Hit(sx, y, secW, rowH, row.key, p, idx.get(j)));
            }
            // each segment says what it does; the words flip colour under the fill
            for (int j = 0; j < n; j++) {
                Part p = parts.get(idx.get(j));
                int sx = x0 + j * (secW + gap);
                String word = fitText(g, n == 1 ? p.full() : p.name(), secW - 8 * k, t);
                int wx = n == 1 ? labelX : sx + (secW - g.textWidth(word, t)) / 2;
                g.clip(sx, y, secW, rowH);
                g.text(word, wx, ty, t, WHITE);
                for (int[] f : fills) {
                    if (f[0] + f[2] <= sx || f[0] >= sx + secW || !light(f[4])) continue;
                    g.clip(f[0], f[1], f[2], f[3]); g.text(word, wx, ty, t, BLACK); g.unclip();
                }
                g.unclip();
            }
        } else {
            if (on || (chosen && avail)) g.fill(menuX, y, menuW, rowH, CHOSEN);   // selected or chosen: always the dark grey
            g.text(row.label, labelX, ty, t, !avail ? DIM : WHITE);
        }

        // patch (Greyscale, Color and Accent show their XOXO marks instead)
        int[] marks = row.marks();
        if (marks != null) {
            // what's behind the marks, so they can be kept clear of it
            int behind = on || (chosen && avail) ? CHOSEN : BLACK;
            int[][] spots = drawMarks(g, px, y, pw, ph, marks, behind);
            if (row.picks() && avail) for (int i = 0; i < spots.length; i++)
                hits.add(new Hit(spots[i][0], spots[i][1], spots[i][2], spots[i][3], row.key, SWATCH, i));
        }
        Object patch = marks != null ? null : row.patch(pw, ph);
        if (patch != null) g.image(patch, px, y);
        if (!avail) veil(g, px, y, pw, ph, on ? CHOSEN : BLACK);
        // plain sliders (Detail, Pixel size) show their value where a patch would be
        String v = row.value();
        if (!v.isEmpty()) g.text(v, menuX + menuW - g.textWidth(v, t), ty, t, GREY);
    }

    /**
     * The fill of a bar section, with a ragged, molten leading edge: it wobbles row by row in
     * whole font pixels, has a few chips knocked out just behind it and a few drips ahead of it.
     * Completely empty or full sections stay straight. Each piece drawn is added to out.
     */
    private void magma(Surface g, int x, int y, int w, int h, int fw, int color, int[] bands, int seed, List<int[]> out) {
        if (fw <= 0) return;
        final int k = 2 * this.k;   // the magma's own, chunkier pixel
        Painter paint = new Painter(g, x, fw, color, bands, out, y, h, k, seed);
        if (fw >= w) {   // full: a straight front, but the seams inside still flow
            for (int yy = y; yy < y + h; yy += k) paint.fill(x, yy, w, Math.min(k, y + h - yy));
            paint.spatter(x + w);
            return;
        }
        int rows = Math.max(1, h / k);
        double lean = Math.min(h * 1.5, w * 0.3);   // how far the bottom of the flow runs ahead of the top
        for (int r = 0; r < rows; r++) {
            int ry = y + r * k, rh = r == rows - 1 ? h - r * k : k;
            // flowing in a tube: a rounded nose, bottom-heavy, the top trailing behind
            double f = rows == 1 ? 1 : r / (double) (rows - 1);
            double nose = lean * (Math.pow(f, 0.35) - 0.7);   // heavier at the bottom
            double wave = Math.sin(r * 0.55 + seed * 1.7) * 1.3 + Math.sin(r * 1.37 + seed * 0.9) * 0.8;
            int jitter = (int) Math.round(wave + (hash(r, seed) % 3 - 1) * 0.6);
            int edge = (int) Math.round(fw + nose) + jitter * k;
            edge = Math.max(0, Math.min(w, edge / k * k));
            if (edge <= 0) continue;
            paint.fill(x, ry, edge, rh);
            int hsh = hash(r * 7 + 3, seed);
            if (hsh % 9 == 0 && edge > 6 * k) g.fill(x + edge - 4 * k, ry, 2 * k, rh, CHOSEN);           // a chip
            if (hsh % 7 == 1 && edge + 3 * k < w) paint.fill(x + edge + k, ry, k, rh);   // a drip
        }
        // particles and specks thrown ahead of the front, more of them low down
        int front = (int) Math.round(fw + lean * 0.3);
        for (int i = 0; i < 24; i++) {
            int a = hash(i * 11 + 5, seed), b = hash(i * 17 + 9, seed);
            double depth = Math.pow((a % 1000) / 1000.0, 0.6);              // biased toward the bottom
            int py = y + (int) (depth * (h - k)) / k * k;
            double reach = (b % 1000) / 1000.0;
            int px = x + (front - (int) ((1 - depth) * lean * 0.8) + (int) (reach * reach * 18 * k)) / k * k + 2 * k;
            int size = (a >> 10) % 4 == 0 ? 2 * k : k;
            if (px < x || px + size > x + w || py + size > y + h) continue;
            paint.fill(px, py, size, size);
        }
        paint.spatter(x + Math.min(w, fw));
    }

    /**
     * Fills pieces of a bar: one colour, or bands of colours laid along the filled length (the
     * grey tones, or the palette). The seams between bands flow like the front of the fill:
     * bottom-heavy and ragged, in the magma's chunky pixels. Every piece is recorded with its
     * colour, for the text flip.
     */
    private static final class Painter {
        final Surface g; final int x0, len, color, y0, h, gk, seed; final int[] bands; final List<int[]> out;
        Painter(Surface g, int x0, int len, int color, int[] bands, List<int[]> out, int y0, int h, int gk, int seed) {
            this.g = g; this.x0 = x0; this.len = Math.max(1, len); this.color = color; this.bands = bands; this.out = out;
            this.y0 = y0; this.h = h; this.gk = gk; this.seed = seed;
        }

        /** Where the seam after band b sits on the pixel row at y. */
        int seam(int b, int y) {
            int n = bands.length;
            double base = x0 + (b + 1) * len / (double) n;
            double bandW = len / (double) n;
            double lean = Math.min(h * 1.2, bandW * 0.9);
            int rows = Math.max(1, h / gk), r = Math.max(0, Math.min(rows - 1, (y - y0) / gk));
            double f = rows == 1 ? 1 : r / (double) (rows - 1);
            double nose = lean * (Math.pow(f, 0.35) - 0.7);
            double wave = Math.sin(r * 0.6 + (seed + b) * 1.9) * 1.2 + Math.sin(r * 1.3 + b * 2.3) * 0.7;
            int v = (int) Math.round(base + nose + wave * gk);
            return v / gk * gk;
        }

        void fill(int x, int y, int w, int hh) {
            if (w <= 0 || hh <= 0) return;
            if (bands == null || bands.length == 0) { put(x, y, w, hh, color); return; }
            int n = bands.length, end = x + w, cx = x;
            int[] seams = new int[n - 1];
            for (int b = 0; b < n - 1; b++) seams[b] = Math.max(b == 0 ? Integer.MIN_VALUE : seams[b - 1], seam(b, y));
            while (cx < end) {
                int b = 0;
                while (b < n - 1 && cx >= seams[b]) b++;
                int stop = b < n - 1 ? Math.min(end, Math.max(cx + 1, seams[b])) : end;
                put(cx, y, stop - cx, hh, bands[b]);
                cx = stop;
            }
        }

        /** Specks of each colour thrown over the seam into the next one, mostly low down. */
        void spatter(int fillEnd) {
            if (bands == null || bands.length < 2) return;
            for (int b = 0; b < bands.length - 1; b++) {
                for (int i = 0; i < 10; i++) {
                    int a = hash(i * 13 + b * 101 + 7, seed), c = hash(i * 29 + b * 57 + 3, seed);
                    double depth = Math.pow((a % 1000) / 1000.0, 0.55);
                    int py = y0 + (int) (depth * (h - gk)) / gk * gk;
                    int sx = seam(b, py);
                    double reach = (c % 1000) / 1000.0;
                    boolean ahead = (a >> 8) % 4 != 0;                  // mostly the earlier colour running ahead
                    int px = (sx + (ahead ? 1 : -1) * (gk + (int) (reach * reach * 6 * gk))) / gk * gk;
                    int size = (c >> 9) % 4 == 0 ? 2 * gk : gk;
                    if (px < x0 || px + size > fillEnd) continue;
                    put(px, py, size, size, bands[ahead ? b : b + 1]);
                }
            }
        }

        void put(int x, int y, int w, int hh, int c) { g.fill(x, y, w, hh, c); out.add(new int[]{x, y, w, hh, c}); }
    }

    private static int hash(int a, int b) {
        int v = a * 73856093 ^ b * 19349663;
        v ^= v >>> 13; v *= 0x5bd1e995; v ^= v >>> 15;
        return v & 0x7fffffff;
    }

    private void veil(Surface g, int x, int y, int w, int h, int c) { for (int yy = 0; yy < h; yy += 2 * k) g.fill(x, y + yy, w, k, c); }

    // ---- patches

    private final Map<String, Object> patchCache = new HashMap<>();

    private Object cached(String key, java.util.function.Supplier<Engine.Image> make) {
        Object img = patchCache.get(key);
        if (img == null) {
            Engine.Image im = make.get();
            img = host.makeImage(im.argb, im.width, im.height);
            if (patchCache.size() > 200) patchCache.clear();
            patchCache.put(key, img);
        }
        return img;
    }

    /** A sample of a style at the real pixel size (smaller only if two pixels wouldn't fit), in the current tones. */
    private Object stylePatch(Style st, int pw, int ph) {
        int cell = Math.max(2, ph / 2);   // a close-up: two rows of cells, whatever the pixel size
        // always the same white and dark grey: the patch shows what the settings do, never the mode's colours
        int[] values = {WHITE, CHOSEN};   // the app's one light grey and one dark grey
        String key = "style|" + st + "|" + pw + "x" + ph + "|" + cell + "|" + Arrays.toString(values) + "|" + Arrays.toString(s.knob[st.ordinal()]);
        return cached(key, () -> {
            int cols = (pw + cell - 1) / cell + 1, rows = (ph + cell - 1) / cell + 1;
            int[] c = new int[cols * rows];
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) c[y * cols + x] = values[(x + y) % values.length];
            Engine.Image im = Engine.draw(new Grid(cols, rows, c), st, cell, s);
            int[] out = new int[pw * ph];
            for (int y = 0; y < ph; y++) System.arraycopy(im.argb, y * im.width, out, y * pw, pw);
            return new Engine.Image(pw, ph, out);
        });
    }

    private static int[] greyValues(int n) {
        n = Math.max(2, Math.min(8, n));
        double gamma = Math.log(255.0 / 96.0) / Math.log(2.0);
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = n == 3 && i == 1 ? 96 : (int) Math.round(255 * Math.pow((double) i / (n - 1), gamma));
        return v;
    }

    private static boolean light(int c) { return (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)) > 118; }

    private int[] greyColors() {
        int[] v = greyValues(s.greyTones), c = new int[v.length];
        for (int i = 0; i < v.length; i++) c[i] = 0xff000000 | v[i] << 16 | v[i] << 8 | v[i];
        return c;
    }

    /** The Color palette for the preview photo, darkest to lightest (null until a photo is loaded). */
    private int[] paletteByLight() {
        int[] pal = currentPalette();
        if (pal == null) return null;
        Integer[] o = new Integer[pal.length];
        for (int i = 0; i < o.length; i++) o[i] = pal[i];
        Arrays.sort(o, Comparator.comparingDouble(c -> 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)));
        int[] out = new int[o.length];
        for (int i = 0; i < out.length; i++) out[i] = o[i];
        return out;
    }

    private int[] currentPalette() {
        Picture p = picture;
        if (p == null) return null;
        String key = pictureKey + "|" + s.colors + "|" + s.cells;
        int[] pal = palettes.get(key);
        if (pal == null) { pal = Engine.palette(p, s); palettes.clear(); palettes.put(key, pal); }
        return pal;
    }

    private final Map<String, int[]> palettes = new HashMap<>();
    static final int MARK_OFF = 0xff333333;

    private static int[] pad8(int[] lit) {
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = i < lit.length ? lit[i] : MARK_OFF;
        return out;
    }

    private int[] colorMarks() {
        int[] pal = paletteByLight();
        int steps = (s.colors - 4) / 4 + 1;
        int[] lit = new int[steps];
        for (int i = 0; i < steps; i++) lit[i] = pal == null ? GREY : pal[(int) Math.round(i * (pal.length - 1) / (double) Math.max(1, steps - 1))];
        return pad8(lit);
    }

    /** The XOXO logo as 8 marks (X O X O / O X O X) in the patch's space, no box behind them. */
    private int[][] drawMarks(Surface g, int px, int py, int pw, int ph, int[] colors, int behind) {
        // spread out: marks half the patch's height, spaced across its whole width
        int d = ph / 2, rowPitch = d, colPitch = (pw - d) / 3, x0 = px, y0 = py;
        boolean[] xs = Engine.stamp(Style.STITCH, d), os = Engine.stamp(Style.BEADS, d);
        g.clip(px, py, pw, ph);
        for (int i = 0; i < 8; i++) {
            int r = i / 4, c = i % 4;
            runs(g, (r + c) % 2 == 0 ? xs : os, x0 + c * colPitch, y0 + r * rowPitch, d, legible(colors[i], behind));
        }
        g.unclip();
        // tap areas: the patch split into a 4 x 2 grid, one cell per mark
        int[][] spots = new int[8][];
        for (int i = 0; i < 8; i++) {
            int r = i / 4, c = i % 4, cx0 = px + c * pw / 4, cy0 = py + r * ph / 2;
            spots[i] = new int[]{cx0, cy0, px + (c + 1) * pw / 4 - cx0, py + (r + 1) * ph / 2 - cy0};
        }
        return spots;
    }

    /** cellmate, each letter in the next colour of the accent palette. */
    private void title(Surface g, int x, int y) {
        String name = "cellmate";
        for (int i = 0; i < name.length(); i++) {
            String ch = name.substring(i, i + 1);
            g.text(ch, x, y, t, Settings.SWATCHES[i % Settings.SWATCHES.length]);
            x += g.textWidth(name.substring(0, i + 1), t) - g.textWidth(name.substring(0, i), t);   // keep the font's own spacing
        }
    }

    private static double luma(int c) { return 0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255); }

    /**
     * A mark's colour, moved just far enough from what's behind it that it never disappears into it
     * (a black tone on the black menu, a white one on the white highlight). Lit colours are squeezed
     * into the range that stands clear of the background, keeping their order and hue; unused marks
     * become a faint ghost that's still clearly dimmer than any lit one.
     */
    private static int legible(int c, int behind) {
        final double gap = 60;
        double b = luma(behind);
        boolean darkBg = b < 128;
        if (c == MARK_OFF) { int v = (int) Math.round(darkBg ? b + 30 : b - 40); return 0xff000000 | v << 16 | v << 8 | v; }
        double l = luma(c);
        int r = c >> 16 & 255, g = c >> 8 & 255, bl = c & 255;
        boolean below = b - gap >= 0, above = b + gap <= 255;   // which sides of the background have room
        double target;
        if (r == g && g == bl) {   // greys: squeeze out of the band around the background, keeping every tone apart and in order
            if (below && above) target = l < b ? l * (b - gap) / b : b + gap + (l - b) * (255 - b - gap) / (255 - b);
            else if (above) target = b + gap + l * (255 - b - gap) / 255;
            else target = l * (b - gap) / 255;
        } else {                   // colours: only nudged if too close, toward the side with room
            target = l;
            if (Math.abs(l - b) < gap) target = (l >= b && above) || !below ? b + gap : b - gap;
        }
        target = Math.max(0, Math.min(255, target));
        if (target > l) {          // lighten toward white
            double t = (target - l) / Math.max(1, 255 - l);
            r += (int) Math.round((255 - r) * t); g += (int) Math.round((255 - g) * t); bl += (int) Math.round((255 - bl) * t);
        } else if (target < l) {   // darken toward black
            double t = (l - target) / Math.max(1, l);
            r -= (int) Math.round(r * t); g -= (int) Math.round(g * t); bl -= (int) Math.round(bl * t);
        }
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    // ---- preview

    private void drawPreview(Surface g, int x, int y, int w, int h) {
        int navH = t + 12 * k;
        // the whole canvas is the button to add pictures, or to change them
        hits.add(new Hit(x, y, w, input == null ? h : h - navH, "", CANVAS, 0));
        if (input == null) { centered(g, "Tap or click here to add a photo or a folder", x, y, w, h, GREY); return; }
        if (items == null || items.isEmpty()) {
            centered(g, items == null ? "Looking for images..." : "No images found", x, y, w, h, DIM);
            drawClear(g, x + w, y + h - (t + 12 * k));
            return;
        }
        int bw = w, bh = h - navH;
        int pw = Math.max(4 * t, w / 4), ph = pw;
        if (bw != boxW || bh != boxH || pw != pipW) { boxW = bw; boxH = bh; pipW = pw; pipH = ph; schedulePreview(); }
        if (hits == mainHits) { boxX = x; boxY = y; }
        if (previewImg == null) centered(g, "Rendering...", x, y, w, bh, DIM);
        else {
            // the picture at the current zoom (stretched until the sharper one arrives), clipped to the canvas
            int dw = (int) Math.round(previewImgW * zoom / previewImgZoom), dh = (int) Math.round(previewImgH * zoom / previewImgZoom);
            int ix = place(x, bw, dw, zx), iy = place(y, bh, dh, zy);
            g.clip(x, y, bw, bh);
            if (dw == previewImgW && dh == previewImgH) g.image(previewImg, ix, iy); else g.image(previewImg, ix, iy, dw, dh);
            if (beforeImg != null) {   // the original, small, in the top-left corner
                int bx = Math.max(ix, x) + 6 * k, by = Math.max(iy, y) + 6 * k;
                if (dw < beforeW * 2) { bx = x; by = y; }
                g.image(beforeImg, bx, by);
            }
            g.unclip();
        }
        // page line: < Style  2 / 3 >
        List<Style> list = chosenStyles();
        if (!list.contains(page)) list.add(page);
        list.sort(Comparator.comparingInt(Enum::ordinal));
        String label = page.label + (styleOn[page.ordinal()] ? "" : " (not chosen)") + "   " + (list.indexOf(page) + 1) + " / " + list.size();
        int lw = g.textWidth(label, t), ly = y + h - navH + 6 * k, cx = x + w / 2;
        g.text(label, cx - lw / 2, ly, t, GREY);
        int aw = 6 * k, cyA = ly + t / 2;
        triangle(g, cx - lw / 2 - 16 * k - aw, cyA, aw, true, list.size() > 1 ? WHITE : DIM);
        triangle(g, cx + lw / 2 + 16 * k, cyA, aw, false, list.size() > 1 ? WHITE : DIM);
        hits.add(new Hit(x, ly - 4 * k, w / 2, t + 8 * k, "", PAGE_LEFT, 0));
        hits.add(new Hit(x + w / 2, ly - 4 * k, w - w / 2, t + 8 * k, "", PAGE_RIGHT, 0));
        drawClear(g, x + w, y + bh);
    }

    /** Where a picture dw wide goes in a canvas from x, w wide: centred if it fits, else showing point f of it in the middle (never past its edges). */
    private static int place(int x, int w, int dw, double f) {
        if (dw <= w) return x + (w - dw) / 2;
        int left = (int) Math.round(x + w / 2.0 - f * dw);
        return Math.max(x + w - dw, Math.min(x, left));
    }

    /** The Start button: green, "Start" and what it will make, in black. */
    private void drawStart(Surface g, int x, int y, int w, int h) {
        g.fill(x, y, w, h, START_GREEN);
        String what = startSummary(), label = "Start";
        int big = h >= 2 * t + 8 * k ? t + t / 2 : t, lw = g.textWidth(label, big), gap = 18 * k, ww = what.isEmpty() ? 0 : gap + g.textWidth(what, t);
        int lx = x + (w - lw - ww) / 2;
        g.text(label, lx, y + (h - big) / 2, big, BLACK);
        if (ww > 0) g.text(what, lx + lw + gap, y + (h - t) / 2 + (big - t) / 3, t, 0xff1c3a24);
        hits.add(new Hit(x, y, w, h, "", START_BUTTON, 0));
    }

    /** The clear button: a red X and the word, in the canvas's bottom-right corner (right, bottom). */
    private void drawClear(Surface g, int right, int bottom) {
        String word = "Clear";
        int d = t, tw = g.textWidth(word, t), pad = 6 * k;
        int bw = pad + d + 6 * k + tw + pad, bh = t + 2 * pad, bx = right - bw, by = bottom - bh;
        g.fill(bx, by, bw, bh, CHOSEN);
        runs(g, Engine.stamp(Style.STITCH, d), bx + pad, by + pad, d, CLEAR_RED);
        g.text(word, bx + pad + d + 6 * k, by + pad, t, WHITE);
        hits.add(new Hit(bx, by, bw, bh, "", CLEAR, 0));
    }

    private void drawPopup(Surface g, int w, int h) {
        // big, easy-to-hit choices: twice a row on upright (touch) screens, half again on wide ones
        boolean tall = w < h;
        popupRowH = tall ? 2 * rowH : rowH * 3 / 2;
        int ot = tall ? t * 5 / 4 : t;   // their text
        int lineH = t + 8 * k, pad = 14 * k;
        popupW = 24 * t;
        for (String line : popupInfo) popupW = Math.max(popupW, g.textWidth(line, t) + 2 * pad);
        // centred on the canvas when it's about the pictures (and the canvas is on this screen), else on the screen
        boolean onCanvas = popupOverCanvas && !previewOnOtherScreen && previewW > 0;
        int ax = onCanvas ? previewX : 0, ay = onCanvas ? previewY : 0, aw = onCanvas ? previewW : w, ah = onCanvas ? previewH : h;
        popupW = Math.min(aw - (onCanvas ? 0 : 40 * k), popupW);
        int ph = pad + lineH + popupInfo.length * lineH + pad + popup.length * popupRowH;
        popupX = ax + (aw - popupW) / 2;
        int top = Math.max(0, ay + (ah - ph) / 2);
        g.fill(0, 0, w, h, 0xc0000000);   // dim the menu behind
        g.fill(popupX, top, popupW, ph, CHOSEN);
        g.text(popupTitle, popupX + pad, top + pad, t, WHITE);
        for (int i = 0; i < popupInfo.length; i++)
            g.text(fitText(g, popupInfo[i], popupW - 2 * pad, t), popupX + pad, top + pad + (i + 1) * lineH, t, GREY);
        popupY = top + pad + lineH + popupInfo.length * lineH + pad;
        for (int i = 0; i < popup.length; i++) {
            int ry = popupY + i * popupRowH;
            boolean on = i == popupSel;
            // a yes (go ahead) is green, a no (back out) red, anything else the usual greys
            int tone = toneOf(popup[i]);
            if (on) g.fill(popupX, ry, popupW, popupRowH, tone != 0 ? tone : HILITE);
            g.text(popup[i], popupX + 10 * k, ry + (popupRowH - ot) / 2, ot, on ? BLACK : tone != 0 ? tone : WHITE);
        }
    }

    private void drawJob(Surface g, int w, int y, int h) {
        int x = w / 6, bw = w - 2 * x;
        int cy = y + h / 2 - 3 * (t + 10 * k);
        boolean running = screen == Screen.RUNNING;
        g.text(running ? (cancel.get() ? "Cancelling..." : "Converting") : (cancel.get() ? "Cancelled" : "Done"), x, cy, t * 2, WHITE);
        cy += 2 * t + 20 * k;
        int barH = t;
        g.fill(x, cy, bw, barH, CHOSEN);   // grey track, white fill (no border)
        int fillW = total == 0 ? 0 : (int) (bw * (long) done / total);
        g.fill(x, cy, fillW, barH, WHITE);
        cy += barH + 14 * k;
        g.text(done + " / " + total, x, cy, t, GREY);
        if (running && done > 0) {
            long ms = System.currentTimeMillis() - started;
            long left = ms * (total - done) / done / 1000;
            String eta = left >= 60 ? (left / 60) + " min left" : left + " s left";
            g.text(eta, x + bw - g.textWidth(eta, t), cy, t, GREY);
        }
        cy += t + 14 * k;
        if (running) {
            g.text(fitText(g, current, bw, t), x, cy, t, DIM);
        } else {
            g.text(written + " written   " + skipped + " skipped   " + failed + " failed", x, cy, t, WHITE);
            cy += t + 10 * k;
            g.text(fitText(g, "Saved in " + outputName, bw, t), x, cy, t, GREY);
            if (!firstError.isEmpty()) { cy += t + 10 * k; g.text(fitText(g, firstError, bw, t), x, cy, t, DIM); }
        }
    }

    private final List<Object> helpKeys = new ArrayList<>();

    /** Button hints, bottom right: glyph, label, key (tappable). */
    /** The button hints along the bottom right; returns where they start on the left. */
    private int drawHelp(Surface g, int w, int h, Object[] items3) {
        int y = h - (t + 20 * k) + 4 * k + (t + 4 * k) / 2;
        int x = w - 14 * k;
        int d = t + 2 * k;
        helpKeys.clear();
        for (int i = items3.length - 3; i >= 0; i -= 3) {
            String glyph = (String) items3[i], label = (String) items3[i + 1];
            int right = x;
            x -= g.textWidth(label, t);
            int tone = toneOf(label);   // OK in green, Cancel in red
            g.text(label, x, y - t / 2, t, tone != 0 ? tone : GREY);
            int gw = glyph.equals("LR") ? 2 * d + 4 * k : d;
            x -= 6 * k + gw;
            if (glyph.equals("LR")) {
                for (int j = 0; j < 2; j++) {
                    int bx = x + j * (d + 4 * k);
                    roundRect(g, bx, y - d / 2, d, d, 3 * k, ICON);
                    String l = j == 0 ? "L" : "R";
                    g.text(l, bx + (d - g.textWidth(l, t)) / 2, y - t / 2, t, BLACK);
                }
            } else {
                circle(g, x, y - d / 2, d, ICON);
                g.text(glyph, x + (d - g.textWidth(glyph, t)) / 2, y - t / 2, t, BLACK);
            }
            helpKeys.add(items3[i + 2]);
            hits.add(new Hit(x, y - d / 2, right - x, d, "", HELP, helpKeys.size() - 1));
            x -= 18 * k;
        }
        return x + 18 * k;
    }

    // ------------------------------------------------------------------ pixel shapes

    private void runs(Surface g, boolean[] m, int x, int y, int d, int color) {
        for (int yy = 0; yy < d; yy++) {
            int xx = 0;
            while (xx < d) {
                if (!m[yy * d + xx]) { xx++; continue; }
                int start = xx;
                while (xx < d && m[yy * d + xx]) xx++;
                g.fill(x + start, y + yy, xx - start, 1, color);
            }
        }
    }

    private void triangle(Surface g, int x, int cy, int w, boolean left, int color) {
        for (int i = 0; i < w; i++) {
            int len = (w - i) * 2 - 1;
            int cx = left ? x + w - 1 - i : x + i;   // tip on the pointing side
            g.fill(cx, cy - len / 2, 1, len, color);
        }
    }

    private void circle(Surface g, int x, int y, int d, int color) {
        double r = d / 2.0;
        for (int i = 0; i < d; i++) {
            double dy = i + 0.5 - r;
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + (int) r - half, y + i, 2 * half, 1, color);
        }
    }

    private void roundRect(Surface g, int x, int y, int w, int h, int r, int color) {
        g.fill(x + r, y, w - 2 * r, h, color);
        g.fill(x, y + r, w, h - 2 * r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + r - half, y + i, w - 2 * (r - half), 1, color);
            g.fill(x + r - half, y + h - 1 - i, w - 2 * (r - half), 1, color);
        }
    }

    private void centered(Surface g, String text, int x, int y, int w, int h, int color) {
        text = fitText(g, text, w, t);
        g.text(text, x + (w - g.textWidth(text, t)) / 2, y + (h - t) / 2, t, color);
    }

    private static String fitText(Surface g, String s, int maxW, int t) {
        if (s == null) return "";
        if (g.textWidth(s, t) <= maxW) return s;
        String dots = "...";
        int lo = 0, hi = s.length();
        while (lo < hi) { int mid = (lo + hi + 1) / 2; if (g.textWidth(s.substring(0, mid) + dots, t) <= maxW) lo = mid; else hi = mid - 1; }
        return s.substring(0, lo) + dots;
    }

    // ================================================================== prefs

    private final Map<String, String> savedKnobs = new HashMap<>();

    private void savePrefs() {
        StringBuilder b = new StringBuilder();
        b.append("color=").append(s.color).append('\n').append("tones=").append(s.greyTones).append('\n')
         .append("colors=").append(s.colors).append('\n').append("accentOn=").append(s.accent).append('\n')
         .append("swatch8=").append(s.swatch).append('\n')
         .append("accentAmount=").append(s.accentRange).append('\n')
         .append("cells=").append(s.cells).append('\n').append("cell=").append(s.cellSize).append('\n')
         .append("replace=").append(replace).append('\n');
        StringBuilder st = new StringBuilder();
        for (Style x : Style.values()) if (styleOn[x.ordinal()]) st.append(x.name()).append(',');
        b.append("styles=").append(st).append('\n');
        for (Map.Entry<String, String> e : savedKnobs.entrySet()) b.append("knobs4.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        if (input != null) b.append("input=").append(inputFolder ? "d" : "f").append(host.savePlace(input)).append('\n').append("inputName=").append(inputName).append('\n');
        try { host.savePrefs(b.toString()); } catch (Exception ignored) { }
    }

    private void loadPrefs() {
        String text;
        try { text = host.loadPrefs(); } catch (Exception e) { return; }
        if (text == null) return;
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) { int eq = line.indexOf('='); if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1)); }
        try {
            s.color = Boolean.parseBoolean(m.getOrDefault("color", "false"));
            s.greyTones = clamp(Integer.parseInt(m.getOrDefault("tones", "3")), 2, 8);
            s.colors = clamp(Integer.parseInt(m.getOrDefault("colors", "12")) / 4 * 4, 4, 32);
            s.accent = Boolean.parseBoolean(m.getOrDefault("accentOn", "false"));
            s.swatch = clamp(Integer.parseInt(m.getOrDefault("swatch8", "0")), 0, Settings.SWATCHES.length - 1);
            s.accentRange = clamp(Integer.parseInt(m.getOrDefault("accentAmount", "" + Settings.ACCENT_DEFAULT)), 0, Settings.ACCENT_SHARE.length - 1);
            s.cells = Settings.DETAIL[Settings.nearest(Settings.DETAIL, Integer.parseInt(m.getOrDefault("cells", "64")))];
            s.cellSize = Settings.PIXEL[Settings.nearest(Settings.PIXEL, Integer.parseInt(m.getOrDefault("cell", "30")))];
            replace = Boolean.parseBoolean(m.getOrDefault("replace", "false"));
            String st = m.get("styles");
            if (st != null) {
                Arrays.fill(styleOn, false);
                for (String n : st.split(",")) if (!n.isEmpty()) try { styleOn[Style.valueOf(n).ordinal()] = true; } catch (IllegalArgumentException ignored) { }
            }
            for (Style x : Style.values()) {
                String v = m.get("knobs4." + x.name());
                if (v == null) continue;
                savedKnobs.put(x.name(), v);
                String[] parts = v.split(",");
                for (int i = 0; i < parts.length && i < s.knob[x.ordinal()].length; i++)
                    if (!parts[i].isEmpty()) s.knob[x.ordinal()][i] = clamp(Integer.parseInt(parts[i]), 0, Settings.KNOBS[x.ordinal()][i].steps() - 1);
            }
        } catch (NumberFormatException ignored) { }
        String in = m.get("input");
        if (in != null && in.length() > 1) {
            Object place = host.restorePlace(in.substring(1));
            if (place != null) { input = place; inputFolder = in.charAt(0) == 'd'; inputName = m.getOrDefault("inputName", ""); scan(); }
        }
    }
}
