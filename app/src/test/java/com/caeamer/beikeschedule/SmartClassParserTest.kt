package com.caeamer.beikeschedule.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * smartclass 响应解析单测。
 *
 * 所有 JSON 都是 **2026-09 实测的真实响应**（截取），包括服务端的空座率 `null` 边界
 * —— 那个值在部分教室上真实出现，若不区分"没有数据"与"空座率 0"，
 * 界面上会把未知显示成"0% 空座"，与事实相反。
 */
class SmartClassParserTest {

    // ——— 教学楼 ———

    @Test
    fun `解析教学楼列表 - 实测响应`() {
        val body = """
            {"code":0,"msg":"success","data":[
              {"id":"7daa6fdcb6664d03a745006e4c4e5d21","name":"南楼"},
              {"id":"38af86134b65d0f10fe33d30dd76442e","name":"逸夫楼"},
              {"id":"1cd73be1e256a7405516501e94e892ac","name":"教学楼"},
              {"id":"8d3215ae97598264ad6529613774a038","name":"智慧教室"},
              {"id":"ef72d53990bc4805684c9b61fa64a102","name":"学院楼"}]}
        """.trimIndent()
        val list = SmartClassParser.parseBuildings(body)
        assertEquals(5, list.size)
        assertEquals("南楼", list[0].name)
        assertEquals("1cd73be1e256a7405516501e94e892ac", list[2].id)
    }

    @Test
    fun `解析教学楼 - 跳过缺字段的条目`() {
        val body = """{"code":0,"data":[{"id":"a","name":"A"},{"id":"","name":"B"},{"id":"c","name":""},{"id":"d","name":"D"}]}"""
        val list = SmartClassParser.parseBuildings(body)
        assertEquals(listOf("A", "D"), list.map { it.name })
    }

    // ——— 节次类型 ———

    @Test
    fun `解析节次类型 - 实测有两种`() {
        val body = """
            {"code":0,"msg":"success","data":[
              {"id":"49c166931e8a70ff2a57a5780dcbb892","name":"默认节次"},
              {"id":"9a8ff1144e336325f8d0d7e868d3a895","name":"小节次"}]}
        """.trimIndent()
        val list = SmartClassParser.parseNodeTypes(body)
        assertEquals(2, list.size)
        assertEquals("默认节次", list[0].name)
        assertEquals("小节次", list[1].name)
    }

    // ——— 空教室（核心）———

    @Test
    fun `解析空教室时段 - 实测响应`() {
        val body = """
            {"code":0,"msg":"success","data":[{
              "nodeId":"f334c47648f6c8f9eb0bc8c416f217a2","nodeName":"第一大节",
              "startTime":"2000-01-01 07:58:00","endTime":"2000-01-01 09:35:00",
              "classroomItems":[
                {"classroomId":26353,"classroomName":"教学楼503","scheduleId":null,"noSeatRate":0.9,"seatCount":96},
                {"classroomId":26355,"classroomName":"教学楼507","scheduleId":null,"noSeatRate":0.72,"seatCount":72}]}]}
        """.trimIndent()
        val slots = SmartClassParser.parseRoomSlots(body)
        assertEquals(1, slots.size)
        val slot = slots[0]
        assertEquals("第一大节", slot.nodeName)
        assertEquals("07:58", slot.startHm)
        assertEquals("09:35", slot.endHm)
        assertEquals(2, slot.rooms.size)
        assertEquals("教学楼503", slot.rooms[0].name)
        assertEquals(96, slot.rooms[0].seatCount)
        assertEquals(0.9, slot.rooms[0].noSeatRate!!, 0.0001)
    }

    @Test
    fun `空座率为 null 时保持 null - 不能当成 0`() {
        // 实测：金物楼323 的 noSeatRate 就是 null（无数据）。
        // 若用 optDouble 的默认值 0.0，界面会显示"0% 空座"，语义完全相反。
        val body = """
            {"code":0,"data":[{"nodeId":"n","nodeName":"第一大节","startTime":"","endTime":"",
              "classroomItems":[{"classroomId":1,"classroomName":"金物楼323","noSeatRate":null,"seatCount":50}]}]}
        """.trimIndent()
        val room = SmartClassParser.parseRoomSlots(body).single().rooms.single()
        assertNull(room.noSeatRate)
        assertEquals(50, room.seatCount)
    }

