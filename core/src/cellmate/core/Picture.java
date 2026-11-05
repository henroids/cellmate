package cellmate.core;

/** A decoded picture (ARGB, already rotated upright) plus cached work that doesn't depend on settings. */
public final class Picture {
    public final int width, height;
    public final int[] argb;
    private byte[] enhanced;    // greyscale with the contrast boost, full size

    public Picture(int width, int height, int[] argb) { this.width = width; this.height = height; this.argb = argb; }

    /** pixel_crt.py's greyscale prep: autocontrast + equalize blend, then unsharp mask. */
    synchronized byte[] enhanced() {
        if (enhanced == null) {
            byte[] l = Pil.toL(argb);
            byte[] g = Pil.blend(Pil.autocontrast(l, 1), Pil.equalize(l), 0.6f);
            enhanced = Pil.unsharpMask(g, width, height, 6, 150, 3);
        }
        return enhanced;
    }

}
