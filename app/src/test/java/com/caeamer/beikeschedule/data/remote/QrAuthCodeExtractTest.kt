package com.caeamer.beikeschedule.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 二维码状态响应里授权码的兼容解析（服务端 data 可能是字符串或对象）。 */
class QrAuthCodeExtractTest {

    @Test
    fun `plain string is returned`() {
        assertEquals("abc", QrAuthApi.extractAuthCode("abc"))
        assertEquals("abc", QrAuthApi.extractAuthCode(null, "  abc  "))
    }

    @Test
    fun `object with authCode key`() {
        val obj = buildJsonObject { put("authCode", "c1") }
        assertEquals("c1", QrAuthApi.extractAuthCode(obj))
    }

    @Test
    fun `object with auth_code key`() {
        val obj = buildJsonObject { put("auth_code", "c2") }
        assertEquals("c2", QrAuthApi.extractAuthCode(obj))
    }

    @Test
    fun `nested data object`() {
        val inner = buildJsonObject { put("authCode", "c3") }
        val outer = buildJsonObject { put("data", inner) }
        assertEquals("c3", QrAuthApi.extractAuthCode(outer))
    }

    @Test
    fun `json primitive`() {
        assertEquals("c4", QrAuthApi.extractAuthCode(JsonPrimitive("c4")))
    }

    @Test
    fun `blank and missing return null`() {
        assertNull(QrAuthApi.extractAuthCode(null, "", "   "))
        assertNull(QrAuthApi.extractAuthCode(JsonObject(emptyMap())))
    }

    @Test
    fun `parse whole state body`() {
        val json = Json { ignoreUnknownKeys = true }
        // 模拟 pollState 对 {"code":1,"data":{"authCode":"zz"}} 的抽取
        val el = json.parseToJsonElement("""{"code":1,"data":{"authCode":"zz"}}""")
        val obj = el as JsonObject
        assertEquals("zz", QrAuthApi.extractAuthCode(obj["data"]))
    }
}
