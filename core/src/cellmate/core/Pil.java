package cellmate.core;

/**
 * Integer image operations that reproduce Pillow's results exactly, so Cellmate's greyscale
 * output matches the original pixel_crt.py tool. Images here are 8-bit single-channel arrays
 * (row by row) unless noted.
 */
final class Pil {
    private Pil() { }

    /** Pillow's RGB to L conversion (ITU-R 601 luma, fixed point). */
    static byte[] toL(int[] argb) {
        byte[] out = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            out[i] = (byte) ((r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16);
        }
        return out;
    }

    static int[] histogram(byte[] im) {
        int[] h = new int[256];
        for (byte v : im) h[v & 255]++;
        return h;
    }

    static byte[] lut(byte[] im, int[] lut) {
        byte[] out = new byte[im.length];
        for (int i = 0; i < im.length; i++) out[i] = (byte) lut[im[i] & 255];
        return out;
    }

    /** ImageOps.equalize. */
    static byte[] equalize(byte[] im) {
        int[] h = histogram(im);
        int[] lut = new int[256];
        int count = 0, last = 0;
        long total = 0;
        for (int v : h) if (v != 0) { count++; last = v; total += v; }
        long step = count <= 1 ? 0 : (total - last) / 255;
        if (step == 0) {
            for (int i = 0; i < 256; i++) lut[i] = i;
        } else {
            long n = step / 2;
            for (int i = 0; i < 256; i++) { lut[i] = (int) (n / step); n += h[i]; }
        }
        return lut(im, lut);
    }

    /** ImageOps.autocontrast with the same cutoff percent at both ends. */
    static byte[] autocontrast(byte[] im, double cutoff) {
        int[] h = histogram(im);
        long n = 0;
        for (int v : h) n += v;
        long cut = (long) Math.floor(n * cutoff / 100.0);
        for (int lo = 0; lo < 256; lo++) {
            if (cut > h[lo]) { cut -= h[lo]; h[lo] = 0; } else { h[lo] -= cut; cut = 0; }
            if (cut <= 0) break;
        }
        cut = (long) Math.floor(n * cutoff / 100.0);
        for (int hi = 255; hi >= 0; hi--) {
            if (cut > h[hi]) { cut -= h[hi]; h[hi] = 0; } else { h[hi] -= cut; cut = 0; }
            if (cut <= 0) break;
        }
        int lo = 0, hi = 255;
        while (lo < 255 && h[lo] == 0) lo++;
        while (hi > 0 && h[hi] == 0) hi--;
        int[] lut = new int[256];
        if (hi <= lo) {
            for (int i = 0; i < 256; i++) lut[i] = i;
        } else {
            double scale = 255.0 / (hi - lo), offset = -lo * scale;
            for (int i = 0; i < 256; i++) {
                int v = (int) (i * scale + offset);
                lut[i] = Math.max(0, Math.min(255, v));
            }
        }
        return lut(im, lut);
    }

