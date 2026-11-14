package pl.whisperlive;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dekoduje dowolny plik audio/wideo obsługiwany przez Androida (MP3, M4A/AAC, OGG, OPUS,
 * FLAC, WAV, AMR, ścieżka audio z MP4/MKV/WEBM…) do WAV 16 kHz mono PCM16,
 * czyli do formatu, którego oczekuje whisper.cpp.
 */
final class AudioDecoder {
    // Wartości AudioFormat.ENCODING_PCM_24BIT_PACKED / ENCODING_PCM_32BIT (API 31).
    private static final int ENCODING_PCM_24BIT_PACKED = 21;
    private static final int ENCODING_PCM_32BIT = 22;
    private static final long TIMEOUT_US = 10_000L;

    interface Progress {
        void onProgress(int percent);
    }

    private AudioDecoder() {}

    /** @return liczba zapisanych próbek 16 kHz mono */
    static long decodeToWav(Context context, Uri source, File target,
                            AtomicBoolean cancelled, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        long written = 0L;
        try {
            try {
                extractor.setDataSource(context, source, null);
            } catch (Exception e) {
                throw new IOException("Nie można otworzyć pliku audio (nieobsługiwany format lub brak dostępu)");
            }
            int track = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    inputFormat = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("Plik nie zawiera ścieżki audio");
            extractor.selectTrack(track);

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : -1L;
            int sampleRate = intOr(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 44100);
            int channels = Math.max(1, intOr(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 1));
            int encoding = intOr(inputFormat, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

            try {
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(inputFormat, null, null, 0);
                codec.start();
            } catch (Exception e) {
                throw new IOException("Brak dekodera dla formatu " + mime);
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int idleAfterInputEnd = 0;
            int lastPercent = -1;
            Resampler resampler = null;
            int resamplerRate = 0;
            float[] mono = new float[0];
            short[] pcm = new short[0];

            try (WavFile.Writer writer = new WavFile.Writer(target)) {
                while (!outputDone) {
                    if (cancelled != null && cancelled.get()) throw new InterruptedException("Anulowano");

                    if (!inputDone) {
                        int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                            int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        sampleRate = intOr(of, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                        channels = Math.max(1, intOr(of, MediaFormat.KEY_CHANNEL_COUNT, channels));
                        encoding = intOr(of, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                    } else if (outIndex >= 0) {
                        idleAfterInputEnd = 0;
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null && info.size > 0) {
                            int bytesPerSample = bytesPerSample(encoding);
                            int frames = info.size / (bytesPerSample * channels);
                            if (frames > 0) {
                                if (mono.length < frames) mono = new float[frames];
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + frames * bytesPerSample * channels);
                                downmix(outBuf.slice().order(ByteOrder.nativeOrder()),
                                        encoding, channels, frames, mono);

                                if (resampler == null || resamplerRate != sampleRate) {
                                    if (resampler != null) {
                                        // Zmiana formatu w trakcie strumienia: domknij poprzedni filtr.
                                        short[] tail = new short[resampler.maxOutput(0)];
                                        int t = resampler.flush(tail);
                                        writer.write(tail, t);
                                        written += t;
                                    }
                                    resampler = new Resampler(sampleRate, WavFile.SAMPLE_RATE);
                                    resamplerRate = sampleRate;
                                }
                                int need = resampler.maxOutput(frames);
                                if (pcm.length < need) pcm = new short[need];
                                int n = resampler.process(mono, frames, pcm);
                                writer.write(pcm, n);
                                written += n;
                            }
                        }
                        if (progress != null && durationUs > 0L) {
                            int percent = (int) Math.max(0L, Math.min(99L,
                                    info.presentationTimeUs * 100L / durationUs));
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                progress.onProgress(percent);
                            }
                        }
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(outIndex, false);
                        if (eos) outputDone = true;
                    } else if (inputDone && ++idleAfterInputEnd > 500) {
                        // Dekoder nie zgłosił końca strumienia — nie czekaj w nieskończoność.
                        outputDone = true;
                    }
                }
                if (resampler != null) {
                    short[] tail = new short[resampler.maxOutput(0)];
                    int t = resampler.flush(tail);
                    writer.write(tail, t);
                    written += t;
                }
            }
            if (progress != null) progress.onProgress(100);
            return written;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static int bytesPerSample(int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT: return 1;
            case ENCODING_PCM_24BIT_PACKED: return 3;
            case ENCODING_PCM_32BIT:
            case AudioFormat.ENCODING_PCM_FLOAT: return 4;
            default: return 2;
        }
    }

    private static void downmix(ByteBuffer in, int encoding, int channels, int frames, float[] out) {
        float inv = 1f / channels;
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                switch (encoding) {
                    case AudioFormat.ENCODING_PCM_8BIT:
                        sum += ((in.get() & 0xff) - 128) / 128f;
                        break;
                    case ENCODING_PCM_24BIT_PACKED: {
                        int b0 = in.get() & 0xff;
                        int b1 = in.get() & 0xff;
                        int b2 = in.get();
                        int v = in.order() == ByteOrder.LITTLE_ENDIAN
                                ? (b2 << 16) | (b1 << 8) | b0
                                : ((byte) b0 << 16) | (b1 << 8) | (b2 & 0xff);
                        sum += v / 8388608f;
                        break;
                    }
                    case ENCODING_PCM_32BIT:
                        sum += in.getInt() / 2147483648f;
                        break;
                    case AudioFormat.ENCODING_PCM_FLOAT:
                        sum += in.getFloat();
                        break;
                    default:
                        sum += in.getShort() / 32768f;
                        break;
                }
            }
            out[f] = sum * inv;
        }
    }

    private static int intOr(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
