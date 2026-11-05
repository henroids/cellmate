package cellmate.ui;

import cellmate.core.Engine;
import cellmate.core.Picture;
import java.util.List;

/**
 * Platform services. A "place" is whatever the platform uses for a file or folder (a File on
 * the desktop, a content Uri on Android); the UI only passes them back.
 */
public interface Host {
    /** Opens a picker; the host later calls ui.picked(kind, place, displayName). */
    void pick(Ui.Pick kind);
    /** All images under a folder (or just the file itself), as relative paths with '/' separators. */
    List<String> listImages(Object input, boolean folder) throws Exception;
    Picture decode(Object input, boolean folder, String relPath) throws Exception;
    boolean exists(Object outFolder, String relPath);
    void writePng(Object outFolder, String relPath, Engine.Image image) throws Exception;
    /** True if out is the same as, or inside, the input folder. */
    boolean isInside(Object out, Object in);

    Object makeImage(int[] argb, int width, int height);
    void repaint();
    void runOnUi(Runnable r);
    void quit();

    String loadPrefs() throws Exception;
    void savePrefs(String text) throws Exception;
    /** Turns a saved place back into one (or null if it's no longer reachable). */
    Object restorePlace(String saved);
    String savePlace(Object place);
}
