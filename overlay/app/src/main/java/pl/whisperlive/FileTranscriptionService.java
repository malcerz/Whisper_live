package pl.whisperlive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import com.whispercpp.java.whisper.WhisperContext;
import com.whispercpp.java.whisper.WhisperSegment;
import com.whispercpp.java.whisper.WhisperWord;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone offline import path; never modifies files created by live capture. */
public final class FileTranscriptionService extends Service {
    static final String ACTION_IMPORT = "pl.whisperlive.IMPORT_AUDIO";
    static final String EXTRA_AUDIO_URI = "import_audio_uri";
    static final String EXTRA_MODEL_URI = "import_model_uri";
    private static final int NOTIFICATION_ID = 7302;
    private static final String CHANNEL_ID = "whisper_live_file";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = SAMPLE_RATE * 30;
    private static final int OVERLAP = SAMPLE_RATE * 2;

    interface Listener { void onState(Snapshot snapshot); }

    static final class Snapshot {
        final boolean busy;
        final int progress;
        final long elapsedMs;
        final long audioMs;
        final String status;
        final String error;
        final String preview;
        final String fileName;
        final boolean hasTxt;
        final boolean hasSrt;

        Snapshot(boolean busy, int progress, long elapsedMs, long audioMs,
                 String status, String error, String preview, String fileName,
                 boolean hasTxt, boolean hasSrt) {
            this.busy = busy;
            this.progress = progress;
            this.elapsedMs = elapsedMs;
            this.audioMs = audioMs;
            this.status = status;
            this.error = error;
            this.preview = preview;
            this.fileName = fileName;
            this.hasTxt = hasTxt;
            this.hasSrt = hasSrt;
        }
    }

    final class LocalBinder extends Binder {
        FileTranscriptionService getService() { return FileTranscriptionService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile WhisperContext activeContext;
    private volatile boolean busy;
    private volatile int progress;
    private volatile long elapsedMs;
    private volatile long audioMs;
    private volatile String status = "Gotowy — wybierz plik audio";
    private volatile String error = "";
    private volatile String preview = "";
    private volatile String fileName = "";
    private long startedAt;
    private long lastPublish;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Transkrypcja plików", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_IMPORT.equals(intent.getAction()) || busy) {
            return START_NOT_STICKY;
        }
        String audio = intent.getStringExtra(EXTRA_AUDIO_URI);
        String model = intent.getStringExtra(EXTRA_MODEL_URI);
        if (audio == null || model == null) return START_NOT_STICKY;
        busy = true;
        stopRequested.set(false);
        progress = 0;
        audioMs = 0L;
        elapsedMs = 0L;
        error = "";
        preview = "";
        fileName = getDisplayName(Uri.parse(audio));
        status = "Przygotowanie importu: " + fileName;
        startedAt = SystemClock.elapsedRealtime();
        lastPublish = 0L;
        Notification notification = makeNotification(status, 0, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        publish(true);
        worker.submit(() -> transcribe(Uri.parse(model), Uri.parse(audio)));
        return START_NOT_STICKY;
    }

    void setListener(Listener value) {
        listener = value;
        if (value != null) {
            Snapshot s = snapshot();
            mainHandler.post(() -> {
                Listener current = listener;
                if (current != null) current.onState(s);
            });
        }
    }

    void clearListener(Listener value) {
        if (listener == value) listener = null;
    }

    Snapshot snapshot() {
        return new Snapshot(busy, progress, elapsedMs, audioMs, status, error, preview,
                fileName, valid(getTxtFile()), valid(getSrtFile()));
    }

    File getTxtFile() { return new File(getFilesDir(), "last_import.txt"); }
    File getSrtFile() { return new File(getFilesDir(), "last_import.srt"); }

    void stopCurrent() {
        if (!busy) return;
        stopRequested.set(true);
        status = "Anulowanie transkrypcji pliku…";
        WhisperContext context = activeContext;
        if (context != null) context.requestAbort();
        publish(true);
    }

