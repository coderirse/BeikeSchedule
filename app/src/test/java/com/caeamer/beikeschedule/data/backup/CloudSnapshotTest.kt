package com.caeamer.beikeschedule.data.backup

import com.caeamer.beikeschedule.data.local.TodoEntity
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云快照编解码（纯 JVM）：老快照可读（ignoreUnknownKeys + 默认值）、新字段可忽略、
 * 超前 schema 拒绝恢复。这是"换机一键恢复"的数据契约，格式变更必须有这组测试兜着。
 */
class CloudSnapshotTest {

    @Test
    fun `roundtrip keeps all fields`() {
        val snapshot = CloudSnapshot(
            exportedAt = 1726900000000L,
            courses = listOf(
                CourseDto(
                    taskId = "T001", name = "高等数学", teacher = "张三", location = "【校本部】教学楼101",
                    dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "01111111111111111111111111111111",
                    colorIndex = 3, source = 0, hidden = true,
                ),
            ),
            sectionTimes = listOf(SectionTimeDto(1, "08:00", "08:45")),
            grades = listOf(GradeDto(kcdm = "MATH101", kcmc = "高等数学", xf = 4.0, zzcj = "92", sffx = false)),
            exams = listOf(ExamDto(kcdm = "MATH101", kcmc = "高等数学", ksrq = "2026-09-21", kssj = "09:00")),
            todos = listOf(TodoEntity(id = 7, title = "自习", time = "19:00", repeatMode = TodoEntity.REPEAT_ONCE, date = "2026-09-21")),
            settings = SettingsDto(semester = SemesterDto(xn = "2026-2027", xq = "1", firstMonday = "2026-09-07")),
        )

        val decoded = CloudSnapshot.decode(CloudSnapshot.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `decode rejects absurd scale snapshots`() {
        // R4 审查 R3：被篡改/损坏的快照不该走完恢复流程整表覆盖本机数据
        val many = List(6000) { CourseDto(name = "课$it") }
        val text = CloudSnapshot.encode(CloudSnapshot(courses = many))
        val err = runCatching { CloudSnapshot.decode(text) }.exceptionOrNull()
        assertTrue("应拒绝超规模快照", err is SerializationException)

        val badSection = CloudSnapshot(sectionTimes = listOf(SectionTimeDto(99, "08:00", "08:45")))
        assertTrue(
            "应拒绝非法节次",
            runCatching { CloudSnapshot.decode(CloudSnapshot.encode(badSection)) }.exceptionOrNull()
                is SerializationException,
        )
    }

    @Test
    fun `decode tolerates unknown fields and missing fields`() {
        // 未来版本加了新字段/新课程字段：当前版本应忽略未知字段、缺失字段取默认值
        val olderJson = """
            {
              "schemaVersion": 1,
              "exportedAt": 1726900000000,
              "futureField": {"x": 1},
              "courses": [{"name": "高等数学", "dayOfWeek": 2, "someNewColumn": "ignored"}]
            }
        """.trimIndent()

        val snapshot = CloudSnapshot.decode(olderJson)

        assertEquals(1, snapshot.courses.size)
        assertEquals("高等数学", snapshot.courses[0].name)
        assertEquals(2, snapshot.courses[0].dayOfWeek)
        assertTrue(snapshot.grades.isEmpty())
    }

    @Test(expected = SerializationException::class)
    fun `decode rejects newer schema version`() {
        CloudSnapshot.decode("""{"schemaVersion": 99, "exportedAt": 1}""")
    }
}
