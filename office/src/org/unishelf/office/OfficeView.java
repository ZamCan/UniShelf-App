/*
 * UniShelf V2 document surface.
 *
 * Rendering is performed exclusively by LibreOfficeKit.  This View only
 * forwards viewport coordinates and paints the returned tile bitmap.  It does
 * not parse, lay out or draw document content itself.
 */

package org.unishelf.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import org.libreoffice.kit.Document;

import java.nio.ByteBuffer;

public class OfficeView extends View {
    private static final int KEY_BACKSPACE = 0x0008;
    private static final int KEY_TAB = 0x0009;
    private static final int KEY_RETURN = 0x000D;
    private static final int KEY_ESCAPE = 0x001B;
    private static final int KEY_PAGE_UP = 0x0021;
    private static final int KEY_PAGE_DOWN = 0x0022;
    private static final int KEY_END = 0x0023;
    private static final int KEY_HOME = 0x0024;
    private static final int KEY_LEFT = 0x0025;
    private static final int KEY_UP = 0x0026;
    private static final int KEY_RIGHT = 0x0027;
    private static final int KEY_DOWN = 0x0028;
    private static final int KEY_DELETE = 0x002E;
    private static final int KEY_INSERT = 0x002D;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Paint shadowPaint = new Paint();
    private final Paint messagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    private OfficeEngine engine;
    private Bitmap bitmap;
    private int renderGeneration;
    private boolean renderInFlight;
    private boolean renderAgain;
    private boolean destroyed;
    private boolean showKeyboardAfterTap;
    private int dpi = 160;
    private float zoom = 1.0f;
    private int scrollX;
    private int scrollY;
    private long widthTwip;
    private long heightTwip;
    private int documentType = Document.DOCTYPE_OTHER;
    private float lastTouchX;
    private float lastTouchY;
    private boolean fitPending;
    private Runnable pendingRender;

    public OfficeView(Context context) {
        this(context, null);
    }

