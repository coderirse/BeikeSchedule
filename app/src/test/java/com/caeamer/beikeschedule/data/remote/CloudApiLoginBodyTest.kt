package com.caeamer.beikeschedule.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 登录请求体：服务端靠 jwTicket 去教务系统核实身份，缺字段时不能发 `null`。 */
class CloudApiLoginBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `带票据时写入 jwTicket`() {
        val body = json.parseToJsonElement(CloudApi.loginBody("U202440760", "李智超", "TICKET")).jsonObject
        assertEquals("U202440760", body["xh"]?.jsonPrimitive?.content)
        assertEquals("李智超", body["xm"]?.jsonPrimitive?.content)
        assertEquals("TICKET", body["jwTicket"]?.jsonPrimitive?.content)
    }

    @Test
    fun `无票据时不写字段而不是写 null`() {
        val body = json.parseToJsonElement(CloudApi.loginBody("U202440760", "李智超", null)).jsonObject
        assertNull(body["jwTicket"])
        val blank = json.parseToJsonElement(CloudApi.loginBody("U202440760", "李智超", "  ")).jsonObject
        assertNull(blank["jwTicket"])
    }
}
