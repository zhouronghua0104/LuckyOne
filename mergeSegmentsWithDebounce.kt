/**
 * 哨兵各窗口推理结果合并
 *
 * 对预测序列应用防抖动和防截断逻辑：
 * 1. 找到连续动作序列，单个负例窗口不打断连续性（连续≥2个负例才打断）
 * 2. 对每个连续序列进行多窗口合并：
 *    - 过滤掉 其它/其他/X 以及空字符串
 *    - 有效动作都保留
 * 3. 合并后按动作出现频次（窗口数）降序排列；同频次按首次出现时间稳定排序
 *
 * @param predSequence 预测的各个窗口的推理结果
 * @param gapThreshold GAP阈值（未使用，保留接口兼容性）
 * @return 危险动作标签合集：key 为动作，value 为 1-based 窗口索引列表
 */
fun mergeSegmentsWithDebounce(
    predSequence: List<String>,
    gapThreshold: Int = 1
): LinkedHashMap<String, List<Int>> {
    // 保留接口兼容性
    @Suppress("UNUSED_PARAMETER")
    val _gapThreshold = gapThreshold

    // 负例标签：需要“无条件过滤”，并做输入规范化避免漏判（空格/不可见字符/大小写等）
    fun normalizeLabel(label: String): String = label.trim()
    fun isNegativeLabel(label: String): Boolean {
        val l = normalizeLabel(label)
        return l.isEmpty() || l.equals("x", ignoreCase = true) || l == "其它" || l == "其他"
    }

    // 1) 构建连续段：连续>=2个负例才切段；单个负例不切段
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

        var start = i
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

    // 2) 合并：保留所有有效动作，并记录其时间轴（1-based）
    val finalActionsWithPositions = LinkedHashMap<String, MutableList<Int>>()
    continuousSegments.forEach { range ->
        for (absIdx in range) {
            val label = predSequence[absIdx]
            val normalized = normalizeLabel(label)
            if (!isNegativeLabel(normalized)) {
                finalActionsWithPositions
                    .getOrPut(normalized) { mutableListOf() }
                    .add(absIdx + 1)
            }
        }
    }

    // 3) 按出现频次降序排列（同频次按首次出现时间稳定排序）
    val sortedEntries = finalActionsWithPositions.entries.sortedWith(
        compareByDescending<Map.Entry<String, MutableList<Int>>> { it.value.size }
            .thenBy { it.value.minOrNull() ?: Int.MAX_VALUE }
    )

    val result = LinkedHashMap<String, List<Int>>(sortedEntries.size)
    for ((label, positions) in sortedEntries) {
        result[label] = positions.toList()
    }
    return result
}

