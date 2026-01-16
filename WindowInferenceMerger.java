import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 哨兵各窗口推理结果合并
 *
 * 对预测序列应用防抖动和防截断逻辑
 * 逻辑：
 * 1. 找到连续动作序列，单个负例窗口不打断连续性（连续≥2个负例才打断）
 * 2. 对每个连续序列进行多窗口合并：
 *    (1) 如果连着两个窗口都是同一个动作，则保留该动作
 *    (2) 如果每个动作的窗口数一致，则保留所有动作
 *    (3) 除去上面两种情况，则取窗口数最多的动作（同时保留连续出现的动作）
 * Args:
 *     predSequence: 预测序列，每个元素是单个标签
 *     gapThreshold: 未使用（保留接口兼容性）
 * Returns:
 *     finalActions: 去重后的动作集合
 * 示例：
 *     [A,B,A,X,X,X,X] → {A}  (序列[A,B,A]中A出现2次最多)
 *     [A,B,B,A] → {A,B}  (A出现2次，B连续且出现2次，都是最多)
 *     [A,A,B,A,C,X,A] → {A}  (A出现4次最多且连续)
 *     [X,X,A,B,X,X] → {A,B}  (序列[A,B]中票数一致)
 *     [X,X,A,X,X] → {A}  (单窗口序列，票数一致)
 *
 * @param predSequence 预测的各个窗口的推理结果
 * @param gapThreshold GAP阈值
 * @return 危险动作标签合集
 * @author zhouronghua
 * @time 2025/11/12 17:34
 */
public class WindowInferenceMerger {
    public static LinkedHashSet<String> mergeSegmentsWithDebounce(
            List<String> predSequence,
            int gapThreshold
    ) {
        // gapThreshold reserved for compatibility; not used.
        Set<String> negativeLabels = new HashSet<>(Arrays.asList("X", "其它", "其他"));
        List<int[]> continuousSegments = new ArrayList<>();
        int i = 0;

        while (i < predSequence.size()) {
            if (negativeLabels.contains(predSequence.get(i))) {
                int j = i;
                int negCount = 0;
                while (j < predSequence.size() && negativeLabels.contains(predSequence.get(j))) {
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
                if (negativeLabels.contains(predSequence.get(j))) {
                    int k = j;
                    int negCount = 0;
                    while (k < predSequence.size() && negativeLabels.contains(predSequence.get(k))) {
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
            continuousSegments.add(new int[] { start, end });
            i = end + 1;
        }

        LinkedHashSet<String> finalActions = new LinkedHashSet<>();

        for (int[] range : continuousSegments) {
            List<String> segment = predSequence.subList(range[0], range[1] + 1);
            Map<String, Integer> actionCount = new HashMap<>();
            Set<String> actionHasContinuous = new HashSet<>();

            for (int idx = 0; idx < segment.size(); idx++) {
                String label = segment.get(idx);
                if (!negativeLabels.contains(label)) {
                    actionCount.put(label, actionCount.getOrDefault(label, 0) + 1);
                    if (idx < segment.size() - 1 && segment.get(idx + 1).equals(label)) {
                        actionHasContinuous.add(label);
                    }
                }
            }

            if (actionCount.isEmpty()) {
                continue;
            }

            boolean allEqual = new HashSet<>(actionCount.values()).size() == 1;
            if (allEqual) {
                addActionsInOrder(finalActions, negativeLabels, segment, label -> true);
            } else {
                int maxCount = Collections.max(actionCount.values());
                addActionsInOrder(finalActions, negativeLabels, segment, actionHasContinuous::contains);
                addActionsInOrder(finalActions, negativeLabels, segment, label -> actionCount.get(label) == maxCount);
            }
        }

        return finalActions;
    }

    private static void addActionsInOrder(
            LinkedHashSet<String> finalActions,
            Set<String> negativeLabels,
            List<String> segment,
            Predicate<String> filter
    ) {
        if (segment == null || filter == null) {
            return;
        }
        for (String label : segment) {
            if (!negativeLabels.contains(label) && filter.test(label)) {
                finalActions.add(label);
            }
        }
    }
}
