/**
 * 合并全部风险点处理
 * 合并规则：
 * 1）如果两个时间区段之间有重合，则合并在一起，[(1.0,2.2),(1.5,3.0)]==>[(1.0,3.0)]
 * 2) 如果两个时间区段直接，区间之间的间距小于或者等于0.2秒，也进行合并。[(1.0,2.2),(2.3,3.0)]==>[(1.0,3.0)]
 * 3）如果两个时间区段直接，区间之间的间距大于于0.2秒，则不需要合并 [(1.0,2.2),(2.5,3.0)]==>[(1.0,2.2),(2.5,3.0)]
 *
 * @param occTimestampList 待合并时间区段
 * @author zhouronghua
 * @time 2026/1/1 16:20
 */
@Suppress("MemberVisibilityCanBePrivate")
object VideoRiskSectionMerger {
    private const val MERGE_GAP_SECONDS = 0.2
    private const val EPS = 1e-9

    /**
     * 对外暴露的合并方法（便于外部调用/测试）。
     */
    fun merge(occTimestampList: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        mergeRiskSection(occTimestampList)

    private fun mergeRiskSection(occTimestampList: List<Pair<Double, Double>>):
        List<Pair<Double, Double>> {
        if (occTimestampList.isEmpty()) return emptyList()

        // 先规范化区间（start <= end），再按 start 排序
        val sorted = occTimestampList
            .map { (start, end) ->
                if (start <= end) start to end else end to start
            }
            .sortedBy { it.first }

        val merged = ArrayList<Pair<Double, Double>>(sorted.size)

        var currentStart = sorted[0].first
        var currentEnd = sorted[0].second

        for (i in 1 until sorted.size) {
            val (nextStart, nextEnd) = sorted[i]
            val gap = nextStart - currentEnd

            // 重叠（gap <= 0）或间距 <= 0.2s，都合并
            if (gap <= MERGE_GAP_SECONDS + EPS) {
                currentEnd = maxOf(currentEnd, nextEnd)
            } else {
                merged.add(currentStart to currentEnd)
                currentStart = nextStart
                currentEnd = nextEnd
            }
        }

        merged.add(currentStart to currentEnd)
        return merged
    }
}

