package cellmate.ui;

import cellmate.core.Engine;
import cellmate.core.Grid;
import cellmate.core.Picture;
import cellmate.core.Settings;
import cellmate.core.Settings.Kind;
import cellmate.core.Settings.Knob;
import cellmate.core.Settings.Style;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cellmate's whole interface, drawn by hand in the Black Mesa Tennis start-screen style: black,
 * pixel font, an inverted white bar for the highlighted row, left/right to change values, A to
 * select, B to go back. The desktop and Android apps only forward drawing and input.
 *
 * A chosen style shows a white-bordered box on the right of its row. The box is its slider: it
 * fills like a pixelated progress bar, split into one section per setting (Melted adds two small
 * dials, rotation and meltiness). L/R moves between sections. One preview shows a style at a
 * time, with arrows at the bottom to page through the chosen styles and the original photo as a
 * small picture in the top-right corner.
 */
public final class Ui {
    public enum Pick { INPUT, INPUT_FOLDER, INPUT_IMAGE, OUTPUT }
    public enum Key { UP, DOWN, LEFT, RIGHT, A, B, PREV, NEXT, PAGE_PREV, PAGE_NEXT, SAVE, RESET }

    static final int BLACK = 0xff000000, WHITE = 0xfffafafa, GREY = 0xff9a9a9a, DIM = 0xff4a4a4a, ICON = 0xff7a7a7a;

    private final Host host;
    private final Settings s = new Settings();
    private final boolean[] styleOn = new boolean[Style.values().length];
    private boolean replace, zoom;

    private Object input, output;
    private boolean inputFolder;
    private String inputName = "", outputName = "";
    private List<String> items;            // images in the input (null while scanning)
    private int previewIndex;
    private String message = "";
    private String[] popup;                // small chooser (Android input: folder or image)
    private int popupSel;

