package org.unishelf.office;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class FileUtils {
    private static final int BUFFER = 64 * 1024;

    private FileUtils() { }

    public static String displayName(Context context, Uri uri, String fallback) {
        if (uri == null) return fallback;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && value.length() > 0) return value;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        if (last != null && last.length() > 0) return last;
        return fallback;
    }

    public static void copyUriToFile(Context context, Uri uri, File destination) throws IOException {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            copyFile(new File(uri.getPath()), destination);
            return;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Cannot read " + uri);
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create " + parent);
            }
            out = new FileOutputStream(destination);
            copy(in, out);
        } finally {
            close(in);
            close(out);
        }
    }

    public static void copyFileToUri(Context context, File source, Uri destination) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = resolver.openOutputStream(destination, "wt");
            if (out == null) throw new IOException("Cannot write " + destination);
            copy(in, out);
            out.flush();
        } finally {
            close(in);
            close(out);
        }
    }

    public static void copyFile(File source, File destination) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create " + parent);
            }
            in = new FileInputStream(source);
            out = new FileOutputStream(destination);
            copy(in, out);
            if (out instanceof FileOutputStream) {
                ((FileOutputStream) out).getFD().sync();
            }
        } finally {
            close(in);
            close(out);
        }
    }

    /**
     * Replaces target with source.  The previous target is retained as
     * target.bak until the rename succeeds, so an interrupted save never
     * leaves a truncated document as the only copy.
     */
    public static void atomicReplace(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create " + parent);
        }
        File backup = new File(target.getAbsolutePath() + ".bak");
        if (backup.exists() && !backup.delete()) {
            throw new IOException("Cannot remove old backup " + backup);
        }
        if (target.exists() && !target.renameTo(backup)) {
            copyFile(target, backup);
        }
        if (!source.renameTo(target)) {
            copyFile(source, target);
            if (!source.delete()) source.deleteOnExit();
        }
        if (backup.exists()) backup.delete();
    }

    public static void writeAsset(Context context, String assetPath, File destination) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create " + parent);
            }
            out = new FileOutputStream(destination);
            copy(in, out);
        } finally {
            close(in);
            close(out);
        }
    }

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static String withoutExtension(String name) {
        if (name == null || name.length() == 0) return "Document";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String safeName(String name, String fallback) {
        if (name == null || name.length() == 0) name = fallback;
        name = name.replace('/', '_').replace('\\', '_').replace(':', '_');
        if (name.length() > 120) {
            String ext = extension(name);
            String base = withoutExtension(name);
            int keep = Math.max(1, 100 - ext.length());
            name = base.substring(0, Math.min(base.length(), keep)) + (ext.length() > 0 ? "." + ext : "");
        }
        return name;
    }

    public static String mimeForName(String name) {
        String ext = extension(name);
        if ("docx".equals(ext)) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if ("doc".equals(ext)) return "application/msword";
        if ("xlsx".equals(ext)) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if ("xls".equals(ext)) return "application/vnd.ms-excel";
        if ("pptx".equals(ext)) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if ("ppt".equals(ext)) return "application/vnd.ms-powerpoint";
        if ("odt".equals(ext)) return "application/vnd.oasis.opendocument.text";
        if ("ods".equals(ext)) return "application/vnd.oasis.opendocument.spreadsheet";
        if ("odp".equals(ext)) return "application/vnd.oasis.opendocument.presentation";
        if ("pdf".equals(ext)) return "application/pdf";
        if ("csv".equals(ext)) return "text/csv";
        if ("txt".equals(ext)) return "text/plain";
        if ("rtf".equals(ext)) return "application/rtf";
        if ("html".equals(ext) || "htm".equals(ext)) return "text/html";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    /**
     * LibreOffice's saveAs() filter names.  The extension is retained for a
     * normal save, so the original container (OOXML, ODF, legacy binary) is
     * preserved whenever the user has not explicitly chosen another format.
     */
    public static String saveFormat(String name) {
        String ext = extension(name);
        if (ext.length() == 0) return null;
        if ("docx".equals(ext) || "doc".equals(ext) || "odt".equals(ext)
                || "rtf".equals(ext) || "txt".equals(ext)) return ext;
        if ("xlsx".equals(ext) || "xls".equals(ext) || "ods".equals(ext)
                || "csv".equals(ext)) return ext;
        if ("pptx".equals(ext) || "ppt".equals(ext) || "odp".equals(ext)) return ext;
        if ("pdf".equals(ext)) return "pdf";
        return null;
    }

    public static String defaultFormat(int documentType) {
        if (documentType == 1) return "xlsx";
        if (documentType == 2) return "pptx";
        return "docx";
    }

    public static String defaultExtension(int documentType) {
        if (documentType == 1) return "xlsx";
        if (documentType == 2) return "pptx";
        return "docx";
    }

    public static String formatLabel(String format) {
        if (format == null) return "Document";
        if ("docx".equals(format)) return "Word document (.docx)";
        if ("doc".equals(format)) return "Word 97-2003 (.doc)";
        if ("odt".equals(format)) return "OpenDocument text (.odt)";
        if ("xlsx".equals(format)) return "Excel workbook (.xlsx)";
        if ("xls".equals(format)) return "Excel 97-2003 (.xls)";
        if ("ods".equals(format)) return "OpenDocument spreadsheet (.ods)";
        if ("pptx".equals(format)) return "PowerPoint presentation (.pptx)";
        if ("ppt".equals(format)) return "PowerPoint 97-2003 (.ppt)";
        if ("odp".equals(format)) return "OpenDocument presentation (.odp)";
        if ("pdf".equals(format)) return "PDF";
        return format;
    }

    public static boolean isKnownFormat(String format) {
        return format != null && ("docx".equals(format) || "doc".equals(format)
                || "odt".equals(format) || "rtf".equals(format) || "txt".equals(format)
                || "xlsx".equals(format) || "xls".equals(format) || "ods".equals(format)
                || "csv".equals(format) || "pptx".equals(format) || "ppt".equals(format)
                || "odp".equals(format) || "pdf".equals(format));
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }
}
