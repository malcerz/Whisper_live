package pl.whisperlive;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureService extends Service {
    static final String ACTION_START = "pl.whisperlive.START_CAPTURE";
    static final String ACTION_RETRANSCRIBE = "pl.whisperlive.RETRANSCRIBE";
    static final String ACTION_TRANSCRIBE_FILE = "pl.whisperlive.TRANSCRIBE_FILE";
    static final String EXTRA_MODEL_URI = "model_uri";
    static final String EXTRA_AUDIO_URI = "audio_uri";
    static final String EXTRA_AUDIO_NAME = "audio_name";
    static final String EXTRA_DURATION_MIN = "duration_min";
    static final String EXTRA_PROJECTION_RESULT_CODE = "projection_result_code";
    static final String EXTRA_PROJECTION_DATA = "projection_data";

    private static final int NOTIFICATION_ID = 7301;
    private static final String CHANNEL_ID = "whisper_live_capture";
    private static final int LIVE_CHUNK_SECONDS = 6;
    private static final int LIVE_OVERLAP_MS = 600;
    private static final int POST_CHUNK_SECONDS = 30;
    private static final int POST_OVERLAP_SECONDS = 2;
    private static final AudioChunk END = new AudioChunk(new float[0], -1L, 0);

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
        /** Trwa dekodowanie wczytanego pliku audio do WAV 16 kHz. */
        public final boolean decoding;
        /** Nagrywanie zakończone, Whisper kończy ostatnie fragmenty live. */
        public final boolean finishingLive;
        /** Ile audio Whisper już przetworzył (ms). */
        public final long processedAudioMs;
        /** Średnia prędkość: sekundy audio na sekundę pracy Whispera (1.0 = czas rzeczywisty); 0 = brak danych. */
        public final double speedAvg;
        /** Prędkość ostatniego fragmentu; 0 = brak danych. */
        public final double speedLast;

        Snapshot(String status, String preview, String error, boolean busy, boolean recording,
                 boolean retranscribing, boolean liveLagged, int progress, long elapsedMs,
                 long remainingMs, long recordedAudioMs, boolean hasRecording,
                 boolean hasTranscript, boolean decoding, boolean finishingLive,
                 long processedAudioMs, double speedAvg, double speedLast) {
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
            this.decoding = decoding;
            this.finishingLive = finishingLive;
            this.processedAudioMs = processedAudioMs;
            this.speedAvg = speedAvg;
            this.speedLast = speedLast;
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
    private volatile boolean decoding;
    private volatile boolean finishingLive;
    private volatile boolean abortLive;
    private volatile long processedAudioMs;
    private volatile double speedAvg;
    private volatile double speedLast;
    private volatile long transcribeStartedAt;
    private volatile int foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    private volatile String status = "Gotowy";
    private volatile String error = "";
    private volatile String preview = "";
    private long captureStartedAt;
    private long durationLimitMs;
    private volatile long lastPublishAt;
    private PowerManager.WakeLock partialWakeLock;
    private PowerManager.WakeLock screenWakeLock;

    /** Odświeża "czas pracy" co sekundę, także w trakcie długiego fragmentu. */
    private final Runnable transcribeTicker = new Runnable() {
        @Override
        public void run() {
            long startedAt = transcribeStartedAt;
            if (!busy || startedAt <= 0L) return;
            elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            publish(false);
            mainHandler.postDelayed(this, 1000L);
        }
    };

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
        String action = intent == null ? null : intent.getAction();
        if (action == null) return START_NOT_STICKY;
        if (busy) {
            // Każde startForegroundService() musi skończyć się wywołaniem startForeground(),
            // inaczej Android zabija aplikację (ForegroundServiceDidNotStartInTimeException).
            startForeground(NOTIFICATION_ID, buildNotification(status, progress, true), foregroundType);
            return START_NOT_STICKY;
        }
        String model = intent.getStringExtra(EXTRA_MODEL_URI);
        if (ACTION_START.equals(action)) {
            int minutes = clamp(intent.getIntExtra(EXTRA_DURATION_MIN, 30), 1, 120);
            int resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            if (model == null || projectionData == null || resultCode != Activity.RESULT_OK) {
                return rejectStart();
            }
            beginForeground("Przygotowanie przechwytywania…", true);
            durationLimitMs = minutes * 60_000L;
            resetState(true, false);
            controlExecutor.submit(() -> runCapture(Uri.parse(model), resultCode, projectionData));
            return START_NOT_STICKY;
        }
        if (ACTION_RETRANSCRIBE.equals(action)) {
            if (model == null) return rejectStart();
            beginForeground("Przygotowanie pełnej transkrypcji…", false);
            resetState(false, true);
            controlExecutor.submit(() -> runTranscription(Uri.parse(model), null, null));
            return START_NOT_STICKY;
        }
        if (ACTION_TRANSCRIBE_FILE.equals(action)) {
            String audio = intent.getStringExtra(EXTRA_AUDIO_URI);
            String name = intent.getStringExtra(EXTRA_AUDIO_NAME);
            if (model == null || audio == null) return rejectStart();
            beginForeground("Przygotowanie transkrypcji pliku…", false);
            resetState(false, true);
            controlExecutor.submit(() -> runTranscription(Uri.parse(model), Uri.parse(audio), name));
            return START_NOT_STICKY;
        }
        return rejectStart();
    }

    private int rejectStart() {
        beginForeground("Whisper Live", false);
        stopForeground(true);
        stopSelf();
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
                getTranscriptFile().isFile() && getTranscriptFile().length() > 0L,
                decoding, finishingLive, processedAudioMs, speedAvg, speedLast);
    }

    public void stopCurrent() {
        if (!busy) return;
        if (recording && !stopRequested.get()) {
            // Pierwszy Stop kończy tylko nagrywanie; Whisper dokańcza fragmenty, które już ma,
            // żeby końcówka nagrania nie znikała z transkrypcji. Drugi Stop przerywa od razu.
            status = "Zatrzymywanie nagrywania…";
            stopRequested.set(true);
            publish(true);
            return;
        }
        status = retranscribing ? "Anulowanie transkrypcji…" : "Przerywanie transkrypcji live…";
        stopRequested.set(true);
        abortLive = true;
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
        decoding = false;
        finishingLive = false;
        abortLive = false;
        processedAudioMs = 0L;
        speedAvg = 0d;
        speedLast = 0d;
        transcribeStartedAt = 0L;
        error = "";
        status = isRecording ? "Przygotowanie…" : "Przygotowanie transkrypcji…";
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
        WhisperContext whisper = null;
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

            whisper = WhisperContext.create(modelFile.getAbsolutePath());
            activeContext = whisper;
            final WhisperContext liveWhisper = whisper;
            liveFuture = liveExecutor.submit(() -> processLiveQueue(queue, liveWhisper));

            if (stopRequested.get()) throw new InterruptedException("Anulowano");
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, projectionData);
            if (projection == null) throw new IllegalStateException("Android nie przyznał MediaProjection");
            activeProjection = projection;
            final MediaProjection thisProjection = projection;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // Spóźniony onStop poprzedniej projekcji nie może zatrzymać nowego nagrania.
                    if (recording && activeProjection == thisProjection) {
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
                chunker.finish();
            } finally {
                recording = false;
            }
            releaseRecorder(recorder);
            recorder = null;

            // Nagrywanie skończone. Whisper dokańcza to, co już ma w kolejce (najwyżej
            // 3 fragmenty + końcówka), więc ostatnie sekundy trafiają do TXT. Kolejka jest
            // ograniczona, więc nie urośnie; drugi Stop przerywa natychmiast.
            finishingLive = true;
            status = "Kończenie transkrypcji live…";
            publish(true);
            finishLive(queue, liveFuture, false);
            liveFuture = null;
            finishingLive = false;
            try { whisper.close(); } catch (Exception ignored) {}
            whisper = null;
            activeContext = null;

            elapsedMs = Math.min(durationLimitMs, Math.max(elapsedMs, recordedAudioMs));
            remainingMs = 0L;
            progress = 100;
            busy = false;
            retranscribing = false;
            status = liveLagged
                    ? "Nagranie gotowe. Live mogło nie nadążyć — użyj pełnej transkrypcji nagrania."
                    : abortLive
                    ? "Nagranie gotowe. Transkrypcja live przerwana — końcówki może brakować."
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
            finishingLive = false;
            releaseRecorder(recorder);
            // Ścieżki błędu/anulowania: wątek live nie może zostać zablokowany na kolejce
            // (blokowałby wszystkie następne nagrania), a kontekst Whispera — wyciec.
            if (liveFuture != null) finishLive(queue, liveFuture, true);
            if (whisper != null) {
                try { whisper.close(); } catch (Exception ignored) {}
            }
            activeContext = null;
            MediaProjection p = projection;
            activeProjection = null;
            if (p != null) {
                try { p.stop(); } catch (Exception ignored) {}
            }
            releaseWakeLocks();
            stopForeground(false);
            stopSelf();
        }
    }

    private void releaseRecorder(AudioRecord recorder) {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
        if (activeRecorder == recorder) activeRecorder = null;
    }

    /**
     * Czeka, aż wątek live skończy pracę. Kontekstu Whispera nie wolno zwolnić, dopóki wątek
     * jest w kodzie natywnym, dlatego czekamy do skutku także po przerwaniu wątku sterującego.
     */
    private void finishLive(ArrayBlockingQueue<AudioChunk> queue, Future<?> liveFuture, boolean abort) {
        if (abort) {
            abortLive = true;
            WhisperContext ctx = activeContext;
            if (ctx != null) ctx.requestAbort();
            queue.clear();
        }
        boolean interrupted = false;
        boolean endQueued = false;
        while (!liveFuture.isDone()) {
            try {
                if (abortLive) queue.clear();
                if (!endQueued) {
                    endQueued = queue.offer(END, 200L, TimeUnit.MILLISECONDS);
                } else {
                    try {
                        liveFuture.get(200L, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException ignored) {
                    } catch (ExecutionException | CancellationException e) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                // Usługa jest niszczona: przerwij Whispera, ale poczekaj na wyjście z JNI.
                interrupted = true;
                abortLive = true;
                WhisperContext ctx = activeContext;
                if (ctx != null) ctx.requestAbort();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void processLiveQueue(ArrayBlockingQueue<AudioChunk> queue, WhisperContext whisper) {
        long whisperMs = 0L;
        long audioMs = 0L;
        boolean wroteAnything = false;
        try (BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(getTranscriptFile(), false), StandardCharsets.UTF_8))) {
            while (true) {
                AudioChunk chunk = takeChunk(queue);
                if (chunk == null || chunk == END || chunk.startSample < 0L) break;
                if (abortLive) break;
                long t0 = SystemClock.elapsedRealtime();
                List<WhisperSegment> segments = whisper.transcribe(chunk.samples, "pl");
                long took = Math.max(1L, SystemClock.elapsedRealtime() - t0);
                if (abortLive) break;

                long chunkAudioMs = chunk.advanceSamples * 1000L / WavFile.SAMPLE_RATE;
                whisperMs += took;
                audioMs += chunkAudioMs;
                speedLast = chunkAudioMs / (double) took;
                speedAvg = audioMs / (double) whisperMs;
                processedAudioMs = audioMs;

                String chunkText = segmentsToText(segments);
                String delta = merger.append(chunkText);
                if (!delta.isEmpty()) {
                    if (wroteAnything) txt.write(' ');
                    txt.write(delta);
                    txt.flush();
                    wroteAnything = true;
                    preview = merger.getText();
                }
                publish(false);
            }
        } catch (Throwable t) {
            liveLagged = true;
            if (error.isEmpty()) error = "Live Whisper: " + message(t);
            publish(true);
        }
    }

    /** Jak queue.take(), ale przerwanie wątku (niszczenie usługi) kończy pętlę zamiast rzucać. */
    private AudioChunk takeChunk(ArrayBlockingQueue<AudioChunk> queue) {
        while (true) {
            if (abortLive) return null;
            try {
                AudioChunk chunk = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (chunk != null) return chunk;
            } catch (InterruptedException e) {
                abortLive = true;
                return null;
            }
        }
    }

    /**
     * Pełna transkrypcja WAV 16 kHz mono. Gdy podano {@code audioUri}, najpierw dekoduje
     * wskazany plik audio do WAV (zastępując ostatnie nagranie), a potem go transkrybuje.
     */
    private void runTranscription(Uri modelUri, Uri audioUri, String audioName) {
        boolean fromFile = audioUri != null;
        String what = fromFile
                ? "Transkrypcja pliku" + (audioName == null || audioName.isEmpty() ? "" : " „" + audioName + "”")
                : "Pełna transkrypcja nagrania";
        try {
            File wav = getRecordingFile();
            if (!fromFile && (!wav.isFile() || wav.length() <= WavFile.HEADER_SIZE)) {
                throw new IllegalStateException("Brak zapisanego nagrania WAV");
            }
            acquireWakeLocks(6 * 60 * 60_000L);
            status = "Ładowanie modelu…";
            publish(true);
            File modelFile = ModelCache.ensure(this, modelUri, stopRequested,
                    (p, s) -> { status = s; progress = p; publish(false); });
            if (stopRequested.get()) throw new InterruptedException("Anulowano");

            if (fromFile) {
                // Dekodujemy do pliku tymczasowego: nieudany/anulowany import nie niszczy
                // poprzedniego nagrania.
                File temp = new File(getFilesDir(), "import.tmp.wav");
                decoding = true;
                progress = 0;
                status = "Dekodowanie pliku audio do 16 kHz mono…";
                publish(true);
                try {
                    long decoded = AudioDecoder.decodeToWav(this, audioUri, temp, stopRequested,
                            p -> { progress = p; status = "Dekodowanie pliku audio… " + p + "%"; publish(false); });
                    if (stopRequested.get()) throw new InterruptedException("Anulowano");
                    if (decoded <= 0L) throw new IllegalStateException("Plik nie zawiera dźwięku do transkrypcji");
                    if (wav.exists() && !wav.delete()) throw new IllegalStateException("Nie można zastąpić poprzedniego nagrania");
                    if (!temp.renameTo(wav)) throw new IllegalStateException("Nie można zapisać zdekodowanego nagrania");
                } finally {
                    decoding = false;
                    if (temp.exists()) temp.delete();
                }
                // Stara transkrypcja dotyczyła poprzedniego nagrania.
                File oldTxt = getTranscriptFile();
                if (oldTxt.exists()) oldTxt.delete();
            }

            long totalSamples = WavFile.sampleCount(wav);
            if (totalSamples <= 0L) throw new IllegalStateException("Nagranie WAV jest puste");
            long step = (POST_CHUNK_SECONDS - POST_OVERLAP_SECONDS) * (long) WavFile.SAMPLE_RATE;
            int chunkSamples = POST_CHUNK_SECONDS * WavFile.SAMPLE_RATE;
            recordedAudioMs = totalSamples * 1000L / WavFile.SAMPLE_RATE;
            progress = 0;
            status = "Ładowanie modelu do pamięci…";
            publish(true);
            merger.clear();
            File txtFile = getTranscriptFile();
            try (WhisperContext whisper = WhisperContext.create(modelFile.getAbsolutePath());
                 BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(txtFile, false), StandardCharsets.UTF_8))) {
                activeContext = whisper;
                if (stopRequested.get()) throw new InterruptedException("Anulowano");
                status = what + "…";
                long started = SystemClock.elapsedRealtime();
                transcribeStartedAt = started;
                mainHandler.postDelayed(transcribeTicker, 1000L);
                publish(true);

                long whisperMs = 0L;
                long measuredAudioMs = 0L;
                long doneSamples = 0L;
                boolean wroteAnything = false;
                for (long start = 0L; start < totalSamples; start += step) {
                    if (stopRequested.get()) throw new InterruptedException("Anulowano");
                    float[] audio = WavFile.readSamples(wav, start, chunkSamples);
                    if (audio.length == 0) break;

                    // Cisza: Whisper tylko halucynowałby tekst, więc fragment pomijamy
                    // (tak samo jak w trybie live) i nie wliczamy go do pomiaru prędkości.
                    boolean silent = isAlmostSilent(audio);
                    long t0 = SystemClock.elapsedRealtime();
                    List<WhisperSegment> segments = silent ? null : whisper.transcribe(audio, "pl");
                    long took = Math.max(1L, SystemClock.elapsedRealtime() - t0);
                    // Fragment przerwany przez Stop jest niepełny — nie dopisujemy go.
                    if (stopRequested.get()) throw new InterruptedException("Anulowano");

                    String delta = merger.append(segmentsToText(segments));
                    if (!delta.isEmpty()) {
                        if (wroteAnything) txt.write(' ');
                        txt.write(delta);
                        txt.flush();
                        wroteAnything = true;
                    }

                    long done = Math.min(totalSamples, start + audio.length);
                    long newAudioMs = (done - doneSamples) * 1000L / WavFile.SAMPLE_RATE;
                    doneSamples = done;
                    processedAudioMs = done * 1000L / WavFile.SAMPLE_RATE;
                    if (!silent) {
                        whisperMs += took;
                        measuredAudioMs += newAudioMs;
                        speedLast = newAudioMs / (double) took;
                        speedAvg = measuredAudioMs / (double) whisperMs;
                    }
                    progress = (int) Math.min(99L, done * 100L / totalSamples);
                    elapsedMs = SystemClock.elapsedRealtime() - started;
                    preview = merger.getText();
                    publish(false);
                    // Bez tego ostatni przebieg czytałby sam 2-sekundowy overlap, już przepisany.
                    if (done >= totalSamples) break;
                }
                elapsedMs = SystemClock.elapsedRealtime() - started;
            } finally {
                transcribeStartedAt = 0L;
                mainHandler.removeCallbacks(transcribeTicker);
                activeContext = null;
            }
            progress = 100;
            processedAudioMs = recordedAudioMs;
            busy = false;
            retranscribing = false;
            liveLagged = false;
            status = fromFile ? "Gotowe — transkrypcja pliku audio" : "Gotowe — pełna transkrypcja zapisanego nagrania";
            preview = merger.getText();
            publish(true);
            updateNotification("Transkrypcja zakończona", 100, false);
        } catch (InterruptedException e) {
            busy = false;
            retranscribing = false;
            status = "Transkrypcja anulowana";
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
            decoding = false;
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
        private int carried;   // ile próbek na początku bufora to overlap z poprzedniego fragmentu
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
                if (!queue.offer(new AudioChunk(copy, bufferStartSample, chunkSize - carried))) {
                    liveLagged = true;
                }
            }
            if (overlap > 0) {
                System.arraycopy(buffer, chunkSize - overlap, buffer, 0, overlap);
                bufferStartSample += chunkSize - overlap;
                fill = overlap;
            } else {
                fill = 0;
            }
            carried = fill;
        }

        /** Oddaje do transkrypcji niepełny ostatni fragment (końcówkę nagrania). */
        void finish() {
            // Warunek "!stopRequested" powodował, że końcówka nigdy nie była transkrybowana:
            // pętla nagrywania kończy się wyłącznie przy ustawionym stopRequested.
            if (fill - carried >= WavFile.SAMPLE_RATE / 2) {
                float[] last = new float[fill];
                System.arraycopy(buffer, 0, last, 0, fill);
                if (!isAlmostSilent(last)
                        && !queue.offer(new AudioChunk(last, bufferStartSample, fill - carried))) {
                    liveLagged = true;
                }
            }
            fill = 0;
            carried = 0;
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

    private void beginForeground(String text, boolean mediaProjection) {
        // Transkrypcja pliku/nagrania nie ma tokenu MediaProjection, więc dostaje typ dataSync
        // (typ mediaProjection bez zgody użytkownika jest odrzucany przez nowsze Androidy).
        foregroundType = mediaProjection
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        startForeground(NOTIFICATION_ID, buildNotification(text, 0, true), foregroundType);
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
        abortLive = true;
        mainHandler.removeCallbacks(transcribeTicker);
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
        /** Ile nowych próbek (bez overlapu z poprzednim fragmentem) niesie ten fragment. */
        final int advanceSamples;

        AudioChunk(float[] samples, long startSample, int advanceSamples) {
            this.samples = samples;
            this.startSample = startSample;
            this.advanceSamples = advanceSamples;
        }
    }
}
