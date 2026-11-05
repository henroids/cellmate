package cellmate.ui;

import cellmate.core.Settings;
import java.util.*;

/** Where each output goes: <style folder>/<same subfolders>/<same name>.png */
public final class Plan {
    private Plan() { }

    /** "summer 96 melted" for a folder input; "melted" for a single image. */
    public static String styleFolder(String inputName, boolean folder, Settings.Style style) {
        return folder ? inputName + " " + style.id() : style.id();
    }

    /**
     * Output paths (relative to the style folder) for the input images, in the same order.
     * Same name with .png; if two inputs would land on the same name (photo.jpg and photo.png),
     * the later one gets " (2)", " (3)"...
     */
    public static List<String> outputs(List<String> inputs) {
        List<String> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String rel : inputs) {
            int slash = rel.lastIndexOf('/');
            String dir = rel.substring(0, slash + 1), name = rel.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String candidate = dir + stem + ".png";
            for (int n = 2; used.contains(candidate.toLowerCase(Locale.ROOT)); n++) candidate = dir + stem + " (" + n + ").png";
            used.add(candidate.toLowerCase(Locale.ROOT));
            out.add(candidate);
        }
        return out;
    }

    public static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "tif", "tiff"));

    public static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && !name.startsWith(".") && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
