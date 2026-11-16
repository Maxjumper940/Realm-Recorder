package com.realmvc.recorder;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** MediaStore helpers: everything is saved to Movies/RealmRecorder and Pictures/RealmRecorder. */
final class Media {
    private static final String TAG = "RealmRec.Media";
    static final String VIDEO_DIR = Environment.DIRECTORY_MOVIES + "/RealmRecorder";
    static final String PICTURE_DIR = Environment.DIRECTORY_PICTURES + "/RealmRecorder";

    private Media() {}

    static final class Recording {
        final Uri uri;
        final String name;
        final long durationMs;
        final long sizeBytes;
        final long dateSec;
        Recording(Uri uri, String name, long durationMs, long sizeBytes, long dateSec) {
            this.uri = uri; this.name = name; this.durationMs = durationMs;
            this.sizeBytes = sizeBytes; this.dateSec = dateSec;
        }
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static Uri newVideo(Context c) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "RealmRec_" + stamp() + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_DIR);
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        return c.getContentResolver().insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
    }

    static void publish(Context c, Uri uri) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            c.getContentResolver().update(uri, v, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "publish failed", e);
        }
    }

    static void delete(Context c, Uri uri) {
        if (uri == null) return;
        try {
            c.getContentResolver().delete(uri, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "delete failed", e);
        }
    }

    /** Saves a PNG screenshot. Returns its Uri or null. */
    static Uri saveScreenshot(Context c, Bitmap bmp) {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "RealmShot_" + stamp() + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, PICTURE_DIR);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) return null;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw new java.io.IOException("compress failed");
                }
            }
            publish(c, uri);
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "screenshot save failed", e);
            delete(c, uri);
            return null;
        }
    }

    /** Recordings in Movies/RealmRecorder, newest first. */
    static List<Recording> listRecordings(Context c, int limit) {
        List<Recording> out = new ArrayList<>();
        Uri coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] proj = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
        };
        String sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {VIDEO_DIR + "%"};
        try (Cursor cur = c.getContentResolver().query(coll, proj, sel, args,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                long id = cur.getLong(0);
                out.add(new Recording(ContentUris.withAppendedId(coll, id),
                        cur.getString(1), cur.getLong(2), cur.getLong(3), cur.getLong(4)));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "list failed", e);
        }
        return out;
    }

    static long freeBytes() {
        try {
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            return fs.getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static long totalBytes() {
        try {
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            return fs.getTotalBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String human(long bytes) {
        if (bytes < 0) return "?";
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        return String.format(Locale.US, "%d MB", bytes / (1024 * 1024));
    }
}
