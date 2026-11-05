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
 * pixel font, an inverted white bar for the selected row, left/right to change values, A to
 * select, B to go back. The desktop and Android apps only forward drawing and input.
 *
 * Styles, Greyscale, Color and Accent are "control rows": an O on the left (selectable; a red X
 * over it when chosen), the name, a few sliders (or a dial and a melt button), and a sample
 * patch on the right. L/R (Tab on a keyboard) moves between a row's controls.
 */
public final class Ui {
    public enum Pick { INPUT, INPUT_FOLDER, INPUT_IMAGE, OUTPUT }
    public enum Key { UP, DOWN, LEFT, RIGHT, A, B, PREV, NEXT }

    static final int BLACK = 0xff000000, WHITE = 0xfffafafa, GREY = 0xff9a9a9a, DIM = 0xff4a4a4a, ICON = 0xff7a7a7a;
    /** The logo's colours: grey-white O, red X. */
    static final int LOGO_WHITE = 0xffb3b3b3, LOGO_RED = 0xff9e2023;
    static final int BUTTON_RED = 0xffe8322c, BUTTON_DARK = 0xff9c1712;

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

    // ---- preview panes: "Before" plus one per chosen style
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "preview"));
    private final AtomicLong previewSeq = new AtomicLong();
    private final Map<String, Pane> panes = new HashMap<>();
    private List<String> paneOrder = new ArrayList<>();
    private Picture picture;
    private String pictureKey;
    private int areaW, areaH;              // preview area of the last layout
    private boolean previewBusy;

    private static final class Pane {
        Object img; int w, h;                       // what's shown
        int x, y, boxW, boxH;                       // where (last layout)
        String info = "";
        double fx0, fy0, fw = 1, fh = 1;            // Before pane: the part of the photo shown, as fractions
    }

    // ---- batch
    private enum Screen { MENU, RUNNING, DONE }
    private Screen screen = Screen.MENU;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile int done, total, written, skipped, failed;
    private volatile String current = "", firstError = "";
    private long started;

    // ---- menu
    private String selectedKey = "Input";
    private final Map<String, Integer> focus = new HashMap<>();
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

    // ================================================================== rows and controls

    /** One adjustable thing in a control row. */
    private abstract static class Control {
        final String name;
        Control(String name) { this.name = name; }
        abstract boolean enabled();
        /** Left/right. */
        abstract void change(int d);
        /** A button: by default A selects the row (its O / X). */
        boolean press() { return false; }
        String describe() { return name; }
    }

    private final class Slider extends Control {
        final int steps; final java.util.function.IntSupplier get; final java.util.function.IntConsumer set;
        final java.util.function.BooleanSupplier on; final java.util.function.IntFunction<String> label;
        Slider(String name, int steps, java.util.function.IntSupplier get, java.util.function.IntConsumer set,
               java.util.function.BooleanSupplier on, java.util.function.IntFunction<String> label) {
            super(name); this.steps = steps; this.get = get; this.set = set; this.on = on; this.label = label;
        }
        boolean enabled() { return on.getAsBoolean(); }
        void change(int d) { set.accept(clamp(get.getAsInt() + d, 0, steps - 1)); changed(); }
        void setIndex(int i) { int v = clamp(i, 0, steps - 1); if (v != get.getAsInt()) { set.accept(v); changed(); } }
        String describe() { return name + "  " + label.apply(get.getAsInt()); }
    }

    private final class KnobControl extends Control {
        final Style st; final int i; final Knob knob;
        KnobControl(Style st, int i) { super(Settings.KNOBS[st.ordinal()][i].name); this.st = st; this.i = i; this.knob = Settings.KNOBS[st.ordinal()][i]; }
        int get() { return s.knob[st.ordinal()][i]; }
        void set(int v) { s.knob[st.ordinal()][i] = clamp(v, 0, knob.steps() - 1); changed(); }
        boolean enabled() { return true; }
        void change(int d) {
            if (knob.kind == Kind.DIAL) set(Math.floorMod(get() + d, knob.steps()));
            else if (knob.kind == Kind.BUTTON) melt(d);
            else set(get() + d);
        }
        boolean press() { if (knob.kind == Kind.BUTTON) { melt(1); return true; } return false; }
        private void melt(int d) {
            int next = get() + d;
            if (next >= knob.steps()) { message = "That's as melted as it gets. Reset to start over."; return; }
            if (next < 0) return;
            set(next);
            message = d > 0 ? "Melted a little more." : "";
        }
        String describe() {
            int v = get();
            if (knob.kind == Kind.DIAL) return name + "  " + (int) knob.values[v] + " deg";
            if (knob.kind == Kind.BUTTON) return "Melt level  " + (v + 1) + " / " + knob.steps();
            return name + "  " + (v + 1) + " / " + knob.steps();
        }
    }

    private final class Action extends Control {
        final Runnable run; final java.util.function.BooleanSupplier on;
        Action(String name, Runnable run, java.util.function.BooleanSupplier on) { super(name); this.run = run; this.on = on; }
        boolean enabled() { return on.getAsBoolean(); }
        void change(int d) { }
        boolean press() { if (enabled()) run.run(); return true; }
    }

    private final class Swatch extends Control {
        Swatch() { super("Accent color"); }
        boolean enabled() { return !s.color; }
        void change(int d) { s.swatch = Math.floorMod(s.swatch + d, Settings.SWATCHES.length); changed(); }
        String describe() { return "Accent color  " + (s.swatch + 1) + " / " + Settings.SWATCHES.length; }
    }

    /** A menu line. Control rows have a mark (O / X), controls and a patch; plain rows a value. */
    private abstract class Row {
        final String key, label;
        Row(String key, String label) { this.key = key; this.label = label; }
        boolean controlRow() { return false; }
        // plain rows
        String value() { return ""; }
        boolean changeable() { return false; }
        void change(int d) { }
        void activate() { }
        // control rows
        boolean selectable() { return true; }
        boolean chosen() { return false; }
        void choose() { }
        List<Control> controls() { return Collections.emptyList(); }
        List<Control> actions() { return Collections.emptyList(); }   // second line, right side
        Object patch(int w, int h) { return null; }
        void tapPatch(boolean right) { }
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
        final Style st;
        final List<Control> controls = new ArrayList<>(), actions = new ArrayList<>();
        StyleRow(Style st) {
            super("style:" + st.name(), st.label);
            this.st = st;
            for (int i = 0; i < Settings.KNOBS[st.ordinal()].length; i++) controls.add(new KnobControl(st, i));
            actions.add(new Action("Save", this::save, () -> true));
            actions.add(new Action("Reset", this::reset, () -> true));
        }
        boolean controlRow() { return true; }
        boolean chosen() { return styleOn[st.ordinal()]; }
        void choose() { styleOn[st.ordinal()] = !styleOn[st.ordinal()]; changed(); }
        List<Control> controls() { return controls; }
        List<Control> actions() { return actions; }
        Object patch(int w, int h) { return stylePatch(st, w, h); }
        void save() { saveKnobs(st); message = st.label + " settings saved. They'll be used next time."; }
        void reset() {
            s.knob[st.ordinal()] = Settings.defaults()[st.ordinal()];
            message = st.label + " is back to its defaults. Save to keep them.";
            changed();
        }
    }

    private List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(new ValueRow("Input", "Input", () -> input != null ? inputName : "Choose", null, this::pickInput));
        r.add(new ValueRow("Output", "Output", () -> output != null ? outputName : "Choose", null, () -> host.pick(Pick.OUTPUT)));
        for (Style st : Style.values()) r.add(new StyleRow(st));

        r.add(new Row("Greyscale", "Greyscale") {
            final List<Control> c = Collections.singletonList(new Slider("Grey tones", 7, () -> s.greyTones - 2, v -> s.greyTones = v + 2,
                    () -> !s.color, v -> v == 0 ? "black and white" : (v + 2) + " tones"));
            boolean controlRow() { return true; }
            boolean chosen() { return !s.color; }
            void choose() { if (s.color) { s.color = false; changed(); } }
            List<Control> controls() { return c; }
            Object patch(int w, int h) { return tonesPatch(w, h); }
        });
        r.add(new Row("Color", "Color") {
            final List<Control> c = Collections.singletonList(new Slider("Colors", 8, () -> (s.colors - 4) / 4, v -> s.colors = 4 + 4 * v,
                    () -> s.color, v -> (4 + 4 * v) + " colors"));
            boolean controlRow() { return true; }
            boolean chosen() { return s.color; }
            void choose() { if (!s.color) { s.color = true; changed(); } }
            List<Control> controls() { return c; }
            Object patch(int w, int h) { return colorPatch(w, h); }
        });
        r.add(new Row("Accent", "Accent") {
            final Swatch sw = new Swatch();
            final List<Control> c = Arrays.asList(new Slider("Color range", 3, () -> s.accentRange, v -> s.accentRange = v,
                    () -> !s.color, v -> Settings.RANGE_NAMES[v]), sw);
            boolean controlRow() { return true; }
            boolean selectable() { return !s.color; }
            boolean chosen() { return s.accent && !s.color; }
            void choose() {
                if (s.color) { message = "The accent color works with Greyscale."; return; }
                s.accent = !s.accent; changed();
            }
            List<Control> controls() { return c; }
            Object patch(int w, int h) { return swatchPatch(w, h); }
            void tapPatch(boolean right) { sw.change(right ? 1 : -1); }
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

    /** Controls of a control row in focus order: the O/X mark (index 0), the controls, then actions. */
    private int focusCount(Row row) { return 1 + row.controls().size() + row.actions().size(); }

    private Control focusedControl(Row row) {
        int f = focus.getOrDefault(row.key, 0);
        if (f <= 0) return null;
        if (f <= row.controls().size()) return row.controls().get(f - 1);
        int a = f - 1 - row.controls().size();
        return a < row.actions().size() ? row.actions().get(a) : null;
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
        String before = selectedKey;
        selectedKey = r.get(i).key;
        if (!before.equals(selectedKey) && (before.startsWith("style:") || selectedKey.startsWith("style:"))) host.repaint();
    }

    private void changed() { savePrefs(); schedulePreview(); host.repaint(); }

    private void pickInput() {
        if (host.canPickEither()) { host.pick(Pick.INPUT); return; }
        popup = new String[]{"A folder of images", "One image"};
        popupSel = 0;
    }

    // ================================================================== keys

    public void key(Key key) {
        if (screen == Screen.RUNNING) { if (key == Key.B) { cancel.set(true); message = "Cancelling..."; } host.repaint(); return; }
        if (screen == Screen.DONE) { if (key == Key.A || key == Key.B) screen = Screen.MENU; host.repaint(); return; }
        if (popup != null) { popupKey(key); host.repaint(); return; }
        List<Row> r = rows();
        int i = selectedIndex(r);
        Row row = r.get(i);
        int f = focus.getOrDefault(row.key, 0);
        switch (key) {
            case UP: select(r, (i - 1 + r.size()) % r.size()); break;
            case DOWN: select(r, (i + 1) % r.size()); break;
            case PREV: case NEXT:
                if (row.controlRow()) focus.put(row.key, Math.floorMod(f + (key == Key.NEXT ? 1 : -1), focusCount(row)));
                break;
            case LEFT: case RIGHT: {
                int d = key == Key.RIGHT ? 1 : -1;
                if (!row.controlRow()) { row.change(d); break; }
                Control c = focusedControl(row);
                if (c == null) { if (row.selectable()) row.choose(); else row.choose(); }
                else if (c.enabled()) c.change(d);
                else message = disabledReason(row);
                break;
            }
            case A: {
                if (!row.controlRow()) { row.activate(); break; }
                Control c = focusedControl(row);
                if (c != null && c.press()) break;
                row.choose();
                break;
            }
            case B:
                if (row.controlRow() && f != 0) focus.put(row.key, 0);
                else if (i != 0) select(r, 0);
                break;
        }
        host.repaint();
    }

    private String disabledReason(Row row) {
        if (row.key.equals("Greyscale")) return "Select Greyscale to change the grey tones.";
        if (row.key.equals("Color")) return "Select Color to change the number of colors.";
        return "The accent color works with Greyscale.";
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

    private void popupChoose(int i) {
        popup = null;
        host.pick(i == 0 ? Pick.INPUT_FOLDER : Pick.INPUT_IMAGE);
    }

    // ================================================================== pointer (mouse and touch)

    /** Something drawn last frame that can be tapped or dragged. */
    private static final class Hit {
        final int x, y, w, h; final String row; final Object what; final int focusIndex;
        Hit(int x, int y, int w, int h, String row, Object what, int focusIndex) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.row = row; this.what = what; this.focusIndex = focusIndex;
        }
        boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }

    private static final Object MARK = new Object(), PATCH = new Object(), ROW = new Object();
    private Hit down;
    private int downX, downY, lastY;
    private boolean dragging;

    public void pointerDown(int x, int y) {
        downX = x; downY = lastY = y; dragging = false; down = null;
        if (screen != Screen.MENU || popup != null) return;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) { down = hits.get(i); break; }
        if (down != null && down.what instanceof Slider || down != null && down.what instanceof KnobControl && ((KnobControl) down.what).knob.kind != Kind.BUTTON) {
            dragTo(down, x, y);
            dragging = true;
        }
    }

    public void pointerMove(int x, int y) {
        if (screen != Screen.MENU || popup != null) return;
        if (dragging && down != null && (down.what instanceof Slider || down.what instanceof KnobControl)) { dragTo(down, x, y); return; }
        if (!dragging && Math.abs(y - downY) > rowH / 2) dragging = true;
        if (dragging && rowH > 0) {
            int rowsMoved = (lastY - y) / rowH;
            if (rowsMoved != 0) { scrollBy(rowsMoved); lastY -= rowsMoved * rowH; }
        }
    }

    public void pointerUp(int x, int y) {
        if (screen == Screen.RUNNING) { cancel.set(true); message = "Cancelling..."; host.repaint(); return; }
        if (screen == Screen.DONE) { screen = Screen.MENU; host.repaint(); return; }
        if (popup != null) { tapPopup(x, y); host.repaint(); return; }
        if (dragging) { dragging = false; host.repaint(); return; }
        if (!previewOnOtherScreen && x >= previewX && y >= previewY && x < previewX + previewW && y < previewY + previewH) { tapPane(x, y); return; }
        if (down == null) return;
        List<Row> r = rows();
        int idx = -1;
        for (int i = 0; i < r.size(); i++) if (r.get(i).key.equals(down.row)) idx = i;
        if (idx < 0) return;
        Row row = r.get(idx);
        boolean wasSelected = row.key.equals(selectedKey);
        select(r, idx);
        if (down.what == MARK) { focus.put(row.key, 0); row.choose(); }
        else if (down.what == PATCH) {
            if (row.key.equals("Accent")) {
                for (int i = 0; i < row.controls().size(); i++) if (row.controls().get(i) instanceof Swatch) focus.put(row.key, i + 1);
                if (row.selectable()) row.tapPatch(x >= down.x + down.w / 2); else message = disabledReason(row);
            }
        }
        else if (down.what instanceof Control) {
            Control c = (Control) down.what;
            focus.put(row.key, down.focusIndex);
            if (!c.enabled()) message = disabledReason(row);
            else if (!c.press() && c instanceof Swatch) c.change(x >= down.x + down.w / 2 ? 1 : -1);
        } else if (wasSelected && !row.controlRow()) {
            // second tap on a plain row: left part of the value lowers, anything else raises / opens
            if (row.changeable() && x >= valueLeft && x < valueMid) row.change(-1);
            else if (row.changeable()) row.change(1);
            else row.activate();
        }
        host.repaint();
    }

    /** Old single-tap entry point (tests). */
    public void tap(int x, int y) { pointerDown(x, y); pointerUp(x, y); }

    private void dragTo(Hit h, int x, int y) {
        Object w = h.what;
        if (w instanceof Slider) {
            Slider sl = (Slider) w;
            if (!sl.enabled()) return;
            sl.setIndex((int) Math.round((x - h.x) / (double) Math.max(1, h.w) * (sl.steps - 1)));
        } else if (w instanceof KnobControl) {
            KnobControl kc = (KnobControl) w;
            if (kc.knob.kind == Kind.SLIDER) {
                int v = (int) Math.round((x - h.x) / (double) Math.max(1, h.w) * (kc.knob.steps() - 1));
                if (v != kc.get()) kc.set(v);
            } else if (kc.knob.kind == Kind.DIAL) {
                double a = Math.toDegrees(Math.atan2(y - (h.y + h.h / 2.0), x - (h.x + h.w / 2.0))) + 90;   // 0 = up
                int v = Math.floorMod((int) Math.round(a / 30.0), kc.knob.steps());
                if (v != kc.get()) kc.set(v);
            }
        }
        focus.put(h.row, h.focusIndex);
        selectedKey = h.row;
    }

    private int popupX, popupY, popupW, popupRowH;

    private void tapPopup(int x, int y) {
        if (x < popupX || x >= popupX + popupW) { popup = null; return; }
        int i = (y - popupY) / Math.max(1, popupRowH);
        if (i >= 0 && i < popup.length) popupChoose(i); else popup = null;
    }

    /** Mouse wheel or swipe, in rows (positive = down). */
    public void scrollBy(int rowsDelta) {
        scroll = Math.max(0, scroll + rowsDelta);
        host.repaint();
    }

    /** A tap on the second screen's previews. */
    public void tapPreviewScreen(int x, int y) { tapPane(x, y); }

    /** Tapping the Before pane picks the nearest accent swatch from the photo. */
    private void tapPane(int x, int y) {
        Pane before = panes.get("before");
        if (before == null || before.img == null || picture == null) return;
        int ix = before.x + (before.boxW - before.w) / 2, iy = before.y + (before.boxH - before.h) / 2;
        if (x < ix || y < iy || x >= ix + before.w || y >= iy + before.h) return;
        if (s.color) { message = "The accent color works with Greyscale."; host.repaint(); return; }
        double fx = before.fx0 + (x - ix) / (double) before.w * before.fw, fy = before.fy0 + (y - iy) / (double) before.h * before.fh;
        int c = Engine.sampleColor(picture, fx, fy);
        float[] hsv = Engine.hsv(c);
        int best = 0; float bd = Float.MAX_VALUE;
        for (int i = 0; i < Settings.SWATCHES.length; i++) {
            float[] o = Engine.hsv(Settings.SWATCHES[i]);
            float dh = Math.abs(o[0] - hsv[0]); dh = Math.min(dh, 360 - dh);
            if (dh < bd) { bd = dh; best = i; }
        }
        s.swatch = best; s.accent = true;
        message = hsv[1] < 0.15f ? "That spot is nearly grey; picked the closest color anyway." : "Accent color picked from the photo.";
        changed();
    }

    // ================================================================== picking

    public void picked(Pick kind, Object place, String name) {
        if (place == null) return;
        if (kind == Pick.OUTPUT) {
            output = place; outputName = name;
        } else {
            input = place; inputName = name; inputFolder = kind == Pick.INPUT_FOLDER;
            items = null; previewIndex = 0; picture = null; pictureKey = null;
            panes.clear();
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

    // ================================================================== preview panes

    /** Tells the UI that the preview is shown on a second screen (the Thor's lower display). */
    public void setPreviewOnOtherScreen(boolean other) { previewOnOtherScreen = other; host.repaint(); }

    /** Before, then each chosen style; if none is chosen, the highlighted style (or Border). */
    private List<String> wantedPanes() {
        List<String> out = new ArrayList<>();
        out.add("before");
        for (Style st : Style.values()) if (styleOn[st.ordinal()]) out.add(st.name());
        if (out.size() == 1) out.add(selectedKey.startsWith("style:") ? selectedKey.substring(6) : Style.BORDER.name());
        return out;
    }

    /** Lays the panes out in a grid that keeps them as big as possible. */
    private void layoutPanes(int x, int y, int w, int h) {
        List<String> want = wantedPanes();
        if (!want.equals(paneOrder)) { paneOrder = want; }
        int n = want.size();
        double aspect = picture != null ? picture.width / (double) picture.height : 1.0;
        int bestCols = 1; double bestScale = 0;
        int head = t + 6 * k, gap = 10 * k;
        for (int cols = 1; cols <= n; cols++) {
            int rowsN = (n + cols - 1) / cols;
            double cw = (w - gap * (cols - 1)) / (double) cols, ch = (h - gap * (rowsN - 1)) / (double) rowsN - head;
            double scale = Math.min(cw / aspect, ch);
            if (scale > bestScale) { bestScale = scale; bestCols = cols; }
        }
        int cols = bestCols, rowsN = (n + cols - 1) / cols;
        int cw = (w - gap * (cols - 1)) / cols, ch = (h - gap * (rowsN - 1)) / rowsN;
        boolean resized = false;
        for (int i = 0; i < n; i++) {
            Pane p = panes.computeIfAbsent(want.get(i), kk -> new Pane());
            int px = x + (i % cols) * (cw + gap), py = y + (i / cols) * (ch + gap);
            int bw = cw, bh = ch - head;
            if (bw != p.boxW || bh != p.boxH) resized = true;
            p.x = px; p.y = py + head; p.boxW = bw; p.boxH = bh;
        }
        if (resized || w != areaW || h != areaH) { areaW = w; areaH = h; schedulePreview(); }
    }

    private void schedulePreview() {
        if (input == null || items == null || items.isEmpty() || paneOrder.isEmpty()) return;
        final long seq = previewSeq.incrementAndGet();
        final Settings set = s.copy();
        final String rel = items.get(Math.min(previewIndex, items.size() - 1));
        final Object in = input; final boolean folder = inputFolder;
        final boolean z = zoom;
        final List<String> order = new ArrayList<>(paneOrder);
        final Map<String, int[]> boxes = new HashMap<>();
        for (String id : order) { Pane p = panes.get(id); if (p != null) boxes.put(id, new int[]{p.boxW, p.boxH}); }
        previewBusy = true;
        previewWorker.execute(() -> {
            if (seq != previewSeq.get()) return;
            try {
                String key = (folder ? "d:" : "f:") + in + "|" + rel;
                Picture p = picture;
                if (p == null || !key.equals(pictureKey)) { p = host.decode(in, folder, rel); picture = p; pictureKey = key; }
                Grid g = null;
                for (String id : order) {
                    if (seq != previewSeq.get()) return;
                    int[] box = boxes.get(id);
                    if (box == null || box[0] <= 0 || box[1] <= 0) continue;
                    Engine.Image shown; String info;
                    double[] region = {0, 0, 1, 1};
                    if (id.equals("before")) {
                        Engine.Image photo = new Engine.Image(p.width, p.height, p.argb);
                        if (z) {   // the same middle part the 1:1 outputs show
                            int[] size = Engine.gridSize(p.width, p.height, set.cells);
                            double scale = p.width / (double) (size[0] * set.cellSize);
                            Engine.Image mid = Engine.crop(photo, (int) Math.ceil(box[0] * scale), (int) Math.ceil(box[1] * scale), 0.5, 0.5);
                            region = new double[]{(p.width - mid.width) / 2.0 / p.width, (p.height - mid.height) / 2.0 / p.height,
                                    mid.width / (double) p.width, mid.height / (double) p.height};
                            shown = Engine.fit(mid, box[0], box[1]);
                        } else shown = Engine.fit(photo, box[0], box[1]);
                        info = "Before";
                    } else {
                        if (g == null) g = Engine.grid(p, set);
                        Style st = Style.valueOf(id);
                        Engine.Image full = Engine.draw(g, st, set.cellSize, set);
                        shown = z ? Engine.crop(full, box[0], box[1], 0.5, 0.5) : Engine.fit(full, box[0], box[1]);
                        info = st.label + (styleOn[st.ordinal()] ? "" : "  (not chosen)");
                    }
                    Object img = host.makeImage(shown.argb, shown.width, shown.height);
                    final double[] fr = region;
                    final Engine.Image fs = shown; final String fi = info; final boolean last = id.equals(order.get(order.size() - 1));
                    host.runOnUi(() -> {
                        if (seq != previewSeq.get()) return;
                        Pane pane = panes.computeIfAbsent(id, kk -> new Pane());
                        pane.img = img; pane.w = fs.width; pane.h = fs.height; pane.info = fi;
                        pane.fx0 = fr[0]; pane.fy0 = fr[1]; pane.fw = fr[2]; pane.fh = fr[3];
                        if (last) previewBusy = false;
                        host.repaint();
                    });
                }
            } catch (Throwable e) {
                host.runOnUi(() -> { if (seq == previewSeq.get()) { previewBusy = false; message = "Preview failed: " + rel; host.repaint(); } });
            }
        });
    }

    // ================================================================== batch

    private void start() {
        List<Style> chosen = new ArrayList<>();
        for (Style st : Style.values()) if (styleOn[st.ordinal()]) chosen.add(st);
        if (input == null) { message = "Choose an input first."; return; }
        if (output == null) { message = "Choose an output folder first."; return; }
        if (chosen.isEmpty()) { message = "Select at least one style."; return; }
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
            drawHelp(g, w, h, screen == Screen.RUNNING ? new String[]{"B", "Cancel"} : new String[]{"A", "OK"});
            return;
        }

        boolean landscape = w >= h;
        if (previewOnOtherScreen) {
            menuX = w / 8; menuY = midY; menuW = w - w / 4; menuH = midH;
            previewW = previewH = 0;
        } else if (landscape) {
            menuX = 0; menuY = midY; menuW = (int) (w * 0.5); menuH = midH;
            previewX = menuW + 16 * k; previewY = midY; previewW = w - previewX - 14 * k; previewH = midH;
        } else {
            previewX = 14 * k; previewY = midY; previewW = w - 28 * k; previewH = (int) (midH * 0.40);
            menuX = 0; menuY = previewY + previewH + 10 * k; menuW = w; menuH = midH - previewH - 10 * k;
        }
        drawMenu(g);
        if (!previewOnOtherScreen) drawPanes(g, previewX, previewY, previewW, previewH);

        g.text(fitText(g, message, w / 2, t), 14 * k, h - bottomH + 6 * k, t, GREY);
        List<Row> r = rows();
        boolean controls = r.get(selectedIndex(r)).controlRow();
        drawHelp(g, w, h, controls ? new String[]{"A", "Select", "<>", "Change", "LR", "Next"} : new String[]{"A", "Select", "B", "Back", "<>", "Change"});
        if (popup != null) drawPopup(g, w, h);
    }

    /** Draws only the previews, for a second screen. */
    public void drawPreviewScreen(Surface g, int w, int h) {
        int kk = Math.max(1, Math.round(h / 540f));
        int saveK = k, saveT = t;
        k = kk; t = 20 * kk;
        g.fill(0, 0, w, h, BLACK);
        drawPanes(g, 14 * kk, 10 * kk, w - 28 * kk, h - 20 * kk);
        k = saveK; t = saveT;
    }

    private void drawMenu(Surface g) {
        List<Row> r = rows();
        int sel = selectedIndex(r);
        // row heights: the highlighted control row gets a second line
        int[] hgt = new int[r.size()];
        for (int i = 0; i < r.size(); i++) hgt[i] = rowH + (i == sel && r.get(i).controlRow() ? t + 10 * k : 0);
        // keep the selection visible
        scroll = clamp(scroll, 0, r.size() - 1);
        if (sel < scroll) scroll = sel;
        while (true) {
            int used = 0;
            for (int i = scroll; i <= sel; i++) used += hgt[i];
            if (used <= menuH || scroll >= sel) break;
            scroll++;
        }
        int labelW = 0;
        for (Row row : r) if (row.controlRow()) labelW = Math.max(labelW, g.textWidth(row.label, t));
        g.clip(menuX, menuY, menuW, menuH);
        int y = menuY, last = scroll;
        for (int i = scroll; i < r.size() && y + hgt[i] <= menuY + menuH; i++) {
            Row row = r.get(i);
            boolean on = i == sel;
            if (on) g.fill(menuX, y, menuW, hgt[i], WHITE);
            if (row.controlRow()) drawControlRow(g, row, y, on, labelW);
            else drawValueRow(g, row, y, on);
            y += hgt[i];
            last = i;
        }
        g.unclip();
        if (scroll > 0) triangleUp(g, menuX + menuW - 10 * k, menuY - 6 * k, 4 * k, true, DIM);
        if (last < r.size() - 1 || y > menuY + menuH) triangleUp(g, menuX + menuW - 10 * k, menuY + menuH + 2 * k, 4 * k, false, DIM);
    }

    private void drawValueRow(Surface g, Row row, int y, boolean on) {
        int ty = y + (rowH - t) / 2, right = menuX + menuW - 14 * k;
        g.text(row.label, menuX + 10 * k, ty, t, on ? BLACK : WHITE);
        hits.add(new Hit(menuX, y, menuW, rowH, row.key, ROW, 0));
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

    private void drawControlRow(Surface g, Row row, int y, boolean on, int labelW) {
        int f = focus.getOrDefault(row.key, 0);
        int fg = on ? BLACK : WHITE;
        int x0 = menuX + 10 * k, right = menuX + menuW - 14 * k, cy = y + rowH / 2;
        boolean enabled = row.selectable();
        hits.add(new Hit(menuX, y, menuW, rowH + (on ? t + 10 * k : 0), row.key, ROW, 0));

        // the mark: O = selectable, a bigger red X over it = chosen (the logo's shapes and colours)
        int box = t;
        if (on && f == 0) focusBox(g, x0 - 3 * k, y + 2 * k, box + 6 * k, rowH - 4 * k);
        int markColor = !enabled ? DIM : on && f != 0 ? BLACK : on ? LOGO_WHITE : LOGO_WHITE;
        int o = (box * 3 / 4) | 1, xs = (box * 5 / 4) | 1, mcx = x0 + box / 2;
        shape(g, Style.BEADS, mcx - o / 2, cy - o / 2, o, markColor);
        if (row.chosen()) shape(g, Style.STITCH, mcx - xs / 2, cy - xs / 2, xs, LOGO_RED);
        hits.add(new Hit(x0 - 6 * k, y, box + 12 * k, rowH, row.key, MARK, 0));

        int labelX = x0 + box + 14 * k;
        g.text(row.label, labelX, y + (rowH - t) / 2, t, enabled ? fg : DIM);

        // patch on the right
        int ph = rowH - 8 * k, pw = ph * 5 / 2, px = right - pw;
        Object patch = row.patch(pw, ph);
        g.fill(px - k, cy - ph / 2 - k, pw + 2 * k, ph + 2 * k, on ? BLACK : DIM);
        if (patch != null) g.image(patch, px, cy - ph / 2);
        if (!enabled) dimOver(g, px, cy - ph / 2, pw, ph);
        hits.add(new Hit(px, y, pw, rowH, row.key, PATCH, 0));

        // controls between the label and the patch
        List<Control> cs = row.controls();
        int cx0 = labelX + labelW + 18 * k, cx1 = px - 16 * k;
        int fixed = 0, sliders = 0;
        for (Control c : cs) if (isRound(c)) fixed += t; else if (!(c instanceof Swatch)) sliders++;
        int gap = 14 * k;
        int nGaps = Math.max(0, visibleCount(cs) - 1);
        int sw = sliders == 0 ? 0 : Math.min(12 * t, Math.max(4 * t / 2, (cx1 - cx0 - fixed - gap * nGaps) / sliders));
        int cx = cx0, idx = 0;
        for (Control c : cs) {
            idx++;
            if (c instanceof Swatch) { if (on && f == idx) focusBox(g, px - 4 * k, y + 2 * k, pw + 8 * k, rowH - 4 * k); continue; }   // the patch is its control
            int cw = isRound(c) ? t : sw;
            boolean focused = on && f == idx;
            if (focused) focusBox(g, cx - 4 * k, y + 2 * k, cw + 8 * k, rowH - 4 * k);
            int ink = !c.enabled() ? DIM : focused ? WHITE : fg;
            drawControl(g, c, cx, cy, cw, ink, on && !focused);
            hits.add(new Hit(cx - 4 * k, y, cw + 8 * k, rowH, row.key, c, idx));
            cx += cw + gap;
        }

        // second line when highlighted: what the focused control is, plus Save / Reset
        if (on) {
            int ly = y + rowH + 2 * k;
            Control fc = focusedControl(row);
            String d = fc == null ? (row.chosen() ? "Chosen" : row.selectable() ? "Not chosen" : "Needs Greyscale") : fc.describe();
            g.text(fitText(g, d, menuW / 2, t), labelX, ly, t, DIM);
            int ax = right;
            List<Control> acts = row.actions();
            for (int i = acts.size() - 1; i >= 0; i--) {
                Control a = acts.get(i);
                int aw = g.textWidth(a.name, t) + 16 * k;
                ax -= aw;
                boolean focused = f == 1 + cs.size() + i;
                g.fill(ax, ly - 2 * k, aw, t + 4 * k, focused ? BLACK : DIM);
                g.text(a.name, ax + 8 * k, ly, t, WHITE);
                hits.add(new Hit(ax, ly - 2 * k, aw, t + 4 * k, row.key, a, 1 + cs.size() + i));
                ax -= 10 * k;
            }
        }
    }

    private static int visibleCount(List<Control> cs) { int n = 0; for (Control c : cs) if (!(c instanceof Swatch)) n++; return n; }

    private static boolean isRound(Control c) {
        return c instanceof KnobControl && ((KnobControl) c).knob.kind != Kind.SLIDER;
    }

    private void focusBox(Surface g, int x, int y, int w, int h) { g.fill(x, y, w, h, BLACK); }

    private void dimOver(Surface g, int x, int y, int w, int h) {
        for (int yy = 0; yy < h; yy += 2 * k) g.fill(x, y + yy, w, k, BLACK);   // scanline veil
    }

    private void drawControl(Surface g, Control c, int x, int cy, int w, int ink, boolean onBar) {
        if (c instanceof KnobControl && ((KnobControl) c).knob.kind == Kind.DIAL) {
            KnobControl kc = (KnobControl) c;
            int d = w | 1, x0 = x, y0 = cy - d / 2;
            boolean[] ring = new boolean[d * d];
            double ce = (d - 1) / 2.0;
            for (int yy = 0; yy < d; yy++) for (int xx = 0; xx < d; xx++) {
                double r = Math.hypot(xx - ce, yy - ce);
                ring[yy * d + xx] = r <= d / 2.0 - 0.3 && r >= d / 2.0 - 0.3 - 2 * k;
            }
            runs(g, ring, x0, y0, d, ink);
            double a = Math.toRadians(kc.knob.values[kc.get()] - 90);
            for (int i = 0; i < d / 2 - 2 * k; i += k) {
                int px = (int) Math.round(x0 + ce + Math.cos(a) * i), py = (int) Math.round(y0 + ce + Math.sin(a) * i);
                g.fill(px - k / 2, py - k / 2, 2 * k, 2 * k, ink);
            }
            return;
        }
        if (c instanceof KnobControl && ((KnobControl) c).knob.kind == Kind.BUTTON) {
            // a big shiny cartoon button
            int d = w | 1, x0 = x, y0 = cy - d / 2;
            double ce = (d - 1) / 2.0;
            for (int yy = 0; yy < d; yy++) for (int xx = 0; xx < d; xx++) {
                double r = Math.hypot(xx - ce, yy - ce);
                if (r > d / 2.0 - 0.3) continue;
                int col = r > d / 2.0 - 0.3 - 2 * k ? BLACK                                     // outline
                        : yy > ce + (d / 2.0) * 0.35 && r > d * 0.30 ? BUTTON_DARK                // shadow at the bottom
                        : BUTTON_RED;
                double hx = xx - (ce - d * 0.18), hy = yy - (ce - d * 0.2);
                if (hx * hx / (d * d * 0.012) + hy * hy / (d * d * 0.006) <= 1) col = 0xffffd9d6;   // highlight
                g.fill(x0 + xx, y0 + yy, 1, 1, col);
            }
            KnobControl kc = (KnobControl) c;
            for (int i = 0; i < kc.get(); i++) g.fill(x0 + d + 3 * k, y0 + d - (i + 1) * 3 * k, 2 * k, 2 * k, BUTTON_RED);   // melt level
            return;
        }
        int steps, value;
        if (c instanceof Slider) { steps = ((Slider) c).steps; value = ((Slider) c).get.getAsInt(); }
        else { KnobControl kc = (KnobControl) c; steps = kc.knob.steps(); value = kc.get(); }
        g.fill(x, cy - k, w, 2 * k, ink == DIM ? DIM : onBar ? ICON : (ink == WHITE ? GREY : DIM));
        for (int i = 0; i < steps; i++) {
            int tx = x + (int) Math.round(i * (w - 1) / (double) Math.max(1, steps - 1));
            g.fill(tx - k / 2, cy - 3 * k, Math.max(1, k), 6 * k, ink == DIM ? DIM : GREY);
        }
        int kx = x + (int) Math.round(value * (w - 1) / (double) Math.max(1, steps - 1));
        int kh = t * 3 / 4;
        g.fill(kx - 3 * k, cy - kh / 2, 6 * k, kh, ink);
    }

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
        int[] values = s.color ? new int[]{0xfffafafa, 0xffd8333a, 0xff3570d8, 0xfff2c230} : lightTones();
        String key = "style|" + st + "|" + pw + "x" + ph + "|" + cell + "|" + Arrays.toString(values) + "|" + Arrays.toString(s.knob[st.ordinal()]);
        return cached(key, () -> {
            int cols = (pw + cell - 1) / cell + 1, rows = (ph + cell - 1) / cell + 1;
            int[] c = new int[cols * rows];
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) c[y * cols + x] = values[(x + y) % values.length];
            return cropTo(Engine.draw(new Grid(cols, rows, c), st, cell, s), pw, ph);
        });
    }

    private static Engine.Image cropTo(Engine.Image im, int pw, int ph) {
        int[] out = new int[pw * ph];
        for (int y = 0; y < ph; y++) System.arraycopy(im.argb, y * im.width, out, y * pw, Math.min(pw, im.width));
        return new Engine.Image(pw, ph, out);
    }

    /** Light tones of the current greyscale setting (black would just show nothing). */
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

    /** All grey tones side by side, black to white. */
    private Object tonesPatch(int pw, int ph) {
        return cached("tones|" + pw + "x" + ph + "|" + s.greyTones, () -> {
            int[] v = greyValues(s.greyTones);
            int[] out = new int[pw * ph];
            for (int x = 0; x < pw; x++) {
                int g = v[Math.min(v.length - 1, x * v.length / pw)];
                for (int y = 0; y < ph; y++) out[y * pw + x] = 0xff000000 | g << 16 | g << 8 | g;
            }
            return new Engine.Image(pw, ph, out);
        });
    }

    /** A colour strip with as many bands as colours (up to the patch's room). */
    private Object colorPatch(int pw, int ph) {
        return cached("colors|" + pw + "x" + ph + "|" + s.colors, () -> {
            int n = Math.min(s.colors, Math.max(4, pw / Math.max(2, ph / 4)));
            int[] out = new int[pw * ph];
            for (int x = 0; x < pw; x++) {
                int band = x * n / pw;
                for (int y = 0; y < ph; y++) {
                    boolean top = y < ph / 2;
                    out[y * pw + x] = Engine.fromHsv((band * 360f / n) % 360f, top ? 0.65f : 0.4f, top ? 0.9f : 0.6f);
                }
            }
            return new Engine.Image(pw, ph, out);
        });
    }

    /** Five of the 24 accent swatches, the chosen one in the middle, framed. */
    private Object swatchPatch(int pw, int ph) {
        return cached("swatch|" + pw + "x" + ph + "|" + s.swatch, () -> {
            int[] out = new int[pw * ph];
            Arrays.fill(out, BLACK);
            int n = 5, cw = pw / n;
            for (int i = 0; i < n; i++) {
                int sw = Settings.SWATCHES[Math.floorMod(s.swatch + i - 2, Settings.SWATCHES.length)];
                boolean mid = i == 2;
                int inset = mid ? 0 : Math.max(2, ph / 6);
                for (int y = inset; y < ph - inset; y++)
                    for (int x = i * cw + (mid ? 0 : 1); x < (i + 1) * cw - (mid ? 0 : 1); x++) out[y * pw + x] = sw;
                if (mid) for (int y = 0; y < ph; y++) for (int x = i * cw; x < (i + 1) * cw; x++)
                    if (y < 2 || y >= ph - 2 || x < i * cw + 2 || x >= (i + 1) * cw - 2) out[y * pw + x] = WHITE;
            }
            return new Engine.Image(pw, ph, out);
        });
    }

    // ---- preview panes

    private void drawPanes(Surface g, int x, int y, int w, int h) {
        if (input == null) { centered(g, "Choose an input folder or image", x, y, w, h, DIM); return; }
        if (items == null) { centered(g, "Looking for images...", x, y, w, h, DIM); return; }
        if (items.isEmpty()) { centered(g, "No images found", x, y, w, h, DIM); return; }
        layoutPanes(x, y, w, h);
        String highlighted = selectedKey.startsWith("style:") ? selectedKey.substring(6) : "";
        for (String id : paneOrder) {
            Pane p = panes.get(id);
            if (p == null) continue;
            String title = id.equals("before") ? "Before" : Style.valueOf(id).label;
            boolean hl = id.equals(highlighted);
            if (p.img == null) {
                g.text(fitText(g, title, p.boxW, t), p.x, p.y + p.boxH / 2 - t - 6 * k, t, hl ? WHITE : GREY);
                centered(g, "...", p.x, p.y, p.boxW, p.boxH, DIM);
                continue;
            }
            int ix = p.x + (p.boxW - p.w) / 2, iy = p.y + (p.boxH - p.h) / 2;
            g.text(fitText(g, title, p.boxW, t), ix, iy - t - 8 * k, t, hl ? WHITE : GREY);   // title right above its picture
            g.image(p.img, ix, iy);
            if (hl) {   // frame the pane of the highlighted style
                g.fill(ix - 2 * k, iy - 2 * k, p.w + 4 * k, k, WHITE); g.fill(ix - 2 * k, iy + p.h + k, p.w + 4 * k, k, WHITE);
                g.fill(ix - 2 * k, iy - 2 * k, k, p.h + 4 * k, WHITE); g.fill(ix + p.w + k, iy - 2 * k, k, p.h + 4 * k, WHITE);
            }
        }
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
        g.fill(x, cy, bw, k * 2, WHITE); g.fill(x, cy + barH - 2 * k, bw, 2 * k, WHITE);
        g.fill(x, cy, 2 * k, barH, WHITE); g.fill(x + bw - 2 * k, cy, 2 * k, barH, WHITE);
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

    private void drawHelp(Surface g, int w, int h, String[] pairs) {
        int y = h - (t + 20 * k) + 4 * k + (t + 4 * k) / 2;
        int x = w - 14 * k;
        int d = t + 2 * k;
        for (int i = pairs.length - 2; i >= 0; i -= 2) {
            String label = pairs[i + 1];
            x -= g.textWidth(label, t);
            g.text(label, x, y - t / 2, t, GREY);
            String glyph = pairs[i];
            int gw = glyph.equals("LR") ? 2 * d + 4 * k : d;
            x -= 6 * k + gw;
            if (glyph.equals("<>")) {
                roundRect(g, x, y - d / 2, d, d, 3 * k, ICON);
                triangle(g, x + 3 * k, y, 4 * k, true, BLACK);
                triangle(g, x + d - 7 * k, y, 4 * k, false, BLACK);
            } else if (glyph.equals("LR")) {
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
            x -= 18 * k;
        }
    }

    // ------------------------------------------------------------------ pixel shapes

    /** The logo's O (bead) or X (cross stitch), d pixels square, pixel-exact. */
    private void shape(Surface g, Style which, int x, int y, int d, int color) { runs(g, Engine.stamp(which, d), x, y, d, color); }

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

    private void saveKnobs(Style st) {
        StringBuilder b = new StringBuilder();
        for (int v : s.knob[st.ordinal()]) b.append(v).append(',');
        savedKnobs.put(st.name(), b.toString());
        savePrefs();
    }

    private void savePrefs() {
        StringBuilder b = new StringBuilder();
        b.append("color=").append(s.color).append('\n').append("tones=").append(s.greyTones).append('\n')
         .append("colors=").append(s.colors).append('\n').append("accentOn=").append(s.accent).append('\n')
         .append("swatch=").append(s.swatch).append('\n').append("range=").append(s.accentRange).append('\n')
         .append("cells=").append(s.cells).append('\n').append("cell=").append(s.cellSize).append('\n')
         .append("replace=").append(replace).append('\n').append("zoom=").append(zoom).append('\n');
        StringBuilder st = new StringBuilder();
        for (Style x : Style.values()) if (styleOn[x.ordinal()]) st.append(x.name()).append(',');
        b.append("styles=").append(st).append('\n');
        for (Map.Entry<String, String> e : savedKnobs.entrySet()) b.append("knobs.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
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
            s.swatch = clamp(Integer.parseInt(m.getOrDefault("swatch", "0")), 0, Settings.SWATCHES.length - 1);
            s.accentRange = clamp(Integer.parseInt(m.getOrDefault("range", "1")), 0, 2);
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
                String v = m.get("knobs." + x.name());
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
