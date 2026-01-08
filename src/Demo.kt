private fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
    if (expected != actual) {
        val prefix = if (message.isBlank()) "" else "$message: "
        throw IllegalStateException(prefix + "expected=$expected, actual=$actual")
    }
}

fun main() {
    // 你的例子：空字符串应当被当作“其它”(负例)并丢弃，不应该进入结果 key
    val seq1 = listOf("人-踹车", "人-踹踹踹踹") + List(80) { "" }
    val r1 = mergeSegmentsWithDebounce(seq1)
    assertEquals(setOf("人-踹车", "人-踹踹踹踹"), r1.keys.toSet(), "seq1 keys")
    check("" !in r1.keys) { "empty label should not be included" }

    // 单个负例窗口不应打断连续性；但负例本身也不能出现在输出
    val seq2 = listOf("A", "", "B")
    val r2 = mergeSegmentsWithDebounce(seq2)
    assertEquals(listOf(1), r2["A"], "seq2 A positions")
    assertEquals(listOf(3), r2["B"], "seq2 B positions")
    check("" !in r2.keys) { "empty label should not be included" }

    // “其它”同样作为负例处理
    val seq3 = listOf("其它", "A", "其它", "其它", "B")
    val r3 = mergeSegmentsWithDebounce(seq3)
    assertEquals(listOf(2), r3["A"], "seq3 A positions")
    assertEquals(listOf(5), r3["B"], "seq3 B positions")

    println("OK")
}

