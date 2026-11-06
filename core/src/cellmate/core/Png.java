package cellmate.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A small, fast PNG writer for Cellmate's pictures. They're big areas of flat colour, so each row
 * is stored as "same as the row above" where it can be (most rows inside a cell are), and the rest
 * as plain RGB; zlib's fastest level then squeezes them about as small as a full encoder would, in
 * a fraction of the time.
 */
public final class Png {
    private Png() { }

    public static byte[] encode(Engine.Image im) {
        int w = im.width, h = im.height;
        byte[] raw = new byte[(w * 3 + 1) * h];
        int o = 0;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            boolean same = y > 0;
            for (int x = 0; same && x < w; x++) same = im.argb[row + x] == im.argb[row - w + x];
            if (same) { raw[o++] = 2; o += w * 3; continue; }   // filter "Up": all zeros
            raw[o++] = 0;
            for (int x = 0; x < w; x++) {
                int c = im.argb[row + x];
                raw[o++] = (byte) (c >> 16); raw[o++] = (byte) (c >> 8); raw[o++] = (byte) c;
            }
        }
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 8 + 1024);
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream(z.size() + 64);
        try {
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            byte[] head = new byte[13];
            put(head, 0, w); put(head, 4, h);
            head[8] = 8; head[9] = 2;   // 8 bits, RGB
            chunk(out, "IHDR", head);
            chunk(out, "IDAT", z.toByteArray());
            chunk(out, "IEND", new byte[0]);
        } catch (IOException e) {
            throw new IllegalStateException(e);   // a ByteArrayOutputStream doesn't fail
        }
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] len = new byte[4]; put(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t); out.write(data);
        CRC32 crc = new CRC32(); crc.update(t); crc.update(data);
        byte[] c = new byte[4]; put(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void put(byte[] b, int at, int v) { b[at] = (byte) (v >> 24); b[at + 1] = (byte) (v >> 16); b[at + 2] = (byte) (v >> 8); b[at + 3] = (byte) v; }
}