    private void transcribe(Uri modelUri, Uri audioUri) {
        File tmpTxt = new File(getFilesDir(), "last_import.txt.tmp");
        File tmpSrt = new File(getFilesDir(), "last_import.srt.tmp");
        tmpTxt.delete();
        tmpSrt.delete();
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperLive:File");
            wakeLock.acquire(6 * 60 * 60_000L);

            status = "Ładowanie modelu Whisper…";
            publish(true);
            File modelFile = ModelCache.ensure(this, modelUri, stopRequested,
                    (p, text) -> {
                        progress = Math.min(4, p * 4 / 100);
                        status = text;
                        publish(false);
                    });
            if (stopRequested.get()) throw new InterruptedException("Anulowano");

            TranscriptMerger merger = new TranscriptMerger();
            StringBuilder previewTail = new StringBuilder();
            try (WhisperContext whisper = WhisperContext.create(modelFile.getAbsolutePath());
                 BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(tmpTxt), StandardCharsets.UTF_8));
                 BufferedWriter srt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(tmpSrt), StandardCharsets.UTF_8))) {
                activeContext = whisper;
                Chunker chunker = new Chunker(whisper, merger, previewTail, txt, srt);
                status = "Dekodowanie i transkrypcja: " + fileName;
                publish(true);
                long decodedSamples = ImportedAudioDecoder.stream(
                        this, audioUri, stopRequested, chunker::feed);
                if (stopRequested.get()) throw new InterruptedException("Anulowano");
                chunker.finish();
                if (stopRequested.get()) throw new InterruptedException("Anulowano");
                audioMs = decodedSamples * 1000L / SAMPLE_RATE;
                if (chunker.subtitleCount == 0) {
                    throw new IOException("Brak rozpoznanej mowy — nie utworzono pustej transkrypcji");
                }
            } finally {
                activeContext = null;
            }
            if (stopRequested.get()) throw new InterruptedException("Anulowano");
            // Keep previous successful results if the current file fails.
            replaceFile(tmpTxt, getTxtFile());
            replaceFile(tmpSrt, getSrtFile());
            progress = 100;
            elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            status = "Gotowe — plik przepisany (TXT i SRT)";
            busy = false;
            publish(true);
        } catch (InterruptedException e) {
            status = "Przerwano transkrypcję pliku";
            busy = false;
            publish(true);
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            status = "Błąd transkrypcji pliku";
            busy = false;
            publish(true);
        } finally {
            tmpTxt.delete();
            tmpSrt.delete();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
            getSystemService(NotificationManager.class).notify(
                    NOTIFICATION_ID, makeNotification(status, progress, false));
            stopForeground(false);
            stopSelf();
        }
    }

    private final class Chunker {
        final WhisperContext whisper;
        final TranscriptMerger merger;
        final StringBuilder tail;
        final BufferedWriter txt;
        final BufferedWriter srt;
        final float[] buffer = new float[CHUNK];
        long windowStartSamples;
        long lastSubtitleEndMs;
        long durationUs = -1L;
        int filled;
        int subtitleCount;
        boolean firstChunk = true;

        Chunker(WhisperContext whisper, TranscriptMerger merger, StringBuilder tail,
                BufferedWriter txt, BufferedWriter srt) {
            this.whisper = whisper;
            this.merger = merger;
            this.tail = tail;
            this.txt = txt;
            this.srt = srt;
        }

        void feed(float[] samples, int count, long totalDurationUs) throws Exception {
            durationUs = totalDurationUs;
            int offset = 0;
            while (offset < count) {
                checkCancelled();
                int n = Math.min(count - offset, CHUNK - filled);
                System.arraycopy(samples, offset, buffer, filled, n);
                filled += n;
                offset += n;
                if (filled == CHUNK) {
                    process(buffer);
                    System.arraycopy(buffer, CHUNK - OVERLAP, buffer, 0, OVERLAP);
                    windowStartSamples += CHUNK - OVERLAP;
                    filled = OVERLAP;
                }
            }
        }

        void finish() throws Exception {
            if (filled > (firstChunk ? 0 : OVERLAP)) {
                float[] last = new float[filled];
                System.arraycopy(buffer, 0, last, 0, filled);
                process(last);
            }
        }

        private void process(float[] audio) throws Exception {
            checkCancelled();
            long chunkStartMs = windowStartSamples * 1000L / SAMPLE_RATE;
            long dedupBeforeMs = chunkStartMs + (firstChunk ? 0L : 2000L);
            firstChunk = false;
            List<WhisperSegment> segments = whisper.transcribe(audio, "pl");
            checkCancelled();
            for (WhisperSegment segment : segments) {
                if (segment == null) continue;
                long startMs = chunkStartMs + segment.getStart() * 10L;
                long endMs = chunkStartMs + segment.getEnd() * 10L;
                if (endMs <= dedupBeforeMs) continue;
                String text = segment.getText() == null ? "" : segment.getText().trim().replaceAll("\\s+", " ");
                if (startMs < dedupBeforeMs) {
                    StringBuilder clipped = new StringBuilder();
                    long firstWordStartMs = -1L;
                    for (WhisperWord word : segment.getWords()) {
                        long wordEndMs = chunkStartMs + word.getEnd() * 10L;
                        if (wordEndMs <= dedupBeforeMs) continue;
                        if (clipped.length() > 0) clipped.append(' ');
                        clipped.append(word.getText());
                        if (firstWordStartMs < 0L) {
                            firstWordStartMs = chunkStartMs + word.getStart() * 10L;
                        }
                    }
                    if (clipped.length() == 0) continue;
                    text = clipped.toString().trim();
                    startMs = Math.max(dedupBeforeMs, firstWordStartMs);
                }
                if (text.isEmpty()) continue;
                String delta = merger.append(text);
                if (delta.isEmpty()) continue;
                if (subtitleCount > 0) txt.write(' ');
                txt.write(delta);
                txt.flush();
                if (tail.length() > 0) tail.append(' ');
                tail.append(delta);
                if (tail.length() > 8000) tail.delete(0, tail.length() - 8000);
                preview = tail.toString();

                startMs = Math.max(startMs, lastSubtitleEndMs);
                endMs = Math.max(startMs + 10L, endMs);
                srt.write(Integer.toString(++subtitleCount));
                srt.newLine();
                srt.write(formatSrtTime(startMs) + " --> " + formatSrtTime(endMs));
                srt.newLine();
                srt.write(delta);
                srt.newLine();
                srt.newLine();
                lastSubtitleEndMs = endMs;
            }
            srt.flush();
            audioMs = (windowStartSamples + audio.length) * 1000L / SAMPLE_RATE;
            elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            if (durationUs > 0L) {
                progress = Math.min(99, Math.max(5, (int) (5L + audioMs * 94L / (durationUs / 1000L + 1L))));
            }
            status = "Transkrypcja: " + fileName + " (" + progress + "%)";
            publish(true);
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (stopRequested.get()) throw new InterruptedException("Anulowano");
    }

    private static String formatSrtTime(long milliseconds) {
        long ms = Math.max(0L, milliseconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                ms / 3600000L, (ms / 60000L) % 60L, (ms / 1000L) % 60L, ms % 1000L);
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "plik audio" : last;
    }

    private static void replaceFile(File tmp, File target) throws IOException {
        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) throw new IOException("Nie można nadpisać " + target.getName());
            if (!tmp.renameTo(target)) throw new IOException("Nie można zapisać " + target.getName());
        }
    }

    private static boolean valid(File f) { return f.isFile() && f.length() > 0L; }

    private void publish(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPublish < 500L) return;
        lastPublish = now;
        Snapshot s = snapshot();
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(s);
        });
        if (busy) getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, makeNotification(status, progress, true));
    }

    private Notification makeNotification(String body, int value, boolean ongoing) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("WhisperLive — transkrypcja pliku")
                .setContentText(body)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) builder.setProgress(100, Math.max(0, Math.min(100, value)), false);
        return builder.build();
    }

    @Override
    public void onDestroy() {
        stopRequested.set(true);
        WhisperContext context = activeContext;
        if (context != null) context.requestAbort();
        worker.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
