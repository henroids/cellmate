package cellmate.core;

/** The picture as cells: one ARGB colour per cell. Pure black (0xff000000) means "empty". */
public final class Grid {
    public final int cols, rows;
    public final int[] color;
    public Grid(int cols, int rows, int[] color) { this.cols = cols; this.rows = rows; this.color = color; }
    public static final int EMPTY = 0xff000000;
}
