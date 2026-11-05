package cellmate.ui;

import cellmate.core.Engine;
import cellmate.core.Grid;
import cellmate.core.Picture;
import cellmate.core.Settings;
import cellmate.core.Settings.Style;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cellmate's whole interface, drawn by hand in the Black Mesa Tennis start-screen style: black,
 * pixel font, an inverted white bar for the selected row, left/right to change values, A to
 * select, B to go back. The desktop and Android apps only forward drawing and input.
 */
public final class Ui {
    public enum Pick { INPUT_FOLDER, INPUT_IMAGE, OUTPUT }
    public enum Key { UP, DOWN, LEFT, RIGHT, A, B }

    /** The logo's colours, used for the style marks: O = selectable, red X over it = chosen. */
    static final int MARK_WHITE = 0xffb3b3b3, MARK_RED = 0xffb52a2e;

    static final int BLACK = 0xff000000, WHITE = 0xfffafafa, GREY = 0xff9a9a9a, DIM = 0xff4a4a4a, ICON = 0xff7a7a7a;

    private final Host host;
    private final Settings s = new Settings();
    private final boolean[] styleOn = new boolean[Style.values().length];
    private Style previewStyle = Style.BORDER;
    private boolean replace, zoom;

    private Object input, output;
    private boolean inputFolder;
    private String inputName = "", outputName = "";
    private List<String> items;            // images in the input (null while scanning)
    private int previewIndex;
    private String message = "";

