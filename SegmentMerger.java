import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SegmentMerger {
    private SegmentMerger() {
    }

    /**
     * Merge inference results across windows with debounce and truncation logic.
     *
     * <p>Rules:</p>
     * <ol>
     *   <li>Build continuous segments; a single negative window does not break
     *       continuity, but two or more consecutive negatives do.</li>
     *   <li>Within each segment, filter negative labels and keep all valid actions.</li>
     *   <li>Sort actions by frequency (descending), then by first appearance
     *       (ascending, stable for ties).</li>
     * </ol>
     *
     * @param predSequence list of per-window inference labels
     * @param gapThreshold GAP threshold (unused, kept for compatibility)
     * @return map of action label to 1-based window indices
     */
    public static LinkedHashMap<String, List<Integer>> mergeSegmentsWithDebounce(
            List<String> predSequence,
            int gapThreshold
    ) {
        List<Range> continuousSegments = new ArrayList<>();
        int i = 0;

        while (i < predSequence.size()) {
            if (isNegativeLabel(predSequence.get(i))) {
                int j = i;
                int negCount = 0;
                while (j < predSequence.size() && isNegativeLabel(predSequence.get(j))) {
                    negCount++;
                    j++;
                }
                i = (negCount >= 2) ? j : i + 1;
                continue;
            }

            int start = i;
            int end = i;
            int j = i + 1;

            while (j < predSequence.size()) {
                if (isNegativeLabel(predSequence.get(j))) {
                    int k = j;
                    int negCount = 0;
                    while (k < predSequence.size() && isNegativeLabel(predSequence.get(k))) {
                        negCount++;
                        k++;
                    }
                    if (negCount >= 2) {
                        end = j - 1;
                        break;
                    }
                    if (k < predSequence.size()) {
                        j = k;
                        end = k;
                        continue;
                    } else {
                        end = predSequence.size() - 1;
                        j = k;
                        break;
                    }
                } else {
                    end = j;
                    j++;
                }
            }

            if (j >= predSequence.size()) {
                end = predSequence.size() - 1;
            }
            continuousSegments.add(new Range(start, end));
            i = end + 1;
        }

        LinkedHashMap<String, List<Integer>> finalActionsWithPositions = new LinkedHashMap<>();
        for (Range range : continuousSegments) {
            for (int absIdx = range.start; absIdx <= range.end; absIdx++) {
                String label = predSequence.get(absIdx);
                String normalized = normalizeLabel(label);
                if (!isNegativeLabel(normalized)) {
                    List<Integer> positions = finalActionsWithPositions.get(normalized);
                    if (positions == null) {
                        positions = new ArrayList<>();
                        finalActionsWithPositions.put(normalized, positions);
                    }
                    positions.add(absIdx + 1);
                }
            }
        }

        List<Map.Entry<String, List<Integer>>> sortedEntries =
                new ArrayList<>(finalActionsWithPositions.entrySet());
        sortedEntries.sort((a, b) -> {
            int countA = a.getValue().size();
            int countB = b.getValue().size();
            if (countA != countB) {
                return Integer.compare(countB, countA);
            }
            return Integer.compare(minOrMax(a.getValue()), minOrMax(b.getValue()));
        });

        LinkedHashMap<String, List<Integer>> result = new LinkedHashMap<>(sortedEntries.size());
        for (Map.Entry<String, List<Integer>> entry : sortedEntries) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    /**
     * Overload with default gapThreshold = 1 for parity with Kotlin default.
     */
    public static LinkedHashMap<String, List<Integer>> mergeSegmentsWithDebounce(
            List<String> predSequence
    ) {
        return mergeSegmentsWithDebounce(predSequence, 1);
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.trim();
    }

    private static boolean isNegativeLabel(String label) {
        String normalized = normalizeLabel(label);
        return normalized.isEmpty()
                || normalized.equalsIgnoreCase("x")
                || "其它".equals(normalized)
                || "其他".equals(normalized);
    }

    private static int minOrMax(List<Integer> values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    private static final class Range {
        private final int start;
        private final int end;

        private Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
