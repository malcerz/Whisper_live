package pl.whisperlive;

/**
 * Strumieniowy resampler mono (polifazowy FIR, sinc z oknem Blackmana).
 * Zamienia dowolną częstotliwość próbkowania na docelową (dla Whisper: 16 kHz),
 * z filtrem antyaliasingowym przy zmniejszaniu częstotliwości.
 */
final class Resampler {
    private static final int ZERO_CROSSINGS = 12;
    private static final double ROLLOFF = 0.92d;

    private final int inRate;
    private final int outRate;
    private final boolean passthrough;
    private final int phases;      // L = outRate / gcd
    private final int stepNum;     // M = inRate / gcd
    private final int half;        // liczba próbek filtra po każdej stronie
    private final double cutoff;   // znormalizowana do częstotliwości Nyquista wejścia (0..1]
    private final float[][] bank;  // współczynniki per faza, liczone leniwie

    private float[] buf;
    private int len;
    private int center;
    private int phase;
    private boolean flushed;

    Resampler(int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0) throw new IllegalArgumentException("Nieprawidłowa częstotliwość próbkowania");
        this.inRate = inRate;
        this.outRate = outRate;
        this.passthrough = inRate == outRate;
        int g = gcd(inRate, outRate);
        this.phases = outRate / g;
        this.stepNum = inRate / g;
        double scale = Math.min(1d, outRate / (double) inRate);
        this.half = (int) Math.ceil(ZERO_CROSSINGS / scale);
        this.cutoff = scale * ROLLOFF;
        this.bank = passthrough ? null : new float[phases][];
        if (!passthrough) {
            buf = new float[Math.max(8192, half * 4)];
            len = half;          // początkowe zera = opóźnienie filtra
            center = half;       // indeks próbki x[0]
        }
    }

    /** Górne ograniczenie liczby próbek wyjściowych dla danej liczby próbek wejściowych. */
    int maxOutput(int inputCount) {
        if (passthrough) return inputCount;
        return (int) ((long) (inputCount + 2 * half + 2) * outRate / inRate) + 2;
    }

    /** Przetwarza {@code count} próbek z {@code in}; zwraca liczbę próbek zapisanych do {@code out}. */
    int process(float[] in, int count, short[] out) {
        if (count <= 0) return 0;
        if (passthrough) {
            for (int i = 0; i < count; i++) out[i] = toPcm16(in[i]);
            return count;
        }
        append(in, count);
        return drain(out);
    }

    /** Wypycha końcówkę sygnału. Wywołać raz po ostatnim {@link #process}. */
    int flush(short[] out) {
        if (passthrough || flushed) return 0;
        flushed = true;
        append(new float[half], half);
        return drain(out);
    }

    private void append(float[] in, int count) {
        if (len + count > buf.length) {
            float[] bigger = new float[Math.max(buf.length * 2, len + count)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(in, 0, buf, len, count);
        len += count;
    }

    private int drain(short[] out) {
        int produced = 0;
        final int taps = 2 * half;
        while (center + half < len) {
            float[] h = bank[phase];
            if (h == null) {
                h = buildPhase(phase);
                bank[phase] = h;
            }
            int base = center - half + 1;
            float acc = 0f;
            for (int j = 0; j < taps; j++) acc += buf[base + j] * h[j];
            out[produced++] = toPcm16(acc);
            phase += stepNum;
            center += phase / phases;
            phase %= phases;
        }
        int drop = center - half;
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop);
            len -= drop;
            center -= drop;
        }
        return produced;
    }

    private float[] buildPhase(int ph) {
        int taps = 2 * half;
        double frac = ph / (double) phases;
        double[] h = new double[taps];
        double sum = 0d;
        for (int j = 0; j < taps; j++) {
            double t = (j - half + 1) - frac;   // odległość w próbkach wejściowych
            double u = t / half;
            double w = Math.abs(u) >= 1d ? 0d
                    : 0.42d + 0.5d * Math.cos(Math.PI * u) + 0.08d * Math.cos(2d * Math.PI * u);
            double x = Math.PI * cutoff * t;
            double s = Math.abs(x) < 1e-9 ? 1d : Math.sin(x) / x;
            h[j] = s * w;
            sum += h[j];
        }
        float[] out = new float[taps];
        if (sum == 0d) sum = 1d;
        for (int j = 0; j < taps; j++) out[j] = (float) (h[j] / sum);
        return out;
    }

    static short toPcm16(float v) {
        float scaled = v * 32767f;
        if (scaled >= 32767f) return Short.MAX_VALUE;
        if (scaled <= -32768f) return Short.MIN_VALUE;
        return (short) Math.round(scaled);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
