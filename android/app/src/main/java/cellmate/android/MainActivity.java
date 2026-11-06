package cellmate.android;

import android.app.Activity;
import android.app.Presentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import cellmate.core.Engine;
import cellmate.core.Orientation;
import cellmate.core.Picture;
import cellmate.core.Png;
import cellmate.ui.Host;
import cellmate.ui.Plan;
import cellmate.ui.Surface;
import cellmate.ui.Ui;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cellmate for Android: hosts the shared UI, with folders picked through Android's file picker. */
public final class MainActivity extends Activity implements Host {
    private static final int REQ_FOLDER = 1, REQ_IMAGE = 2, REQ_OUTPUT = 3;

    private Ui ui;
    private View view;
    private Typeface font;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PreviewScreen previewScreen;

    // Document ids found while scanning, by relative path, so decoding doesn't search again.
    private final Map<String, String> scanned = new ConcurrentHashMap<>();
    // Output folder lookups: parent document id + "/" + child name -> document id.
    private final Map<String, String> children = new ConcurrentHashMap<>();
    private final Set<String> listed = ConcurrentHashMap.newKeySet();   // folders whose children are all in "children"

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { font = Typeface.createFromAsset(getAssets(), "GamePocket-Regular.ttf"); } catch (RuntimeException e) { font = Typeface.MONOSPACE; }
        view = new View(this) {
            @Override protected void onDraw(Canvas c) { if (ui != null) ui.draw(new CanvasSurface(c, font), getWidth(), getHeight()); }
        };
        view.setFocusable(true);
        view.setDefaultFocusHighlightEnabled(false);
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: ui.pointerDown((int) e.getX(), (int) e.getY()); return true;
                case MotionEvent.ACTION_MOVE: ui.pointerMove((int) e.getX(), (int) e.getY()); return true;
                case MotionEvent.ACTION_UP: ui.pointerUp((int) e.getX(), (int) e.getY()); return true;
                default: return true;
            }
        });
        setContentView(view);
        ui = new Ui(this);
        view.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        showPreviewScreen();
    }

    @Override protected void onPause() {
        if (previewScreen != null) { previewScreen.dismiss(); previewScreen = null; ui.setPreviewOnOtherScreen(false); }
        super.onPause();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        Ui.Key k;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: k = Ui.Key.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = Ui.Key.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: k = Ui.Key.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = Ui.Key.RIGHT; break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BUTTON_START: k = Ui.Key.A; break;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK: k = Ui.Key.B; break;
            case KeyEvent.KEYCODE_BUTTON_L1: k = Ui.Key.PREV; break;
            case KeyEvent.KEYCODE_BUTTON_R1: k = Ui.Key.NEXT; break;
            case KeyEvent.KEYCODE_BUTTON_L2: k = Ui.Key.PAGE_PREV; break;
            case KeyEvent.KEYCODE_BUTTON_R2: k = Ui.Key.PAGE_NEXT; break;
            case KeyEvent.KEYCODE_BUTTON_X: k = Ui.Key.SAVE; break;
            case KeyEvent.KEYCODE_BUTTON_Y: k = Ui.Key.RESET; break;
            default: return super.onKeyDown(code, e);
        }
        ui.key(k);
        return true;
    }

    // ------------------------------------------------------------------ second screen (AYN Thor)

    private void showPreviewScreen() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null || previewScreen != null) return;
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) return;
        try {
            previewScreen = new PreviewScreen(this, displays[0]);
            previewScreen.show();
            ui.setPreviewOnOtherScreen(true);
        } catch (RuntimeException e) {
            previewScreen = null;
        }
    }

    private final class PreviewScreen extends Presentation {
        View v;
        PreviewScreen(Context c, Display d) { super(c, d); }
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            v = new View(getContext()) {
                @Override protected void onDraw(Canvas c) { ui.drawPreviewScreen(new CanvasSurface(c, font), getWidth(), getHeight()); }
            };
            v.setOnTouchListener((view, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) ui.tapPreviewScreen((int) e.getX(), (int) e.getY());
                return true;
            });
            setContentView(v);
        }
    }

    // ------------------------------------------------------------------ pickers

    @Override public boolean canPickEither() { return false; }

    @Override public void pick(Ui.Pick kind) {
        Intent i;
        if (kind == Ui.Pick.INPUT_IMAGE) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | (kind == Ui.Pick.OUTPUT ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
        startActivityForResult(i, kind == Ui.Pick.INPUT_FOLDER ? REQ_FOLDER : kind == Ui.Pick.INPUT_IMAGE ? REQ_IMAGE : REQ_OUTPUT);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | (req == REQ_OUTPUT ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (SecurityException ignored) { }
        Ui.Pick kind = req == REQ_FOLDER ? Ui.Pick.INPUT_FOLDER : req == REQ_IMAGE ? Ui.Pick.INPUT_IMAGE : Ui.Pick.OUTPUT;
        if (kind == Ui.Pick.OUTPUT) { children.clear(); listed.clear(); }
        ui.picked(kind, uri, displayName(uri, kind != Ui.Pick.INPUT_IMAGE));
    }

    private String displayName(Uri uri, boolean tree) {
        Uri doc = tree ? DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) : uri;
        try (Cursor c = getContentResolver().query(doc, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "?" : last.substring(last.lastIndexOf(':') + 1);
    }

    // ------------------------------------------------------------------ files

    private static final String[] COLS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};

    @Override public List<String> listImages(Object input, boolean folder) {
        Uri uri = (Uri) input;
        List<String> out = new ArrayList<>();
        if (!folder) { out.add(displayName(uri, false)); return out; }
        scanned.clear();
        walk(uri, DocumentsContract.getTreeDocumentId(uri), "", out);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void walk(Uri tree, String docId, String prefix, List<String> out) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(kids, COLS, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(tree, id, prefix + name + "/", out);
                else if (Plan.isImage(name)) { out.add(prefix + name); scanned.put(prefix + name, id); }
            }
        }
    }

    @Override public Picture decode(Object input, boolean folder, String rel) throws IOException {
        Uri uri = (Uri) input;
        Uri doc = folder ? DocumentsContract.buildDocumentUriUsingTree(uri, scanned.get(rel)) : uri;
        byte[] data = readAll(doc);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) throw new IOException("can't open this image");
        o.inJustDecodeBounds = false;
        // Decode no bigger than needed: the most detail is 160 pixels across, so 1600 on the
        // short side is plenty, and halving the decode is much faster and lighter on memory.
        o.inSampleSize = 1;
        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 1600) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) throw new IOException("can't open this image");
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) != 255) px[i] |= 0xff000000;
        int orient = 1;
        try {
            ExifInterface ex = new ExifInterface(new java.io.ByteArrayInputStream(data));
            orient = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (IOException | RuntimeException ignored) { orient = Orientation.fromJpeg(data); }
        return Orientation.apply(w, h, px, orient);
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("can't read " + uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Finds a child document by name (cached); null if missing. */
    /**
     * The document id of a folder's child, or null. Each folder is listed once (then everything
     * made in it is noted here), so checking many files doesn't re-list the folder every time.
     */
    private String child(Uri tree, String parentId, String name) {
        String key = parentId + "/" + name;
        String id = children.get(key);
        if (id != null || listed.contains(parentId)) return id;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(kids, COLS, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) children.put(parentId + "/" + c.getString(1), c.getString(0));
        }
        listed.add(parentId);
        return children.get(key);
    }

    @Override public boolean exists(Object out, String rel) {
        Uri tree = (Uri) out;
        String id = DocumentsContract.getTreeDocumentId(tree);
        for (String part : rel.split("/")) { id = child(tree, id, part); if (id == null) return false; }
        return true;
    }

    @Override public void writePng(Object out, String rel, Engine.Image image) throws Exception {
        byte[] png = Png.encode(image);   // the slow part, done outside the lock so pictures encode side by side
        Uri file = makeFile((Uri) out, rel);
        try (OutputStream os = getContentResolver().openOutputStream(file, "w")) {
            if (os == null) throw new IOException("couldn't write " + rel);
            os.write(png);
        }
    }

    /** Makes (or replaces) the file at rel, and any folders on the way; one at a time, so two pictures don't make the same folder twice. */
    private synchronized Uri makeFile(Uri tree, String rel) throws IOException {
        ContentResolver cr = getContentResolver();
        String[] parts = rel.split("/");
        String id = DocumentsContract.getTreeDocumentId(tree);
        for (int i = 0; i < parts.length - 1; i++) {
            String next = child(tree, id, parts[i]);
            if (next == null) {
                Uri made = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        DocumentsContract.Document.MIME_TYPE_DIR, parts[i]);
                if (made == null) throw new IOException("couldn't create folder " + parts[i]);
                next = DocumentsContract.getDocumentId(made);
                children.put(id + "/" + parts[i], next);
                listed.add(next);   // just made, so empty: no need to list it
            }
            id = next;
        }
        String name = parts[parts.length - 1];
        String existing = child(tree, id, name);
        if (existing != null) DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, existing));
        Uri file = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id), "image/png", name);
        if (file == null) throw new IOException("couldn't create " + name);
        children.put(id + "/" + name, DocumentsContract.getDocumentId(file));
        return file;
    }

    @Override public boolean isInside(Object out, Object in) {
        try {
            String o = DocumentsContract.getTreeDocumentId((Uri) out), i = DocumentsContract.getTreeDocumentId((Uri) in);
            return ((Uri) out).getAuthority().equals(((Uri) in).getAuthority()) && (o.equals(i) || o.startsWith(i.endsWith("/") ? i : i + "/"));
        } catch (RuntimeException e) { return false; }
    }

    @Override public Object makeImage(int[] argb, int w, int h) { return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888); }

    @Override public void repaint() {
        main.post(() -> { view.invalidate(); if (previewScreen != null && previewScreen.v != null) previewScreen.v.invalidate(); });
    }

    @Override public void runOnUi(Runnable r) { main.post(r); }
    @Override public void quit() { finishAndRemoveTask(); }

    private SharedPreferences prefs() { return getSharedPreferences("cellmate", MODE_PRIVATE); }
    @Override public String loadPrefs() { return prefs().getString("settings", null); }
    @Override public void savePrefs(String text) { prefs().edit().putString("settings", text).apply(); }

    @Override public Object restorePlace(String saved) {
        Uri u = Uri.parse(saved);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) if (p.getUri().equals(u)) return u;
        return null;
    }

    @Override public String savePlace(Object place) { return place.toString(); }

    // ------------------------------------------------------------------ drawing

    static final class CanvasSurface implements Surface {
        private final Canvas c;
        private final Paint fill = new Paint(), text = new Paint();
        CanvasSurface(Canvas c, Typeface font) {
            this.c = c;
            fill.setAntiAlias(false);
            text.setAntiAlias(false);
            text.setTypeface(font);
            text.setSubpixelText(false);
            text.setLinearText(false);
        }
        @Override public void fill(int x, int y, int w, int h, int argb) { if (w > 0 && h > 0) { fill.setColor(argb); c.drawRect(x, y, x + w, y + h, fill); } }
        @Override public void text(String s, int x, int y, int size, int argb) {
            if (s == null || s.isEmpty()) return;
            text.setTextSize(size); text.setColor(argb);
            c.drawText(s, x, y + Math.round(size * 0.85f), text);
        }
        @Override public int textWidth(String s, int size) { if (s == null) return 0; text.setTextSize(size); return Math.round(text.measureText(s)); }
        @Override public void image(Object image, int x, int y) { c.drawBitmap((Bitmap) image, x, y, null); }
        @Override public void clip(int x, int y, int w, int h) { c.save(); c.clipRect(new Rect(x, y, x + w, y + h)); }
        @Override public void unclip() { c.restore(); }
    }
}