    public OfficeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setBackgroundColor(Color.rgb(226, 222, 210));
        dpi = Math.max(96, Math.min(480, getResources().getDisplayMetrics().densityDpi));
        backgroundPaint.setColor(Color.rgb(226, 222, 210));
        shadowPaint.setColor(Color.argb(70, 0, 0, 0));
        messagePaint.setColor(Color.rgb(31, 77, 58));
        messagePaint.setTextSize(dp(14));
        messagePaint.setTextAlign(Paint.Align.CENTER);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                requestFocus();
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e.getX(), e.getY(), 1);
                if (showKeyboardAfterTap) showKeyboard();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                handleTap(e.getX(), e.getY(), 2);
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleGestures.isInProgress()) return false;
                scrollX += (int) dx;
                scrollY += (int) dy;
                clampScroll();
                invalidate();
                scheduleRender();
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float newZoom = zoom * detector.getScaleFactor();
                setZoomAround(newZoom, detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        showKeyboardAfterTap = true;
    }

    public void setEngine(OfficeEngine engine) {
        this.engine = engine;
    }

    public void onDocumentReady() {
        if (engine == null) return;
        widthTwip = engine.getWidthTwip();
        heightTwip = engine.getHeightTwip();
        documentType = engine.getDocumentType();
        zoom = 1.0f;
        scrollX = 0;
        scrollY = 0;
        if (widthTwip <= 0) widthTwip = 11906; // A4 fallback, only for the
        if (heightTwip <= 0) heightTwip = 16838; // viewport calculation.
        fitPending = true;
        fitPageToView();
        requestRender();
    }

    public void onPageChanged() {
        if (engine == null) return;
        widthTwip = engine.getWidthTwip();
        heightTwip = engine.getHeightTwip();
        scrollX = 0;
        scrollY = 0;
        fitPending = true;
        fitPageToView();
        requestRender();
    }

    private void fitPageToView() {
        if (!fitPending || getWidth() <= 0 || getHeight() <= 0
                || widthTwip <= 0 || heightTwip <= 0) return;
        final float availableW = Math.max(1.0f, getWidth() - dp(24));
        final float availableH = Math.max(1.0f, getHeight() - dp(24));
        final float pageW = widthTwip * (dpi / 1440.0f);
        final float pageH = heightTwip * (dpi / 1440.0f);
        zoom = Math.max(0.1f, Math.min(1.0f,
                Math.min(availableW / pageW, availableH / pageH)));
        fitPending = false;
    }

    public void setShowKeyboardAfterTap(boolean value) {
        showKeyboardAfterTap = value;
    }

    public void requestRender() {
        if (destroyed || engine == null || getWidth() == 0 || getHeight() == 0) return;
        final int w = getWidth();
        final int h = getHeight();
        final float pageW = widthTwip * (dpi / 1440.0f) * zoom;
        final float pageH = heightTwip * (dpi / 1440.0f) * zoom;
        clampScroll();

        final int renderW;
        final int renderH;
        final int xTwip;
        final int yTwip;
        final int wTwip;
        final int hTwip;
        if (pageW <= w + 0.5f && pageH <= h + 0.5f) {
            renderW = Math.max(1, Math.round(pageW));
            renderH = Math.max(1, Math.round(pageH));
            xTwip = 0;
            yTwip = 0;
            wTwip = (int) widthTwip;
            hTwip = (int) heightTwip;
        } else {
            renderW = w;
            renderH = h;
            xTwip = Math.round((scrollX / zoom) / dpi * 1440.0f);
            yTwip = Math.round((scrollY / zoom) / dpi * 1440.0f);
            wTwip = Math.max(1, Math.round((w / zoom) / dpi * 1440.0f));
            hTwip = Math.max(1, Math.round((h / zoom) / dpi * 1440.0f));
        }
        final int generation = ++renderGeneration;
        if (renderInFlight) {
            renderAgain = true;
            return;
        }
        renderInFlight = true;
        engine.render(renderW, renderH, xTwip, yTwip, wTwip, hTwip,
                new OfficeEngine.RenderCallback() {
                    @Override public void onRendered(ByteBuffer pixels, int width, int height) {
                        renderInFlight = false;
                        if (destroyed || generation != renderGeneration) {
                            if (renderAgain) {
                                renderAgain = false;
                                requestRender();
                            }
                            return;
                        }
                        Bitmap next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        pixels.rewind();
                        next.copyPixelsFromBuffer(pixels);
                        if (bitmap != null && bitmap != next) bitmap.recycle();
                        bitmap = next;
                        invalidate();
                        if (renderAgain) {
                            renderAgain = false;
                            requestRender();
                        }
                    }
                });
    }

    private void scheduleRender() {
        if (pendingRender != null) uiHandler.removeCallbacks(pendingRender);
        pendingRender = new Runnable() {
            @Override public void run() {
                pendingRender = null;
                requestRender();
            }
        };
        uiHandler.postDelayed(pendingRender, 80);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitPageToView();
        clampScroll();
        requestRender();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float pageW = widthTwip * (dpi / 1440.0f) * zoom;
        final float pageH = heightTwip * (dpi / 1440.0f) * zoom;
        final int drawX;
        final int drawY;
        if (pageW <= getWidth() + 0.5f && pageH <= getHeight() + 0.5f) {
            drawX = Math.max(0, (int) ((getWidth() - pageW) / 2));
            drawY = Math.max(0, (int) ((getHeight() - pageH) / 2));
        } else {
            drawX = 0;
            drawY = 0;
        }
        if (bitmap != null) {
            if (pageW <= getWidth() + 0.5f && pageH <= getHeight() + 0.5f) {
                canvas.drawRect(drawX + dp(4), drawY + dp(4),
                        drawX + bitmap.getWidth() + dp(4), drawY + bitmap.getHeight() + dp(4),
                        shadowPaint);
            }
            canvas.drawBitmap(bitmap, drawX, drawY, bitmapPaint);
        } else {
            canvas.drawText("Rendering document...", getWidth() / 2.0f,
                    getHeight() / 2.0f, messagePaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            requestFocus();
        }
        boolean scale = scaleGestures.onTouchEvent(event);
        boolean gesture = gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !scale) {
            // GestureDetector handles taps; keep the focus for the IME.
        }
        return scale || gesture || super.onTouchEvent(event);
    }

    private void handleTap(float x, float y, int count) {
        if (engine == null) return;
        int[] twip = toDocumentTwips(x, y);
        engine.postMouse(Document.MOUSE_EVENT_BUTTON_DOWN, twip[0], twip[1], count,
                Document.MOUSE_BUTTON_LEFT, Document.KEYBOARD_MODIFIER_NONE);
        engine.postMouse(Document.MOUSE_EVENT_BUTTON_UP, twip[0], twip[1], count,
                Document.MOUSE_BUTTON_LEFT, Document.KEYBOARD_MODIFIER_NONE);
        scheduleRender();
    }

    private int[] toDocumentTwips(float x, float y) {
        float pageW = widthTwip * (dpi / 1440.0f) * zoom;
        float pageH = heightTwip * (dpi / 1440.0f) * zoom;
        float drawX = pageW <= getWidth() ? Math.max(0, (getWidth() - pageW) / 2) : 0;
        float drawY = pageH <= getHeight() ? Math.max(0, (getHeight() - pageH) / 2) : 0;
        float docPxX = (x - drawX + scrollX) / zoom;
        float docPxY = (y - drawY + scrollY) / zoom;
        return new int[] {
                Math.round(docPxX / dpi * 1440.0f),
                Math.round(docPxY / dpi * 1440.0f)
        };
    }

    private void setZoomAround(float requested, float focusX, float focusY) {
        float next = Math.max(0.25f, Math.min(5.0f, requested));
        if (Math.abs(next - zoom) < 0.001f) return;
        fitPending = false;
        float docX = (focusX + scrollX) / zoom;
        float docY = (focusY + scrollY) / zoom;
        zoom = next;
        scrollX = Math.round(docX * zoom - focusX);
        scrollY = Math.round(docY * zoom - focusY);
        clampScroll();
        invalidate();
        scheduleRender();
    }

    private void clampScroll() {
        float pageW = widthTwip * (dpi / 1440.0f) * zoom;
        float pageH = heightTwip * (dpi / 1440.0f) * zoom;
        int maxX = Math.max(0, Math.round(pageW - getWidth()));
        int maxY = Math.max(0, Math.round(pageH - getHeight()));
        if (pageW <= getWidth()) scrollX = 0; else scrollX = Math.max(0, Math.min(maxX, scrollX));
        if (pageH <= getHeight()) scrollY = 0; else scrollY = Math.max(0, Math.min(maxY, scrollY));
    }

    public void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    public void zoomIn() {
        setZoomAround(zoom * 1.25f, getWidth() / 2.0f, getHeight() / 2.0f);
    }

    public void zoomOut() {
        setZoomAround(zoom / 1.25f, getWidth() / 2.0f, getHeight() / 2.0f);
    }

    public float getZoom() { return zoom; }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleKey(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);
        if (engine != null && mapKeyCode(keyCode) != 0) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean handleKey(int keyCode, KeyEvent event) {
        if (engine == null) return false;
        int lokKey = mapKeyCode(keyCode);
        if (lokKey != 0) {
            engine.postKey(0, lokKey, false);
            engine.postKey(0, lokKey, true);
            return true;
        }
        int unicode = event.getUnicodeChar();
        if (unicode != 0) {
            engine.postKey(unicode, 0, false);
            return true;
        }
        return false;
    }

    private static int mapKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return KEY_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return KEY_DELETE;
            case KeyEvent.KEYCODE_ENTER: return KEY_RETURN;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return KEY_RETURN;
            case KeyEvent.KEYCODE_TAB: return KEY_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return KEY_ESCAPE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return KEY_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return KEY_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return KEY_END;
            case KeyEvent.KEYCODE_PAGE_UP: return KEY_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return KEY_PAGE_DOWN;
            case KeyEvent.KEYCODE_INSERT: return KEY_INSERT;
            default: return 0;
        }
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        final StringBuilder composition = new StringBuilder();
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                composition.setLength(0);
                sendText(text == null ? "" : text.toString());
                return true;
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Keep the IME composition local until it is committed.  The
                // engine is not a text widget and does not expose a composing
                // region; inserting every intermediate candidate would
                // duplicate text on many keyboards.
                composition.setLength(0);
                if (text != null) composition.append(text);
                return true;
            }

            @Override public boolean finishComposingText() {
                if (composition.length() > 0) {
                    sendText(composition.toString());
                    composition.setLength(0);
                }
                return true;
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) sendKey(KEY_BACKSPACE);
                for (int i = 0; i < afterLength; i++) sendKey(KEY_DELETE);
                return true;
            }

            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int key = mapKeyCode(event.getKeyCode());
                    if (key != 0) {
                        sendKey(key);
                        return true;
                    }
                    int unicode = event.getUnicodeChar();
                    if (unicode != 0) {
                        sendText(new String(Character.toChars(unicode)));
                        return true;
                    }
                }
                return super.sendKeyEvent(event);
            }

            @Override public boolean performEditorAction(int actionCode) {
                sendKey(KEY_RETURN);
                return true;
            }
        };
    }

    private void sendText(String text) {
        if (engine == null || text == null) return;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == '\n' || cp == '\r') {
                engine.postKey(0, KEY_RETURN, false);
                engine.postKey(0, KEY_RETURN, true);
            } else if (cp == '\t') {
                engine.postKey(0, KEY_TAB, false);
                engine.postKey(0, KEY_TAB, true);
            } else {
                engine.postKey(cp, 0, false);
            }
            i += Character.charCount(cp);
        }
        scheduleRender();
    }

    private void sendKey(int key) {
        if (engine == null) return;
        engine.postKey(0, key, false);
        engine.postKey(0, key, true);
        scheduleRender();
    }

    @Override protected void onDetachedFromWindow() {
        destroyed = true;
        if (pendingRender != null) uiHandler.removeCallbacks(pendingRender);
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
