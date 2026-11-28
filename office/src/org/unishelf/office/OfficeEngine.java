/*
 * UniShelf V2 LibreOfficeKit integration.
 *
 * This class is the only bridge between the Android UI and LibreOfficeKit.
 * All LibreOfficeKit calls are serialized on a private HandlerThread because
 * LOK is not thread-safe and because rendering and saving can take seconds.
 *
 * The engine itself is the upstream LibreOffice Android native runtime
 * (liblo-native-code.so + NSS + LibreOffice assets).  No document parsing or
 * rendering is implemented here.
 */

package org.unishelf.office;

import android.app.Activity;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.libreoffice.kit.Document;
import org.libreoffice.kit.LibreOfficeKit;
import org.libreoffice.kit.Office;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class OfficeEngine {
    private static final String TAG = "UniShelfOffice";
    private static final String ASSET_MARKER = "unishelf_office_assets_v2";
    private static final int COPY_BUFFER = 64 * 1024;

    public interface Listener {
        void onStatus(String message);
        void onReady();
        void onError(String message);
        void onPasswordRequired();
        void onDocumentChanged(boolean modified);
        void onInvalidate();
        void onCallback(int signal, String payload);
    }

    public interface RenderCallback {
        void onRendered(ByteBuffer pixels, int width, int height);
    }

    public interface SaveCallback {
        void onSaved(File file, String error);
    }

    private final Activity activity;
    private final Listener listener;
    private final HandlerThread loThread;
    private final Handler loHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Office office;
    private Document document;
    private File currentFile;
    private boolean modified;
    private boolean closed;

    private int documentType = Document.DOCTYPE_OTHER;
    private int parts = 1;
    private int currentPart = 0;
    private long widthTwip;
    private long heightTwip;
    private int lastTilePixelWidth = 256;
    private int lastTilePixelHeight = 256;
    private int lastTileTwipWidth = 256;
    private int lastTileTwipHeight = 256;
    private String lastError = "";

    public OfficeEngine(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.loThread = new HandlerThread("UniShelfLibreOffice", android.os.Process.THREAD_PRIORITY_DISPLAY);
        this.loThread.start();
        this.loHandler = new Handler(loThread.getLooper());
    }

    public void open(final File file, final String password) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    ensureInitialized();
                    openDocument(file, password);
                } catch (Throwable t) {
                    fail("Unable to open document", t);
                }
            }
        });
    }

    public void closeDocument() {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document != null) {
                    try { document.destroy(); } catch (Throwable ignored) { }
                    document = null;
                }
                currentFile = null;
                modified = false;
                widthTwip = 0;
                heightTwip = 0;
            }
        });
    }

    public void close() {
        closed = true;
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document != null) {
                    try { document.destroy(); } catch (Throwable ignored) { }
                    document = null;
                }
                // LibreOfficeKit is process-global in the upstream Android
                // bootstrap.  Destroying the Office object here would make a
                // later OfficeActivity launch in the same process fail, so
                // the engine handle is intentionally retained until process
                // death.  Only the document is closed.
                loThread.quitSafely();
            }
        });
    }

    public void render(final int width, final int height,
                       final int xTwip, final int yTwip,
                       final int widthTwip, final int heightTwip,
                       final RenderCallback callback) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try {
                    final ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
                    lastTilePixelWidth = width;
                    lastTilePixelHeight = height;
                    lastTileTwipWidth = widthTwip;
                    lastTileTwipHeight = heightTwip;
                    document.setClientZoom(width, height, widthTwip, heightTwip);
                    document.paintTile(buffer, width, height, xTwip, yTwip, widthTwip, heightTwip);
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (!closed) callback.onRendered(buffer, width, height);
                        }
                    });
                } catch (Throwable t) {
                    fail("Unable to render page", t);
                }
            }
        });
    }

    public void postKey(final int charCode, final int keyCode, final boolean release) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try {
                    document.postKeyEvent(release ? Document.KEY_EVENT_RELEASE : Document.KEY_EVENT_PRESS,
                            charCode, keyCode);
                    markModified();
                } catch (Throwable t) {
                    fail("Unable to send key to the document engine", t);
                }
            }
        });
    }

    public void postMouse(final int type, final int xTwip, final int yTwip,
                          final int count, final int button, final int modifier) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try {
                    document.setClientZoom(lastTilePixelWidth, lastTilePixelHeight,
                            lastTileTwipWidth, lastTileTwipHeight);
                    document.postMouseEvent(type, xTwip, yTwip, count, button, modifier);
                } catch (Throwable t) {
                    fail("Unable to send pointer event", t);
                }
            }
        });
    }

    public void uno(final String command, final String arguments, final boolean notify) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try {
                    document.postUnoCommand(command, arguments, notify);
                    markModified();
                } catch (Throwable t) {
                    fail("Unable to run " + command, t);
                }
            }
        });
    }

    public void saveAs(final File file, final String format, final String options,
                       final SaveCallback callback) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    if (document == null) throw new IllegalStateException("No document is open");
                    if (file.exists() && !file.delete()) {
                        throw new IOException("Cannot replace " + file.getAbsolutePath());
                    }
                    final String url = Uri.fromFile(file).toString();
                    document.saveAs(url, format, options == null ? "" : options);
                    final String officeError = office == null ? "" : office.getError();
                    if (officeError != null && officeError.length() > 0) {
                        error = officeError;
                    } else if (!file.isFile() || file.length() == 0) {
                        error = "The engine did not produce an output file";
                    }
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.toString() : t.getMessage();
                    Log.e(TAG, "saveAs failed", t);
                }
                final String result = error;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        callback.onSaved(file, result);
                    }
                });
            }
        });
    }

    public void setPart(final int part) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try {
                    document.setPart(part);
                    currentPart = part;
                    widthTwip = document.getDocumentWidth();
                    heightTwip = document.getDocumentHeight();
                    mainHandler.post(new Runnable() {
                        @Override public void run() { listener.onInvalidate(); }
                    });
                } catch (Throwable t) {
                    fail("Unable to change page", t);
                }
            }
        });
    }

    public void setTextSelection(final int type, final int xTwip, final int yTwip) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try { document.setTextSelection(type, xTwip, yTwip); }
                catch (Throwable t) { fail("Unable to set selection", t); }
            }
        });
    }

    public void paste(final String mime, final String data) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                if (document == null) return;
                try {
                    if (document.paste(mime, data)) markModified();
                } catch (Throwable t) { fail("Unable to paste", t); }
            }
        });
    }

    public void getTextSelection(final SelectionCallback callback) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                String text = "";
                if (document != null) {
                    try { text = document.getTextSelection("text/plain"); }
                    catch (Throwable ignored) { }
                }
                final String value = text;
                mainHandler.post(new Runnable() {
                    @Override public void run() { callback.onSelection(value); }
                });
            }
        });
    }

    public interface SelectionCallback { void onSelection(String text); }

    public void getCommandValues(final String command, final ValuesCallback callback) {
        loHandler.post(new Runnable() {
            @Override public void run() {
                String value = "";
                if (document != null) {
                    try { value = document.getCommandValues(command); }
                    catch (Throwable ignored) { }
                }
                final String result = value;
                mainHandler.post(new Runnable() {
                    @Override public void run() { callback.onValues(result); }
                });
            }
        });
    }

    public interface ValuesCallback { void onValues(String json); }

    public int getDocumentType() { return documentType; }
    public int getParts() { return parts; }
    public int getCurrentPart() { return currentPart; }
    public long getWidthTwip() { return widthTwip; }
    public long getHeightTwip() { return heightTwip; }
    public boolean isModified() { return modified; }
    public File getCurrentFile() { return currentFile; }
    public String getLastError() { return lastError; }

    public void resetModified() {
        modified = false;
        mainHandler.post(new Runnable() {
            @Override public void run() { listener.onDocumentChanged(false); }
        });
    }

    private void ensureInitialized() throws IOException {
        if (office != null) return;
        prepareAssets();
        File dataDir = new File(activity.getApplicationInfo().dataDir);
        LibreOfficeKit.putenv("BRAND_BASE_DIR=" + Uri.fromFile(dataDir));
        LibreOfficeKit.putenv("SAL_USE_VCLPLUGIN=svp");
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts");
        LibreOfficeKit.putenv("SAL_LOG=+WARN+INFO");
        LibreOfficeKit.init(activity);
        ByteBuffer handle = LibreOfficeKit.getLibreOfficeKitHandle();
        if (handle == null) {
            throw new IOException("LibreOfficeKit did not return an engine handle");
        }
        office = new Office(handle);
        office.setMessageCallback(new Office.MessageCallback() {
            @Override public void messageRetrieved(final int signal, final String payload) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (signal == 20 || signal == 21) listener.onPasswordRequired();
                        else listener.onCallback(signal, payload);
                    }
                });
            }
        });
        office.setOptionalFeatures(Document.LOK_FEATURE_DOCUMENT_PASSWORD);
    }

    private void openDocument(File file, String password) throws IOException {
        if (office == null) throw new IOException("The document engine is not initialized");
        if (document != null) {
            try { document.destroy(); } catch (Throwable ignored) { }
            document = null;
        }
        currentFile = file;
        if (password != null) {
            office.setDocumentPassword(Uri.fromFile(file).toString(), password);
        }
        document = office.documentLoad(file.getAbsolutePath());
        if (document == null) {
            String error = office.getError();
            lastError = error == null ? "" : error;
            if (lastError.toLowerCase().contains("password")) {
                // LOK may ask for a password through the callback.  Keep the
                // activity open and let it supply one.
                mainHandler.post(new Runnable() {
                    @Override public void run() { listener.onPasswordRequired(); }
                });
                return;
            }
            throw new IOException("LibreOffice could not open the document"
                    + (lastError.length() == 0 ? "" : ": " + lastError));
        }
        document.setMessageCallback(new Document.MessageCallback() {
            @Override public void messageRetrieved(final int signal, final String payload) {
                if (signal == Document.CALLBACK_DOCUMENT_PASSWORD
                        || signal == Document.CALLBACK_DOCUMENT_PASSWORD_TO_MODIFY) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { listener.onPasswordRequired(); }
                    });
                    return;
                }
                if (signal == Document.CALLBACK_INVALIDATE_TILES
                        || signal == Document.CALLBACK_DOCUMENT_SIZE_CHANGED
                        || signal == Document.CALLBACK_SET_PART) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { listener.onInvalidate(); }
                    });
                }
                mainHandler.post(new Runnable() {
                    @Override public void run() { listener.onCallback(signal, payload); }
                });
            }
        });
        document.initializeForRendering();
        documentType = document.getDocumentType();
        parts = document.getParts();
        if (parts < 1) parts = 1;
        currentPart = 0;
        document.setPart(0);
        widthTwip = document.getDocumentWidth();
        heightTwip = document.getDocumentHeight();
        lastError = office.getError();
        modified = false;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onReady();
                listener.onDocumentChanged(false);
            }
        });
    }

    private void prepareAssets() throws IOException {
        File dataDir = new File(activity.getApplicationInfo().dataDir);
        File marker = new File(dataDir, ASSET_MARKER);
        if (marker.isFile()) return;
        Log.i(TAG, "Extracting LibreOffice runtime assets into " + dataDir);
        mainHandler.post(new Runnable() {
            @Override public void run() { listener.onStatus("Preparing offline Office engine..."); }
        });
        // Files read by fontconfig/freetype and by the UNO bootstrap must be
        // real files.  LibreOffice reads program/ and share/ through
        // AssetManager, but share/ is also used as BRAND_BASE_DIR by the
        // configuration manager, so it is materialized as well.
        copyAssetTree("unpack", dataDir);
        copyAssetTree("share", new File(dataDir, "share"));
        // A copy of the runtime configuration is useful for recovery and for
        // future engine updates; it is small.
        copyAssetTree("program", new File(dataDir, "program"));
        if (!marker.createNewFile()) {
            Log.w(TAG, "Could not create asset marker, but assets were copied");
        }
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        AssetManager assets = activity.getAssets();
        String[] children;
        try {
            children = assets.list(assetPath);
        } catch (IOException e) {
            children = new String[0];
        }
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
                throw new IOException("Cannot create " + destination);
            }
            for (String child : children) {
                copyAssetTree(assetPath + "/" + child, new File(destination, child));
            }
        } else {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create " + parent);
            }
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assets.open(assetPath);
                out = new FileOutputStream(destination);
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException e) {
                // AssetManager.list() cannot distinguish an empty directory
                // from a file on all Android versions.  If opening failed and
                // the destination was not created, treat it as an empty dir.
                if (!destination.exists()) throw e;
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) { }
                if (in != null) try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void markModified() {
        if (modified) return;
        modified = true;
        mainHandler.post(new Runnable() {
            @Override public void run() { listener.onDocumentChanged(true); }
        });
    }

    private void fail(String message, Throwable t) {
        Log.e(TAG, message, t);
        final String detail = message + (t.getMessage() == null ? "" : ": " + t.getMessage());
        lastError = detail;
        mainHandler.post(new Runnable() {
            @Override public void run() { listener.onError(detail); }
        });
    }
}
