package cellmate.core;

import java.util.Random;

/**
 * Turns a picture into cells (grid), then draws the cells in one of the styles.
 * Greyscale with 2 or 3 tones follows pixel_crt.py exactly; everything else builds on it.
 */
public final class Engine {
    private Engine() { }

    static final int BACKGROUND = 38;   // grey seen between pixels in border / rounded / jitter
    static final int SUPERSAMPLE = 4;

    // ------------------------------------------------------------------ cells

    public static int[] gridSize(int w, int h, int cells) {
        if (w <= h) return new int[]{cells, Math.max(1, (int) Math.rint((double) h * cells / w))};
        return new int[]{Math.max(1, (int) Math.rint((double) w * cells / h)), cells};
    }

    public static Grid grid(Picture p, Settings s) {
        int[] size = gridSize(p.width, p.height, s.cells);
        int cols = size[0], rows = size[1];
        return s.color ? colorGrid(p, s, cols, rows) : greyGrid(p, s, cols, rows);
    }

    /** Grey tone values for n tones (3 tones gives pixel_crt.py's dark grey, 96). */
    static int[] greyValues(int n) {
        int[] v = new int[n];
        double gamma = Math.log(255.0 / 96.0) / Math.log(2.0);
        for (int i = 0; i < n; i++) v[i] = n == 3 && i == 1 ? 96 : (int) Math.round(255 * Math.pow((double) i / (n - 1), gamma));
        return v;
    }

