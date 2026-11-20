package kr.ganzithon.backend.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Offline Korean supplementary support routing using a synthetic-data linear classifier. */
final class KoreanCrisisSimilarity {
    private static final Pattern WORD = Pattern.compile("[가-힣ᄀ-ᇿA-Za-z0-9]+");
    private static final String RESOURCE = "/chat-safety/ko-linear-v1.tsv";
    private static final Model MODEL = loadModel();

    private KoreanCrisisSimilarity() {}

    static boolean needsSupport(String text) {
        return score(text) >= MODEL.threshold();
    }

    static double score(String text) {
        if (text == null || text.isBlank()) {
            return MODEL.bias();
        }
        var active = new TreeSet<String>();
        var compact = new StringBuilder();
        var matcher = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            addIfKnown(active, "w" + word);
            compact.append(word);
        }
        for (int n = 2; n <= 4; n++) {
            for (int start = 0; start + n <= compact.length(); start++) {
                addIfKnown(active, "c" + compact.substring(start, start + n));
            }
        }
        double total = 0;
        for (String feature : active) {
            total += MODEL.weights().get(feature);
        }
        return MODEL.bias() + total / Math.sqrt(Math.max(1, active.size()));
    }

    private static void addIfKnown(TreeSet<String> active, String key) {
        if (MODEL.weights().containsKey(key)) {
            active.add(key);
        }
    }

    private record Model(double bias, double threshold, Map<String, Double> weights) {}

    private static Model loadModel() {
        var stream = KoreanCrisisSimilarity.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing support-routing model " + RESOURCE);
        }
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            String[] fields = header == null ? new String[0] : header.split("\t");
            if (fields.length != 3 || !"ko-linear-v1".equals(fields[0])) {
                throw new IllegalStateException("Invalid support-routing model header");
            }
            double bias = Double.parseDouble(fields[1]);
            double threshold = Double.parseDouble(fields[2]);
            var weights = new HashMap<String, Double>();
            for (String row; (row = reader.readLine()) != null;) {
                String[] parts = row.split("\t");
                weights.put(parts[0], Double.parseDouble(parts[1]));
            }
            if (!(threshold > bias) || weights.isEmpty()) {
                throw new IllegalStateException("Invalid support-routing model parameters");
            }
            return new Model(bias, threshold, Map.copyOf(weights));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }
}
