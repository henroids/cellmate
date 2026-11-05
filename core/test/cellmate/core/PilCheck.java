package cellmate.core;

import java.io.*;
import java.nio.file.*;

/** Reads raw RGB (w h then bytes), writes each step's 8-bit result for Python to compare. */
public class PilCheck {
    public static void main(String[] a) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[0])));
        int w = in.readInt(), h = in.readInt();
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        int cells = Integer.parseInt(a[2]);
        byte[] L = Pil.toL(px);
        byte[] eq = Pil.equalize(L);
        byte[] ac = Pil.autocontrast(L, 1);
        byte[] bl = Pil.blend(ac, eq, 0.6f);
        byte[] gb = Pil.gaussianBlur(bl, w, h, 6);
        byte[] um = Pil.unsharpMask(bl, w, h, 6, 150, 3);
        int cols, rows;
        if (w <= h) { cols = cells; rows = Math.max(1, (int) Math.rint((double) h * cells / w)); }
        else { rows = cells; cols = Math.max(1, (int) Math.rint((double) w * cells / h)); }
        int[] u = new int[um.length];
        for (int i = 0; i < u.length; i++) u[i] = um[i] & 255;
        int[] sm = Pil.resize(new int[][]{u}, w, h, cols, rows, Pil.BOX)[0];
        Path o = Paths.get(a[1]);
        Files.write(o.resolve("L.bin"), L); Files.write(o.resolve("eq.bin"), eq); Files.write(o.resolve("ac.bin"), ac);
        Files.write(o.resolve("bl.bin"), bl); Files.write(o.resolve("gb.bin"), gb); Files.write(o.resolve("um.bin"), um);
        byte[] s = new byte[sm.length]; for (int i = 0; i < s.length; i++) s[i] = (byte) sm[i];
        Files.write(o.resolve("small.bin"), s);
        System.out.println(cols + " " + rows);
    }
}
