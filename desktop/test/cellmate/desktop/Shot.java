package cellmate.desktop;

import cellmate.core.Engine;
import cellmate.core.Picture;
import cellmate.ui.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Headless check: drives the UI with keys and saves screenshots. args: out dir, input dir, output dir, w, h, then key script. */
public class Shot {
    public static void main(String[] a) throws Exception {
        ExecutorService uiThread = Executors.newSingleThreadExecutor();
        Main files = new Main();   // reuse the desktop file code (no window)
        final Ui[] ui = new Ui[1];
        Host host = new Host() {
            public void pick(Ui.Pick kind) { }
            public List<String> listImages(Object in, boolean folder) throws Exception { return files.listImages(in, folder); }
            public Picture decode(Object in, boolean folder, String rel) throws Exception { return files.decode(in, folder, rel); }
            public boolean exists(Object out, String rel) { return files.exists(out, rel); }
            public void writePng(Object out, String rel, Engine.Image im) throws Exception { files.writePng(out, rel, im); }
            public boolean isInside(Object out, Object in) { return files.isInside(out, in); }
            public Object makeImage(int[] px, int w, int h) { return files.makeImage(px, w, h); }
            public void repaint() { }
            public void runOnUi(Runnable r) { uiThread.execute(r); }
            public void quit() { }
            public String loadPrefs() { return null; }
            public void savePrefs(String t) { }
            public Object restorePlace(String s) { return null; }
            public String savePlace(Object p) { return ((File) p).getPath(); }
        };
        Font font = Main.loadFont();
        int w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
        uiThread.submit(() -> {
            ui[0] = new Ui(host);
            File in = new File(a[1]);
            ui[0].picked(Ui.Pick.INPUT_FOLDER, in, in.getName());
            ui[0].picked(Ui.Pick.OUTPUT, new File(a[2]), new File(a[2]).getName());
        }).get();
        int shot = 0;
        for (int i = 5; i < a.length; i++) {
            String cmd = a[i];
            if (cmd.equals("shot")) {
                Thread.sleep(200);
                waitIdle(uiThread);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                uiThread.submit(() -> { ui[0].draw(new Java2dSurface(img.createGraphics(), font), w, h); }).get();
                // first draw sets the preview size and may start a render: wait and draw again
                Thread.sleep(1500); waitIdle(uiThread);
                uiThread.submit(() -> { ui[0].draw(new Java2dSurface(img.createGraphics(), font), w, h); }).get();
                ImageIO.write(img, "png", new File(a[0], "shot" + (shot++) + ".png"));
            } else if (cmd.startsWith("wait")) {
                Thread.sleep(Long.parseLong(cmd.substring(4)));
            } else if (cmd.startsWith("tap")) {
                String[] xy = cmd.substring(3).split(",");
                uiThread.submit(() -> ui[0].tap(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]))).get();
            } else {
                Ui.Key k = Ui.Key.valueOf(cmd);
                uiThread.submit(() -> ui[0].key(k)).get();
            }
        }
        System.exit(0);
    }

    static void waitIdle(ExecutorService ex) throws Exception { ex.submit(() -> { }).get(); }
}
