package com.caeamer.beikeschedule.model

/**
 * 教室名自然排序：把名字里的**数字段**按数值比较，而不是按字符比较。
 *
 * 教务/平台的教室名形如 `教学楼503`、`逸夫楼107`、`高工302`。逐字符比较会得到
 * `教学楼103 < 教学楼107 < 教学楼503`（本例恰好正确），但遇到位数不同的教室号就错了：
 * 字符序下 `教学楼1002` 会排在 `教学楼503` **前面**（'1' < '5'），而用户预期是 503 在前。
 * 所以把数字段当整数比。
 *
 * 规则：
 * 1. 按"非数字段 / 数字段"切分，非数字段忽略大小写比较；
 * 2. 数字段按数值比较（前导零不影响，`0503` 与 `503` 相等）；
 * 3. 数字段整体**优先于**同位置的文本（`教学楼503` 里的 503 与 `教学楼A` 里的 A：
 *    数字在前）；
 * 4. 全部相等时用原始字符串兜底，保证排序稳定（否则同名前缀的教室顺序随机）。
 */
object RoomNameOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        for (i in 0 until minOf(ta.size, tb.size)) {
            val c = compareToken(ta[i], tb[i])
            if (c != 0) return c
        }
        // 前缀相同：短的在前；再相同则按原串（保证稳定性）
        if (ta.size != tb.size) return ta.size - tb.size
        return a.compareTo(b)
    }

    /**
     * 一段要么是数字，要么是文本。
     *
     * 数字用"归一化十进制串 + 长度"表示而非 Long：
     * - **前导零必须去掉**，否则 `0503` 与 `503` 会被当成不同的数字段长度而无法比较
     *   （这是本类最初版本的缺陷）；
     * - 位数可以任意长，不会因超出 Long 而退化成文本比较（那样 `9…9`(25位) 会排错）。
     */
    private sealed interface Token {
        data class Num(val digits: String) : Token
        data class Text(val value: String) : Token
    }

    private fun tokenize(s: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val isDigit = s[i].isDigit()
            var j = i
            while (j < s.length && s[j].isDigit() == isDigit) j++
            val seg = s.substring(i, j)
            out += if (isDigit) Token.Num(seg.trimStart('0').ifEmpty { "0" }) else Token.Text(seg.lowercase())
            i = j
        }
        return out
    }

    private fun compareToken(a: Token, b: Token): Int = when {
        a is Token.Num && b is Token.Num -> compareDigits(a.digits, b.digits)
        a is Token.Num -> -1          // 数字段排在文本段之前
        b is Token.Num -> 1
        else -> (a as Token.Text).value.compareTo((b as Token.Text).value)
    }

    /** 十进制串比较：先比位数，位数相同再逐字符比（等价于数值比较，且无位数上限）。 */
    private fun compareDigits(a: String, b: String): Int {
        if (a.length != b.length) return a.length - b.length
        return a.compareTo(b)
    }
}
