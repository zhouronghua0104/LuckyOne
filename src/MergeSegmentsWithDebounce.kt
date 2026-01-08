/**
 * 哨兵各窗口推理结果合并
 *
 * 对预测序列应用防抖动和防截断逻辑
 *
 * BUGFIX:
 * - 将 "" / 空白字符串 视为“其它”(负例)处理：不会参与分段、计票与最终输出。
 */
fun mergeSegmentsWithDebounce(
    predSequence: List<String>,
    gapThreshold: Int = 1
): LinkedHashMap<String, List<Int>> {
    @Suppress("UNUSED_PARAMETER")
    val _gapThreshold = gapThreshold // 保留接口兼容性

    val negativeLabels = setOf("X", "其它")
    fun isNegativeLabel(label: String): Boolean = label.isBlank() || label in negativeLabels

    val continuousSegments = mutableListOf<IntRange>()
    var i = 0

    while (i < predSequence.size) {
        if (isNegativeLabel(predSequence[i])) {
            var j = i
            var negCount = 0
            while (j < predSequence.size && isNegativeLabel(predSequence[j])) {
                negCount++
                j++
            }
            i = if (negCount >= 2) j else i + 1
            continue
        }

        val start = i
        var end = i
        var j = i + 1

        while (j < predSequence.size) {
            if (isNegativeLabel(predSequence[j])) {
                var k = j
                var negCount = 0
                while (k < predSequence.size && isNegativeLabel(predSequence[k])) {
                    negCount++
                    k++
                }
                if (negCount >= 2) {
                    end = j - 1
                    break
                }
                if (k < predSequence.size) {
                    j = k
                    end = k
                    continue
                } else {
                    end = predSequence.lastIndex
                    j = k
                    break
                }
            } else {
                end = j
                j++
            }
        }

        if (j >= predSequence.size) {
            end = predSequence.lastIndex
        }
        continuousSegments.add(start..end)
        i = end + 1
    }

    val finalActionsWithPositions = LinkedHashMap<String, MutableList<Int>>()

    continuousSegments.forEach { range ->
        val segment = predSequence.subList(range.first, range.last + 1)
        val actionCount = mutableMapOf<String, Int>()
        val actionHasContinuous = mutableSetOf<String>()

        segment.forEachIndexed { idxInSegment, label ->
            if (!isNegativeLabel(label)) {
                actionCount[label] = actionCount.getOrDefault(label, 0) + 1
                if (idxInSegment < segment.lastIndex && segment[idxInSegment + 1] == label) {
                    actionHasContinuous.add(label)
                }
            }
        }

        if (actionCount.isEmpty()) return@forEach

        val selectedLabels = linkedSetOf<String>()
        fun selectLabels(predicate: (String) -> Boolean) {
            for (absIdx in range) {
                val label = predSequence[absIdx]
                if (!isNegativeLabel(label) && predicate(label)) {
                    selectedLabels.add(label)
                }
            }
        }

        val allEqual = actionCount.values.distinct().size == 1
        if (allEqual) {
            selectLabels { true }
        } else {
            val maxCount = actionCount.values.max() ?: return@forEach
            selectLabels { it in actionHasContinuous }
            selectLabels { actionCount[it] == maxCount }
        }

        for (absIdx in range) {
            val label = predSequence[absIdx]
            if (label in selectedLabels) {
                finalActionsWithPositions
                    .getOrPut(label) { mutableListOf() }
                    .add(absIdx + 1) // store 1-based index
            }
        }
    }

    return finalActionsWithPositions
        .mapValuesTo(LinkedHashMap()) { it.value.toList() }
}

