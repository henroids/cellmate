package cellmate.core;

import java.io.*;

public class EngineCheck {
    public static void main(String[] a) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[0])));
        int w = in.readInt(), h = in.readInt();
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = 0xff000000 | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        Settings s = new Settings();
        s.greyTones = Integer.parseInt(a[3]); s.cells = Integer.parseInt(a[4]); s.cellSize = Integer.parseInt(a[5]);
        if (a.length > 6) { s.color = a[6].equals("color"); if (!s.color && !a[6].equals("grey")) { s.accent = true; s.swatch = Integer.parseInt(a[6]); } }
        if (a.length > 7) s.colors = Integer.parseInt(a[7]);
        Picture p = new Picture(w, h, px);
        long t = System.nanoTime();
        Grid g = Engine.grid(p, s);
        Engine.Image im = Engine.draw(g, Settings.Style.valueOf(a[2].toUpperCase()), s.cellSize);
        System.err.printf("%s %.0f ms%n", a[2], (System.nanoTime() - t) / 1e6);
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[1])));
        o.writeInt(im.width); o.writeInt(im.height);
        for (int v : im.argb) { o.write((v >> 16) & 255); o.write((v >> 8) & 255); o.write(v & 255); }
        o.close();
    }
}
