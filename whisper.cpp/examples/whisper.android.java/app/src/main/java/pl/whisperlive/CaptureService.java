package pl.whisperlive;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.whispercpp.java.whisper.WhisperContext;
import com.whispercpp.java.whisper.WhisperSegment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureService extends Service {
    static final String ACTION_START = "pl.whisperlive.START_CAPTURE";
    static final String ACTION_RETRANSCRIBE = "pl.whisperlive.RETRANSCRIBE";
    static final String EXTRA_MODEL_URI = "model_uri";
    static final String EXTRA_DURATION_MIN = "duration_min";
    static final String EXTRA_PROJECTION_RESULT_CODE = "projection_result_code";
    static final String EXTRA_PROJECTION_DATA = "projection_data";

    private static final int NOTIFICATION_ID = 7301;
    private static final String CHANNEL_ID = "whisper_live_capture";
    private static final int LIVE_CHUNK_SECONDS = 6;
    private static final int LIVE_OVERLAP_MS = 600;
    private static final int POST_CHUNK_SECONDS = 30;
    private static final int POST_OVERLAP_SECONDS = 2;
    private static final AudioChunk END = new AudioChunk(new float[0], -1L);

    public interface Listener {
        void onState(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String status;
        public final String preview;
        public final String error;
        public final boolean busy;
        public final boolean recording;
        public final boolean retranscribing;
        public final boolean liveLagged;
        public final int progress;
        public final long elapsedMs;
        public final long remainingMs;
        public final long recordedAudioMs;
        public final boolean hasRecording;
        public final boolean hasTranscript;

        Snapshot(String status, String preview, String error, boolean busy, boolean recording,
                 boolean retranscribing, boolean liveLagged, int progress, long elapsedMs,
                 long remainingMs, long recordedAudioMs, boolean hasRecording,
                 boolean hasTranscript) {
            this.status = status;
            this.preview = preview;
            this.error = error;
            this.busy = busy;
            this.recording = recording;
            this.retranscribing = retranscribing;
            this.liveLagged = liveLagged;
            this.progress = progress;
            this.elapsedMs = elapsedMs;
            this.remainingMs = remainingMs;
            this.recordedAudioMs = recordedAudioMs;
            this.hasRecording = hasRecording;
            this.hasTranscript = hasTranscript;
        }
    }

    public final class LocalBinder extends Binder {
        public CaptureService getService() { return CaptureService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService liveExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final TranscriptMerger merger = new TranscriptMerger();

    private volatile Listener listener;
    private volatile WhisperContext activeContext;
    private volatile MediaProjection activeProjection;
    private volatile AudioRecord activeRecorder;
    private volatile boolean busy;
    private volatile boolean recording;
    private volatile boolean retranscribing;
    private volatile boolean liveLagged;
    private volatile int progress;
    private volatile long elapsedMs;
    private volatile long remainingMs;
    private volatile long recordedAudioMs;
    private volatile String status = "Gotowy";
    private volatile String error = "";
    private volatile String preview = "";
    private long captureStartedAt;
    private long durationLimitMs;
    private long lastPublishAt;
    private PowerManager.WakeLock partialWakeLock;
    private PowerManager.WakeLock screenWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Whisper Live", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Nagrywanie dźwięku odtwarzanego przez telefon i transkrypcja Whisper");
        nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            if (busy) return START_NOT_STICKY;
            String model = intent.getStringExtra(EXTRA_MODEL_URI);
            int minutes = clamp(intent.getIntExtra(EXTRA_DURATION_MIN, 30), 1, 120);
            int resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            if (model == null || projectionData == null || resultCode != Activity.RESULT_OK) {
                return START_NOT_STICKY;
            }
            beginForeground("Przygotowanie przechwytywania…", 0, false, true);
            durationLimitMs = minutes * 60_000L;
            resetState(true, false);
            controlExecutor.submit(() -> runCapture(Uri.parse(model), resultCode, projectionData));
            return START_NOT_STICKY;
        }
        if (ACTION_RETRANSCRIBE.equals(intent.getAction())) {
            if (busy) return START_NOT_STICKY;
            String model = intent.getStringExtra(EXTRA_MODEL_URI);
            if (model == null) return START_NOT_STICKY;
            beginForeground("Przygotowanie pełnej transkrypcji…", 0, true, false);
            resetState(false, true);
            controlExecutor.submit(() -> runPostTranscription(Uri.parse(model)));
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            Snapshot s = snapshot();
            mainHandler.post(() -> {
                Listener current = listener;
                if (current != null) current.onState(s);
            });
        }
    }

    public void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    public Snapshot snapshot() {
        return new Snapshot(status, preview, error, busy, recording, retranscribing,
                liveLagged, progress, elapsedMs, remainingMs, recordedAudioMs,
                getRecordingFile().isFile() && getRecordingFile().length() > WavFile.HEADER_SIZE,
                getTranscriptFile().isFile() && getTranscriptFile().length() > 0L);
    }

    public void stopCurrent() {
        if (!busy) return;
        status = recording ? "Zatrzymywanie nagrywania…" : "Anulowanie transkrypcji…";
        stopRequested.set(true);
        WhisperContext ctx = activeContext;
        if (ctx != null) ctx.requestAbort();
        publish(true);
    }

    public File getRecordingFile() {
        return new File(getFilesDir(), "last_capture.wav");
    }

    public File getTranscriptFile() {
        return new File(getFilesDir(), "last_capture.txt");
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLocks(long timeoutMs) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperLive:CPU");
        screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "WhisperLive:Screen");
        long safeTimeout = Math.max(60_000L, timeoutMs);
        partialWakeLock.acquire(safeTimeout);
        screenWakeLock.acquire(safeTimeout);
    }

    private void releaseWakeLocks() {
        try {
            if (partialWakeLock != null && partialWakeLock.isHeld()) partialWakeLock.release();
        } catch (Exception ignored) {}
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception ignored) {}
        partialWakeLock = null;
        screenWakeLock = null;
    }

    private void resetState(boolean isRecording, boolean isRetranscribing) {
        busy = true;
        recording = isRecording;
        retranscribing = isRetranscribing;
        liveLagged = false;
        progress = 0;
        elapsedMs = 0L;
        remainingMs = isRecording ? durationLimitMs : -1L;
        recordedAudioMs = 0L;
        error = "";
        status = isRecording ? "Przygotowanie…" : "Przygotowanie pełnej transkrypcji…";
        preview = "";
        merger.clear();
        stopRequested.set(false);
        lastPublishAt = 0L;
        publish(true);
    }

    private void runCapture(Uri modelUri, int resultCode, Intent projectionData) {
        MediaProjection projection = null;
        AudioRecord recorder = null;
        Future<?> liveFuture = null;
        ArrayBlockingQueue<AudioChunk> queue = new ArrayBlockingQueue<>(2);
        try {
            acquireWakeLocks(durationLimitMs + 10 * 60_000L);
            status = "Ładowanie modelu…";
            publish(true);
            File modelFile = ModelCache.ensure(this, modelUri, stopRequested,
                    (p, s) -> { status = s; progress = Math.min(10, p / 10); publish(false); });
            if (stopRequested.get()) throw new InterruptedException("Anulowano");

            File txt = getTranscriptFile();
            if (txt.exists()) txt.delete();

            WhisperContext whisper = WhisperContext.create(modelFile.getAbsolutePath());
            activeContext = whisper;
            liveFuture = liveExecutor.submit(() -> processLiveQueue(queue, whisper));

            if (stopRequested.get()) throw new InterruptedException("Anulowano");
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, projectionData);
            if (projection == null) throw new IllegalStateException("Android nie przyznał MediaProjection");
            activeProjection = projection;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (recording) {
                        status = "System zakończył przechwytywanie dźwięku";
                        stopRequested.set(true);
                        publish(true);
                    }
                }
            }, mainHandler);

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(WavFile.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int minBuffer = AudioRecord.getMinBufferSize(
                    WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(16 * 1024, minBuffer > 0 ? minBuffer * 4 : 16 * 1024);
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Nie można uruchomić AudioPlaybackCapture na tym urządzeniu");
            }
            activeRecorder = recorder;

            File wav = getRecordingFile();
            LiveChunker chunker = new LiveChunker(queue);
            short[] readBuffer = new short[Math.max(2048, bufferBytes / 2)];
            long samplesWritten = 0L;

            try (WavFile.Writer writer = new WavFile.Writer(wav)) {
                recorder.startRecording();
                captureStartedAt = SystemClock.elapsedRealtime();
                recording = true;
                status = "Nagrywanie dźwięku telefonu + transkrypcja live";
                progress = 0;
                publish(true);

                while (!stopRequested.get()) {
                    long now = SystemClock.elapsedRealtime();
                    elapsedMs = Math.max(0L, now - captureStartedAt);
                    remainingMs = Math.max(0L, durationLimitMs - elapsedMs);
                    if (elapsedMs >= durationLimitMs) {
                        status = "Osiągnięto ustawiony czas — zatrzymywanie";
                        stopRequested.set(true);
                        break;
                    }

                    int n = recorder.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_BLOCKING);
                    if (n > 0) {
                        writer.write(readBuffer, n);
                        chunker.feed(readBuffer, n, samplesWritten);
                        samplesWritten += n;
                        recordedAudioMs = samplesWritten * 1000L / WavFile.SAMPLE_RATE;
                    } else if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("AudioRecord został zatrzymany przez system");
                    } else if (n < 0 && !stopRequested.get()) {
                        throw new IllegalStateException("Błąd AudioRecord: " + n);
                    }
                    progress = (int) Math.min(100L, elapsedMs * 100L / Math.max(1L, durationLimitMs));
                    publish(false);
                }
                chunker.finish(samplesWritten);
            } finally {
                recording = false;
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    recorder.release();
                    activeRecorder = null;
                }
            }

            // Timer/Stop ma zakończyć także transkrypcję, więc nie pozwalamy kolejce
            // rosnąć po zakończeniu nagrywania. Nagranie WAV pozostaje kompletne i
            // może być potem przepisane przyciskiem "Ponownie transkrybuj nagranie".
            if (!queue.isEmpty()) liveLagged = true;
            queue.clear();
            queue.offer(END);
            WhisperContext ctx = activeContext;
            if (ctx != null) ctx.requestAbort();
            if (liveFuture != null) {
                try { liveFuture.get(); } catch (Exception ignored) { liveLagged = true; }
            }
            try { whisper.close(); } catch (Exception ignored) {}
            activeContext = null;

            elapsedMs = Math.min(durationLimitMs, Math.max(elapsedMs, recordedAudioMs));
            remainingMs = 0L;
            progress = 100;
            busy = false;
            retranscribing = false;
            status = liveLagged
                    ? "Nagranie gotowe. Live mogło nie nadążyć — użyj pełnej transkrypcji nagrania."
                    : "Nagranie i transkrypcja live zakończone";
            preview = readTranscriptQuietly();
            publish(true);
            updateNotification("Nagranie zakończone", 100, false);
        } catch (InterruptedException e) {
            busy = false;
            recording = false;
            retranscribing = false;
            status = "Zatrzymano";
            preview = readTranscriptQuietly();
            publish(true);
            updateNotification("Zatrzymano", progress, false);
        } catch (Throwable t) {
            busy = false;
            recording = false;
            retranscribing = false;
            error = message(t);
            status = "Błąd";
            preview = readTranscriptQuietly();
            publish(true);
            updateNotification("Błąd przechwytywania", progress, false);
        } finally {
            if (recorder != null && activeRecorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                activeRecorder = null;
            }
            if (projection != null) {
                try { projection.stop(); } catch (Exception ignored) {}
            }
            activeProjection = null;
            releaseWakeLocks();
            stopForeground(false);
            stopSelf();
        }
    }

    private void processLiveQueue(ArrayBlockingQueue<AudioChunk> queue, WhisperContext whisper) {
        try (BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(getTranscriptFile(), true), StandardCharsets.UTF_8))) {
            while (true) {
                AudioChunk chunk = queue.take();
                if (chunk == END || chunk.startSample < 0L) break;
                if (stopRequested.get() && !recording) break;
                List<WhisperSegment> segments = whisper.transcribe(chunk.samples, "pl");
                if (stopRequested.get() && !recording) break;
                String chunkText = segmentsToText(segments);
                String delta = merger.append(chunkText);
                if (!delta.isEmpty()) {
                    if (getTranscriptFile().length() > 0L) txt.write(' ');
                    txt.write(delta);
                    txt.flush();
                    preview = merger.getText();
                    publish(false);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            liveLagged = true;
            if (error.isEmpty()) error = "Live Whisper: " + message(t);
            publish(true);
        }
    }

    private void runPostTranscription(Uri modelUri) {
        try {
            File wav = getRecordingFile();
            if (!wav.isFile() || wav.length() <= WavFile.HEADER_SIZE) {
                throw new IllegalStateException("Brak zapisanego nagrania WAV");
            }
            acquireWakeLocks(3 * 60 * 60_000L);
            status = "Ładowanie modelu…";
            publish(true);
            File modelFile = ModelCache.ensure(this, modelUri, stopRequested,
                    (p, s) -> { status = s; progress = Math.min(8, p * 8 / 100); publish(false); });
            if (stopRequested.get()) throw new InterruptedException("Anulowano");

            long totalSamples = WavFile.sampleCount(wav);
            if (totalSamples <= 0L) throw new IllegalStateException("Nagranie WAV jest puste");
            long step = (POST_CHUNK_SECONDS - POST_OVERLAP_SECONDS) * (long) WavFile.SAMPLE_RATE;
            int chunkSamples = POST_CHUNK_SECONDS * WavFile.SAMPLE_RATE;
            merger.clear();
            File txtFile = getTranscriptFile();
            try (WhisperContext whisper = WhisperContext.create(modelFile.getAbsolutePath());
                 BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(txtFile, false), StandardCharsets.UTF_8))) {
                activeContext = whisper;
                long started = SystemClock.elapsedRealtime();
                for (long start = 0L; start < totalSamples; start += step) {
                    if (stopRequested.get()) throw new InterruptedException("Anulowano");
                    float[] audio = WavFile.readSamples(wav, start, chunkSamples);
                    if (audio.length == 0) break;
                    status = "Pełna transkrypcja nagrania…";
                    List<WhisperSegment> segments = whisper.transcribe(audio, "pl");
                    String delta = merger.append(segmentsToText(segments));
                    if (!delta.isEmpty()) {
                        if (txtFile.length() > 0L) txt.write(' ');
                        txt.write(delta);
                        txt.flush();
                    }
                    long done = Math.min(totalSamples, start + audio.length);
                    progress = (int) Math.min(99L, done * 100L / totalSamples);
                    elapsedMs = SystemClock.elapsedRealtime() - started;
                    recordedAudioMs = totalSamples * 1000L / WavFile.SAMPLE_RATE;
                    preview = merger.getText();
                    publish(false);
                }
            } finally {
                activeContext = null;
            }
            progress = 100;
            busy = false;
            retranscribing = false;
            liveLagged = false;
            status = "Gotowe — pełna transkrypcja zapisanego nagrania";
            preview = merger.getText();
            publish(true);
            updateNotification("Pełna transkrypcja zakończona", 100, false);
        } catch (InterruptedException e) {
            busy = false;
            retranscribing = false;
            status = "Pełna transkrypcja anulowana";
            preview = readTranscriptQuietly();
            publish(true);
            updateNotification("Transkrypcja anulowana", progress, false);
        } catch (Throwable t) {
            busy = false;
            retranscribing = false;
            error = message(t);
            status = "Błąd";
            preview = readTranscriptQuietly();
            publish(true);
            updateNotification("Błąd transkrypcji", progress, false);
        } finally {
            releaseWakeLocks();
            stopForeground(false);
            stopSelf();
        }
    }

    private final class LiveChunker {
        private final ArrayBlockingQueue<AudioChunk> queue;
        private final int chunkSize = LIVE_CHUNK_SECONDS * WavFile.SAMPLE_RATE;
        private final int overlap = LIVE_OVERLAP_MS * WavFile.SAMPLE_RATE / 1000;
        private final float[] buffer = new float[chunkSize];
        private int fill;
        private long bufferStartSample;

        LiveChunker(ArrayBlockingQueue<AudioChunk> queue) {
            this.queue = queue;
        }

        void feed(short[] input, int count, long absoluteStartSample) {
            int offset = 0;
            while (offset < count) {
                if (fill == 0) bufferStartSample = absoluteStartSample + offset;
                int n = Math.min(count - offset, chunkSize - fill);
                for (int i = 0; i < n; i++) buffer[fill + i] = input[offset + i] / 32768f;
                fill += n;
                offset += n;
                if (fill == chunkSize) emitFull();
            }
        }

        private void emitFull() {
            float[] copy = buffer.clone();
            if (!isAlmostSilent(copy)) {
                if (!queue.offer(new AudioChunk(copy, bufferStartSample))) liveLagged = true;
            }
            if (overlap > 0) {
                System.arraycopy(buffer, chunkSize - overlap, buffer, 0, overlap);
                bufferStartSample += chunkSize - overlap;
                fill = overlap;
            } else {
                fill = 0;
            }
        }

        void finish(long totalSamples) {
            if (fill >= WavFile.SAMPLE_RATE / 2 && !stopRequested.get()) {
                float[] last = new float[fill];
                System.arraycopy(buffer, 0, last, 0, fill);
                if (!isAlmostSilent(last) && !queue.offer(new AudioChunk(last, bufferStartSample))) {
                    liveLagged = true;
                }
            }
        }
    }

    private static boolean isAlmostSilent(float[] audio) {
        if (audio.length == 0) return true;
        double sum = 0d;
        int stride = Math.max(1, audio.length / 16000);
        int n = 0;
        for (int i = 0; i < audio.length; i += stride) {
            double v = audio[i];
            sum += v * v;
            n++;
        }
        double rms = Math.sqrt(sum / Math.max(1, n));
        return rms < 0.00035d;
    }

    private static String segmentsToText(List<WhisperSegment> segments) {
        StringBuilder out = new StringBuilder();
        if (segments != null) {
            for (WhisperSegment segment : segments) {
                if (segment == null || segment.getText() == null) continue;
                String t = segment.getText().trim().replaceAll("\\s+", " ");
                if (t.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(t);
            }
        }
        return out.toString();
    }

    private void publish(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPublishAt < 350L) return;
        lastPublishAt = now;
        Snapshot snap = snapshot();
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(snap);
        });
        if (busy) updateNotification(status, progress, true);
    }

    private String readTranscriptQuietly() {
        try {
            File f = getTranscriptFile();
            if (!f.isFile()) return "";
            byte[] data = new byte[(int) Math.min(Integer.MAX_VALUE, f.length())];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(data, 0, off, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return preview == null ? "" : preview;
        }
    }

    private void beginForeground(String text, int p, boolean ongoing, boolean mediaProjection) {
        Notification notification = buildNotification(text, p, ongoing);
        if (mediaProjection && Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text, int p, boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text, p, ongoing));
    }

    private Notification buildNotification(String text, int p, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Whisper Live")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) b.setProgress(100, clamp(p, 0, 100), false);
        return b.build();
    }

    @Override
    public void onDestroy() {
        stopRequested.set(true);
        WhisperContext ctx = activeContext;
        if (ctx != null) ctx.requestAbort();
        AudioRecord r = activeRecorder;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
        MediaProjection p = activeProjection;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
        }
        releaseWakeLocks();
        controlExecutor.shutdownNow();
        liveExecutor.shutdownNow();
        super.onDestroy();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static final class AudioChunk {
        final float[] samples;
        final long startSample;

        AudioChunk(float[] samples, long startSample) {
            this.samples = samples;
            this.startSample = startSample;
        }
    }
}