    /** Image.blend(a, b, alpha). */
    static byte[] blend(byte[] a, byte[] b, float alpha) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            int x = a[i] & 255, y = b[i] & 255;
            float t = x + alpha * (y - x);
            out[i] = (byte) (t <= 0f ? 0 : t >= 255f ? 255 : (int) t);
        }
        return out;
    }

    // ---------------------------------------------------------------- Gaussian blur (BoxBlur.c)

    static float gaussianBoxRadius(float radius, int passes) {
        float sigma2 = radius * radius / passes;
        float L = (float) Math.sqrt(12.0 * sigma2 + 1.0);
        float l = (float) Math.floor((L - 1.0) / 2.0);
        float a = (2 * l + 1) * (l * (l + 1) - 3 * sigma2);
        a /= 6 * (sigma2 - (l + 1) * (l + 1));
        return l + a;
    }

    private static void lineBoxBlur(int[] in, int[] out, int len, float floatRadius) {
        int radius = (int) floatRadius;
        long ww = (long) ((double) (1 << 24) / (floatRadius * 2 + 1));
        long fw = ((1L << 24) - (radius * 2 + 1) * ww) / 2;
        int lastx = len - 1;
        int edgeA = Math.min(radius + 1, len);
        int edgeB = Math.max(len - radius - 1, 0);
        long acc = (long) in[0] * (radius + 1);
        for (int x = 0; x < edgeA - 1; x++) acc += in[x];
        acc += (long) in[lastx] * (radius - edgeA + 1);
        if (edgeA <= edgeB) {
            for (int x = 0; x < edgeA; x++) {
                acc += in[x + radius] - in[0];
                out[x] = save(acc * ww + (long) (in[0] + in[x + radius + 1]) * fw);
            }
            for (int x = edgeA; x < edgeB; x++) {
                acc += in[x + radius] - in[x - radius - 1];
                out[x] = save(acc * ww + (long) (in[x - radius - 1] + in[x + radius + 1]) * fw);
            }
            for (int x = edgeB; x <= lastx; x++) {
                acc += in[lastx] - in[x - radius - 1];
                out[x] = save(acc * ww + (long) (in[x - radius - 1] + in[lastx]) * fw);
            }
        } else {
            for (int x = 0; x < edgeB; x++) {
                acc += in[x + radius] - in[0];
                out[x] = save(acc * ww + (long) (in[0] + in[x + radius + 1]) * fw);
            }
            for (int x = edgeB; x < edgeA; x++) {
                acc += in[lastx] - in[0];
                out[x] = save(acc * ww + (long) (in[0] + in[lastx]) * fw);
            }
            for (int x = edgeA; x <= lastx; x++) {
                acc += in[lastx] - in[x - radius - 1];
                out[x] = save(acc * ww + (long) (in[x - radius - 1] + in[lastx]) * fw);
            }
        }
    }

    private static int save(long bulk) { return (int) (((bulk + (1L << 23)) >> 24) & 0xff); }

    /** ImageFilter.GaussianBlur(radius) on an 8-bit image (3 box passes per axis). */
    static byte[] gaussianBlur(byte[] im, int w, int h, float radius) {
        float r = gaussianBoxRadius(radius, 3);
        int[] buf = new int[w * h];
        for (int i = 0; i < buf.length; i++) buf[i] = im[i] & 255;
        int[] line = new int[Math.max(w, h)], res = new int[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) {
                System.arraycopy(buf, y * w, line, 0, w);
                lineBoxBlur(line, res, w, r);
                System.arraycopy(res, 0, buf, y * w, w);
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) line[y] = buf[y * w + x];
                lineBoxBlur(line, res, h, r);
                for (int y = 0; y < h; y++) buf[y * w + x] = res[y];
            }
        }
        byte[] out = new byte[w * h];
        for (int i = 0; i < out.length; i++) out[i] = (byte) buf[i];
        return out;
    }

    /** ImageFilter.UnsharpMask(radius, percent, threshold). */
    static byte[] unsharpMask(byte[] im, int w, int h, float radius, int percent, int threshold) {
        byte[] blur = gaussianBlur(im, w, h, radius);
        byte[] out = new byte[im.length];
        for (int i = 0; i < im.length; i++) {
            int in = im[i] & 255, diff = in - (blur[i] & 255);
            if (Math.abs(diff) > threshold) {
                int v = in + diff * percent / 100;
                out[i] = (byte) Math.max(0, Math.min(255, v));
            } else {
                out[i] = (byte) in;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- resize (Resample.c)

    interface Filter { double support(); double apply(double x); }

    static final Filter BOX = new Filter() {
        public double support() { return 0.5; }
        public double apply(double x) { return x > -0.5 && x <= 0.5 ? 1.0 : 0.0; }
    };

    static final Filter LANCZOS = new Filter() {
        public double support() { return 3.0; }
        public double apply(double x) { return -3.0 <= x && x < 3.0 ? sinc(x) * sinc(x / 3) : 0.0; }
        private double sinc(double x) { if (x == 0.0) return 1.0; x *= Math.PI; return Math.sin(x) / x; }
    };

    private static final int PRECISION_BITS = 32 - 8 - 2;

    /** Fixed-point coefficients for one axis: bounds[2*i] = first input, bounds[2*i+1] = count. */
    private static int[][] coeffs(int inSize, int outSize, Filter f, int[] bounds) {
        double scale = (double) inSize / outSize;
        double filterscale = Math.max(1.0, scale);
        double support = f.support() * filterscale;
        int[][] kk = new int[outSize][];
        for (int xx = 0; xx < outSize; xx++) {
            double center = (xx + 0.5) * scale, ww = 0.0, ss = 1.0 / filterscale;
            int xmin = (int) (center - support + 0.5);
            if (xmin < 0) xmin = 0;
            int xmax = (int) (center + support + 0.5);
            if (xmax > inSize) xmax = inSize;
            xmax -= xmin;
            double[] k = new double[Math.max(0, xmax)];
            for (int x = 0; x < xmax; x++) { k[x] = f.apply((x + xmin - center + 0.5) * ss); ww += k[x]; }
            int[] ki = new int[k.length];
            for (int x = 0; x < xmax; x++) {
                double v = ww != 0.0 ? k[x] / ww : k[x];
                ki[x] = (int) (v < 0 ? -0.5 + v * (1 << PRECISION_BITS) : 0.5 + v * (1 << PRECISION_BITS));
            }
            kk[xx] = ki;
            bounds[2 * xx] = xmin;
            bounds[2 * xx + 1] = xmax;
        }
        return kk;
    }

    private static int clip8(long in) {
        if (in >= (1L << PRECISION_BITS << 8)) return 255;
        if (in <= 0) return 0;
        return (int) (in >> PRECISION_BITS);
    }

    /**
     * Image.resize for planar 8-bit channels (each int[] is one channel, values 0-255),
     * horizontal pass first, then vertical, like Pillow.
     */
    static int[][] resize(int[][] chans, int w, int h, int nw, int nh, Filter f) {
        int[][] out = new int[chans.length][];
        int[] bx = new int[2 * nw], by = new int[2 * nh];
        int[][] kx = nw != w ? coeffs(w, nw, f, bx) : null;
        int[][] ky = nh != h ? coeffs(h, nh, f, by) : null;
        for (int c = 0; c < chans.length; c++) {
            int[] src = chans[c];
            int cw = w;
            if (kx != null) {
                int[] tmp = new int[nw * h];
                for (int y = 0; y < h; y++) {
                    int row = y * w;
                    for (int xx = 0; xx < nw; xx++) {
                        int[] k = kx[xx];
                        int xmin = bx[2 * xx];
                        long ss = 1L << (PRECISION_BITS - 1);
                        for (int x = 0; x < k.length; x++) ss += (long) src[row + xmin + x] * k[x];
                        tmp[y * nw + xx] = clip8(ss);
                    }
                }
                src = tmp;
                cw = nw;
            }
            if (ky != null) {
                int[] tmp = new int[cw * nh];
                for (int yy = 0; yy < nh; yy++) {
                    int[] k = ky[yy];
                    int ymin = by[2 * yy];
                    for (int xx = 0; xx < cw; xx++) {
                        long ss = 1L << (PRECISION_BITS - 1);
                        for (int y = 0; y < k.length; y++) ss += (long) src[(ymin + y) * cw + xx] * k[y];
                        tmp[yy * cw + xx] = clip8(ss);
                    }
                }
                src = tmp;
            }
            out[c] = src;
        }
        return out;
    }
}
