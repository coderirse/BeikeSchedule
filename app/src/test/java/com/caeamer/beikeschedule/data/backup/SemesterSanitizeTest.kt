package com.caeamer.beikeschedule.data.backup

import com.caeamer.beikeschedule.import.parser.JwParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云恢复前的学期配置清洗。
 *
 * 快照是网络来的数据（旧版本客户端上传的、或被篡改的），教务导入入口那套校验管不到它，
 * 而它一落 DataStore 就成了全局口径：坏日期串让教学周序号错位、totalWeeks 为 0 让
 * `coerceIn(1, totalWeeks)` 在 stateIn 的共享协程里抛异常，且坏值已持久化 → 每次启动都崩。
 */
class SemesterSanitizeTest {

    private val goodCalendar = listOf("2026-09-07", "2026-09-14", "2026-09-21")

    @Test
    fun `合法配置原样通过`() {
        val dto = SemesterDto(
            xn = "2026-2027", xq = "1", name = "2026-2027-1",
            firstMonday = "2026-09-07", totalWeeks = 20, weekMondays = goodCalendar,
        )
        assertEquals(dto, CloudSnapshotCodec.sanitizeSemester(dto))
    }

    @Test
    fun `总周数越界夹到合法区间`() {
        assertEquals(1, CloudSnapshotCodec.sanitizeSemester(SemesterDto(totalWeeks = 0)).totalWeeks)
        assertEquals(1, CloudSnapshotCodec.sanitizeSemester(SemesterDto(totalWeeks = -5)).totalWeeks)
        assertEquals(
            JwParser.MAX_TOTAL_WEEKS,
            CloudSnapshotCodec.sanitizeSemester(SemesterDto(totalWeeks = 999_999)).totalWeeks,
        )
    }

    @Test
    fun `校历含坏日期串整表丢弃（不能只剔坏的那一项）`() {
        val cleaned = CloudSnapshotCodec.sanitizeSemester(
            SemesterDto(
                firstMonday = "2026-09-07",
                totalWeeks = 20,
                weekMondays = listOf("2026-09-07", "not-a-date", "2026-09-21"),
            ),
        )
        assertTrue(cleaned.weekMondays.isEmpty())
        // 开学日期仍可用：下游按 firstMonday 推算，不会错位也不会崩
        assertEquals("2026-09-07", cleaned.firstMonday)
    }

    @Test
    fun `校历规模异常整表丢弃`() {
        val huge = List(JwParser.MAX_TOTAL_WEEKS + 1) { "2026-09-07" }
        assertTrue(CloudSnapshotCodec.sanitizeSemester(SemesterDto(weekMondays = huge)).weekMondays.isEmpty())
    }

    @Test
    fun `开学日期非法时置空`() {
        assertEquals(
            "",
            CloudSnapshotCodec.sanitizeSemester(SemesterDto(firstMonday = "2026/09/07")).firstMonday,
        )
        assertEquals(
            "",
            CloudSnapshotCodec.sanitizeSemester(SemesterDto(firstMonday = "not-a-date")).firstMonday,
        )
        // 空串是合法默认值（还没设置学期），不能被当成坏值
        assertEquals("", CloudSnapshotCodec.sanitizeSemester(SemesterDto(firstMonday = "")).firstMonday)
    }

    @Test
    fun `空校历保持为空（回退 firstMonday 推算）`() {
        assertTrue(CloudSnapshotCodec.sanitizeSemester(SemesterDto()).weekMondays.isEmpty())
    }
}
