package pl.whisperlive;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class ModelCache {
    private static final String PREFS = "whisper_live";
    private static final String PREF_MODEL_URI = "cached_model_uri";

    interface Progress {
        void onProgress(int percent, String status);
    }

    private ModelCache() {}

    static File ensure(Context context, Uri modelUri, AtomicBoolean cancelled, Progress progress) throws Exception {
        File model = new File(context.getFilesDir(), "whisper-model.bin");
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String previous = prefs.getString(PREF_MODEL_URI, "");
        if (modelUri.toString().equals(previous) && model.isFile() && model.length() > 1024 * 1024L) {
            if (progress != null) progress.onProgress(100, "Model gotowy");
            return model;
        }

        ContentResolver resolver = context.getContentResolver();
        long total = querySize(resolver, modelUri);
        File temp = new File(context.getFilesDir(), "whisper-model.tmp");
        if (temp.exists()) temp.delete();

        long copied = 0L;
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream raw = resolver.openInputStream(modelUri);
             BufferedInputStream in = raw == null ? null : new BufferedInputStream(raw, buffer.length);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Nie można otworzyć modelu Whisper");
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (cancelled != null && cancelled.get()) throw new InterruptedException("Anulowano");
                out.write(buffer, 0, n);
                copied += n;
                if (progress != null && total > 0L) {
                    int p = (int) Math.min(99L, copied * 100L / total);
                    progress.onProgress(p, "Kopiowanie modelu… " + p + "%");
                }
            }
            out.getFD().sync();
        } catch (Throwable t) {
            temp.delete();
            throw t;
        }

        if (model.exists() && !model.delete()) throw new IOException("Nie można zastąpić cache modelu");
        if (!temp.renameTo(model)) throw new IOException("Nie można zapisać cache modelu");
        prefs.edit().putString(PREF_MODEL_URI, modelUri.toString()).apply();
        if (progress != null) progress.onProgress(100, "Model gotowy");
        return model;
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1L;
    }
}
