package io.webagent4j.locator;

import io.webagent4j.locator.api.TextMatch;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic exact, structural and conservative fuzzy text matcher. */
public final class TextMatcher {

    private static final Set<String> STOP_WORDS =
            Set.of(
                    "a", "an", "the", "to", "of", "au", "aux", "de", "des", "du", "le", "la", "les",
                    "se");

    private final ITextNormalizer normalizer;

    /** Creates a matcher using the default Unicode-aware normalizer. */
    public TextMatcher() {
        this(new DefaultTextNormalizer());
    }

    /** Creates a matcher using a custom shared normalizer. */
    public TextMatcher(ITextNormalizer normalizer) {
        this.normalizer = java.util.Objects.requireNonNull(normalizer, "normalizer");
    }

    /** Returns a match quality from zero to one for the requested criterion. */
    public double score(TextMatch match, String actual) {
        return score(match, actual, Locale.ROOT);
    }

    /** Returns a match quality using an explicit deterministic case-folding locale. */
    public double score(TextMatch match, String actual, Locale locale) {
        String requested = normalizer.normalize(match.value());
        String observed = normalizer.normalize(actual == null ? "" : actual);
        String foldedRequested =
                requested.toLowerCase(java.util.Objects.requireNonNull(locale, "locale"));
        String foldedObserved = observed.toLowerCase(locale);
        return switch (match.type()) {
            case EXACT -> requested.equals(observed) ? 1.0 : 0.0;
            case CASE_INSENSITIVE_EXACT -> foldedRequested.equals(foldedObserved) ? 1.0 : 0.0;
            case CONTAINS -> foldedObserved.contains(foldedRequested) ? 0.95 : 0.0;
            case STARTS_WITH -> foldedObserved.startsWith(foldedRequested) ? 0.93 : 0.0;
            case ENDS_WITH -> foldedObserved.endsWith(foldedRequested) ? 0.92 : 0.0;
            case REGEX -> Pattern.compile(match.value()).matcher(observed).find() ? 1.0 : 0.0;
            case FUZZY -> similarityFolded(foldedRequested, foldedObserved);
        };
    }

    /** Returns a conservative similarity from zero to one. */
    public double similarity(String left, String right) {
        String first = normalizer.normalizeCaseFolded(left);
        String second = normalizer.normalizeCaseFolded(right);
        return similarityFolded(first, second);
    }

    private double similarityFolded(String first, String second) {
        if (first.equals(second)) {
            return 1.0;
        }
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0;
        }
        double edit =
                1.0
                        - ((double) levenshtein(first, second)
                                / Math.max(first.length(), second.length()));
        double jaro = jaroWinkler(first, second);
        double tokens = tokenSimilarity(first, second);
        double stems = stemSimilarity(first, second);
        double compact = compactContainment(first, second);
        return Math.min(
                1.0, Math.max(Math.max(edit, jaro), Math.max(tokens, Math.max(stems, compact))));
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] =
                        Math.min(
                                Math.min(current[column - 1] + 1, previous[column] + 1),
                                previous[column - 1] + substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static double tokenSimilarity(String left, String right) {
        return tokenSimilarity(tokens(left), tokens(right));
    }

    private static double tokenSimilarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        if (intersection.size() == Math.min(left.size(), right.size())) {
            double sizePenalty =
                    (double) Math.min(left.size(), right.size())
                            / Math.max(left.size(), right.size());
            return 0.82 + 0.18 * sizePenalty;
        }
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>(Arrays.asList(value.split("[^\\p{L}\\p{N}]+")));
        result.removeIf(token -> token.isBlank() || STOP_WORDS.contains(token));
        return result;
    }

    private static Set<String> stemTokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : tokens(value)) {
            result.add(stem(token));
        }
        return result;
    }

    private static double stemSimilarity(String left, String right) {
        double best = 0.0;
        for (String leftToken : stemTokens(left)) {
            for (String rightToken : stemTokens(right)) {
                best = Math.max(best, jaroWinkler(leftToken, rightToken));
            }
        }
        return best;
    }

    private static double compactContainment(String left, String right) {
        String first = left.replaceAll("[^\\p{L}\\p{N}]", "");
        String second = right.replaceAll("[^\\p{L}\\p{N}]", "");
        int shorter = Math.min(first.length(), second.length());
        if (shorter < 5 || !(first.contains(second) || second.contains(first))) {
            return 0.0;
        }
        return 0.82 + 0.18 * ((double) shorter / Math.max(first.length(), second.length()));
    }

    private static String stem(String value) {
        for (String suffix :
                new String[] {"ement", "ation", "ition", "ions", "ion", "er", "es", "s"}) {
            if (value.length() > suffix.length() + 3 && value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static double jaroWinkler(String left, String right) {
        int range = Math.max(left.length(), right.length()) / 2 - 1;
        boolean[] leftMatches = new boolean[left.length()];
        boolean[] rightMatches = new boolean[right.length()];
        int matches = 0;
        for (int leftIndex = 0; leftIndex < left.length(); leftIndex++) {
            int start = Math.max(0, leftIndex - range);
            int end = Math.min(leftIndex + range + 1, right.length());
            for (int rightIndex = start; rightIndex < end; rightIndex++) {
                if (!rightMatches[rightIndex]
                        && left.charAt(leftIndex) == right.charAt(rightIndex)) {
                    leftMatches[leftIndex] = true;
                    rightMatches[rightIndex] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int rightIndex = 0;
        for (int leftIndex = 0; leftIndex < left.length(); leftIndex++) {
            if (!leftMatches[leftIndex]) {
                continue;
            }
            while (!rightMatches[rightIndex]) {
                rightIndex++;
            }
            if (left.charAt(leftIndex) != right.charAt(rightIndex++)) {
                transpositions++;
            }
        }
        double matchCount = matches;
        double jaro =
                (matchCount / left.length()
                                + matchCount / right.length()
                                + (matchCount - transpositions / 2.0) / matchCount)
                        / 3.0;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(left.length(), right.length()))
                && left.charAt(prefix) == right.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