    // ---- preview
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "preview"));
    private final AtomicLong previewSeq = new AtomicLong();
    private Object previewImage;
    private int previewImgW, previewImgH, previewFullW, previewFullH, previewCropX, previewCropY;
    private String previewInfo = "";
    private boolean previewBusy;
    private Picture picture;
    private String pictureKey;
    private int boxW, boxH;              // preview area of the last layout

    // ---- batch
    private enum Screen { MENU, RUNNING, DONE }
    private Screen screen = Screen.MENU;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile int done, total, written, skipped, failed;
    private volatile String current = "", firstError = "";
    private long started;

    // ---- menu
    private String selectedKey = "Input folder";
    private int scroll;
    private boolean previewOnOtherScreen;

    // ---- layout of the last frame, for touch and mouse
    private int k = 1, rowH, menuX, menuY, menuW, menuH, previewX, previewY, previewW, previewH;
    private int imageX, imageY, leftArrowX, valueMidX;
    private final List<Row> shown = new ArrayList<>();

    public Ui(Host host) {
        this.host = host;
        styleOn[Style.BORDER.ordinal()] = true;
        loadPrefs();
    }

    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, "cellmate-" + name); t.setDaemon(true); return t; }

    // ================================================================== rows

    private final class Row {
        final String key, label;
        final java.util.function.Supplier<String> value;
        final java.util.function.IntConsumer change;   // null = not changeable
        final Runnable activate;
        Row(String key, String label, java.util.function.Supplier<String> value, java.util.function.IntConsumer change, Runnable activate) {
            this.key = key; this.label = label; this.value = value; this.change = change; this.activate = activate;
        }
    }

    private List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(new Row("Input folder", "Input folder", () -> input != null && inputFolder ? inputName : "Choose", null, () -> host.pick(Pick.INPUT_FOLDER)));
        r.add(new Row("Input image", "Input image", () -> input != null && !inputFolder ? inputName : "Choose", null, () -> host.pick(Pick.INPUT_IMAGE)));
        r.add(new Row("Output", "Output folder", () -> output != null ? outputName : "Choose", null, () -> host.pick(Pick.OUTPUT)));
        if (inputFolder && items != null && items.size() > 1)
            r.add(new Row("Preview image", "Preview image", () -> (previewIndex + 1) + " / " + items.size(), d -> {
                previewIndex = Math.floorMod(previewIndex + d, items.size()); schedulePreview();
            }, null));
        r.add(new Row("Zoom", "Preview zoom", () -> zoom ? "1:1" : "Fit", d -> { zoom = !zoom; changed(); }, null));
        for (Style st : Style.values())
            r.add(new Row("style:" + st.name(), st.label, () -> styleOn[st.ordinal()] ? "on" : "off",
                    d -> { styleOn[st.ordinal()] = !styleOn[st.ordinal()]; previewStyle = st; changed(); }, null));
        r.add(new Row("Mode", "Mode", () -> s.color ? "Color" : "Greyscale", d -> { s.color = !s.color; changed(); }, null));
        if (s.color)
            r.add(new Row("Colors", "Colors", () -> String.valueOf(s.colors), d -> { s.colors = clamp(s.colors + d, 2, 32); changed(); }, null));
        else
            r.add(new Row("Tones", "Grey tones", () -> String.valueOf(s.greyTones), d -> { s.greyTones = clamp(s.greyTones + d, 2, 8); changed(); }, null));
        if (!s.color) {
            r.add(new Row("Accent", "Accent color", () -> Settings.ACCENT_NAMES[s.accent], d -> {
                int n = Settings.ACCENT_NAMES.length, last = n - 1;
                int next = s.accent;
                do { next = Math.floorMod(next + d, n); } while (next == last && s.pickedColor == 0);
                s.accent = next; changed();
            }, null));
            if (s.accent > 0)
                r.add(new Row("Range", "Accent range", () -> Settings.RANGE_NAMES[s.accentRange], d -> { s.accentRange = clamp(s.accentRange + d, 0, 2); changed(); }, null));
        }
        r.add(new Row("Detail", "Detail", () -> s.cells + " across", d -> { s.cells = clamp(s.cells + 8 * d, 16, 256); changed(); }, null));
        r.add(new Row("Pixel", "Pixel size", () -> s.cellSize + " px", d -> { s.cellSize = clamp(s.cellSize + d, 2, 40); changed(); }, null));
        r.add(new Row("Existing", "Existing files", () -> replace ? "Replace" : "Skip", d -> { replace = !replace; savePrefs(); }, null));
        r.add(new Row("Start", "Start", this::startSummary, null, this::start));
        r.add(new Row("Quit", "Quit", () -> "", null, host::quit));
        return r;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

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
        if (row.key.startsWith("style:")) {
            Style st = Style.valueOf(row.key.substring(6));
            if (st != previewStyle) { previewStyle = st; schedulePreview(); }
        }
    }

    private void changed() { savePrefs(); schedulePreview(); host.repaint(); }

    // ================================================================== input

    public void key(Key key) {
        if (screen == Screen.RUNNING) { if (key == Key.B) { cancel.set(true); message = "Cancelling..."; } host.repaint(); return; }
        if (screen == Screen.DONE) { if (key == Key.A || key == Key.B) screen = Screen.MENU; host.repaint(); return; }
        List<Row> r = rows();
        int i = selectedIndex(r);
        Row row = r.get(i);
        switch (key) {
            case UP: select(r, (i - 1 + r.size()) % r.size()); break;
            case DOWN: select(r, (i + 1) % r.size()); break;
            case LEFT: if (row.change != null) row.change.accept(-1); break;
            case RIGHT: if (row.change != null) row.change.accept(1); break;
            case A: if (row.activate != null) row.activate.run(); else if (row.change != null) row.change.accept(1); break;
            case B: if (!row.key.equals("Input folder")) select(r, 0); break;
        }
        host.repaint();
    }

    /** A tap or click at (x, y). */
    public void tap(int x, int y) {
        if (screen == Screen.RUNNING) { cancel.set(true); message = "Cancelling..."; host.repaint(); return; }
        if (screen == Screen.DONE) { screen = Screen.MENU; host.repaint(); return; }
        if (!previewOnOtherScreen && inside(x, y, previewX, previewY, previewW, previewH)) { tapPreview(x - imageX, y - imageY); return; }
        if (!inside(x, y, menuX, menuY, menuW, menuH)) return;
        int idx = scroll + (y - menuY) / rowH;
        List<Row> r = rows();
        if (idx < 0 || idx >= r.size()) return;
        Row row = r.get(idx);
        if (!row.key.equals(selectedKey)) { select(r, idx); host.repaint(); return; }
        if (row.change != null) {
            // Second tap: on the left arrow (or just left of the value) lowers, anywhere else raises.
            row.change.accept(leftArrowX >= 0 && x >= leftArrowX - 6 * k && x < valueMidX ? -1 : 1);
        } else if (row.activate != null) {
            row.activate.run();
        }
        host.repaint();
    }

    /** Mouse wheel or swipe, in rows (positive = down). */
    public void scrollBy(int rowsDelta) {
        List<Row> r = rows();
        int visible = Math.max(1, menuH / Math.max(1, rowH));
        scroll = clamp(scroll + rowsDelta, 0, Math.max(0, r.size() - visible));
        host.repaint();
    }

    private static boolean inside(int x, int y, int rx, int ry, int rw, int rh) { return x >= rx && y >= ry && x < rx + rw && y < ry + rh; }

    /** A tap on the second screen's preview. */
    public void tapPreviewScreen(int x, int y) { tapPreview(x - imageX, y - imageY); }

    /** Tapping the preview in greyscale mode picks the accent colour from the photo. */
    public void tapPreview(int px, int py) {
        if (s.color || picture == null || previewImage == null || px < 0 || py < 0 || px >= previewImgW || py >= previewImgH) return;
        double fx = (previewCropX + px) / (double) previewFullW, fy = (previewCropY + py) / (double) previewFullH;
        if (!zoom) { fx = px / (double) previewImgW; fy = py / (double) previewImgH; }
        s.pickedColor = Engine.sampleColor(picture, fx, fy);
        s.accent = Settings.ACCENT_NAMES.length - 1;
        message = "Accent picked from the photo";
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
            scan();
        }
        message = "";
        savePrefs();
        host.repaint();
    }

    private void scan() {
        final Object in = input; final boolean folder = inputFolder;
        Thread t = daemon(() -> {
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
        }, "scan");
        t.start();
    }

    private void say(String text) { host.runOnUi(() -> { message = text; host.repaint(); }); }

    // ================================================================== preview

    /** Tells the UI that the preview is shown on a second screen (the Thor's lower display). */
    public void setPreviewOnOtherScreen(boolean other) { previewOnOtherScreen = other; host.repaint(); }

    private void schedulePreview() {
        if (input == null || items == null || items.isEmpty() || boxW <= 0) return;
        final long seq = previewSeq.incrementAndGet();
        final Settings set = s.copy();
        final Style style = previewStyleNow();
        final String rel = items.get(Math.min(previewIndex, items.size() - 1));
        final Object in = input; final boolean folder = inputFolder;
        final int bw = boxW, bh = boxH; final boolean z = zoom;
        previewBusy = true;
        previewWorker.execute(() -> {
            if (seq != previewSeq.get()) return;
            try {
                String key = (folder ? "d:" : "f:") + in + "|" + rel;
                Picture p = picture;
                if (p == null || !key.equals(pictureKey)) { p = host.decode(in, folder, rel); picture = p; pictureKey = key; }
                if (seq != previewSeq.get()) return;
                Grid g = Engine.grid(p, set);
                if (seq != previewSeq.get()) return;
                Engine.Image full = Engine.draw(g, style, set.cellSize);
                Engine.Image shownImg = z ? Engine.crop(full, bw, bh, 0.5, 0.5) : Engine.fit(full, bw, bh);
                int cropX = z ? (full.width - shownImg.width) / 2 : 0, cropY = z ? (full.height - shownImg.height) / 2 : 0;
                Object img = host.makeImage(shownImg.argb, shownImg.width, shownImg.height);
                String info = style.label + "  " + g.cols + "x" + g.rows + "  " + full.width + "x" + full.height + " px";
                host.runOnUi(() -> {
                    if (seq != previewSeq.get()) return;
                    previewImage = img; previewImgW = shownImg.width; previewImgH = shownImg.height;
                    previewFullW = full.width; previewFullH = full.height; previewCropX = cropX; previewCropY = cropY;
                    previewInfo = info; previewBusy = false;
                    host.repaint();
                });
            } catch (Throwable e) {
                host.runOnUi(() -> { if (seq == previewSeq.get()) { previewBusy = false; message = "Preview failed: " + rel; host.repaint(); } });
            }
        });
    }

    private Style previewStyleNow() {
        if (selectedKey.startsWith("style:")) return Style.valueOf(selectedKey.substring(6));
        if (styleOn[previewStyle.ordinal()]) return previewStyle;
        for (Style st : Style.values()) if (styleOn[st.ordinal()]) return st;
        return previewStyle;
    }

    // ================================================================== batch

    private void start() {
        List<Style> chosen = new ArrayList<>();
        for (Style st : Style.values()) if (styleOn[st.ordinal()]) chosen.add(st);
        if (input == null) { message = "Choose an input folder or image first."; return; }
        if (output == null) { message = "Choose an output folder first."; return; }
        if (chosen.isEmpty()) { message = "Turn on at least one style."; return; }
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
        Thread t = daemon(() -> {
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
                            host.writePng(out, Plan.styleFolder(name, folder, st) + "/" + outs.get(i), Engine.draw(g, st, set.cellSize));
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
        }, "batch");
        t.start();
    }

    // ================================================================== drawing

    /** Draws everything. previewArea: whether the preview goes on this surface too. */
    public void draw(Surface g, int w, int h) {
        k = Math.max(1, Math.min(Math.round(h / 540f), Math.round(w / 640f)));
        int t = 20 * k;          // pixel font size (one font pixel = k screen pixels)
        rowH = t + 8 * k;
        g.fill(0, 0, w, h, BLACK);

        // top line: name, and what's loaded
        g.text("CELLMATE", 14 * k, 8 * k, t, GREY);
        String top = input == null ? "" : inputName + (items == null ? "" : "  " + items.size() + (items.size() == 1 ? " image" : " images"));
        top = fitText(g, top, w / 2, t);
        g.text(top, w - 14 * k - g.textWidth(top, t), 8 * k, t, GREY);

        int topH = t + 18 * k, bottomH = t + 20 * k;
        int midY = topH, midH = h - topH - bottomH;

        if (screen != Screen.MENU) {
            drawJob(g, w, midY, midH, t);
            drawHelp(g, w, h, t, screen == Screen.RUNNING ? new String[]{"B", "Cancel"} : new String[]{"A", "OK"});
            return;
        }

        boolean landscape = w >= h;
        if (previewOnOtherScreen) {
            menuX = w / 6; menuY = midY; menuW = w - w / 6; menuH = midH;
            previewW = previewH = 0;
        } else if (landscape) {
            menuX = 0; menuY = midY; menuW = (int) (w * 0.46); menuH = midH;
            previewX = menuW + 16 * k; previewY = midY; previewW = w - previewX - 14 * k; previewH = midH;
        } else {
            previewX = 14 * k; previewY = midY; previewW = w - 28 * k; previewH = (int) (midH * 0.45);
            menuX = 0; menuY = previewY + previewH + 10 * k; menuW = w; menuH = midH - previewH - 10 * k;
        }
        drawMenu(g, t);
        if (!previewOnOtherScreen) drawPreviewArea(g, previewX, previewY, previewW, previewH, t);

        String msg = fitText(g, message, w / 2, t);
        g.text(msg, 14 * k, h - bottomH + 6 * k, t, GREY);
        drawHelp(g, w, h, t, new String[]{"A", "Select", "B", "Back", "<>", "Change"});
    }

    /** Draws only the preview, for a second screen. */
    public void drawPreviewScreen(Surface g, int w, int h) {
        int kk = Math.max(1, Math.round(h / 540f)), t = 20 * kk;
        g.fill(0, 0, w, h, BLACK);
        int saveK = k; k = kk;
        drawPreviewArea(g, 14 * kk, 10 * kk, w - 28 * kk, h - 20 * kk, t);
        k = saveK;
    }

    private void drawMenu(Surface g, int t) {
        List<Row> r = rows();
        shown.clear(); shown.addAll(r);
        int sel = selectedIndex(r);
        int visible = Math.max(1, menuH / rowH);
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + visible) scroll = sel - visible + 1;
        scroll = clamp(scroll, 0, Math.max(0, r.size() - visible));
        g.clip(menuX, menuY, menuW, menuH);
        for (int i = scroll; i < r.size() && i < scroll + visible; i++) {
            Row row = r.get(i);
            int y = menuY + (i - scroll) * rowH;
            boolean on = i == sel;
            if (on) g.fill(menuX, y, menuW, rowH, WHITE);
            int ty = y + (rowH - t) / 2;
            int right = menuX + menuW - 14 * k;
            if (row.key.startsWith("style:")) {
                // Selectable style: an O on the left; a bigger red X over it when it's chosen for output.
                int box = t, cx = menuX + 10 * k + box / 2, cy = y + rowH / 2;
                int o = (box * 3 / 4) | 1, xs = (box * 5 / 4) | 1;
                stamp(g, Style.BEADS, cx - o / 2, cy - o / 2, o, on ? BLACK : MARK_WHITE);
                if (row.value.get().equals("on")) cross(g, cx - xs / 2, cy - xs / 2, xs, MARK_RED);
                g.text(row.label, menuX + 10 * k + box + 12 * k, ty, t, on ? BLACK : WHITE);
                // Sample patch: a few pixels drawn exactly as this style will draw them.
                int ph = rowH - 6 * k, pw = ph * 5 / 2;
                g.fill(right - pw - k, y + 2 * k, pw + 2 * k, ph + 2 * k, on ? BLACK : DIM);
                g.image(patch(Style.valueOf(row.key.substring(6)), pw, ph), right - pw, y + 3 * k);
                if (on) { leftArrowX = -1; valueMidX = -1; }
                continue;
            }
            g.text(row.label, menuX + 10 * k, ty, t, on ? BLACK : WHITE);
            String v = row.value.get();
            int labelEnd = menuX + 10 * k + g.textWidth(row.label, t) + 16 * k;
            boolean arrows = on && row.change != null;
            int arrowW = arrows ? 6 * k : 0, gap = arrows ? 6 * k : 0;
            v = fitText(g, v, right - labelEnd - 2 * (arrowW + gap), t);
            int vw = g.textWidth(v, t);
            int vx = right - vw - (arrowW + gap);
            g.text(v, vx, ty, t, on ? BLACK : GREY);
            if (on) { leftArrowX = vx - gap - arrowW; valueMidX = vx + vw / 2; }
            if (arrows) {
                int cy = y + rowH / 2;
                triangle(g, vx - gap - arrowW, cy, arrowW, true, BLACK);
                triangle(g, vx + vw + gap, cy, arrowW, false, BLACK);
            }
        }
        g.unclip();
        // scroll hints
        if (scroll > 0) triangleUp(g, menuX + menuW - 10 * k, menuY - 6 * k, 4 * k, true, DIM);
        if (scroll + visible < r.size()) triangleUp(g, menuX + menuW - 10 * k, menuY + visible * rowH + 2 * k, 4 * k, false, DIM);
    }

    private void drawPreviewArea(Surface g, int x, int y, int w, int h, int t) {
        int infoH = t + 8 * k;
        int bw = w, bh = h - infoH;
        if (bw != boxW || bh != boxH) { boxW = bw; boxH = bh; schedulePreview(); }
        if (input == null) { centered(g, "Choose a folder or an image", x, y, w, h, t, DIM); return; }
        if (items == null) { centered(g, "Looking for images...", x, y, w, h, t, DIM); return; }
        if (items.isEmpty()) { centered(g, "No images found", x, y, w, h, t, DIM); return; }
        String info = previewBusy ? previewInfo + "  ..." : previewInfo;
        if (previewImage == null) { centered(g, "Rendering...", x, y, w, h, t, DIM); return; }
        g.text(fitText(g, info, w, t), x, y, t, GREY);
        imageX = x + (bw - previewImgW) / 2;
        imageY = y + infoH + (bh - previewImgH) / 2;
        g.image(previewImage, imageX, imageY);
    }

    private void drawJob(Surface g, int w, int y, int h, int t) {
        int x = w / 6, bw = w - 2 * x;
        int cy = y + h / 2 - 3 * (t + 10 * k);
        boolean running = screen == Screen.RUNNING;
        g.text(running ? (cancel.get() ? "Cancelling..." : "Converting") : (cancel.get() ? "Cancelled" : "Done"), x, cy, t * 2, WHITE);
        cy += 2 * t + 20 * k;
        // progress bar: white outline, white fill
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

    private void drawHelp(Surface g, int w, int h, int t, String[] pairs) {
        int y = h - (t + 20 * k) + 4 * k + (t + 4 * k) / 2;   // vertical centre of the help line
        int x = w - 14 * k;
        int d = t + 2 * k;  // glyph diameter
        for (int i = pairs.length - 2; i >= 0; i -= 2) {
            String label = pairs[i + 1];
            int lw = g.textWidth(label, t);
            x -= lw;
            g.text(label, x, y - t / 2, t, GREY);
            x -= 6 * k + d;
            if (pairs[i].equals("<>")) {
                roundRect(g, x, y - d / 2, d, d, 3 * k, ICON);
                triangle(g, x + 3 * k, y, 4 * k, true, BLACK);
                triangle(g, x + d - 7 * k, y, 4 * k, false, BLACK);
            } else {
                circle(g, x, y - d / 2, d, ICON);
                int tw = g.textWidth(pairs[i], t);
                g.text(pairs[i], x + (d - tw) / 2, y - t / 2, t, BLACK);
            }
            x -= 18 * k;
        }
    }

    // ------------------------------------------------------------------ pixel shapes

    private final Map<String, Object> patches = new HashMap<>();

    /**
     * A pw x ph sample of a style at the real pixel size (smaller only if two pixels wouldn't
     * fit in the row), in the current mode's tones.
     */
    private Object patch(Style st, int pw, int ph) {
        int cell = Math.max(2, Math.min(s.cellSize, ph / 2));
        int[] values = s.color ? new int[]{0xfffafafa, 0xffd8333a, 0xff3570d8, 0xfff2c230}
                : greyPatchTones();
        String key = st + "|" + pw + "x" + ph + "|" + cell + "|" + Arrays.toString(values);
        Object img = patches.get(key);
        if (img != null) return img;
        int cols = (pw + cell - 1) / cell + 1, rows = (ph + cell - 1) / cell + 1;
        int[] c = new int[cols * rows];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) c[y * cols + x] = values[(x + y) % values.length];
        Engine.Image im = Engine.draw(new Grid(cols, rows, c), st, cell);
        int[] crop = new int[pw * ph];
        for (int y = 0; y < ph; y++) System.arraycopy(im.argb, y * im.width, crop, y * pw, pw);
        img = host.makeImage(crop, pw, ph);
        patches.put(key, img);
        return img;
    }

    /** Light tones of the current greyscale setting (black would just show nothing). */
    private int[] greyPatchTones() {
        int n = Math.max(2, Math.min(8, s.greyTones));
        double gamma = Math.log(255.0 / 96.0) / Math.log(2.0);
        List<Integer> v = new ArrayList<>();
        for (int i = n - 1; i >= 1; i--) {
            int g = n == 3 && i == 1 ? 96 : (int) Math.round(255 * Math.pow((double) i / (n - 1), gamma));
            v.add(0xff000000 | g << 16 | g << 8 | g);
        }
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    /** A slimmer X than the stitch shape, so the O under it still shows. */
    private void cross(Surface g, int x, int y, int d, int color) {
        int half = Math.max(1, Math.round(d / 13f));
        for (int yy = 0; yy < d; yy++) {
            int a = yy, b = d - 1 - yy;   // centres of the two strokes on this row
            g.fill(x + Math.max(0, a - half), y + yy, Math.min(d, a + half + 1) - Math.max(0, a - half), 1, color);
            g.fill(x + Math.max(0, b - half), y + yy, Math.min(d, b + half + 1) - Math.max(0, b - half), 1, color);
        }
    }

    /** A bead (O) or cross stitch (X) shape, d pixels square, drawn pixel-exact. */
    private void stamp(Surface g, Style shape, int x, int y, int d, int color) {
        boolean[] m = Engine.stamp(shape, d);
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
            int len = (w - i) * 2 - 1;   // column height
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

    private void centered(Surface g, String text, int x, int y, int w, int h, int t, int color) {
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

    private void savePrefs() {
        StringBuilder b = new StringBuilder();
        b.append("color=").append(s.color).append('\n').append("tones=").append(s.greyTones).append('\n')
         .append("colors=").append(s.colors).append('\n').append("accent=").append(s.accent).append('\n')
         .append("picked=").append(s.pickedColor).append('\n').append("range=").append(s.accentRange).append('\n')
         .append("cells=").append(s.cells).append('\n').append("cell=").append(s.cellSize).append('\n')
         .append("replace=").append(replace).append('\n').append("zoom=").append(zoom).append('\n');
        StringBuilder st = new StringBuilder();
        for (Style x : Style.values()) if (styleOn[x.ordinal()]) st.append(x.name()).append(',');
        b.append("styles=").append(st).append('\n');
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
            s.colors = clamp(Integer.parseInt(m.getOrDefault("colors", "12")), 2, 32);
            s.accent = clamp(Integer.parseInt(m.getOrDefault("accent", "0")), 0, Settings.ACCENT_NAMES.length - 1);
            s.pickedColor = Integer.parseInt(m.getOrDefault("picked", "0"));
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
