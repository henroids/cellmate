package cellmate.desktop;

import cellmate.core.Engine;
import cellmate.core.Orientation;
import cellmate.core.Picture;
import cellmate.ui.Host;
import cellmate.ui.Plan;
import cellmate.ui.Ui;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Cellmate for Windows / desktop: a window that hosts the shared UI. */
public final class Main implements Host {
    private JFrame frame;
    private JPanel panel;
    private Ui ui;
    private Font font;
    private final File prefsFile = new File(System.getProperty("user.home"), ".cellmate/prefs.txt");

    public static void main(String[] args) {
        // Draw at real pixels (no Windows display scaling) so the pixel font stays sharp;
        // the UI picks its own size from the window instead.
        System.setProperty("sun.java2d.uiScale", "1");
        System.setProperty("sun.java2d.uiScale.enabled", "false");
        SwingUtilities.invokeLater(() -> new Main().open());
    }

    static Font loadFont() {
        try (InputStream in = Main.class.getResourceAsStream("/GamePocket-Regular.ttf")) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            return new Font(Font.MONOSPACED, Font.PLAIN, 20);
        }
    }

    private void open() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        font = loadFont();
        frame = new JFrame("Cellmate");
        panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                if (Main.this.ui != null) Main.this.ui.draw(new Java2dSurface((Graphics2D) g, font), getWidth(), getHeight());
            }
        };
        panel.setBackground(Color.BLACK);
        panel.setFocusable(true);
        panel.setFocusTraversalKeysEnabled(false);
        panel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP: case KeyEvent.VK_W: ui.key(Ui.Key.UP); break;
                    case KeyEvent.VK_DOWN: case KeyEvent.VK_S: ui.key(Ui.Key.DOWN); break;
                    case KeyEvent.VK_LEFT: case KeyEvent.VK_A: ui.key(Ui.Key.LEFT); break;
                    case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: ui.key(Ui.Key.RIGHT); break;
                    case KeyEvent.VK_ENTER: case KeyEvent.VK_SPACE: ui.key(Ui.Key.A); break;
                    case KeyEvent.VK_ESCAPE: case KeyEvent.VK_BACK_SPACE: ui.key(Ui.Key.B); break;
                    case KeyEvent.VK_TAB: ui.key(e.isShiftDown() ? Ui.Key.PREV : Ui.Key.NEXT); break;
                    case KeyEvent.VK_Q: ui.key(Ui.Key.PREV); break;
                    case KeyEvent.VK_E: ui.key(Ui.Key.NEXT); break;
                    case KeyEvent.VK_OPEN_BRACKET: case KeyEvent.VK_PAGE_UP: ui.key(Ui.Key.PAGE_PREV); break;
                    case KeyEvent.VK_CLOSE_BRACKET: case KeyEvent.VK_PAGE_DOWN: ui.key(Ui.Key.PAGE_NEXT); break;
                    case KeyEvent.VK_X: ui.key(Ui.Key.SAVE); break;
                    case KeyEvent.VK_Y: ui.key(Ui.Key.RESET); break;
                    default: break;
                }
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { panel.requestFocusInWindow(); ui.pointerDown(e.getX(), e.getY()); }
            @Override public void mouseDragged(MouseEvent e) { ui.pointerMove(e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { ui.pointerUp(e.getX(), e.getY()); }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
        panel.addMouseWheelListener(e -> ui.wheel(e.getX(), e.getY(), e.getPreciseWheelRotation()));
        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        try (InputStream in = Main.class.getResourceAsStream("/icon.png")) {
            if (in != null) frame.setIconImage(ImageIO.read(in));
        } catch (IOException ignored) { }
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(1600, screen.width * 3 / 4), Math.min(1000, screen.height * 3 / 4));
        frame.setLocationRelativeTo(null);
        ui = new Ui(this);
        frame.setVisible(true);
        panel.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ Host

    @Override public boolean canPickEither() { return true; }

    @Override public void pick(Ui.Pick kind) {
        JFileChooser fc = new JFileChooser();
        if (kind == Ui.Pick.INPUT) {
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            fc.addChoosableFileFilter(new FileNameExtensionFilter("Folders and images", Plan.EXTENSIONS.toArray(new String[0])));
            fc.setAcceptAllFileFilterUsed(false);
            fc.setDialogTitle("Choose a folder of images, or one image");
        } else if (kind == Ui.Pick.INPUT_IMAGE) {
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setFileFilter(new FileNameExtensionFilter("Images", Plan.EXTENSIONS.toArray(new String[0])));
            fc.setDialogTitle("Choose an image");
        } else {
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle(kind == Ui.Pick.OUTPUT ? "Choose where to save" : "Choose a folder of images");
        }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            Ui.Pick k = kind == Ui.Pick.INPUT ? (f.isDirectory() ? Ui.Pick.INPUT_FOLDER : Ui.Pick.INPUT_IMAGE) : kind;
            ui.picked(k, f, f.getName().isEmpty() ? f.getPath() : f.getName());
        }
        panel.requestFocusInWindow();
    }

    @Override public List<String> listImages(Object input, boolean folder) throws IOException {
        File in = (File) input;
        List<String> out = new ArrayList<>();
        if (!folder) { out.add(in.getName()); return out; }
        Path root = in.toPath();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p) && Plan.isImage(p.getFileName().toString()))
                .filter(p -> { for (Path part : root.relativize(p)) if (part.toString().startsWith(".")) return false; return true; })
                .forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override public Picture decode(Object input, boolean folder, String rel) throws IOException {
        File f = folder ? new File((File) input, rel) : (File) input;
        byte[] data = Files.readAllBytes(f.toPath());
        BufferedImage im = ImageIO.read(new ByteArrayInputStream(data));
        if (im == null) throw new IOException("this kind of image can't be opened on this computer");
        int w = im.getWidth(), h = im.getHeight();
        int[] px = im.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {   // flatten transparency onto white, like Pillow's convert("L"/"RGB")
            int a = px[i] >>> 24;
            if (a == 255) continue;
            int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
            px[i] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return Orientation.apply(w, h, px, Orientation.fromJpeg(data));
    }

    @Override public boolean exists(Object out, String rel) { return new File((File) out, rel).exists(); }

    @Override public void writePng(Object out, String rel, Engine.Image image) throws IOException {
        File f = new File((File) out, rel);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("couldn't create " + dir);
        BufferedImage bi = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width);
        File tmp = new File(f.getPath() + ".part");
        if (!ImageIO.write(bi, "png", tmp)) throw new IOException("PNG writer missing");
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public boolean isInside(Object out, Object in) {
        try {
            Path o = ((File) out).toPath().toRealPath(), i = ((File) in).toPath().toRealPath();
            return o.startsWith(i);
        } catch (IOException e) { return false; }
    }

    @Override public Object makeImage(int[] argb, int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        return bi;
    }

    @Override public void repaint() { if (panel != null) panel.repaint(); }
    @Override public void runOnUi(Runnable r) { SwingUtilities.invokeLater(r); }
    @Override public void quit() { System.exit(0); }

    @Override public String loadPrefs() throws IOException {
        return prefsFile.exists() ? new String(Files.readAllBytes(prefsFile.toPath()), StandardCharsets.UTF_8) : null;
    }

    @Override public void savePrefs(String text) throws IOException {
        prefsFile.getParentFile().mkdirs();
        Files.write(prefsFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Override public Object restorePlace(String saved) { File f = new File(saved); return f.exists() ? f : null; }
    @Override public String savePlace(Object place) { return ((File) place).getAbsolutePath(); }
}
