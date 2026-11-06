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

/** Headless check: drives the UI with keys and saves screenshots. args: out dir, input dir (or none), output dir (used when the UI asks where to save), w, h, then key script. */
public class Shot {
    public static void main(String[] a) throws Exception {
        ExecutorService uiThread = Executors.newSingleThreadExecutor();
        Main files = new Main();   // reuse the desktop file code (no window)
        final Ui[] ui = new Ui[1];
        Host host = new Host() {
            public void pick(Ui.Pick kind) {   // the "chosen" folder is always the one from the command line
                File f = new File(kind == Ui.Pick.OUTPUT ? a[2] : a[1]);
                uiThread.execute(() -> ui[0].picked(kind == Ui.Pick.OUTPUT ? kind : Ui.Pick.INPUT_FOLDER, f, f.getName()));
            }
            public boolean canPickEither() { return true; }
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
            if (!a[1].equals("none")) ui[0].picked(Ui.Pick.INPUT_FOLDER, in, in.getName());
        }).get();
        int shot = 0;
        for (int i = 5; i < a.length; i++) {
            String cmd = a[i];
            if (cmd.equals("shot2")) {   // the second screen's preview, 1280 x 720
                BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
                uiThread.submit(() -> { ui[0].drawPreviewScreen(new Java2dSurface(img.createGraphics(), font), 1280, 720); }).get();
                ImageIO.write(img, "png", new File(a[0], "shot" + (shot++) + ".png"));
                continue;
            }
            if (cmd.startsWith("pinch")) {   // pinchX,Y,SCALE then pinchend
                if (cmd.equals("pinchend")) { uiThread.submit(() -> ui[0].pinchEnd()).get(); continue; }
                String[] v = cmd.substring(5).split(",");
                uiThread.submit(() -> ui[0].pinch(Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]))).get();
                continue;
            }
            if (cmd.startsWith("ptap")) {   // ptapX,Y on the second screen
                String[] v = cmd.substring(4).split(",");
                uiThread.submit(() -> ui[0].tapPreviewScreen(Integer.parseInt(v[0]), Integer.parseInt(v[1]))).get();
                continue;
            }
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
            } else if (cmd.startsWith("drag")) {
                String[] v = cmd.substring(4).split(",");
                uiThread.submit(() -> { ui[0].pointerDown(Integer.parseInt(v[0]), Integer.parseInt(v[1])); ui[0].pointerMove(Integer.parseInt(v[2]), Integer.parseInt(v[3])); ui[0].pointerUp(Integer.parseInt(v[2]), Integer.parseInt(v[3])); }).get();
            } else if (cmd.startsWith("wheel")) {   // wheelX,Y,ROWS
                String[] v = cmd.substring(5).split(",");
                uiThread.submit(() -> ui[0].wheel(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Double.parseDouble(v[2]))).get();
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
