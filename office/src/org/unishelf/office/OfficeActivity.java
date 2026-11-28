/*
 * UniShelf V2 Office activity.
 *
 * The original UniShelf Flutter application is deliberately not modified by
 * this source.  This activity is an additional, opt-in document editor that
 * exposes the genuine LibreOfficeKit engine to the user through Android's
 * normal Open/Share intents.  It uses only framework widgets and the UniShelf
 * colour palette so that the existing application identity is preserved.
 */

package org.unishelf.office;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.libreoffice.kit.Document;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public class OfficeActivity extends Activity implements OfficeEngine.Listener {
    private static final int GREEN = 0xFF1F4D3A;
    private static final int GREEN_LIGHT = 0xFF2E6A50;
    private static final int CREAM = 0xFFF7F4EA;
    private static final int CREAM_DARK = 0xFFEDE8DA;
    private static final int INK = 0xFF24332B;
    private static final int MUTED = 0xFF66756B;

    private static final int REQ_OPEN = 4001;
    private static final int REQ_SAVE_AS = 4002;
    private static final int REQ_EXPORT_PDF = 4003;

    private static final String EXTRA_NEW_TYPE = "UNISHELF_NEW_TYPE";
    private static final String NEW_DOC_TYPE_KEY = "NEW_DOC_TYPE_KEY";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private OfficeEngine engine;
    private OfficeView view;
    private LinearLayout root;
    private LinearLayout topBar;
    private LinearLayout titles;
    private TextView titleView;
    private TextView subtitleView;
    private TextView saveButton;
    private TextView menuButton;
    private HorizontalScrollView formatScroll;
    private LinearLayout formatRow;
    private TextView statusView;
    private FrameLayout contentFrame;
    private LinearLayout progressOverlay;
    private ProgressBar progressBar;
    private TextView progressText;

    private File workingFile;
    private Uri sourceUri;
    private String displayName = "Document";
    private String format;
    private boolean readOnly;
    private boolean engineReady;
    private boolean passwordPromptShowing;
    private boolean finishAfterSave;
    private File pendingPdf;
    private int pendingSaveRequest = -1;
    private TextView pageLabel;
    private boolean selfTest;
    private boolean selfTestReopen;
    private String selfTestSummary = "";
    private boolean legacySaveWarned;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(GREEN);
            getWindow().setNavigationBarColor(CREAM);
        }
        buildUi();
        engine = new OfficeEngine(this, this);
        view.setEngine(engine);
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("UNISHELF_SELFTEST", false)
                && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            selfTest = true;
            startSelfTest();
            return;
        }
        handleIntent(intent);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(GREEN);
        topBar.setPadding(dp(16), dp(6), dp(8), dp(6));

        titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextColor(CREAM);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(0xFFD6E2D9);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titles.addView(subtitleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        saveButton = makeTopButton("Save");
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        topBar.addView(saveButton);
        menuButton = makeTopButton("\u22ee");
        menuButton.setContentDescription("More document actions");
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showOverflow(v); }
        });
        topBar.addView(menuButton);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        formatScroll = new HorizontalScrollView(this);
        formatScroll.setHorizontalScrollBarEnabled(false);
        formatScroll.setBackgroundColor(CREAM_DARK);
        formatScroll.setVisibility(View.GONE);
        formatRow = new LinearLayout(this);
        formatRow.setOrientation(LinearLayout.HORIZONTAL);
        formatRow.setGravity(Gravity.CENTER_VERTICAL);
        formatRow.setPadding(dp(10), dp(4), dp(10), dp(4));
        formatScroll.addView(formatRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(formatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        contentFrame = new FrameLayout(this);
        view = new OfficeView(this);
        contentFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressOverlay = new LinearLayout(this);
        progressOverlay.setOrientation(LinearLayout.VERTICAL);
        progressOverlay.setGravity(Gravity.CENTER);
        progressOverlay.setBackgroundColor(0xCCF7F4EA);
        progressBar = new ProgressBar(this);
        progressText = new TextView(this);
        progressText.setTextColor(GREEN);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        progressText.setGravity(Gravity.CENTER);
        progressText.setPadding(dp(24), dp(8), dp(24), 0);
        progressOverlay.addView(progressBar);
        progressOverlay.addView(progressText);
        contentFrame.addView(progressOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        statusView = new TextView(this);
        statusView.setTextColor(GREEN);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setBackgroundColor(CREAM);
        statusView.setPadding(dp(12), dp(5), dp(12), dp(5));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        setContentView(root);
        updateTitle();
        updateStatus("Starting LibreOffice engine...");
    }

    private TextView makeTopButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(CREAM);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x22FFFFFF);
        background.setCornerRadius(dp(6));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button makeFormatButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(GREEN);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CREAM);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), 0x331F4D3A);
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void addFormatButton(String label, View.OnClickListener listener) {
        Button button = makeFormatButton(label);
        button.setOnClickListener(listener);
        formatRow.addView(button);
    }

    private void addFormatButton(String label, int typefaceStyle) {
        Button button = makeFormatButton(label);
        button.setTypeface(Typeface.DEFAULT, typefaceStyle);
        formatRow.addView(button);
    }

    private void addSeparator() {
        View separator = new View(this);
        separator.setBackgroundColor(0x331F4D3A);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(22));
        params.setMargins(dp(5), 0, dp(5), 0);
        formatRow.addView(separator, params);
    }

    private void buildFormatBar() {
        formatRow.removeAllViews();
        if (!engineReady) {
            formatScroll.setVisibility(View.GONE);
            return;
        }
        formatScroll.setVisibility(View.VISIBLE);
        addFormatButton("Undo", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:Undo"); }
        });
        addFormatButton("Redo", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:Redo"); }
        });
        addSeparator();
        Button bold = makeFormatButton("B");
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        bold.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:Bold"); }
        });
        formatRow.addView(bold);
        Button italic = makeFormatButton("I");
        italic.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        italic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:Italic"); }
        });
        formatRow.addView(italic);
        Button underline = makeFormatButton("U");
        underline.setPaintFlags(underline.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        underline.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:Underline"); }
        });
        formatRow.addView(underline);
        addSeparator();
        addFormatButton("Left", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:LeftPara"); }
        });
        addFormatButton("Center", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:CenterPara"); }
        });
        addFormatButton("Right", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:RightPara"); }
        });
        addFormatButton("Justify", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:JustifyPara"); }
        });
        addSeparator();
        addFormatButton("Font", new View.OnClickListener() {
            @Override public void onClick(View v) { showFontSizeDialog(); }
        });
        addSeparator();
        addFormatButton("Bullets", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:DefaultBullet"); }
        });
        addFormatButton("Numbering", new View.OnClickListener() {
            @Override public void onClick(View v) { uno(".uno:DefaultNumbering"); }
        });

        if (engine.getParts() > 1) {
            addSeparator();
            addFormatButton("\u25c0", new View.OnClickListener() {
                @Override public void onClick(View v) { changePart(-1); }
            });
            pageLabel = new TextView(this);
            pageLabel.setTextColor(GREEN);
            pageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pageLabel.setGravity(Gravity.CENTER);
            pageLabel.setPadding(dp(6), 0, dp(6), 0);
            formatRow.addView(pageLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
            addFormatButton("\u25b6", new View.OnClickListener() {
                @Override public void onClick(View v) { changePart(1); }
            });
            updatePageLabel();
        }

        addSeparator();
        addFormatButton("\u2212", new View.OnClickListener() {
            @Override public void onClick(View v) { view.zoomOut(); updateStatus(); }
        });
        addFormatButton("+", new View.OnClickListener() {
            @Override public void onClick(View v) { view.zoomIn(); updateStatus(); }
        });
        addSeparator();
        addFormatButton("PDF", new View.OnClickListener() {
            @Override public void onClick(View v) { beginExportPdf(false); }
        });
        addFormatButton("Print", new View.OnClickListener() {
            @Override public void onClick(View v) { beginExportPdf(true); }
        });
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            showStartDialog();
            return;
        }
        Uri data = intent.getData();
        if (data != null && "unishelf".equals(data.getScheme())) {
            String type = data.getQueryParameter("type");
            if (type != null) {
                newDocument(type);
                return;
            }
        }
        String newType = intent.getStringExtra(EXTRA_NEW_TYPE);
        if (newType == null) newType = intent.getStringExtra(NEW_DOC_TYPE_KEY);
        if (newType != null) {
            newDocument(newType);
            return;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
            openUri(data, hasWriteGrant(intent));
            return;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream == null && intent.getClipData() != null
                    && intent.getClipData().getItemCount() > 0) {
                stream = intent.getClipData().getItemAt(0).getUri();
            }
            if (stream != null) {
                openUri(stream, hasWriteGrant(intent));
                return;
            }
        }
        showStartDialog();
    }

    private boolean hasWriteGrant(Intent intent) {
        int flags = intent.getFlags();
        return (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
    }

    private void showStartDialog() {
        new AlertDialog.Builder(this)
                .setTitle("UniShelf Office")
                .setMessage("Open an existing document or start a new one. Everything in this editor is processed offline by the integrated LibreOffice engine.")
                .setPositiveButton("New document", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        showNewDocumentDialog();
                    }
                })
                .setNegativeButton("Open document", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        openPicker();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override public void onCancel(android.content.DialogInterface dialog) { finish(); }
                })
                .show();
    }

    private void showNewDocumentDialog() {
        final String[] items = new String[] {
                "Writer document", "Spreadsheet", "Presentation"
        };
        new AlertDialog.Builder(this)
                .setTitle("New document")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 1) newDocument("calc");
                        else if (which == 2) newDocument("impress");
                        else newDocument("writer");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_OPEN);
        } catch (ActivityNotFoundException e) {
            showError("No file picker is available on this device.");
        }
    }

    private void openUri(final Uri uri, final boolean writeGranted) {
        final String name = FileUtils.displayName(this, uri, "Document");
        final String finalFormat = FileUtils.saveFormat(name);
        final boolean readOnlyValue = !writeGranted && !isFileWritable(uri);
        showProgress("Copying document...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File cacheDir = new File(getCacheDir(), "unishelf_office");
                    if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
                        throw new IOException("Cannot create office cache directory");
                    }
                    String safe = FileUtils.safeName(name, "Document." +
                            FileUtils.defaultExtension(Document.DOCTYPE_TEXT));
                    String prefix = Integer.toHexString(uri.toString().hashCode());
                    File local = new File(cacheDir, prefix + "-" + safe);
                    FileUtils.copyUriToFile(OfficeActivity.this, uri, local);
                    final File finalLocal = local;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            openLocalDocument(finalLocal, uri, name, finalFormat, readOnlyValue);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            hideProgress();
                            showError("Could not read the selected document: " + safeMessage(t));
                        }
                    });
                }
            }
        }, "UniShelfOfficeCopy").start();
    }

    private void openLocalDocument(final File local, final Uri uri, final String name,
                                   final String fmt, final boolean readOnlyValue) {
        final File recovery = recoveryCandidate(local);
        if (recovery != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Recover unsaved changes?")
                    .setMessage("UniShelf found a newer local copy of this document from an interrupted save ("
                            + recovery.length() + " bytes). Recovering it can restore changes that were not written back to the original file.")
                    .setPositiveButton("Recover", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            try {
                                FileUtils.atomicReplace(recovery, local);
                            } catch (IOException e) {
                                showError("Could not restore the recovery copy: " + safeMessage(e));
                            }
                            openFileNow(local, uri, name, fmt, readOnlyValue);
                        }
                    })
                    .setNegativeButton("Open original", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            preserveRecovery(recovery);
                            openFileNow(local, uri, name, fmt, readOnlyValue);
                        }
                    })
                    .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                        @Override public void onCancel(android.content.DialogInterface dialog) {
                            hideProgress();
                            finish();
                        }
                    })
                    .show();
            return;
        }
        openFileNow(local, uri, name, fmt, readOnlyValue);
    }

    private File recoveryCandidate(File local) {
        File saving = new File(local.getPath() + ".saving");
        File backup = new File(local.getPath() + ".bak");
        File candidate = null;
        if (saving.isFile() && saving.length() > 0) candidate = saving;
        if (backup.isFile() && backup.length() > 0
                && (candidate == null || backup.lastModified() > candidate.lastModified())) {
            candidate = backup;
        }
        // A .bak can remain if the process died after the new target was
        // renamed into place.  In that case the live target is at least as new
        // and must not be replaced by the stale backup.
        if (candidate != null && local.isFile()
                && candidate.lastModified() <= local.lastModified()) {
            candidate = null;
        }
        return candidate;
    }

    private void preserveRecovery(File recovery) {
        try {
            File stale = new File(recovery.getPath() + ".ignored-"
                    + System.currentTimeMillis());
            if (!recovery.renameTo(stale)) {
                // Keeping the original file is more important than removing a
                // stale recovery candidate.  A rename failure is harmless.
                android.util.Log.w("UniShelfOffice", "Could not move ignored recovery file");
            }
        } catch (Throwable ignored) {
        }
    }

    private void openFileNow(File local, Uri uri, String name, String fmt,
                             boolean readOnlyValue) {
        workingFile = local;
        sourceUri = uri;
        displayName = name;
        format = fmt;
        readOnly = readOnlyValue;
        engine.open(workingFile, null);
    }

    private boolean isFileWritable(Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).canWrite();
        }
        return false;
    }

    private void newDocument(String type) {
        String normalized = type == null ? "writer" : type.toLowerCase(Locale.US);
        final String asset;
        final String extension;
        if (normalized.contains("calc") || normalized.contains("spread")
                || normalized.contains("sheet") || normalized.contains("xls")) {
            asset = "blank.xlsx";
            extension = "xlsx";
        } else if (normalized.contains("impress") || normalized.contains("present")
                || normalized.contains("ppt")) {
            asset = "blank.pptx";
            extension = "pptx";
        } else {
            asset = "blank.docx";
            extension = "docx";
        }
        showProgress("Creating document...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File cacheDir = new File(getCacheDir(), "unishelf_office");
                    if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
                        throw new IOException("Cannot create office cache directory");
                    }
                    File local = new File(cacheDir, "Untitled-" + System.currentTimeMillis() + "." + extension);
                    FileUtils.writeAsset(OfficeActivity.this, "unishelf_office/" + asset, local);
                    final File finalLocal = local;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            workingFile = finalLocal;
                            sourceUri = null;
                            displayName = "Untitled." + extension;
                            format = extension;
                            readOnly = false;
                            engine.open(workingFile, null);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            hideProgress();
                            showError("The blank document template is missing or unreadable: " + safeMessage(t));
                        }
                    });
                }
            }
        }, "UniShelfOfficeNew").start();
    }

    @Override public void onReady() {
        final boolean firstReady = !engineReady;
        engineReady = true;
        hideProgress();
        if (format == null) format = FileUtils.saveFormat(displayName);
        if (format == null) format = FileUtils.defaultFormat(engine.getDocumentType());
        view.onDocumentReady();
        if (firstReady) buildFormatBar();
        updateTitle();
        updateStatus();
        if (selfTest) {
            if (selfTestReopen) {
                selfTestReopen = false;
                final boolean reopened = workingFile != null && workingFile.isFile()
                        && workingFile.length() > 1000 && engine.getWidthTwip() > 0;
                writeSelfTestResult(selfTestSummary
                        + "reopened=" + (reopened ? "OK" : "FAIL") + "\n"
                        + "reopenedBytes=" + (workingFile == null ? 0 : workingFile.length()) + "\n"
                        + "result=" + (reopened ? "PASS" : "FAIL") + "\n");
                finish();
            } else {
                runSelfTest();
            }
        }
    }

    private void startSelfTest() {
        showProgress("Running on-device LibreOffice self-test...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File cacheDir = new File(getCacheDir(), "unishelf_office");
                    if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
                        throw new IOException("Cannot create office cache");
                    }
                    File local = new File(cacheDir, "selftest.docx");
                    FileUtils.writeAsset(OfficeActivity.this,
                            "unishelf_office/selftest.docx", local);
                    final File finalLocal = local;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            workingFile = finalLocal;
                            sourceUri = null;
                            displayName = "selftest.docx";
                            format = "docx";
                            readOnly = false;
                            engine.open(workingFile, null);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            writeSelfTestResult("FAIL copy: " + safeMessage(t));
                            finish();
                        }
                    });
                }
            }
        }, "UniShelfSelfTestCopy").start();
    }

    private void runSelfTest() {
        final File resultDir = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null) : getCacheDir();
        final File result = new File(resultDir, "selftest-result.txt");
        final int width = 512;
        final int height = 512;
        engine.render(width, height, 0, 0, 512, 512, new OfficeEngine.RenderCallback() {
            @Override public void onRendered(java.nio.ByteBuffer pixels, int w, int h) {
                int nonWhite = 0;
                byte[] row = new byte[w * 4];
                pixels.rewind();
                for (int y = 0; y < h; y++) {
                    pixels.get(row);
                    for (int i = 0; i < row.length; i += 4) {
                        int b = row[i] & 0xFF;
                        int g = row[i + 1] & 0xFF;
                        int r = row[i + 2] & 0xFF;
                        if (r < 250 || g < 250 || b < 250) nonWhite++;
                    }
                }
                final int painted = nonWhite;
                engine.uno(".uno:InsertText",
                        "{\"text\":{\"type\":\"string\",\"value\":\" UniShelfSelfTestOK \"}}", true);
                uiHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        final File docx = new File(resultDir, "selftest-out.docx");
                        final File pdf = new File(resultDir, "selftest-out.pdf");
                        final long start = System.currentTimeMillis();
                        engine.saveAs(docx, "docx", "", new OfficeEngine.SaveCallback() {
                            @Override public void onSaved(File docxFile, String docxError) {
                                if (docxError != null) {
                                    writeSelfTestResult("FAIL docx: " + docxError);
                                    finish();
                                    return;
                                }
                                engine.saveAs(pdf, "pdf", "", new OfficeEngine.SaveCallback() {
                                    @Override public void onSaved(File pdfFile, String pdfError) {
                                        StringBuilder out = new StringBuilder();
                                        out.append("engine=LibreOfficeKit\n");
                                        out.append("load=OK\n");
                                        out.append("documentType=").append(engine.getDocumentType()).append('\n');
                                        out.append("parts=").append(engine.getParts()).append('\n');
                                        out.append("tileNonWhite=").append(painted).append('\n');
                                        out.append("docxBytes=").append(docxFile.length()).append('\n');
                                        out.append("pdfBytes=").append(pdfFile.length()).append('\n');
                                        out.append("pdfError=").append(pdfError == null ? "" : pdfError).append('\n');
                                        out.append("elapsedMs=").append(System.currentTimeMillis() - start).append('\n');
                                        if (docxFile.length() <= 1000 || pdfFile.length() <= 1000 || painted <= 0) {
                                            out.append("result=FAIL\n");
                                            writeSelfTestResult(out.toString());
                                            finish();
                                            return;
                                        }
                                        selfTestSummary = out.toString();
                                        selfTestReopen = true;
                                        workingFile = docxFile;
                                        engine.open(docxFile, null);
                                    }
                                });
                            }
                        });
                    }
                }, 1200);
            }
        });
    }

    private void writeSelfTestResult(String text) {
        try {
            File dir = getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getCacheDir();
            File file = new File(dir, "selftest-result.txt");
            FileOutputStream out = new FileOutputStream(file);
            out.write(text.getBytes("UTF-8"));
            out.close();
            android.util.Log.i("UniShelfSelfTest", text);
        } catch (Throwable t) {
            android.util.Log.e("UniShelfSelfTest", "result write failed", t);
        }
    }

    @Override public void onStatus(final String message) {
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (progressOverlay.getVisibility() == View.VISIBLE) progressText.setText(message);
                statusView.setText(message);
            }
        });
    }

    @Override public void onError(final String message) {
        uiHandler.post(new Runnable() {
            @Override public void run() {
                hideProgress();
                if (selfTest) {
                    writeSelfTestResult(selfTestSummary + "reopened=FAIL\n"
                            + "error=" + (message == null ? "" : message) + "\n"
                            + "result=FAIL\n");
                    finish();
                    return;
                }
                showError(message);
            }
        });
    }

    @Override public void onPasswordRequired() {
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (passwordPromptShowing || workingFile == null) return;
                passwordPromptShowing = true;
                final EditText input = new EditText(OfficeActivity.this);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setHint("Document password");
                new AlertDialog.Builder(OfficeActivity.this)
                        .setTitle("Password required")
                        .setView(input)
                        .setPositiveButton("Open", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                passwordPromptShowing = false;
                                showProgress("Opening protected document...");
                                engine.open(workingFile, input.getText().toString());
                            }
                        })
                        .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                passwordPromptShowing = false;
                                finish();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override public void onCancel(android.content.DialogInterface dialog) {
                                passwordPromptShowing = false;
                                finish();
                            }
                        })
                        .show();
            }
        });
    }

    @Override public void onDocumentChanged(boolean modified) {
        updateTitle();
        updateStatus();
    }

    @Override public void onInvalidate() {
        if (view != null) view.requestRender();
        updateStatus();
    }

    @Override public void onCallback(int signal, String payload) {
        if (signal == Document.CALLBACK_DOCUMENT_SIZE_CHANGED) {
            view.onPageChanged();
        } else if (signal == Document.CALLBACK_SET_PART) {
            if (view != null) view.onPageChanged();
            updatePageLabel();
        } else if (signal == Document.CALLBACK_CELL_FORMULA
                || signal == Document.CALLBACK_CELL_ADDRESS) {
            if (payload != null && payload.length() > 0) statusView.setText(payload);
        } else if (signal == Document.CALLBACK_ERROR) {
            if (payload != null && payload.length() > 0) statusView.setText("Engine: " + payload);
        }
        updateStatusIfIdle();
    }

    private void updateStatusIfIdle() {
        if (engineReady && (statusView.getText() == null || statusView.getText().length() == 0)) {
            updateStatus();
        }
    }

    private void updateTitle() {
        if (titleView == null) return;
        String name = displayName == null ? "Document" : displayName;
        boolean modified = engine != null && engine.isModified();
        titleView.setText(name + (modified ? " *" : ""));
        String label = format == null ? "Document" : FileUtils.formatLabel(format);
        subtitleView.setText((readOnly ? "Read-only \u2022 " : "") + label);
        if (saveButton != null) {
            saveButton.setAlpha(modified ? 1.0f : 0.55f);
        }
    }

    private void updateStatus() {
        updateStatus(null);
    }

    private void updateStatus(String override) {
        if (statusView == null) return;
        if (override != null) {
            statusView.setText(override);
            return;
        }
        if (!engineReady) {
            statusView.setText("Starting LibreOffice engine...");
            return;
        }
        int parts = Math.max(1, engine.getParts());
        int part = Math.min(parts, engine.getCurrentPart() + 1);
        String state = engine.isModified() ? "Modified" : "Saved";
        String page = parts > 1 ? " \u2022 Page " + part + "/" + parts : "";
        statusView.setText("LibreOffice \u2022 " + state + page + " \u2022 "
                + Math.round(view.getZoom() * 100) + "%");
    }

    private void updatePageLabel() {
        if (pageLabel == null || engine == null) return;
        int parts = Math.max(1, engine.getParts());
        int part = Math.min(parts, engine.getCurrentPart() + 1);
        pageLabel.setText(part + "/" + parts);
    }

    private void changePart(int delta) {
        if (!engineReady) return;
        int parts = engine.getParts();
        int next = engine.getCurrentPart() + delta;
        if (next < 0) next = 0;
        if (next >= parts) next = parts - 1;
        if (next == engine.getCurrentPart()) return;
        engine.setPart(next);
        updatePageLabel();
        view.onPageChanged();
    }

    private void uno(String command) {
        if (!engineReady) return;
        engine.uno(command, null, true);
        view.requestRender();
    }

    private void showFontSizeDialog() {
        if (!engineReady) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Font size (pt)");
        input.setText("12");
        new AlertDialog.Builder(this)
                .setTitle("Font size")
                .setView(input)
                .setPositiveButton("Apply", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            int size = Integer.parseInt(input.getText().toString().trim());
                            size = Math.max(4, Math.min(200, size));
                            engine.uno(".uno:FontHeight",
                                    "{\"FontHeight\":{\"type\":\"long\",\"value\":" + size + "}}", true);
                            view.requestRender();
                        } catch (NumberFormatException ignored) {
                            Toast.makeText(OfficeActivity.this, "Enter a whole number", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void save() {
        if (!engineReady || workingFile == null) return;
        if (!engine.isModified()) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show();
            if (finishAfterSave) finish();
            return;
        }
        if (sourceUri != null && !readOnly) {
            if (isFidelityReducing(format) && !legacySaveWarned) {
                new AlertDialog.Builder(this)
                        .setTitle("Format fidelity warning")
                        .setMessage("This document uses a legacy or fixed-layout format (."
                                + format + "). Saving changes through LibreOffice rewrites the file and may change advanced layout, embedded objects or unsupported markup. Keep a copy of the original if it matters.\n\nContinue saving?")
                        .setPositiveButton("Save anyway", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                legacySaveWarned = true;
                                saveToSource();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            saveToSource();
        } else {
            beginSaveAs();
        }
    }

    private void saveToSource() {
        saveWorkingThen(new Runnable() {
            @Override public void run() { copyBackToSource(); }
        });
    }

    private static boolean isFidelityReducing(String fmt) {
        return "doc".equals(fmt) || "xls".equals(fmt) || "ppt".equals(fmt)
                || "pdf".equals(fmt);
    }

    private void saveWorkingThen(final Runnable after) {
        if (workingFile == null) return;
        if (format == null || !FileUtils.isKnownFormat(format)) {
            beginSaveAs();
            return;
        }
        final File temp = new File(workingFile.getParentFile(),
                workingFile.getName() + ".saving");
        if (temp.exists() && !temp.delete()) {
            showError("Cannot replace the temporary save file.");
            return;
        }
        showProgress("Saving with LibreOffice...");
        engine.saveAs(temp, format, "", new OfficeEngine.SaveCallback() {
            @Override public void onSaved(File file, String error) {
                hideProgress();
                if (error != null) {
                    if (temp.exists()) temp.delete();
                    showError("Save failed: " + error);
                    return;
                }
                try {
                    FileUtils.atomicReplace(temp, workingFile);
                    engine.resetModified();
                    updateTitle();
                    updateStatus();
                    if (after != null) after.run();
                    if (finishAfterSave && (after == null)) finish();
                } catch (IOException e) {
                    showError("Could not commit the saved document: " + safeMessage(e));
                }
            }
        });
    }

    private void copyBackToSource() {
        if (sourceUri == null || workingFile == null) return;
        try {
            if ("file".equals(sourceUri.getScheme()) && sourceUri.getPath() != null) {
                FileUtils.copyFile(workingFile, new File(sourceUri.getPath()));
            } else {
                FileUtils.copyFileToUri(this, workingFile, sourceUri);
            }
            Toast.makeText(this, "Saved " + displayName, Toast.LENGTH_SHORT).show();
            if (finishAfterSave) finish();
        } catch (IOException e) {
            showError("The document was saved in the app cache, but the original file could not be updated. Use Save As to choose a destination.\n\n" + safeMessage(e));
        }
    }

    private void beginSaveAs() {
        if (!engineReady) return;
        if (format == null || !FileUtils.isKnownFormat(format)) {
            format = FileUtils.defaultFormat(engine.getDocumentType());
        }
        String ext = format;
        String mime = FileUtils.mimeForName("document." + ext);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE,
                FileUtils.withoutExtension(displayName) + "." + ext);
        try {
            startActivityForResult(intent, REQ_SAVE_AS);
        } catch (ActivityNotFoundException e) {
            showError("No document provider is available for Save As.");
        }
    }

    private void copyWorkingToTarget(final Uri target) {
        final String name = FileUtils.displayName(this, target,
                FileUtils.withoutExtension(displayName) + "." + format);
        final String targetFormat = FileUtils.saveFormat(name);
        if (targetFormat != null && !targetFormat.equals(format)) {
            new AlertDialog.Builder(this)
                    .setTitle("Change file format?")
                    .setMessage("Saving as ." + targetFormat + " uses a different LibreOffice export filter. Fonts, layout, formulas, macros or embedded objects can change and some original features may not survive the conversion. Keep an original-format copy if you need full fidelity.")
                    .setPositiveButton("Convert and save", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            format = targetFormat;
                            performCopyToTarget(target, name);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        performCopyToTarget(target, name);
    }

    private void performCopyToTarget(final Uri target, final String name) {
        showProgress("Saving copy...");
        engine.saveAs(new File(workingFile.getParentFile(), workingFile.getName() + ".export"),
                format, "", new OfficeEngine.SaveCallback() {
                    @Override public void onSaved(File file, String error) {
                        if (error != null) {
                            hideProgress();
                            if (file.exists()) file.delete();
                            showError("Export failed: " + error);
                            return;
                        }
                        try {
                            if ("file".equals(target.getScheme()) && target.getPath() != null) {
                                FileUtils.copyFile(file, new File(target.getPath()));
                            } else {
                                FileUtils.copyFileToUri(OfficeActivity.this, file, target);
                            }
                            if (!file.delete()) file.deleteOnExit();
                            hideProgress();
                            sourceUri = target;
                            readOnly = false;
                            displayName = name;
                            engine.resetModified();
                            updateTitle();
                            updateStatus();
                            Toast.makeText(OfficeActivity.this, "Saved " + name, Toast.LENGTH_SHORT).show();
                            if (finishAfterSave) finish();
                        } catch (IOException e) {
                            hideProgress();
                            showError("The document was written locally but could not be copied to the chosen destination: " + safeMessage(e));
                        }
                    }
                });
    }

    private void beginExportPdf(final boolean print) {
        if (!engineReady || workingFile == null) return;
        final File pdf = new File(getCacheDir(), "unishelf_office/"
                + FileUtils.withoutExtension(displayName) + "-"
                + System.currentTimeMillis() + ".pdf");
        if (pdf.getParentFile() != null && !pdf.getParentFile().isDirectory()) pdf.getParentFile().mkdirs();
        showProgress("Exporting PDF with LibreOffice...");
        engine.saveAs(pdf, "pdf", "", new OfficeEngine.SaveCallback() {
            @Override public void onSaved(File file, String error) {
                if (error != null) {
                    hideProgress();
                    if (file.exists()) file.delete();
                    showError("PDF export failed: " + error);
                    return;
                }
                if (print) {
                    hideProgress();
                    printPdf(file);
                } else {
                    pendingPdf = file;
                    hideProgress();
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/pdf");
                    intent.putExtra(Intent.EXTRA_TITLE,
                            FileUtils.withoutExtension(displayName) + ".pdf");
                    try {
                        startActivityForResult(intent, REQ_EXPORT_PDF);
                    } catch (ActivityNotFoundException e) {
                        showError("No document provider is available for PDF export.");
                    }
                }
            }
        });
    }

    private void printPdf(final File pdf) {
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (printManager == null) {
            showError("Printing is not available on this device.");
            return;
        }
        PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                           android.os.CancellationSignal cancellationSignal,
                                           LayoutResultCallback callback, Bundle extras) {
                if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(displayName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build();
                callback.onLayoutFinished(info, true);
            }

            @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                         android.os.CancellationSignal cancellationSignal,
                                         WriteResultCallback callback) {
                FileInputStream in = null;
                FileOutputStream out = null;
                try {
                    in = new FileInputStream(pdf);
                    out = new FileOutputStream(destination.getFileDescriptor());
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                            callback.onWriteCancelled();
                            return;
                        }
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    callback.onWriteFinished(new PageRange[] { PageRange.ALL_PAGES });
                } catch (IOException e) {
                    callback.onWriteFailed(safeMessage(e));
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ignored) { }
                    // Do not close `out`: the framework owns the descriptor.
                }
            }
        };
        try {
            printManager.print(displayName, adapter, new PrintAttributes.Builder().build());
        } catch (Throwable t) {
            showError("Printing failed: " + safeMessage(t));
        }
    }

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "New Writer document");
        menu.getMenu().add(0, 2, 1, "New spreadsheet");
        menu.getMenu().add(0, 3, 2, "New presentation");
        menu.getMenu().add(0, 4, 3, "Open document...");
        menu.getMenu().add(0, 5, 4, "Save As...");
        menu.getMenu().add(0, 6, 5, "Export PDF...");
        menu.getMenu().add(0, 7, 6, "Print...");
        menu.getMenu().add(0, 8, 7, "Engine information");
        menu.getMenu().add(0, 9, 8, "Close");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: newDocument("writer"); return true;
                    case 2: newDocument("calc"); return true;
                    case 3: newDocument("impress"); return true;
                    case 4: openPicker(); return true;
                    case 5: beginSaveAs(); return true;
                    case 6: beginExportPdf(false); return true;
                    case 7: beginExportPdf(true); return true;
                    case 8: showEngineInfo(); return true;
                    case 9: finish(); return true;
                    default: return false;
                }
            }
        });
        menu.show();
    }

    private void showEngineInfo() {
        String type;
        if (!engineReady) type = "not loaded";
        else if (engine.getDocumentType() == Document.DOCTYPE_TEXT) type = "Writer (text)";
        else if (engine.getDocumentType() == Document.DOCTYPE_SPREADSHEET) type = "Calc (spreadsheet)";
        else if (engine.getDocumentType() == Document.DOCTYPE_PRESENTATION) type = "Impress (presentation)";
        else if (engine.getDocumentType() == Document.DOCTYPE_DRAWING) type = "Draw / PDF";
        else type = "other";
        String message = "Engine: LibreOfficeKit (LibreOffice 26.2.7.3 Android)\n"
                + "Document type: " + type + "\n"
                + "Processing: entirely on this device; no document is uploaded.\n\n"
                + "Format notes:\n"
                + "\u2022 DOCX/XLSX/PPTX and ODF are edited through the real LibreOffice engine.\n"
                + "\u2022 Saving without edits keeps the original bytes unchanged.\n"
                + "\u2022 Macros are not executed. Complex PDF editing and some embedded media or external links may be limited by LibreOffice import filters.\n"
                + "\u2022 Save As to another format can change layout; keep the original format when possible.";
        new AlertDialog.Builder(this)
                .setTitle("UniShelf Office engine")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showProgress(String message) {
        progressText.setText(message);
        progressOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        progressOverlay.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("UniShelf Office")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "Unknown error";
        String message = t.getMessage();
        return message == null || message.length() == 0 ? t.toString() : message;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Throwable ignored) { }
                openUri(uri, hasWriteGrant(data));
            }
        } else if (requestCode == REQ_SAVE_AS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                copyWorkingToTarget(data.getData());
            }
        } else if (requestCode == REQ_EXPORT_PDF) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingPdf != null && pendingPdf.isFile()) {
                try {
                    FileUtils.copyFileToUri(this, pendingPdf, data.getData());
                    Toast.makeText(this, "PDF exported", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    showError("Could not write the PDF: " + safeMessage(e));
                }
            }
            if (pendingPdf != null) pendingPdf.delete();
            pendingPdf = null;
        }
    }

    @Override public void onBackPressed() {
        if (engine != null && engine.isModified()) {
            new AlertDialog.Builder(this)
                    .setTitle("Unsaved changes")
                    .setMessage("Save the changes to this document before closing?")
                    .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            finishAfterSave = true;
                            save();
                        }
                    })
                    .setNegativeButton("Discard", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        if (engine != null) engine.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
