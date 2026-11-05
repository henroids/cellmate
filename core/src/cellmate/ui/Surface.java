package cellmate.ui;

/** What the UI draws on. Desktop and Android each provide one. Coordinates are real pixels. */
public interface Surface {
    void fill(int x, int y, int w, int h, int argb);
    /** Text in the pixel font; (x, y) is the top-left of the line box. size is the font size in px. */
    void text(String s, int x, int y, int size, int argb);
    int textWidth(String s, int size);
    /** Draws an image made with Host.makeImage, unscaled, at (x, y). */
    void image(Object image, int x, int y);
    void clip(int x, int y, int w, int h);
    void unclip();
}
