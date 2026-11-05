package cellmate.desktop;

import java.io.File;
import java.util.Collection;
import java.util.TreeSet;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import static org.lwjgl.util.nfd.NativeFileDialog.*;

/**
 * The system's own open / choose-folder windows (on Windows the same Explorer window every other
 * app uses, following the system's size and dark mode), through Native File Dialog Extended.
 */
final class NativePicker {
    private NativePicker() { }

    /** True when the native windows can be used here (Windows build with the libraries bundled). */
    static boolean available() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) return false;
        try {
            Class.forName("org.lwjgl.util.nfd.NativeFileDialog");
            if (NFD_Init() != NFD_OKAY) return false;
            NFD_Quit();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** An image file (null if cancelled). */
    static File openImage(Collection<String> extensions, File startIn) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NFD_Init();
            try {
                NFDFilterItem.Buffer filters = NFDFilterItem.malloc(1, stack);
                filters.get(0).name(stack.UTF8("Images")).spec(stack.UTF8(String.join(",", new TreeSet<>(extensions))));
                PointerBuffer out = stack.mallocPointer(1);
                return result(NFD_OpenDialog(out, filters, startIn == null ? null : startIn.getPath()), out);
            } finally {
                NFD_Quit();
            }
        }
    }

    /** A folder (null if cancelled). */
    static File pickFolder(File startIn) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NFD_Init();
            try {
                PointerBuffer out = stack.mallocPointer(1);
                return result(NFD_PickFolder(out, startIn == null ? null : startIn.getPath()), out);
            } finally {
                NFD_Quit();
            }
        }
    }

    private static File result(int code, PointerBuffer out) {
        if (code != NFD_OKAY) return null;
        File f = new File(out.getStringUTF8(0));
        NFD_FreePath(out.get(0));
        return f;
    }
}
