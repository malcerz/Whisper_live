package pl.whisperlive;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stream system-decoded audio as mono 16 kHz float PCM. Does not retain the
 * compressed source or the whole decoded recording in application memory.
 */
final class ImportedAudioDecoder {
    interface Samples {
        void accept(float[] mono, int count, long durationUs) throws Exception;
    }

    private ImportedAudioDecoder() {}

    static long stream(Context context, Uri uri, AtomicBoolean cancelled, Samples receiver)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
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
            if (track < 0 || inputFormat == null) {
                throw new IOException("Nie znaleziono ścieżki audio w pliku");
            }
            extractor.selectTrack(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : -1L;
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (sampleRate <= 0 || channels <= 0 || channels > 32) {
                throw new IOException("Nieobsługiwane parametry ścieżki audio");
            }

            Resampler resampler = new Resampler(sampleRate, receiver, durationUs);
            // Some WAV extractors provide decoded audio/raw already. No MediaCodec
            // is needed in that case.
            if ("audio/raw".equals(mime)) {
                int encoding = inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ? inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        : AudioFormat.ENCODING_PCM_16BIT;
                ByteBuffer data = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder());
                while (true) {
                    checkCancelled(cancelled);
                    data.clear();
                    int n = extractor.readSampleData(data, 0);
                    if (n < 0) break;
                    data.position(0);
                    data.limit(n);
                    decodePcm(data, encoding, channels, resampler);
                    extractor.advance();
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(inputFormat, null, null, 0);
                codec.start();
                boolean inputEnded = false;
                boolean outputEnded = false;
                int outputRate = sampleRate;
                int outputChannels = channels;
                int outputEncoding = AudioFormat.ENCODING_PCM_16BIT;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (!outputEnded) {
                    checkCancelled(cancelled);
                    if (!inputEnded) {
                        int inputIndex = codec.dequeueInputBuffer(10000);
                        if (inputIndex >= 0) {
                            ByteBuffer input = codec.getInputBuffer(inputIndex);
                            if (input == null) throw new IOException("Brak bufora wejściowego dekodera");
                            input.clear();
                            int read = extractor.readSampleData(input, 0);
                            if (read < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                long time = Math.max(0L, extractor.getSampleTime());
                                codec.queueInputBuffer(inputIndex, 0, read, time, 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outputFormat = codec.getOutputFormat();
                        int rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int count = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (rate != outputRate && resampler.getInputFrames() > 0) {
                            throw new IOException("Zmiana częstotliwości audio w trakcie dekodowania");
                        }
                        outputRate = rate;
                        outputChannels = count;
                        outputEncoding = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                : AudioFormat.ENCODING_PCM_16BIT;
                        resampler.setRate(outputRate);
                    } else if (outputIndex >= 0) {
                        try {
                            if (info.size > 0) {
                                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                                if (output == null) throw new IOException("Brak bufora wyjściowego dekodera");
                                output.order(ByteOrder.nativeOrder());
                                output.position(info.offset);
                                output.limit(info.offset + info.size);
                                decodePcm(output, outputEncoding, outputChannels, resampler);
                            }
                            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false);
                        }
                    }
                }
            }
            resampler.finish();
            if (resampler.getOutputSamples() == 0L) {
                throw new IOException("Dekoder nie zwrócił próbek audio");
            }
            return resampler.getOutputSamples();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }

    private static void decodePcm(ByteBuffer input, int encoding, int channels,
                                  Resampler resampler) throws Exception {
        if (channels < 1 || channels > 32) throw new IOException("Nieobsługiwana liczba kanałów");
        int sampleBytes;
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) sampleBytes = 2;
        else if (encoding == AudioFormat.ENCODING_PCM_FLOAT
                || encoding == AudioFormat.ENCODING_PCM_32BIT) sampleBytes = 4;
        else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) sampleBytes = 3;
        else if (encoding == AudioFormat.ENCODING_PCM_8BIT) sampleBytes = 1;
        else throw new IOException("Nieobsługiwany format PCM: " + encoding);
        int frames = input.remaining() / (sampleBytes * channels);
        for (int i = 0; i < frames; i++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                float value;
                if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                    value = input.getShort() / 32768f;
                } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    value = input.getFloat();
                    if (!Float.isFinite(value)) value = 0f;
                } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                    value = ((input.get() & 0xff) - 128) / 128f;
                } else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                    int a = input.get() & 0xff;
                    int b = input.get() & 0xff;
                    int d = input.get();
                    value = ((d << 16) | (b << 8) | a) / 8388608f;
                } else {
                    value = input.getInt() / 2147483648f;
                }
                sum += value;
            }
            resampler.add(Math.max(-1f, Math.min(1f, sum / channels)));
        }
        if (input.remaining() > 0) {
            throw new IOException("Niepełna ramka danych PCM");
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if (cancelled.get()) throw new InterruptedException("Anulowano");
    }

    private static final class Resampler {
        private static final int OUT_RATE = 16000;
        private final Samples consumer;
        private final long durationUs;
        private final float[] output = new float[8192];
        private int outputCount;
        private int inputRate;
        private long inputFrames;
        private long outputSamples;
        private double nextOutputAt;
        private float previous;

        Resampler(int inputRate, Samples consumer, long durationUs) {
            this.inputRate = inputRate;
            this.consumer = consumer;
            this.durationUs = durationUs;
        }

        long getInputFrames() { return inputFrames; }
        long getOutputSamples() { return outputSamples; }

        void setRate(int rate) throws IOException {
            if (rate <= 0) throw new IOException("Nieprawidłowe próbkowanie");
            inputRate = rate;
        }

        void add(float value) throws Exception {
            long current = inputFrames++;
            if (current == 0) {
                previous = value;
            }
            while (nextOutputAt <= current) {
                double fraction = current == 0 ? 0d : nextOutputAt - (current - 1d);
                float interpolated = current == 0
                        ? value : (float) (previous + fraction * (value - previous));
                output[outputCount++] = interpolated;
                outputSamples++;
                if (outputCount == output.length) flush();
                nextOutputAt += inputRate / (double) OUT_RATE;
            }
            previous = value;
        }

        void finish() throws Exception { flush(); }

        private void flush() throws Exception {
            if (outputCount > 0) {
                consumer.accept(output, outputCount, durationUs);
                outputCount = 0;
            }
        }
    }
}
