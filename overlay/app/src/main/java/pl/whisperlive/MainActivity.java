package pl.whisperlive;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

public final class MainActivity extends Activity implements CaptureService.Listener, FileTranscriptionService.Listener {
    private static final int PICK_MODEL = 2001;
    private static final int CAPTURE_PERMISSION = 2002;
    private static final int SAVE_TXT = 2003;
    private static final int SAVE_WAV = 2004;
    private static final int REQUEST_RECORD_AUDIO = 2005;
    private static final int PICK_AUDIO = 2006;
    private static final int SAVE_IMPORT_TXT = 2007;
    private static final int SAVE_IMPORT_SRT = 2008;

    private static final String PREFS = "whisper_live_ui";
    private static final String PREF_MODEL_URI = "model_uri";
    private static final String PREF_DURATION = "duration_min";

    private Uri modelUri;
    private CaptureService service;
    private boolean bound;
    private boolean pendingStartAfterPermission;
    private FileTranscriptionService fileService;
    private boolean fileBound;
    private boolean importMode;

    private TextView modelLabel;
    private TextView statusLabel;
    private TextView timerLabel;
    private TextView warningLabel;
    private TextView preview;
    private ProgressBar progress;
    private NumberPicker durationPicker;
    private Button startButton;
    private Button stopButton;
    private Button retranscribeButton;
    private Button saveTxtButton;
    private Button saveWavButton;
    private Button importAudioButton;
    private Button saveImportTxtButton;
    private Button saveImportSrtButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            CaptureService.LocalBinder local = (CaptureService.LocalBinder) binder;
            service = local.getService();
            bound = true;
            service.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.clearListener(MainActivity.this);
            service = null;
            bound = false;
        }
    };

    private final ServiceConnection fileConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            FileTranscriptionService.LocalBinder local = (FileTranscriptionService.LocalBinder) binder;
            fileService = local.getService();
            fileBound = true;
            fileService.setListener(MainActivity.this);
            updateIdleButtons();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            fileService = null;
            fileBound = false;
            updateIdleButtons();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreSettings();
        updateModelLabel();
        updateIdleButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, CaptureService.class), connection, Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, FileTranscriptionService.class), fileConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (bound && service != null) {
            service.clearListener(this);
            unbindService(connection);
        }
        bound = false;
        service = null;
        if (fileBound && fileService != null) {
            fileService.clearListener(this);
            unbindService(fileConnection);
        }
        fileBound = false;
        fileService = null;
        super.onStop();
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(text("Whisper Live", 28, true));
        TextView intro = text(
                "Nagrywa dźwięk odtwarzany przez telefon i równolegle zapisuje transkrypcję TXT przez whisper.cpp.",
                15, false);
        intro.setPadding(0, dp(4), 0, dp(12));
        root.addView(intro);

        TextView limitation = text(
                "Android poprosi o zgodę na nagrywanie/udostępnianie ekranu. Aplikacja używa tej zgody wyłącznie do AudioPlaybackCapture. " +
                        "Niektóre aplikacje blokują przechwytywanie dźwięku (np. DRM, rozmowy i treści chronione).",
                13, false);
        limitation.setPadding(0, 0, 0, dp(16));
        root.addView(limitation);

        Button pickModel = button("Wybierz model Whisper .bin");
        pickModel.setOnClickListener(v -> pickModel());
        root.addView(pickModel);
        modelLabel = text("Nie wybrano modelu", 14, false);
        modelLabel.setPadding(0, dp(4), 0, dp(14));
        root.addView(modelLabel);

        TextView fileTitle = text("Transkrypcja plików audio — offline", 19, true);
        fileTitle.setPadding(0, dp(4), 0, dp(6));
        root.addView(fileTitle);
        importAudioButton = button("Wczytaj plik audio (MP3 / M4A / M4B / WAV)");
        importAudioButton.setOnClickListener(v -> pickAudio());
        root.addView(importAudioButton);
        TextView fileDescription = text("Wybierz lokalny plik. Dekodowanie i Whisper pracują na telefonie, bez internetu i bez zgody na przechwytywanie ekranu.", 13, false);
        fileDescription.setPadding(0, 0, 0, dp(7));
        root.addView(fileDescription);
        LinearLayout importSaveRow = new LinearLayout(this);
        importSaveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveImportTxtButton = button("Zapisz plik TXT");
        saveImportSrtButton = button("Zapisz napisy SRT");
        saveImportTxtButton.setOnClickListener(v -> createImportOutput(false));
        saveImportSrtButton.setOnClickListener(v -> createImportOutput(true));
        importSaveRow.addView(saveImportTxtButton, weighted());
        importSaveRow.addView(saveImportSrtButton, weighted());
        root.addView(importSaveRow);

        TextView durationLabel = text("Automatyczne zakończenie nagrywania i live-transkrypcji (1–120 min)", 14, true);
        root.addView(durationLabel);
        durationPicker = new NumberPicker(this);
        durationPicker.setMinValue(1);
        durationPicker.setMaxValue(120);
        durationPicker.setWrapSelectorWheel(false);
        root.addView(durationPicker, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(90)));

        startButton = button("START — przechwytuj dźwięk telefonu");
        startButton.setOnClickListener(v -> startRequested());
        root.addView(startButton);

        stopButton = button("STOP");
        stopButton.setOnClickListener(v -> {
            if (fileService != null && fileService.snapshot().busy) fileService.stopCurrent();
            else if (service != null) service.stopCurrent();
        });
        root.addView(stopButton);

        statusLabel = text("Gotowy", 16, true);
        statusLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(statusLabel);
        timerLabel = text("", 14, false);
        root.addView(timerLabel);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        warningLabel = text("", 14, true);
        warningLabel.setPadding(0, dp(6), 0, dp(8));
        root.addView(warningLabel);

        retranscribeButton = button("Ponownie transkrybuj całe zapisane nagranie");
        retranscribeButton.setOnClickListener(v -> retranscribeRecording());
        root.addView(retranscribeButton);

        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveTxtButton = button("Zapisz TXT");
        saveWavButton = button("Zapisz WAV");
        saveTxtButton.setOnClickListener(v -> createOutput(false));
        saveWavButton.setOnClickListener(v -> createOutput(true));
        saveRow.addView(saveTxtButton, weighted());
        saveRow.addView(saveWavButton, weighted());
        root.addView(saveRow);

        TextView transcriptTitle = text("Transkrypcja", 19, true);
        transcriptTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(transcriptTitle);
        preview = text("", 15, false);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(8), dp(8), dp(8), dp(24));
        root.addView(preview);

        setContentView(scroll);
    }

    private void startRequested() {
        if (fileService != null && fileService.snapshot().busy) {
            Toast.makeText(this, "Najpierw zakończ transkrypcję pliku", Toast.LENGTH_SHORT).show();
            return;
        }
        importMode = false;
        if (modelUri == null) {
            Toast.makeText(this, "Najpierw wybierz model Whisper .bin", Toast.LENGTH_LONG).show();
            return;
        }
        if (service != null && service.snapshot().busy) {
            Toast.makeText(this, "Aplikacja już pracuje", Toast.LENGTH_SHORT).show();
            return;
        }
        persistDuration();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        requestProjectionPermission();
    }

    private void requestProjectionPermission() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), CAPTURE_PERMISSION);
    }

    private void startCaptureService(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, CaptureService.class);
        serviceIntent.setAction(CaptureService.ACTION_START);
        serviceIntent.putExtra(CaptureService.EXTRA_MODEL_URI, modelUri.toString());
        serviceIntent.putExtra(CaptureService.EXTRA_DURATION_MIN, durationPicker.getValue());
        serviceIntent.putExtra(CaptureService.EXTRA_PROJECTION_RESULT_CODE, resultCode);
        serviceIntent.putExtra(CaptureService.EXTRA_PROJECTION_DATA, data);
        startForegroundService(serviceIntent);
        statusLabel.setText("Uruchamianie…");
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void retranscribeRecording() {
        if (fileService != null && fileService.snapshot().busy) {
            Toast.makeText(this, "Najpierw zakończ transkrypcję pliku", Toast.LENGTH_SHORT).show();
            return;
        }
        importMode = false;
        if (modelUri == null) {
            Toast.makeText(this, "Wybierz model Whisper .bin", Toast.LENGTH_SHORT).show();
            return;
        }
        File wav = new File(getFilesDir(), "last_capture.wav");
        if (!wav.isFile() || wav.length() <= WavFile.HEADER_SIZE) {
            Toast.makeText(this, "Brak zapisanego nagrania", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class);
        i.setAction(CaptureService.ACTION_RETRANSCRIBE);
        i.putExtra(CaptureService.EXTRA_MODEL_URI, modelUri.toString());
        startForegroundService(i);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        statusLabel.setText("Uruchamianie pełnej transkrypcji…");
        updateIdleButtons();
    }

    private void pickAudio() {
        if (modelUri == null) {
            Toast.makeText(this, "Najpierw wybierz model Whisper .bin", Toast.LENGTH_LONG).show();
            return;
        }
        if ((fileService != null && fileService.snapshot().busy)
                || (service != null && service.snapshot().busy)) {
            Toast.makeText(this, "Aplikacja już pracuje", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // Some providers label M4B as application/octet-stream.
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_AUDIO);
    }

    private void startImport(Uri uri) {
        String title = displayName(uri);
        String lower = title.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".mp3") || lower.endsWith(".m4a")
                || lower.endsWith(".m4b") || lower.endsWith(".wav")
                || lower.endsWith(".wave"))) {
            Toast.makeText(this, "Wybierz plik MP3, M4A, M4B lub WAV", Toast.LENGTH_LONG).show();
            return;
        }
        if ((fileService != null && fileService.snapshot().busy)
                || (service != null && service.snapshot().busy)) {
            Toast.makeText(this, "Najpierw zakończ bieżące zadanie", Toast.LENGTH_SHORT).show();
            return;
        }
        importMode = true;
        Intent request = new Intent(this, FileTranscriptionService.class);
        request.setAction(FileTranscriptionService.ACTION_IMPORT);
        request.putExtra(FileTranscriptionService.EXTRA_AUDIO_URI, uri.toString());
        request.putExtra(FileTranscriptionService.EXTRA_MODEL_URI, modelUri.toString());
        startForegroundService(request);
        statusLabel.setText("Uruchamianie transkrypcji: " + title);
        progress.setProgress(0);
        startButton.setEnabled(false);
        retranscribeButton.setEnabled(false);
        importAudioButton.setEnabled(false);
        stopButton.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "audio" : last;
    }

    private void createImportOutput(boolean subtitles) {
        File source = new File(getFilesDir(), subtitles ? "last_import.srt" : "last_import.txt");
        if (!source.isFile() || source.length() <= 0L) {
            Toast.makeText(this, "Brak gotowej transkrypcji pliku", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(subtitles ? "application/x-subrip" : "text/plain");
        i.putExtra(Intent.EXTRA_TITLE, subtitles ? "whisper-import.srt" : "whisper-import.txt");
        startActivityForResult(i, subtitles ? SAVE_IMPORT_SRT : SAVE_IMPORT_TXT);
    }

    private void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_MODEL);
    }

    private void createOutput(boolean wav) {
        File source = new File(getFilesDir(), wav ? "last_capture.wav" : "last_capture.txt");
        if (!source.isFile() || source.length() == 0L) {
            Toast.makeText(this, "Brak pliku do zapisania", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(wav ? "audio/wav" : "text/plain");
        i.putExtra(Intent.EXTRA_TITLE, wav ? "whisper-live-recording.wav" : "whisper-live-transcript.txt");
        startActivityForResult(i, wav ? SAVE_WAV : SAVE_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MODEL && resultCode == RESULT_OK && data != null && data.getData() != null) {
            modelUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(modelUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_MODEL_URI, modelUri.toString()).apply();
            updateModelLabel();
            updateIdleButtons();
            return;
        }
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            startImport(uri);
            return;
        }
        if (requestCode == CAPTURE_PERMISSION) {
            if (resultCode == RESULT_OK && data != null) {
                startCaptureService(resultCode, data);
            } else {
                Toast.makeText(this, "Bez zgody MediaProjection Android nie pozwala przechwycić dźwięku aplikacji.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if ((requestCode == SAVE_IMPORT_TXT || requestCode == SAVE_IMPORT_SRT)
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            File source = new File(getFilesDir(), requestCode == SAVE_IMPORT_SRT
                    ? "last_import.srt" : "last_import.txt");
            try {
                copyFile(source, data.getData());
                Toast.makeText(this, "Zapisano transkrypcję pliku", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Błąd zapisu: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if ((requestCode == SAVE_TXT || requestCode == SAVE_WAV)
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            File source = new File(getFilesDir(), requestCode == SAVE_WAV ? "last_capture.wav" : "last_capture.txt");
            try {
                copyFile(source, data.getData());
                Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Błąd zapisu: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingStartAfterPermission) {
            pendingStartAfterPermission = false;
            requestProjectionPermission();
        } else {
            pendingStartAfterPermission = false;
            Toast.makeText(this,
                    "Android wymaga uprawnienia RECORD_AUDIO także dla AudioPlaybackCapture. Mikrofon nie jest używany.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onState(CaptureService.Snapshot s) {
        runOnUiThread(() -> {
            if (importMode) {
                updateIdleButtons();
                return;
            }
            statusLabel.setText(s.error.isEmpty() ? s.status : s.status + ": " + s.error);
            progress.setProgress(s.progress);
            preview.setText(s.preview);
            if (s.recording) {
                timerLabel.setText("Minęło: " + formatDuration(s.elapsedMs) +
                        "   |   Pozostało: " + formatDuration(s.remainingMs) +
                        "   |   nagrano audio: " + formatDuration(s.recordedAudioMs));
            } else if (s.retranscribing) {
                timerLabel.setText("Pełna transkrypcja: " + s.progress + "%   |   czas pracy: " + formatDuration(s.elapsedMs));
            } else {
                timerLabel.setText(s.recordedAudioMs > 0L
                        ? "Ostatnie nagranie: " + formatDuration(s.recordedAudioMs) : "");
            }
            warningLabel.setText(s.liveLagged
                    ? "Live Whisper nie nadążył za nagraniem. WAV jest kompletny — użyj „Ponownie transkrybuj całe zapisane nagranie”."
                    : "");
            boolean fileBusy = fileService != null && fileService.snapshot().busy;
            startButton.setEnabled(!s.busy && !fileBusy && modelUri != null);
            importAudioButton.setEnabled(!s.busy && !fileBusy && modelUri != null);
            stopButton.setEnabled(s.busy || fileBusy);
            retranscribeButton.setEnabled(!s.busy && !fileBusy && s.hasRecording && modelUri != null);
            saveTxtButton.setEnabled(!s.busy && s.hasTranscript);
            saveWavButton.setEnabled(!s.busy && s.hasRecording);
            durationPicker.setEnabled(!s.busy && !fileBusy);
            saveImportTxtButton.setEnabled(!s.busy && !fileBusy && new File(getFilesDir(), "last_import.txt").length() > 0);
            saveImportSrtButton.setEnabled(!s.busy && !fileBusy && new File(getFilesDir(), "last_import.srt").length() > 0);
            if (s.busy || fileBusy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @Override
    public void onState(FileTranscriptionService.Snapshot s) {
        runOnUiThread(() -> {
            if (s.busy) importMode = true;
            if (importMode) {
                statusLabel.setText(s.error.isEmpty() ? s.status : s.status + ": " + s.error);
                timerLabel.setText("Audio: " + formatDuration(s.audioMs)
                        + "   |   czas pracy: " + formatDuration(s.elapsedMs));
                progress.setProgress(s.progress);
                preview.setText(s.preview);
                warningLabel.setText("");
            }
            updateIdleButtons();
            boolean capturing = service != null && service.snapshot().busy;
            if (s.busy || capturing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private void restoreSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String model = prefs.getString(PREF_MODEL_URI, "");
        if (!model.isEmpty()) modelUri = Uri.parse(model);
        durationPicker.setValue(Math.max(1, Math.min(120, prefs.getInt(PREF_DURATION, 30))));
    }

    private void persistDuration() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_DURATION, durationPicker.getValue()).apply();
    }

    private void updateModelLabel() {
        modelLabel.setText(modelUri == null ? "Nie wybrano modelu" : modelUri.getLastPathSegment());
    }

    private void updateIdleButtons() {
        boolean busy = (service != null && service.snapshot().busy)
                || (fileService != null && fileService.snapshot().busy);
        File wav = new File(getFilesDir(), "last_capture.wav");
        File txt = new File(getFilesDir(), "last_capture.txt");
        startButton.setEnabled(!busy && modelUri != null);
        importAudioButton.setEnabled(!busy && modelUri != null);
        stopButton.setEnabled(busy);
        retranscribeButton.setEnabled(!busy && modelUri != null && wav.isFile() && wav.length() > WavFile.HEADER_SIZE);
        saveWavButton.setEnabled(!busy && wav.isFile() && wav.length() > WavFile.HEADER_SIZE);
        saveTxtButton.setEnabled(!busy && txt.isFile() && txt.length() > 0L);
        durationPicker.setEnabled(!busy);
        saveImportTxtButton.setEnabled(!busy && new File(getFilesDir(), "last_import.txt").length() > 0);
        saveImportSrtButton.setEnabled(!busy && new File(getFilesDir(), "last_import.srt").length() > 0);
    }

    private void copyFile(File source, Uri destination) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            if (out == null) throw new IOException("Nie można otworzyć pliku docelowego");
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.flush();
        }
    }

    private String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (h > 0L) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
