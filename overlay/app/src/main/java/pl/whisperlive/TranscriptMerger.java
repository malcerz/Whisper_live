package pl.whisperlive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TranscriptMerger {
    private final StringBuilder text = new StringBuilder();
    private final ArrayList<String> words = new ArrayList<>();

    synchronized String append(String incoming) {
        String clean = normalizeWhitespace(incoming);
        if (clean.isEmpty()) return "";
        String[] incomingWords = clean.split(" ");
        int overlap = findOverlap(words, incomingWords);
        StringBuilder delta = new StringBuilder();
        for (int i = overlap; i < incomingWords.length; i++) {
            String w = incomingWords[i];
            if (w.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(w);
            words.add(w);
            if (delta.length() > 0) delta.append(' ');
            delta.append(w);
        }
        return delta.toString();
    }

    synchronized void clear() {
        text.setLength(0);
        words.clear();
    }

    synchronized String getText() {
        return text.toString();
    }

    private static int findOverlap(List<String> previous, String[] incoming) {
        int max = Math.min(14, Math.min(previous.size(), incoming.length));
        for (int n = max; n >= 1; n--) {
            boolean same = true;
            int prevStart = previous.size() - n;
            for (int i = 0; i < n; i++) {
                if (!key(previous.get(prevStart + i)).equals(key(incoming[i]))) {
                    same = false;
                    break;
                }
            }
            if (same) return n;
        }
        return 0;
    }

    private static String normalizeWhitespace(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }
}
