package cellmate.core;

/** Reads the EXIF orientation from JPEG bytes and turns pixels upright (like ImageOps.exif_transpose). */
public final class Orientation {
    private Orientation() { }

    /** EXIF orientation 1..8 (1 = upright), or 1 if there is none. */
    public static int fromJpeg(byte[] d) {
        try {
            if (d.length < 4 || (d[0] & 255) != 0xFF || (d[1] & 255) != 0xD8) return 1;
            int i = 2;
            while (i + 4 < d.length) {
                if ((d[i] & 255) != 0xFF) return 1;
                int marker = d[i + 1] & 255, len = ((d[i + 2] & 255) << 8) | (d[i + 3] & 255);
                if (marker == 0xDA || marker == 0xD9) return 1;   // start of image data: no EXIF
                if (marker == 0xE1 && len > 8 && d[i + 4] == 'E' && d[i + 5] == 'x' && d[i + 6] == 'i' && d[i + 7] == 'f') {
                    int t = i + 10;   // TIFF header
                    boolean le = d[t] == 'I';
                    int ifd = t + u32(d, t + 4, le);
                    int n = u16(d, ifd, le);
                    for (int e = 0; e < n; e++) {
                        int at = ifd + 2 + e * 12;
                        if (u16(d, at, le) == 0x0112) { int v = u16(d, at + 8, le); return v >= 1 && v <= 8 ? v : 1; }
                    }
                    return 1;
                }
                i += 2 + len;
            }
        } catch (RuntimeException ignored) { }
        return 1;
    }

    private static int u16(byte[] d, int i, boolean le) {
        return le ? (d[i] & 255) | (d[i + 1] & 255) << 8 : (d[i] & 255) << 8 | (d[i + 1] & 255);
    }

    private static int u32(byte[] d, int i, boolean le) {
        return le ? u16(d, i, true) | u16(d, i + 2, true) << 16 : u16(d, i, false) << 16 | u16(d, i + 2, false);
    }

    /** Applies an EXIF orientation to a picture. */
    public static Picture apply(int w, int h, int[] px, int o) {
        if (o <= 1 || o > 8) return new Picture(w, h, px);
        boolean swap = o >= 5;
        int nw = swap ? h : w, nh = swap ? w : h;
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                switch (o) {
                    case 2: nx = w - 1 - x; ny = y; break;              // mirror
                    case 3: nx = w - 1 - x; ny = h - 1 - y; break;      // 180
                    case 4: nx = x; ny = h - 1 - y; break;              // flip
                    case 5: nx = y; ny = x; break;                      // transpose
                    case 6: nx = h - 1 - y; ny = x; break;              // 90 clockwise
                    case 7: nx = h - 1 - y; ny = w - 1 - x; break;      // transverse
                    default: nx = y; ny = w - 1 - x; break;             // 8: 90 counter-clockwise
                }
                out[ny * nw + nx] = px[y * w + x];
            }
        }
        return new Picture(nw, nh, out);
    }
}