    // ---- preview: one style at a time, plus the original as a picture-in-picture
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "preview"));
    private final AtomicLong previewSeq = new AtomicLong();
    private Style page = Style.BORDER;     // the style the preview shows
    private Object previewImg, beforeImg;
    private int previewImgW, previewImgH, beforeW, beforeH;
    private Style previewImgStyle;
    private Picture picture;
    private String pictureKey;
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
    private boolean previewOnOtherScreen;

    // ---- layout of the last frame, for touch and mouse
    private int k = 1, t = 20, rowH, menuX, menuY, menuW, menuH, previewX, previewY, previewW, previewH;
    private final List<Hit> hits = new ArrayList<>();

    public Ui(Host host) {
        this.host = host;
        styleOn[Style.BORDER.ordinal()] = true;
        loadPrefs();
    }

    private static Thread daemon(Runnable r, String name) { Thread th = new Thread(r, "cellmate-" + name); th.setDaemon(true); return th; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ================================================================== rows

    /** One section of a slider box (or a dial next to it). */
    private interface Part {
        int steps();
        int get();
        void set(int v);
        boolean dial();
        String name();
    }

    private Part knobPart(Style st, int i) {
        Knob kn = Settings.KNOBS[st.ordinal()][i];
        return new Part() {
            public int steps() { return kn.steps(); }
            public int get() { return s.knob[st.ordinal()][i]; }
            public void set(int v) { s.knob[st.ordinal()][i] = clamp(v, 0, kn.steps() - 1); changed(); }
            public boolean dial() { return kn.kind == Kind.DIAL; }
            public String name() { return SHORT.getOrDefault(kn.name, kn.name); }
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

    private Part part(String name, int steps, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
        return new Part() {
            public int steps() { return steps; }
            public int get() { return get.getAsInt(); }
            public void set(int v) { set.accept(clamp(v, 0, steps - 1)); changed(); }
            public boolean dial() { return false; }
            public String name() { return name; }
        };
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
        /** Box rows whose sections are colour cells (Accent). */
        int[] cellColors() { return null; }
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

    private List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(new ValueRow("Input", "Input", () -> input != null ? inputName : "Choose", null, this::pickInput));
        r.add(new ValueRow("Output", "Output", () -> output != null ? outputName : "Choose", null, () -> host.pick(Pick.OUTPUT)));
        for (Style st : Style.values()) r.add(new StyleRow(st));
        r.add(new Row("Greyscale", "Greyscale") {
            final List<Part> p = Collections.singletonList(part("Tones", 7, () -> s.greyTones - 2, v -> s.greyTones = v + 2));
            boolean box() { return true; }
            boolean chosen() { return !s.color; }
            void choose() { if (s.color) { s.color = false; changed(); } }
            List<Part> parts() { return p; }
            Object patch(int w, int h) { return tonesPatch(w, h); }
        });
        r.add(new Row("Color", "Color") {
            final List<Part> p = Collections.singletonList(part("Colors", 8, () -> (s.colors - 4) / 4, v -> s.colors = 4 + 4 * v));
            boolean box() { return true; }
            boolean chosen() { return s.color; }
            void choose() { if (!s.color) { s.color = true; changed(); } }
            List<Part> parts() { return p; }
            Object patch(int w, int h) { return colorPatch(w, h); }
        });
        r.add(new Row("Accent", "Accent") {
            final List<Part> p = Collections.singletonList(part("Accent", Settings.SWATCHES.length, () -> s.swatch, v -> s.swatch = v));
            boolean box() { return true; }
            boolean available() { return !s.color; }
            boolean chosen() { return s.accent && !s.color; }
            void choose() {
                if (s.color) { message = "Accent works with Greyscale."; return; }
                s.accent = !s.accent; changed();
            }
            List<Part> parts() { return p; }
            Object patch(int w, int h) { return accentPatch(w, h); }
            int[] cellColors() { return Settings.SWATCHES; }
        });
        if (items != null && items.size() > 1)
            r.add(new ValueRow("Preview image", "Preview image", () -> (previewIndex + 1) + " / " + items.size(), d -> {
                previewIndex = Math.floorMod(previewIndex + d, items.size()); schedulePreview();
            }, null));
        r.add(new ValueRow("Zoom", "Preview zoom", () -> zoom ? "1:1" : "Fit", d -> { zoom = !zoom; changed(); }, null));
        r.add(new ValueRow("Detail", "Detail", () -> s.cells + " across", d -> { s.cells = clamp(s.cells + 8 * d, 16, 256); changed(); }, null));
        r.add(new ValueRow("Pixel", "Pixel size", () -> s.cellSize + " px", d -> { s.cellSize = clamp(s.cellSize + d, 2, 40); changed(); }, null));
        r.add(new ValueRow("Existing", "Existing files", () -> replace ? "Replace" : "Skip", d -> { replace = !replace; savePrefs(); }, null));
        r.add(new ValueRow("Start", "Start", this::startSummary, null, this::start));
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
        if (row.style() != null && row.style() != page) { page = row.style(); schedulePreview(); }
    }

    private void changed() { savePrefs(); schedulePreview(); host.repaint(); }

    private void pickInput() {
        if (host.canPickEither()) { host.pick(Pick.INPUT); return; }
        popup = new String[]{"A folder of images", "One image"};
        popupSel = 0;
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
                p.set(p.dial() && p.steps() > 6 ? Math.floorMod(p.get() + d, p.steps()) : p.get() + d);
                break;
            }
            case A: if (row.box()) row.choose(); else row.activate(); break;
            case B: if (i != 0) select(r, 0); break;
            case SAVE: if (row.style() != null) save(row.style()); break;
            case RESET: if (row.style() != null) reset(row.style()); break;
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

    private void reset(Style st) {
        s.knob[st.ordinal()] = Settings.defaults()[st.ordinal()];
        message = st.label + " is back to its defaults.";
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

    private void popupChoose(int i) { popup = null; host.pick(i == 0 ? Pick.INPUT_FOLDER : Pick.INPUT_IMAGE); }

    // ================================================================== pointer (mouse and touch)

    /** Something drawn last frame that can be tapped or dragged. */
    private static final class Hit {
        final int x, y, w, h; final String row; final Object what; final int index;
        Hit(int x, int y, int w, int h, String row, Object what, int index) { this.x = x; this.y = y; this.w = w; this.h = h; this.row = row; this.what = what; this.index = index; }
        boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }

    private static final Object ROW = new Object(), PAGE_LEFT = new Object(), PAGE_RIGHT = new Object(), HELP = new Object();
    private Hit down;
    private int downY, lastY;
    private boolean dragging, sliding;

    public void pointerDown(int x, int y) {
        downY = lastY = y; dragging = false; sliding = false; down = null;
        if (screen != Screen.MENU || popup != null) return;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) { down = hits.get(i); break; }
        if (down != null && down.what instanceof Part) { slide(down, x, y); sliding = true; }
    }

    public void pointerMove(int x, int y) {
        if (screen != Screen.MENU || popup != null) return;
        if (sliding) { slide(down, x, y); return; }
        if (!dragging && Math.abs(y - downY) > rowH / 2) dragging = true;
        if (dragging && rowH > 0) {
            int moved = (lastY - y) / rowH;
            if (moved != 0) { scrollBy(moved); lastY -= moved * rowH; }
        }
    }

    public void pointerUp(int x, int y) {
        if (screen == Screen.RUNNING) { cancel.set(true); message = "Cancelling..."; host.repaint(); return; }
        if (screen == Screen.DONE) { screen = Screen.MENU; host.repaint(); return; }
        if (popup != null) { tapPopup(x, y); host.repaint(); return; }
        if (sliding || dragging) { sliding = dragging = false; host.repaint(); return; }
        if (down == null) return;
        if (down.what == PAGE_LEFT) { turnPage(-1); return; }
        if (down.what == PAGE_RIGHT) { turnPage(1); return; }
        if (down.what == HELP) { key((Key) helpKeys.get(down.index)); return; }
        List<Row> r = rows();
        int idx = -1;
        for (int i = 0; i < r.size(); i++) if (r.get(i).key.equals(down.row)) idx = i;
        if (idx < 0) return;
        Row row = r.get(idx);
        boolean wasSelected = row.key.equals(selectedKey);
        select(r, idx);
        if (wasSelected) {
            if (row.box()) row.choose();
            else if (row.changeable() && x >= valueLeft && x < valueMid) row.change(-1);
            else if (row.changeable()) row.change(1);
            else row.activate();
        }
        host.repaint();
    }

    /** Old single-tap entry point (tests). */
    public void tap(int x, int y) { pointerDown(x, y); pointerUp(x, y); }

    private void slide(Hit h, int x, int y) {
        Part p = (Part) h.what;
        List<Row> r = rows();
        for (int i = 0; i < r.size(); i++) if (r.get(i).key.equals(h.row)) select(r, i);
        section.put(h.row, h.index);
        if (p.dial()) {
            double a = Math.toDegrees(Math.atan2(y - (h.y + h.h / 2.0), x - (h.x + h.w / 2.0))) + 90;   // 0 = up
            a = Math.floorMod((int) Math.round(a), 360);
            int v = p.steps() > 6 ? (int) Math.round(a / (360.0 / p.steps())) % p.steps()
                    : clamp((int) Math.round(Math.min(a, 270) / 270.0 * (p.steps() - 1)), 0, p.steps() - 1);
            if (v != p.get()) p.set(v);
        } else {
            int v = clamp((x - h.x) * p.steps() / Math.max(1, h.w), 0, p.steps() - 1);
            if (v != p.get()) p.set(v);
        }
        host.repaint();
    }

    private int popupX, popupY, popupW, popupRowH;

    private void tapPopup(int x, int y) {
        if (x < popupX || x >= popupX + popupW) { popup = null; return; }
        int i = (y - popupY) / Math.max(1, popupRowH);
        if (i >= 0 && i < popup.length) popupChoose(i); else popup = null;
    }

    /** Mouse wheel or swipe, in rows (positive = down). */
    public void scrollBy(int rowsDelta) { scroll = Math.max(0, scroll + rowsDelta); host.repaint(); }

    /** A tap on the second screen's preview: the arrows page through the styles. */
    public void tapPreviewScreen(int x, int y) {
        for (Hit h : hits) if (h.contains(x, y) && (h.what == PAGE_LEFT || h.what == PAGE_RIGHT)) { turnPage(h.what == PAGE_LEFT ? -1 : 1); return; }
    }

    // ================================================================== picking

    public void picked(Pick kind, Object place, String name) {
        if (place == null) return;
        if (kind == Pick.OUTPUT) {
            output = place; outputName = name;
        } else {
            input = place; inputName = name; inputFolder = kind == Pick.INPUT_FOLDER;
            items = null; previewIndex = 0; picture = null; pictureKey = null; previewImg = beforeImg = null;
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
        final int bw = boxW, bh = boxH, pw = pipW, ph = pipH; final boolean z = zoom;
        previewWorker.execute(() -> {
            if (seq != previewSeq.get()) return;
            try {
                String key = (folder ? "d:" : "f:") + in + "|" + rel;
                Picture p = picture;
                boolean newPicture = p == null || !key.equals(pictureKey);
                if (newPicture) { p = host.decode(in, folder, rel); picture = p; pictureKey = key; }
                Engine.Image photo = Engine.fit(new Engine.Image(p.width, p.height, p.argb), pw, ph);
                Object before = host.makeImage(photo.argb, photo.width, photo.height);
                if (seq != previewSeq.get()) return;
                Engine.Image full = Engine.draw(Engine.grid(p, set), style, set.cellSize, set);
                Engine.Image shown = z ? Engine.crop(full, bw, bh, 0.5, 0.5) : Engine.fit(full, bw, bh);
                Object img = host.makeImage(shown.argb, shown.width, shown.height);
                host.runOnUi(() -> {
                    if (seq != previewSeq.get()) return;
                    previewImg = img; previewImgW = shown.width; previewImgH = shown.height; previewImgStyle = style;
                    beforeImg = before; beforeW = photo.width; beforeH = photo.height;
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
        if (input == null) { message = "Choose an input first."; return; }
        if (output == null) { message = "Choose an output folder first."; return; }
        if (chosen.isEmpty()) { message = "Choose at least one style."; return; }
        if (items == null) { message = "Still looking for images..."; return; }
        if (items.isEmpty()) { message = "No images to convert."; return; }
        if (inputFolder && host.isInside(output, input)) { message = "Pick an output folder outside the input folder."; return; }
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
        daemon(() -> {
            for (int i = 0; i < list.size() && !cancel.get(); i++) {
                String rel = list.get(i);
                current = rel;
                List<Style> todo = new ArrayList<>();
                for (Style st : chosen) {
                    String target = Plan.styleFolder(name, folder, st) + "/" + outs.get(i);
                    if (!rep && host.exists(out, target)) { skipped++; done++; } else todo.add(st);
                }
                if (!todo.isEmpty()) {
                    try {
                        Picture p = host.decode(in, folder, rel);
                        Grid g = Engine.grid(p, set);
                        for (Style st : todo) {
                            if (cancel.get()) break;
                            host.writePng(out, Plan.styleFolder(name, folder, st) + "/" + outs.get(i), Engine.draw(g, st, set.cellSize, set));
                            written++; done++;
                            host.repaint();
                        }
                    } catch (Throwable e) {
                        int left = todo.size();
                        failed += left; done += left;
                        if (firstError.isEmpty()) firstError = rel + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
                host.repaint();
            }
            host.runOnUi(() -> { screen = Screen.DONE; host.repaint(); });
        }, "batch").start();
    }

    // ================================================================== drawing

    private int valueLeft, valueMid;

    /** Draws everything. */
    public void draw(Surface g, int w, int h) {
        k = Math.max(1, Math.min(Math.round(h / 540f), Math.round(w / 640f)));
        t = 20 * k;          // pixel font size (one font pixel = k screen pixels)
        rowH = t + 8 * k;
        hits.clear();
        g.fill(0, 0, w, h, BLACK);

        g.text("CELLMATE", 14 * k, 8 * k, t, GREY);
        String top = input == null ? "" : inputName + (items == null ? "" : "  " + items.size() + (items.size() == 1 ? " image" : " images"));
        top = fitText(g, top, w / 2, t);
        g.text(top, w - 14 * k - g.textWidth(top, t), 8 * k, t, GREY);

        int topH = t + 18 * k, bottomH = t + 20 * k;
        int midY = topH, midH = h - topH - bottomH;

        if (screen != Screen.MENU) {
            drawJob(g, w, midY, midH);
            drawHelp(g, w, h, screen == Screen.RUNNING ? new Object[]{"B", "Cancel", Key.B} : new Object[]{"A", "OK", Key.A});
            return;
        }

        if (previewOnOtherScreen) {
            menuX = w / 8; menuY = midY; menuW = w - w / 4; menuH = midH;
            previewW = previewH = 0;
        } else if (w >= h) {
            menuX = 0; menuY = midY; menuW = (int) (w * 0.46); menuH = midH;
            previewX = menuW + 16 * k; previewY = midY; previewW = w - previewX - 14 * k; previewH = midH;
        } else {
            previewX = 14 * k; previewY = midY; previewW = w - 28 * k; previewH = (int) (midH * 0.42);
            menuX = 0; menuY = previewY + previewH + 10 * k; menuW = w; menuH = midH - previewH - 10 * k;
        }
        drawMenu(g);
        if (!previewOnOtherScreen) drawPreview(g, previewX, previewY, previewW, previewH);

        g.text(fitText(g, message, w / 2, t), 14 * k, h - bottomH + 6 * k, t, GREY);
        List<Row> r = rows();
        Row row = r.get(selectedIndex(r));
        List<Object> help = new ArrayList<>(Arrays.asList("A", row.box() ? (row.chosen() && row.style() != null ? "Remove" : "Choose") : "Select", Key.A));
        if (row.box() && row.parts().size() > 1 && row.chosen()) help.addAll(Arrays.asList("LR", "Section", Key.NEXT));
        if (row.style() != null && row.chosen()) help.addAll(Arrays.asList("X", "Save", Key.SAVE, "Y", "Reset", Key.RESET));
        else help.addAll(Arrays.asList("B", "Back", Key.B));
        drawHelp(g, w, h, help.toArray());
        if (popup != null) drawPopup(g, w, h);
    }

    /** Draws only the preview, for a second screen. */
    public void drawPreviewScreen(Surface g, int w, int h) {
        int saveK = k, saveT = t;
        k = Math.max(1, Math.round(h / 540f)); t = 20 * k;
        g.fill(0, 0, w, h, BLACK);
        hits.removeIf(x -> x.what == PAGE_LEFT || x.what == PAGE_RIGHT);
        drawPreview(g, 14 * k, 10 * k, w - 28 * k, h - 20 * k);
        k = saveK; t = saveT;
    }

    private void drawMenu(Surface g) {
        List<Row> r = rows();
        int sel = selectedIndex(r);
        int visible = Math.max(1, menuH / rowH);
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + visible) scroll = sel - visible + 1;
        scroll = clamp(scroll, 0, Math.max(0, r.size() - visible));
        int labelW = 0, widest = 0;
        for (Row row : r) if (row.box()) {
            labelW = Math.max(labelW, g.textWidth(row.label, t));
            int dials = 0;
            for (Part p : row.parts()) if (p.dial()) dials++;
            widest = Math.max(widest, boxWidth(row.parts().size() - dials) + dials * ((t | 1) + 8 * k));
        }
        boxColumn = menuX + menuW - 14 * k - widest;
        g.clip(menuX, menuY, menuW, menuH);
        for (int i = scroll; i < r.size() && i < scroll + visible; i++) {
            Row row = r.get(i);
            int y = menuY + (i - scroll) * rowH;
            boolean on = i == sel;
            if (on) g.fill(menuX, y, menuW, rowH, WHITE);
            hits.add(new Hit(menuX, y, menuW, rowH, row.key, ROW, 0));
            if (row.box()) drawBoxRow(g, row, y, on, labelW); else drawValueRow(g, row, y, on);
        }
        g.unclip();
        if (scroll > 0) triangleUp(g, menuX + menuW - 10 * k, menuY - 6 * k, 4 * k, true, DIM);
        if (scroll + visible < r.size()) triangleUp(g, menuX + menuW - 10 * k, menuY + visible * rowH + 2 * k, 4 * k, false, DIM);
    }

    private void drawValueRow(Surface g, Row row, int y, boolean on) {
        int ty = y + (rowH - t) / 2, right = menuX + menuW - 14 * k;
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
     * Label, sample patch, and the slider box. The box only appears once the row is chosen: a
     * border in the row's ink, split into sections that fill with pixel blocks like a progress
     * bar; dials sit after it.
     */
    private void drawBoxRow(Surface g, Row row, int y, boolean on, int labelW) {
        int ink = on ? BLACK : WHITE, cy = y + rowH / 2;
        boolean avail = row.available();
        g.text(row.label, menuX + 10 * k, y + (rowH - t) / 2, t, !avail ? DIM : ink);
        int right = menuX + menuW - 14 * k;
        List<Part> parts = row.parts();
        int dials = 0;
        for (Part p : parts) if (p.dial()) dials++;
        int bars = parts.size() - dials;
        int bh = rowH - 2 * k, dialD = (t | 1);
        int boxW = boxWidth(bars);
        int boxX = boxColumn;   // all boxes start in one column, all patches in another

        // patch, just left of the box
        int ph = rowH - 10 * k, pw = ph * 2;
        int px = boxX - 14 * k - pw;
        Object patch = row.patch(pw, ph);
        if (patch != null) g.image(patch, px, cy - ph / 2);
        if (!avail) veil(g, px, cy - ph / 2, pw, ph, on ? WHITE : BLACK);

        if (!row.chosen()) {
            if (on && avail) outline(g, boxX, cy - bh / 2, boxW, bh, k, ICON);   // a hint of where the box will be
            return;
        }
        int focusSec = clamp(section.getOrDefault(row.key, 0), 0, parts.size() - 1);
        boolean multi = parts.size() > 1;
        outline(g, boxX, cy - bh / 2, boxW, bh, 2 * k, ink);
        int inner = boxW - 8 * k, sx = boxX + 4 * k, secGap = 4 * k;
        int secW = bars == 0 ? 0 : (inner - secGap * (bars - 1)) / bars;
        int bi = 0, di = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            boolean focused = on && (!multi || i == focusSec);
            int c = !on ? WHITE : (focused ? BLACK : ICON);
            if (!on && multi && i != focusSec) c = GREY;
            if (p.dial()) {
                int dx = boxX + boxW + 8 * k + di * (dialD + 8 * k);
                dial(g, dx, cy - dialD / 2, dialD, p, c);
                hits.add(new Hit(dx, y, dialD, rowH, row.key, p, i));
                di++;
                continue;
            }
            int x0 = sx + bi * (secW + secGap);
            if (bi > 0) g.fill(x0 - secGap / 2 - k / 2, cy - bh / 2, Math.max(1, k), bh, ink);   // section divider
            blocks(g, x0, cy - bh / 2 + 3 * k, secW, bh - 6 * k, p, row.cellColors(), c, on ? WHITE : BLACK);
            hits.add(new Hit(x0, y, secW, rowH, row.key, p, i));
            bi++;
        }
    }

    /**
     * A pixelated progress bar: one block per step, filled up to the value. The setting's name
     * sits inside and flips colour where the blocks pass under it, like loading-bar text.
     */
    private void blocks(Surface g, int x, int y, int w, int h, Part p, int[] colors, int ink, int paper) {
        int n = p.steps();
        if (colors != null) {   // colour cells: all shown, the chosen one full height
            int gap = Math.max(1, k), bw = Math.max(1, (w - gap * (n - 1)) / n), x0 = x + (w - (bw * n + gap * (n - 1))) / 2;
            for (int i = 0; i < n; i++) {
                int inset = i == p.get() ? 0 : h / 4;
                g.fill(x0 + i * (bw + gap), y + inset, bw, h - 2 * inset, colors[i]);
            }
            return;
        }
        // fill in whole steps (the pixelated part), with a notch under each step boundary
        int filledTo = x + (int) Math.round((p.get() + 1) * w / (double) n);
        g.fill(x, y, filledTo - x, h, ink);
        for (int i = 1; i < n; i++) {
            int nx = x + (int) Math.round(i * w / (double) n);
            g.fill(nx - k / 2, y + h - 2 * k, Math.max(1, k), 2 * k, nx < filledTo ? paper : ink);
        }
        String word = fitText(g, p.name(), w - 4 * k, t);
        int tw = g.textWidth(word, t), tx = x + (w - tw) / 2, ty = y + (h - t) / 2 - 2 * k;
        g.clip(x, y, Math.max(0, filledTo - x), h);           // over the fill: paper colour
        g.text(word, tx, ty, t, paper);
        g.unclip();
        g.clip(filledTo, y, Math.max(0, x + w - filledTo), h); // over the empty part: ink
        g.text(word, tx, ty, t, ink);
        g.unclip();
    }

    private int boxColumn;

    private int boxWidth(int bars) { return Math.min(menuW * 2 / 5, Math.max(5 * t, bars * 4 * t)); }

    private void dial(Surface g, int x, int y, int d, Part p, int ink) {
        double ce = (d - 1) / 2.0;
        boolean[] ring = new boolean[d * d];
        for (int yy = 0; yy < d; yy++) for (int xx = 0; xx < d; xx++) {
            double r = Math.hypot(xx - ce, yy - ce);
            ring[yy * d + xx] = r <= d / 2.0 - 0.3 && r >= d / 2.0 - 0.3 - 2 * k;
        }
        runs(g, ring, x, y, d, ink);
        // rotation: all the way round; meltiness: a 270-degree sweep
        double deg = p.steps() > 6 ? p.get() * 360.0 / p.steps() : p.get() * 270.0 / (p.steps() - 1);
        double a = Math.toRadians(deg - 90);
        for (int i = 0; i <= d / 2 - 3 * k; i += k) {
            int px = (int) Math.round(x + ce + Math.cos(a) * i), py = (int) Math.round(y + ce + Math.sin(a) * i);
            g.fill(px - k, py - k, 2 * k, 2 * k, ink);
        }
    }

    private void outline(Surface g, int x, int y, int w, int h, int th, int c) {
        g.fill(x, y, w, th, c); g.fill(x, y + h - th, w, th, c); g.fill(x, y, th, h, c); g.fill(x + w - th, y, th, h, c);
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
        int cell = Math.max(2, Math.min(s.cellSize, ph / 2));
        int[] values = s.color ? new int[]{0xfffafafa, Settings.SWATCHES[0], Settings.SWATCHES[5], Settings.SWATCHES[2]} : lightTones();
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

    private int[] lightTones() {
        int[] all = greyValues(s.greyTones);
        int[] out = new int[all.length - 1];
        for (int i = 0; i < out.length; i++) { int v = all[all.length - 1 - i]; out[i] = 0xff000000 | v << 16 | v << 8 | v; }
        return out;
    }

    private static int[] greyValues(int n) {
        n = Math.max(2, Math.min(8, n));
        double gamma = Math.log(255.0 / 96.0) / Math.log(2.0);
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = n == 3 && i == 1 ? 96 : (int) Math.round(255 * Math.pow((double) i / (n - 1), gamma));
        return v;
    }

    private Object bands(String key, int pw, int ph, int[] colors) {
        return cached(key + "|" + pw + "x" + ph + "|" + Arrays.toString(colors), () -> {
            int[] out = new int[pw * ph];
            for (int x = 0; x < pw; x++) { int c = colors[Math.min(colors.length - 1, x * colors.length / pw)]; for (int y = 0; y < ph; y++) out[y * pw + x] = c; }
            return new Engine.Image(pw, ph, out);
        });
    }

    /** All grey tones side by side, black to white. */
    private Object tonesPatch(int pw, int ph) {
        int[] v = greyValues(s.greyTones), c = new int[v.length];
        for (int i = 0; i < v.length; i++) c[i] = 0xff000000 | v[i] << 16 | v[i] << 8 | v[i];
        return bands("tones", pw, ph, c);
    }

    /** The colour family, one band per step of the Colors slider (up to all 8). */
    private Object colorPatch(int pw, int ph) {
        int n = clamp((s.colors - 4) / 4 + 1, 1, 8);
        return bands("colors", pw, ph, Arrays.copyOf(Settings.SWATCHES, n));
    }

    /** Grey with the accent: what a picture will look like. */
    private Object accentPatch(int pw, int ph) {
        int a = Settings.SWATCHES[s.swatch];
        return bands("accent", pw, ph, new int[]{0xff606060, a, 0xffb0b0b0});
    }

    // ---- preview

    private void drawPreview(Surface g, int x, int y, int w, int h) {
        if (input == null) { centered(g, "Choose an input folder or image", x, y, w, h, DIM); return; }
        if (items == null) { centered(g, "Looking for images...", x, y, w, h, DIM); return; }
        if (items.isEmpty()) { centered(g, "No images found", x, y, w, h, DIM); return; }
        int navH = t + 12 * k;
        int bw = w, bh = h - navH;
        int pw = Math.max(4 * t, w / 4), ph = pw;
        if (bw != boxW || bh != boxH || pw != pipW) { boxW = bw; boxH = bh; pipW = pw; pipH = ph; schedulePreview(); }
        if (previewImg == null) centered(g, "Rendering...", x, y, w, bh, DIM);
        else {
            int ix = x + (bw - previewImgW) / 2, iy = y + (bh - previewImgH) / 2;
            g.image(previewImg, ix, iy);
            if (beforeImg != null) {   // the original, small, in the top-right corner
                int bx = ix + previewImgW - beforeW - 6 * k, by = iy + 6 * k;
                if (previewImgW < beforeW * 2) { bx = x + bw - beforeW; by = y; }
                outline(g, bx - 2 * k, by - 2 * k, beforeW + 4 * k, beforeH + 4 * k, 2 * k, BLACK);
                g.image(beforeImg, bx, by);
                outline(g, bx - k, by - k, beforeW + 2 * k, beforeH + 2 * k, k, GREY);
            }
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
    }

    private void drawPopup(Surface g, int w, int h) {
        popupRowH = rowH;
        popupW = Math.min(w - 40 * k, 24 * t);
        int ph = popup.length * rowH + t + 20 * k;
        popupX = (w - popupW) / 2;
        int top = (h - ph) / 2;
        g.fill(popupX - 2 * k, top - 2 * k, popupW + 4 * k, ph + 4 * k, WHITE);
        g.fill(popupX, top, popupW, ph, BLACK);
        g.text("Input", popupX + 10 * k, top + 8 * k, t, GREY);
        popupY = top + t + 16 * k;
        for (int i = 0; i < popup.length; i++) {
            int ry = popupY + i * rowH;
            boolean on = i == popupSel;
            if (on) g.fill(popupX, ry, popupW, rowH, WHITE);
            g.text(popup[i], popupX + 10 * k, ry + (rowH - t) / 2, t, on ? BLACK : WHITE);
        }
    }

    private void drawJob(Surface g, int w, int y, int h) {
        int x = w / 6, bw = w - 2 * x;
        int cy = y + h / 2 - 3 * (t + 10 * k);
        boolean running = screen == Screen.RUNNING;
        g.text(running ? (cancel.get() ? "Cancelling..." : "Converting") : (cancel.get() ? "Cancelled" : "Done"), x, cy, t * 2, WHITE);
        cy += 2 * t + 20 * k;
        int barH = t;
        outline(g, x, cy, bw, barH, 2 * k, WHITE);
        int fillW = total == 0 ? 0 : (int) ((bw - 8 * k) * (long) done / total);
        g.fill(x + 4 * k, cy + 4 * k, fillW, barH - 8 * k, WHITE);
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
    private void drawHelp(Surface g, int w, int h, Object[] items3) {
        int y = h - (t + 20 * k) + 4 * k + (t + 4 * k) / 2;
        int x = w - 14 * k;
        int d = t + 2 * k;
        helpKeys.clear();
        for (int i = items3.length - 3; i >= 0; i -= 3) {
            String glyph = (String) items3[i], label = (String) items3[i + 1];
            int right = x;
            x -= g.textWidth(label, t);
            g.text(label, x, y - t / 2, t, GREY);
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

    private void triangleUp(Surface g, int cx, int y, int hgt, boolean up, int color) {
        for (int i = 0; i < hgt; i++) {
            int len = 2 * (up ? i : hgt - 1 - i) + 1;
            g.fill(cx - len / 2, y + i, len, 1, color);
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
         .append("cells=").append(s.cells).append('\n').append("cell=").append(s.cellSize).append('\n')
         .append("replace=").append(replace).append('\n').append("zoom=").append(zoom).append('\n');
        StringBuilder st = new StringBuilder();
        for (Style x : Style.values()) if (styleOn[x.ordinal()]) st.append(x.name()).append(',');
        b.append("styles=").append(st).append('\n');
        for (Map.Entry<String, String> e : savedKnobs.entrySet()) b.append("knobs2.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        if (input != null) b.append("input=").append(inputFolder ? "d" : "f").append(host.savePlace(input)).append('\n').append("inputName=").append(inputName).append('\n');
        if (output != null) b.append("output=").append(host.savePlace(output)).append('\n').append("outputName=").append(outputName).append('\n');
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
            s.cells = clamp(Integer.parseInt(m.getOrDefault("cells", "96")), 16, 256);
            s.cellSize = clamp(Integer.parseInt(m.getOrDefault("cell", "15")), 2, 40);
            replace = Boolean.parseBoolean(m.getOrDefault("replace", "false"));
            zoom = Boolean.parseBoolean(m.getOrDefault("zoom", "false"));
            String st = m.get("styles");
            if (st != null) {
                Arrays.fill(styleOn, false);
                for (String n : st.split(",")) if (!n.isEmpty()) try { styleOn[Style.valueOf(n).ordinal()] = true; } catch (IllegalArgumentException ignored) { }
            }
            for (Style x : Style.values()) {
                String v = m.get("knobs2." + x.name());
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
        String out = m.get("output");
        if (out != null) {
            Object place = host.restorePlace(out);
            if (place != null) { output = place; outputName = m.getOrDefault("outputName", ""); }
        }
    }
}
