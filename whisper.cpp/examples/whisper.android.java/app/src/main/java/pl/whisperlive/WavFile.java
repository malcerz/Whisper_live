package pl.whisperlive;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class WavFile {
    static final int SAMPLE_RATE = 16000;
    static final int CHANNELS = 1;
    static final int BITS_PER_SAMPLE = 16;
    static final int HEADER_SIZE = 44;

    private WavFile() {}

    static final class Writer implements AutoCloseable {
        private final RandomAccessFile out;
        private long dataBytes;
        private boolean closed;

        Writer(File file) throws IOException {
            out = new RandomAccessFile(file, "rw");
            out.setLength(0L);
            writeHeader(out, 0L);
        }

        void write(short[] samples, int count) throws IOException {
            if (count <= 0) return;
            byte[] bytes = new byte[count * 2];
            int j = 0;
            for (int i = 0; i < count; i++) {
                short v = samples[i];
                bytes[j++] = (byte) (v & 0xff);
                bytes[j++] = (byte) ((v >>> 8) & 0xff);
            }
            out.write(bytes);
            dataBytes += bytes.length;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            out.seek(0L);
            writeHeader(out, dataBytes);
            out.getFD().sync();
            out.close();
        }
    }

    static long sampleCount(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            validate(in);
            return Math.max(0L, (in.length() - HEADER_SIZE) / 2L);
        }
    }

    static float[] readSamples(File file, long startSample, int maxSamples) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            validate(in);
            long total = Math.max(0L, (in.length() - HEADER_SIZE) / 2L);
            if (startSample >= total) return new float[0];
            int count = (int) Math.min((long) maxSamples, total - startSample);
            byte[] bytes = new byte[count * 2];
            in.seek(HEADER_SIZE + startSample * 2L);
            in.readFully(bytes);
            float[] out = new float[count];
            int j = 0;
            for (int i = 0; i < count; i++) {
                int lo = bytes[j++] & 0xff;
                int hi = bytes[j++];
                short v = (short) (lo | (hi << 8));
                out[i] = v / 32768f;
            }
            return out;
        }
    }

    private static void validate(RandomAccessFile in) throws IOException {
        if (in.length() < HEADER_SIZE) throw new IOException("Nagranie WAV jest niekompletne");
        byte[] tag = new byte[4];
        in.seek(0L);
        in.readFully(tag);
        if (tag[0] != 'R' || tag[1] != 'I' || tag[2] != 'F' || tag[3] != 'F') {
            throw new IOException("Nieprawidłowy plik WAV");
        }
    }

    private static void writeHeader(RandomAccessFile out, long dataBytes) throws IOException {
        long riffSize = 36L + dataBytes;
        long byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8L;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        out.writeBytes("RIFF");
        writeLeInt(out, (int) Math.min(0xffffffffL, riffSize));
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeLeInt(out, 16);
        writeLeShort(out, 1);
        writeLeShort(out, CHANNELS);
        writeLeInt(out, SAMPLE_RATE);
        writeLeInt(out, (int) byteRate);
        writeLeShort(out, blockAlign);
        writeLeShort(out, BITS_PER_SAMPLE);
        out.writeBytes("data");
        writeLeInt(out, (int) Math.min(0xffffffffL, dataBytes));
    }

    private static void writeLeInt(RandomAccessFile out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static void writeLeShort(RandomAccessFile out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }
}