    private static Grid greyGrid(Picture p, Settings s, int cols, int rows) {
        byte[] e = p.enhanced();
        int[] ei = new int[e.length];
        for (int i = 0; i < e.length; i++) ei[i] = e[i] & 255;
        int[] sm = Pil.resize(new int[][]{ei}, p.width, p.height, cols, rows, Pil.BOX)[0];
        int n = Math.max(2, Math.min(8, s.greyTones));
        int[] q = new int[cols * rows];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float small = sm[y * cols + x] / 255f;
                float pat = bayer(x, y);
                int i = y * cols + x;
                if (n == 2) {
                    q[i] = small > 0.35f + 0.30f * pat ? 1 : 0;
                } else {
                    // Thresholds spread around the middle; 3 tones = pixel_crt.py's 0.38 / 0.66.
                    float span = 0.28f + (n - 3) * 0.096f, first = 0.52f - span / 2;
                    float amp = n == 3 ? 0.08f : Math.min(0.08f, span / (n - 2) * 0.5f);
                    int t = 0;
                    for (int k = 0; k < n - 1; k++) {
                        float base = n == 3 ? (k == 0 ? 0.38f : 0.66f) : first + span * k / (n - 2);
                        if (small > base + amp * (pat - 0.5f)) t = k + 1;
                    }
                    q[i] = t;
                }
            }
        }
        if (n >= 3) q = tidy(q, cols, rows, n);
        int[] values = greyValues(n);
        int[] color = new int[q.length];
        for (int i = 0; i < q.length; i++) { int v = values[q[i]]; color[i] = 0xff000000 | (v << 16) | (v << 8) | v; }
        if (s.accent > 0) applyAccent(p, s, cols, rows, color);
        return new Grid(cols, rows, color);
    }

    private static float bayer(int x, int y) {
        // [[0, 2], [3, 1]] / 4 + 0.125, as float32
        int b = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 2) : ((x & 1) == 0 ? 3 : 1);
        return b / 4.0f + 0.125f;
    }

    /** A lone cell that matches none of its 8 neighbours takes the most common value around it. */
    private static int[] tidy(int[] q, int cols, int rows, int n) {
        int[] out = new int[q.length];
        int[] count = new int[n];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int me = q[y * cols + x];
                java.util.Arrays.fill(count, 0);
                boolean lone = true;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int yy = Math.max(0, Math.min(rows - 1, y + dy)), xx = Math.max(0, Math.min(cols - 1, x + dx));
                        int v = q[yy * cols + xx];
                        count[v]++;
                        if (v == me) lone = false;
                    }
                }
                int best = 0;
                for (int t = 1; t < n; t++) if (count[t] > count[best]) best = t;
                out[y * cols + x] = lone ? best : me;
            }
        }
        return out;
    }

    /** Colour splash: cells whose colour is close to the accent hue get the accent colour. */
    private static void applyAccent(Picture p, Settings s, int cols, int rows, int[] color) {
        int[][] rgb = boxRgb(p, cols, rows);
        float hue = s.accentHue(), range = Settings.RANGE_DEGREES[s.accentRange];
        int ink = s.accentColor();
        boolean[] hit = new boolean[color.length];
        for (int i = 0; i < color.length; i++) {
            float[] hsv = hsv(0xff000000 | (rgb[0][i] << 16) | (rgb[1][i] << 8) | rgb[2][i]);
            float d = Math.abs(hsv[0] - hue);
            d = Math.min(d, 360 - d);
            hit[i] = d <= range && hsv[1] >= 0.28f && hsv[2] >= 0.18f;
        }
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int i = y * cols + x;
                if (!hit[i]) continue;
                boolean company = false;  // skip lone specks
                for (int dy = -1; dy <= 1 && !company; dy++)
                    for (int dx = -1; dx <= 1 && !company; dx++) {
                        int yy = y + dy, xx = x + dx;
                        if ((dx != 0 || dy != 0) && yy >= 0 && yy < rows && xx >= 0 && xx < cols && hit[yy * cols + xx]) company = true;
                    }
                if (company) color[i] = ink;
            }
        }
    }

    private static Grid colorGrid(Picture p, Settings s, int cols, int rows) {
        int[][] rgb = boxRgb(p, cols, rows);
        int n = cols * rows;
        // Gentle level stretch, the same on all channels so colours keep their hue.
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[(rgb[0][i] * 299 + rgb[1][i] * 587 + rgb[2][i] * 114) / 1000]++;
        int lo = level(hist, n, 0.005, false), hi = level(hist, n, 0.005, true);
        float scale = hi > lo ? 255f / (hi - lo) : 1f;
        float[][] px = new float[n][3];
        for (int i = 0; i < n; i++) {
            float r = (rgb[0][i] - lo) * scale, g = (rgb[1][i] - lo) * scale, b = (rgb[2][i] - lo) * scale;
            float l = 0.299f * r + 0.587f * g + 0.114f * b;   // a little more saturation
            px[i][0] = clamp(l + (r - l) * 1.15f); px[i][1] = clamp(l + (g - l) * 1.15f); px[i][2] = clamp(l + (b - l) * 1.15f);
        }
        int k = Math.max(2, Math.min(32, s.colors));
        float[][] centres = kmeans(px, k);
        int[] q = new int[n];
        for (int i = 0; i < n; i++) q[i] = nearest(px[i], centres);
        q = tidy(q, cols, rows, centres.length);
        int[] color = new int[n];
        for (int i = 0; i < n; i++) {
            float[] c = centres[q[i]];
            color[i] = 0xff000000 | (Math.round(c[0]) << 16) | (Math.round(c[1]) << 8) | Math.round(c[2]);
        }
        return new Grid(cols, rows, color);
    }

    /** Average colour of each cell's area of the picture, as R, G, B planes. */
    static int[][] boxRgb(Picture p, int cols, int rows) {
        int n = cols * rows;
        long[] r = new long[n], g = new long[n], b = new long[n];
        int[] cnt = new int[n];
        int[] cx = new int[p.width];
        for (int x = 0; x < p.width; x++) cx[x] = (int) ((long) x * cols / p.width);
        for (int y = 0; y < p.height; y++) {
            int row = (int) ((long) y * rows / p.height) * cols, base = y * p.width;
            for (int x = 0; x < p.width; x++) {
                int v = p.argb[base + x], i = row + cx[x];
                r[i] += (v >> 16) & 255; g[i] += (v >> 8) & 255; b[i] += v & 255; cnt[i]++;
            }
        }
        int[][] out = new int[3][n];
        for (int i = 0; i < n; i++) {
            int c = Math.max(1, cnt[i]);
            out[0][i] = (int) ((r[i] + c / 2) / c); out[1][i] = (int) ((g[i] + c / 2) / c); out[2][i] = (int) ((b[i] + c / 2) / c);
        }
        return out;
    }

    /** Average colour of the picture around a point given as fractions of its width and height. */
    public static int sampleColor(Picture p, double fx, double fy) {
        int x = (int) Math.max(0, Math.min(p.width - 1, fx * p.width)), y = (int) Math.max(0, Math.min(p.height - 1, fy * p.height));
        int rad = Math.max(1, Math.min(p.width, p.height) / 200);
        long r = 0, g = 0, b = 0; int n = 0;
        for (int yy = Math.max(0, y - rad); yy <= Math.min(p.height - 1, y + rad); yy++)
            for (int xx = Math.max(0, x - rad); xx <= Math.min(p.width - 1, x + rad); xx++) {
                int v = p.argb[yy * p.width + xx]; r += (v >> 16) & 255; g += (v >> 8) & 255; b += v & 255; n++;
            }
        return 0xff000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /** Scales an image down to fit a box (area average, so fine patterns stay true), never up. */
    public static Image fit(Image im, int boxW, int boxH) {
        double f = Math.min(1.0, Math.min((double) boxW / im.width, (double) boxH / im.height));
        int w = Math.max(1, (int) Math.floor(im.width * f)), h = Math.max(1, (int) Math.floor(im.height * f));
        if (w == im.width && h == im.height) return im;
        int n = im.argb.length;
        int[] r = new int[n], g = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) { int v = im.argb[i]; r[i] = (v >> 16) & 255; g[i] = (v >> 8) & 255; b[i] = v & 255; }
        int[][] o = Pil.resize(new int[][]{r, g, b}, im.width, im.height, w, h, Pil.BOX);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) out[i] = 0xff000000 | (o[0][i] << 16) | (o[1][i] << 8) | o[2][i];
        return new Image(w, h, out);
    }

    /** A box-sized piece of an image at real size, centred on (fx, fy) given as fractions. */
    public static Image crop(Image im, int boxW, int boxH, double fx, double fy) {
        int w = Math.min(boxW, im.width), h = Math.min(boxH, im.height);
        int x0 = (int) Math.max(0, Math.min(im.width - w, fx * im.width - w / 2.0));
        int y0 = (int) Math.max(0, Math.min(im.height - h, fy * im.height - h / 2.0));
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(im.argb, (y0 + y) * im.width + x0, out, y * w, w);
        return new Image(w, h, out);
    }

    private static int level(int[] hist, int n, double frac, boolean top) {
        long cut = (long) (n * frac), acc = 0;
        if (!top) { for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc > cut) return i; } return 0; }
        for (int i = 255; i >= 0; i--) { acc += hist[i]; if (acc > cut) return i; }
        return 255;
    }

    private static float clamp(float v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static float dist(float[] a, float[] b) {
        float dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db;
    }

    private static int nearest(float[] p, float[][] c) {
        int best = 0; float bd = Float.MAX_VALUE;
        for (int j = 0; j < c.length; j++) { float d = dist(p, c[j]); if (d < bd) { bd = d; best = j; } }
        return best;
    }

    /** k-means with k-means++ starts; fixed seed so the same picture always gives the same palette. */
    private static float[][] kmeans(float[][] px, int k) {
        Random rnd = new Random(7);
        int n = px.length;
        float[][] c = new float[k][];
        c[0] = px[rnd.nextInt(n)].clone();
        float[] d = new float[n];
        int made = 1;
        for (; made < k; made++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                float best = Float.MAX_VALUE;
                for (int j = 0; j < made; j++) best = Math.min(best, dist(px[i], c[j]));
                d[i] = best; total += best;
            }
            if (total <= 0) break;   // fewer distinct colours than k
            double r = rnd.nextDouble() * total;
            int pick = n - 1;
            for (int i = 0; i < n; i++) { r -= d[i]; if (r <= 0) { pick = i; break; } }
            c[made] = px[pick].clone();
        }
        float[][] cent = java.util.Arrays.copyOf(c, made);
        int[] assign = new int[n];
        for (int iter = 0; iter < 16; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) { int a = nearest(px[i], cent); if (a != assign[i]) { assign[i] = a; changed = true; } }
            double[][] sum = new double[cent.length][3];
            int[] cnt = new int[cent.length];
            for (int i = 0; i < n; i++) { int a = assign[i]; cnt[a]++; for (int ch = 0; ch < 3; ch++) sum[a][ch] += px[i][ch]; }
            for (int j = 0; j < cent.length; j++)
                if (cnt[j] > 0) for (int ch = 0; ch < 3; ch++) cent[j][ch] = (float) (sum[j][ch] / cnt[j]);
            if (!changed && iter > 0) break;
        }
        return cent;
    }

    static float[] hsv(int argb) {
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, h;
        if (d == 0) h = 0;
        else if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    // ------------------------------------------------------------------ drawing

    /** A drawn image: ARGB pixels, row by row. */
    public static final class Image {
        public final int width, height;
        public final int[] argb;
        public Image(int width, int height, int[] argb) { this.width = width; this.height = height; this.argb = argb; }
    }

    public static Image draw(Grid g, Settings.Style style, int cellSize) {
        switch (style) {
            case SOLID: return solid(g, cellSize);
            case STITCH: case BEADS: return stamps(g, cellSize, stamp(style, cellSize));
            case MELTED: return melted(g, cellSize);
            default: return blocks(g, cellSize, style);
        }
    }

    private static Image solid(Grid g, int c) {
        int w = g.cols * c, h = g.rows * c;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = (y / c) * g.cols;
            for (int x = 0; x < w; x++) out[y * w + x] = g.color[row + x / c];
        }
        return new Image(w, h, out);
    }

    public static boolean[] stamp(Settings.Style style, int c) {
        boolean[] s = new boolean[c * c];
        if (style == Settings.Style.BEADS) {
            double centre = (c - 1) / 2.0;
            for (int y = 0; y < c; y++) for (int x = 0; x < c; x++) {
                double d = Math.hypot(x - centre, y - centre);
                s[y * c + x] = d <= c / 2.0 - 0.35 && d >= c * 0.17;
            }
        } else {   // cross stitch in the logo's proportions
            int m = (int) Math.rint(c / 24.0), k = Math.max(0, (int) Math.rint(c * 4 / 24.0));
            for (int y = 0; y < c; y++) for (int x = 0; x < c; x++) {
                boolean inside = x >= m && x < c - m && y >= m && y < c - m;
                s[y * c + x] = inside && (Math.abs(x - y) <= k || Math.abs(x + y - (c - 1)) <= k);
            }
        }
        return s;
    }

    private static Image stamps(Grid g, int c, boolean[] stamp) {
        int w = g.cols * c, h = g.rows * c;
        int[] out = new int[w * h];
        java.util.Arrays.fill(out, 0xff000000);
        for (int y = 0; y < h; y++) {
            int cy = y / c, ly = y % c;
            for (int x = 0; x < w; x++) if (stamp[ly * c + x % c]) out[y * w + x] = g.color[cy * g.cols + x / c];
        }
        return new Image(w, h, out);
    }

    private static Image melted(Grid g, int c) {
        int rows = g.rows, cols = g.cols, n = c + 4, pad = 2;
        int W = cols * c + 2 * pad, H = rows * c + 2 * pad;
        int[] out = new int[W * H];
        java.util.Arrays.fill(out, 0xff000000);
        Random rnd = new Random(7);
        double[] melt = new double[rows * cols];
        for (int i = 0; i < melt.length; i++) {
            boolean m = rnd.nextDouble() < 0.35;
            double amount = 0.3 + 0.7 * rnd.nextDouble();
            melt[i] = m ? amount : 0;
        }
        double centre = (n - 1) / 2.0;
        boolean[] plain = new boolean[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double d = Math.hypot(x - centre, y - centre);
            plain[y * n + x] = d <= c / 2.0 - 0.35 && d >= c * 0.17;
        }
        // Plain beads first, melted ones on top (they may spread a little past their cell).
        Integer[] order = new Integer[melt.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(melt[a], melt[b]));
        boolean[] shape = new boolean[n * n];
        for (int idx : order) {
            int col = g.color[idx];
            if (col == Grid.EMPTY) continue;
            int r = idx / cols, k = idx % cols;
            double m = melt[idx];
            boolean[] sh = plain;
            if (m > 0) {
                double p1 = rnd.nextDouble() * 2 * Math.PI, p2 = rnd.nextDouble() * 2 * Math.PI;
                double hx = (rnd.nextDouble() * 2 - 1) * m * c * 0.06, hy = (rnd.nextDouble() * 2 - 1) * m * c * 0.06;
                double hole = c * 0.17 * (1 - 0.45 * m);
                for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                    double dx = x - centre, dy = y - centre, a = Math.atan2(dy, dx);
                    double outer = (c / 2.0 - 0.35 + m * 0.9) * (1 + m * (0.07 * Math.sin(2 * a + p1) + 0.05 * Math.sin(3 * a + p2)));
                    shape[y * n + x] = Math.hypot(dx, dy) <= outer && Math.hypot(dx - hx, dy - hy) >= hole;
                }
                sh = shape;
            }
            for (int y = 0; y < n; y++) {
                int oy = r * c + y;
                for (int x = 0; x < n; x++) if (sh[y * n + x]) out[oy * W + k * c + x] = col;
            }
        }
        int w = cols * c, h = rows * c;
        int[] crop = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(out, (y + pad) * W + pad, crop, y * w, w);
        return new Image(w, h, crop);
    }

    /**
     * Border, rounded and jitter: squares drawn 4x larger on a grey background, then scaled down
     * with Lanczos (as pixel_crt.py does). Streamed a few rows at a time to keep memory small.
     */
    private static Image blocks(Grid g, int cellSize, Settings.Style style) {
        final int c = cellSize * SUPERSAMPLE, cols = g.cols, rows = g.rows;
        final int W = cols * c, H = rows * c, w = cols * cellSize, h = rows * cellSize;
        final double inset = style == Settings.Style.BORDER ? 0.045 : style == Settings.Style.JITTER ? 0.03 : 0.0;
        double[] jitter = new double[rows * cols];
        if (style == Settings.Style.JITTER) {
            Random rnd = new Random(7);
            for (int i = 0; i < jitter.length; i++) jitter[i] = -0.05 + 0.1 * rnd.nextDouble();
        }
        // Per cell: inclusive rectangle in supersampled pixels (Pillow truncates the corners).
        int[] x0 = new int[rows * cols], x1 = new int[rows * cols], y0 = new int[rows * cols], y1 = new int[rows * cols];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            int i = y * cols + x;
            double m = (inset + jitter[i]) * c;
            x0[i] = (int) (x * c + m); y0[i] = (int) (y * c + m);
            x1[i] = (int) ((x + 1) * c - m - 1); y1[i] = (int) ((y + 1) * c - m - 1);
        }
        final boolean[] round = style == Settings.Style.ROUNDED ? roundedMask(c, 0.2 * c) : null;

        int[] bx = new int[2 * w], by = new int[2 * h];
        int[][] kx = coeffs(W, w, bx), ky = coeffs(H, h, by);
        int[] out = new int[w * h];
        // Horizontally resampled super-rows, cached while the vertical window slides down.
        java.util.HashMap<Integer, int[][]> cache = new java.util.HashMap<>();
        int[] line = new int[W * 3];
        for (int yy = 0; yy < h; yy++) {
            int ymin = by[2 * yy], cnt = by[2 * yy + 1];
            final int keepFrom = ymin;
            cache.keySet().removeIf(k -> k < keepFrom);
            long[][] acc = new long[3][w];
            for (int ch = 0; ch < 3; ch++) java.util.Arrays.fill(acc[ch], 1L << (PB - 1));
            for (int t = 0; t < cnt; t++) {
                int sy = ymin + t;
                int[][] hr = cache.get(sy);
                if (hr == null) {
                    superRow(g, sy, c, x0, x1, y0, y1, round, line);
                    hr = new int[3][w];
                    for (int xx = 0; xx < w; xx++) {
                        int[] k = kx[xx]; int xmin = bx[2 * xx];
                        long r = 1L << (PB - 1), gg = r, b = r;
                        for (int q = 0; q < k.length; q++) {
                            int base = (xmin + q) * 3;
                            r += (long) line[base] * k[q]; gg += (long) line[base + 1] * k[q]; b += (long) line[base + 2] * k[q];
                        }
                        hr[0][xx] = clip(r); hr[1][xx] = clip(gg); hr[2][xx] = clip(b);
                    }
                    cache.put(sy, hr);
                }
                int kv = ky[yy][t];
                for (int ch = 0; ch < 3; ch++) { long[] a = acc[ch]; int[] src = hr[ch]; for (int xx = 0; xx < w; xx++) a[xx] += (long) src[xx] * kv; }
            }
            for (int xx = 0; xx < w; xx++)
                out[yy * w + xx] = 0xff000000 | (clip(acc[0][xx]) << 16) | (clip(acc[1][xx]) << 8) | clip(acc[2][xx]);
        }
        return new Image(w, h, out);
    }

    private static final int PB = 22;
    private static int clip(long v) { if (v >= (1L << PB << 8)) return 255; if (v <= 0) return 0; return (int) (v >> PB); }

    private static int[][] coeffs(int inSize, int outSize, int[] bounds) {
        // Same as Pil.coeffs with Lanczos (kept here so blocks() can stream rows).
        double scale = (double) inSize / outSize, fs = Math.max(1.0, scale), support = 3.0 * fs;
        int[][] kk = new int[outSize][];
        for (int xx = 0; xx < outSize; xx++) {
            double center = (xx + 0.5) * scale, ww = 0.0, ss = 1.0 / fs;
            int xmin = Math.max(0, (int) (center - support + 0.5));
            int xmax = Math.min(inSize, (int) (center + support + 0.5)) - xmin;
            double[] k = new double[Math.max(0, xmax)];
            for (int x = 0; x < xmax; x++) { k[x] = Pil.LANCZOS.apply((x + xmin - center + 0.5) * ss); ww += k[x]; }
            int[] ki = new int[k.length];
            for (int x = 0; x < xmax; x++) {
                double v = ww != 0.0 ? k[x] / ww : k[x];
                ki[x] = (int) (v < 0 ? -0.5 + v * (1 << PB) : 0.5 + v * (1 << PB));
            }
            kk[xx] = ki; bounds[2 * xx] = xmin; bounds[2 * xx + 1] = xmax;
        }
        return kk;
    }

    private static void superRow(Grid g, int sy, int c, int[] x0, int[] x1, int[] y0, int[] y1, boolean[] round, int[] line) {
        java.util.Arrays.fill(line, BACKGROUND);
        int W = g.cols * c, r = sy / c;
        // Jittered squares can reach slightly into the next cell, so look at the rows around
        // this one too; later cells are drawn over earlier ones, as in Pillow.
        for (int rr = Math.max(0, r - 1); rr <= Math.min(g.rows - 1, r + 1); rr++) {
            for (int k = 0; k < g.cols; k++) {
                int i = rr * g.cols + k;
                if (sy < y0[i] || sy > y1[i]) continue;
                int col = g.color[i], cr = (col >> 16) & 255, cg = (col >> 8) & 255, cb = col & 255;
                int from = Math.max(0, x0[i]), to = Math.min(W - 1, x1[i]);
                for (int x = from; x <= to; x++) {
                    if (round != null && !round[(sy - rr * c) * c + (x - k * c)]) continue;
                    line[x * 3] = cr; line[x * 3 + 1] = cg; line[x * 3 + 2] = cb;
                }
            }
        }
    }

    /**
     * Full-cell square with rounded corners, exactly as Pillow's rounded_rectangle draws it
     * (radius 0.2 of the cell). Pillow's corners aren't quite symmetric, so its shapes are
     * stored here: per supersampled cell size (4..160 in steps of 4), the left/right insets of
     * the top rows and of the bottom rows (bottom-up). Rows not listed are full width.
     */
    private static boolean[] roundedMask(int c, double radiusUnused) {
        boolean[] m = new boolean[c * c];
        java.util.Arrays.fill(m, true);
        int idx = c / 4 - 1;
        if (c % 4 != 0 || idx < 0 || idx >= ROUNDED.length) return m;
        String[] halves = ROUNDED[idx].split("\\|", -1);
        for (int half = 0; half < 2; half++) {
            if (halves[half].isEmpty()) continue;
            String[] rowsIns = halves[half].split(" ");
            for (int i = 0; i < rowsIns.length; i++) {
                String[] lr = rowsIns[i].split(",");
                int l = Integer.parseInt(lr[0]), r = Integer.parseInt(lr[1]);
                int y = half == 0 ? i : c - 1 - i;
                for (int x = 0; x < c; x++) m[y * c + x] = x >= l && x <= c - 1 - r;
            }
        }
        return m;
    }

    private static final String[] ROUNDED = {
        "|0,1",
        "1,1|1,1",
        "1,1|1,2 0,1",
        "2,2 1,1|2,2 1,1",
        "3,3 1,1 1,1|3,3 1,1 1,1",
        "3,3 2,2 1,1|3,3 2,2 1,1",
        "4,4 2,2 1,1 1,1|4,4 2,3 1,2 1,1",
        "4,4 3,3 2,2 1,1|4,5 3,3 2,2 1,1 0,1",
        "5,5 3,3 2,2 1,1 1,1|5,5 3,4 2,2 1,2 1,1",
        "6,6 4,4 3,3 2,2 1,1 1,1|6,6 4,4 3,3 2,2 1,1 1,1",
        "6,7 4,4 3,3 2,2 1,1 1,1|6,7 4,5 3,3 2,2 1,2 1,1 1,1",
        "7,7 5,5 4,4 3,3 2,2 1,1 1,1|7,7 5,5 4,4 3,3 2,2 1,1 1,1",
        "7,8 5,5 4,4 3,3 2,2 1,1 1,1|7,8 5,6 4,4 3,3 2,2 1,2 1,1 1,1",
        "8,9 6,6 5,5 3,4 3,3 2,2 1,1 1,1|8,9 6,6 5,5 4,4 3,3 2,2 1,1 1,1 1,1",
        "9,9 7,7 5,5 4,4 3,3 2,2 2,2 1,1 1,1|9,9 7,7 5,5 4,4 3,3 2,2 2,2 1,1 1,1",
        "10,10 7,7 6,6 4,4 3,3 3,3 2,2 1,1 1,1 1,1|10,10 7,7 6,6 4,5 3,4 3,3 2,2 1,1 1,1 1,1",
        "10,11 8,8 6,6 5,5 4,4 3,3 2,2 2,2 1,1 1,1|10,11 8,8 6,7 5,5 4,4 3,3 2,3 2,2 1,1 1,1 1,1",
        "11,11 8,8 7,7 5,5 4,4 3,3 3,3 2,2 1,1 1,1 1,1|11,11 8,9 7,7 5,6 4,5 3,4 3,3 2,2 1,2 1,1 1,1",
        "12,12 9,9 7,7 6,6 5,5 4,4 3,3 2,2 2,2 1,1 1,1 1,1|12,12 9,9 7,8 6,6 5,5 4,4 3,3 2,3 2,2 1,1 1,1 1,1",
        "13,13 10,10 8,8 7,7 5,5 4,4 4,4 3,3 2,2 2,2 1,1 1,1 1,1|13,13 10,10 8,8 7,7 5,5 4,4 4,4 3,3 2,2 2,2 1,1 1,1 1,1",
        "13,13 10,10 8,9 7,7 6,6 5,5 4,4 3,3 2,2 2,2 1,1 1,1 1,1|13,13 10,11 8,9 7,7 6,6 5,5 4,4 3,3 3,3 2,2 1,2 1,1 1,1",
        "14,14 11,11 9,9 8,8 6,6 5,5 4,4 4,4 3,3 2,2 2,2 1,1 1,1 1,1|14,14 11,11 9,9 8,8 6,7 5,6 4,5 4,4 3,3 2,2 2,2 1,1 1,1 1,1",
        "14,15 11,12 9,10 8,8 7,7 6,6 5,5 4,4 3,3 2,2 2,2 1,1 1,1 1,1|14,15 11,12 9,10 8,8 7,7 6,6 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1",
        "15,16 12,12 10,10 9,9 7,7 6,6 5,5 4,4 4,4 3,3 2,2 2,2 1,1 1,1 1,1|15,16 12,13 10,10 9,9 7,8 6,6 5,5 4,5 4,4 3,3 2,2 2,2 1,2 1,1 1,1 1,1",
        "16,16 13,13 11,11 9,9 8,8 7,7 6,6 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1|16,16 13,13 11,11 9,9 8,8 7,7 6,6 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1",
        "17,17 13,14 11,11 10,10 8,8 7,7 6,6 5,5 4,4 4,4 3,3 2,2 2,2 1,1 1,1 1,1 1,1|17,17 13,14 11,12 10,10 8,9 7,7 6,6 5,5 4,5 4,4 3,3 2,3 2,2 2,2 1,1 1,1 1,1",
        "17,18 14,14 12,12 10,10 9,9 8,8 7,7 6,6 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1|17,18 14,15 12,12 10,11 9,9 8,8 7,7 6,6 5,5 4,4 3,4 3,3 2,2 2,2 1,2 1,1 1,1 1,1",
        "18,18 15,15 12,13 11,11 9,9 8,8 7,7 6,6 5,5 4,4 4,4 3,3 2,2 2,2 2,2 1,1 1,1 1,1|18,18 15,15 12,13 11,11 9,10 8,8 7,7 6,6 5,5 4,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1",
        "19,19 15,16 13,13 11,12 10,10 9,9 7,8 6,7 6,6 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1|19,19 15,16 13,13 11,12 10,10 9,9 8,8 7,7 6,6 5,5 4,4 4,4 3,3 2,2 2,2 2,2 1,1 1,1 1,1",
        "20,20 16,16 14,14 12,12 11,11 9,9 8,8 7,7 6,6 5,5 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1|20,20 16,16 14,14 12,12 11,11 9,9 8,8 7,7 6,6 5,5 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "20,21 17,17 14,15 12,13 11,11 10,10 8,9 7,8 6,7 6,6 5,5 4,4 3,4 3,3 2,2 2,2 2,2 1,1 1,1 1,1|20,21 17,17 14,15 13,13 11,11 10,10 9,9 8,8 7,7 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "21,21 17,18 15,15 13,13 12,12 10,10 9,9 8,8 7,7 6,6 5,5 5,5 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1|21,21 17,18 15,15 13,13 12,12 10,11 9,9 8,8 7,7 6,6 5,6 5,5 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1",
        "21,22 18,18 15,16 13,14 12,12 11,11 9,9 8,8 7,7 6,6 6,6 5,5 4,4 3,4 3,3 2,2 2,2 2,2 1,1 1,1 1,1|21,22 18,18 15,16 14,14 12,12 11,11 9,10 8,9 7,8 6,7 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "22,23 19,19 16,16 14,14 13,13 11,11 10,10 9,9 8,8 7,7 6,6 5,5 5,5 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1|22,23 19,19 16,17 14,15 13,13 11,12 10,10 9,9 8,8 7,7 6,6 5,6 5,5 4,4 3,4 3,3 2,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "23,23 19,19 17,17 15,15 13,13 12,12 11,11 9,9 8,8 7,7 7,7 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1|23,23 19,19 17,17 15,15 13,13 12,12 11,11 9,9 8,8 7,7 7,7 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "24,24 20,20 17,18 15,16 14,14 12,12 11,11 10,10 9,9 8,8 7,7 6,6 5,5 5,5 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1 1,1|24,24 20,20 17,18 15,16 14,14 12,13 11,11 10,10 9,9 8,8 7,7 6,6 5,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "25,25 21,21 18,18 16,16 14,15 13,13 12,12 10,11 9,9 8,8 7,7 7,7 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1 1,1|25,25 21,21 18,19 16,16 14,15 13,13 12,12 10,11 9,10 8,9 8,8 7,7 6,6 5,5 5,5 4,4 3,3 3,3 2,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "25,25 21,21 19,19 16,17 15,15 13,13 12,12 11,11 10,10 9,9 8,8 7,7 6,6 5,5 5,5 4,4 3,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1|25,26 21,22 19,19 17,17 15,15 13,14 12,12 11,11 10,10 9,9 8,8 7,7 6,6 5,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 1,2 1,1 1,1 1,1 0,1",
        "26,26 22,22 19,20 17,17 15,16 14,14 13,13 11,11 10,10 9,9 8,8 7,7 7,7 6,6 5,5 4,5 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1 1,1|26,26 22,22 19,20 17,18 16,16 14,14 13,13 11,12 10,10 9,9 8,8 7,8 7,7 6,6 5,5 5,5 4,4 3,4 3,3 3,3 2,2 2,2 1,1 1,1 1,1 1,1",
        "27,27 23,23 20,20 18,18 16,16 15,15 13,13 12,12 11,11 10,10 9,9 8,8 7,7 6,6 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1 1,1|27,27 23,23 20,20 18,18 16,16 15,15 13,13 12,12 11,11 10,10 9,9 8,8 7,7 6,6 6,6 5,5 4,4 4,4 3,3 3,3 2,2 2,2 2,2 1,1 1,1 1,1 1,1",
    };
}
