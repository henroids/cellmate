package cellmate.desktop;

import cellmate.ui.Surface;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Draws the UI with Java2D: no anti-aliasing, so the pixel font and shapes stay crisp. */
final class Java2dSurface implements Surface {
    private final Graphics2D g;
    private final Font base;

    Java2dSurface(Graphics2D g, Font base) {
        this.g = g;
        this.base = base;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    private Font font(int size) { return base.deriveFont((float) size); }

    @Override public void fill(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, w, h);
    }

    @Override public void text(String s, int x, int y, int size, int argb) {
        if (s == null || s.isEmpty()) return;
        Font f = font(size);
        g.setFont(f);
        g.setColor(new Color(argb, true));
        // GamePocket: cap height is 14 of the font's 20 pixel grid; put the baseline so text sits centred in a size-tall box.
        g.drawString(s, x, y + Math.round(size * 0.85f));
    }

    @Override public int textWidth(String s, int size) {
        if (s == null || s.isEmpty()) return 0;
        return g.getFontMetrics(font(size)).stringWidth(s);
    }

    @Override public void image(Object image, int x, int y) { g.drawImage((BufferedImage) image, x, y, null); }

    private final java.util.ArrayDeque<Shape> clips = new java.util.ArrayDeque<>();

    @Override public void clip(int x, int y, int w, int h) {
        Shape old = g.getClip();
        clips.push(old == null ? new Rectangle(-100000, -100000, 200000, 200000) : old);
        g.clipRect(x, y, w, h);   // inside any clip already set
    }

    @Override public void unclip() { if (!clips.isEmpty()) g.setClip(clips.pop()); else g.setClip(null); }
}