    @Test
    fun `空座率真的为 0 时保留 0`() {
        val body = """
            {"code":0,"data":[{"nodeId":"n","nodeName":"第一大节","startTime":"","endTime":"",
              "classroomItems":[{"classroomId":1,"classroomName":"X","noSeatRate":0,"seatCount":40}]}]}
        """.trimIndent()
        val room = SmartClassParser.parseRoomSlots(body).single().rooms.single()
        assertEquals(0.0, room.noSeatRate!!, 0.0)
    }

    @Test
    fun `跳过没有教室的时段`() {
        // 实测某些时段 classroomItems 为空数组；展示空标题只让页面变长
        val body = """
            {"code":0,"data":[
              {"nodeId":"a","nodeName":"空时段","startTime":"","endTime":"","classroomItems":[]},
              {"nodeId":"b","nodeName":"有课室","startTime":"","endTime":"",
               "classroomItems":[{"classroomId":1,"classroomName":"X","noSeatRate":0.5,"seatCount":10}]}]}
        """.trimIndent()
        val slots = SmartClassParser.parseRoomSlots(body)
        assertEquals(listOf("有课室"), slots.map { it.nodeName })
    }

    @Test
    fun `空座率越界时钳制到 0 到 1`() {
        val body = """
            {"code":0,"data":[{"nodeId":"n","nodeName":"T","startTime":"","endTime":"",
              "classroomItems":[{"classroomId":1,"classroomName":"A","noSeatRate":1.8,"seatCount":1},
                                {"classroomId":2,"classroomName":"B","noSeatRate":-0.5,"seatCount":1}]}]}
        """.trimIndent()
        val rooms = SmartClassParser.parseRoomSlots(body).single().rooms
        assertEquals(1.0, rooms[0].noSeatRate!!, 0.0)
        assertEquals(0.0, rooms[1].noSeatRate!!, 0.0)
    }

    @Test
    fun `时间串异常时 startHm 返回空串而不抛异常`() {
        val body = """
            {"code":0,"data":[{"nodeId":"n","nodeName":"T","startTime":"bad","endTime":"",
              "classroomItems":[{"classroomId":1,"classroomName":"A","noSeatRate":0.5,"seatCount":1}]}]}
        """.trimIndent()
        val slot = SmartClassParser.parseRoomSlots(body).single()
        assertEquals("", slot.startHm)
        assertEquals("", slot.endHm)
    }

    // ——— 错误处理 ———

    @Test
    fun `code 非 0 时解析为空且能取到错误消息`() {
        val body = """{"code":-1,"msg":"服务端异常","data":null}"""
        assertTrue(SmartClassParser.parseBuildings(body).isEmpty())
        assertTrue(SmartClassParser.parseRoomSlots(body).isEmpty())
        assertEquals("服务端异常", SmartClassParser.errorMessage(body))
    }

    @Test
    fun `成功响应没有错误消息`() {
        assertNull(SmartClassParser.errorMessage("""{"code":0,"msg":"success","data":[]}"""))
    }

    @Test
    fun `非 JSON 响应必须算失败 - 不能当成空结果`() {
        // 网关维护页/WAF 挑战页/校园网认证门户会以 HTTP 200 返回 HTML。
        // 若这里返回 null，SmartClassApi.fetch 会把它当成"成功但结果为空"，
        // 界面显示"当前没有查询到无课教室"——一个貌似正常但错误的结论。
        for (bad in listOf("", "not json", "<html><body>502 Bad Gateway</body></html>", "[]", "null")) {
            assertNotNull("输入=$bad 必须判为失败", SmartClassParser.errorMessage(bad))
        }
        // JSON 但没有 code 字段：同样不是成功响应
        assertNotNull(SmartClassParser.errorMessage("""{"foo":1}"""))
    }

    @Test
    fun `非法输入不抛异常`() {
        for (bad in listOf("", "not json", "[]", "{}", """{"code":0}""", """{"code":0,"data":{}}""")) {
            assertTrue("输入=$bad", SmartClassParser.parseBuildings(bad).isEmpty())
            assertTrue("输入=$bad", SmartClassParser.parseRoomSlots(bad).isEmpty())
            assertTrue("输入=$bad", SmartClassParser.parseNodeTypes(bad).isEmpty())
        }
    }

    @Test
    fun `data 数组里混入非对象元素时跳过`() {
        val body = """{"code":0,"data":[{"id":"a","name":"A"},123,null,{"id":"b","name":"B"}]}"""
        assertEquals(listOf("A", "B"), SmartClassParser.parseBuildings(body).map { it.name })
    }
}
