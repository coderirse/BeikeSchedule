package com.caeamer.beikeschedule.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * smartclass 配置解密与请求签名单测。
 *
 * fixture `smartclass-config.json` 是 2026-09 真实抓取的 `/config.json`。
 * 期望值 `s0k6e5a1t3i46kglaz9` 是**实测解密结果**（用站点自己的 JS 库对拍确认），
 * 不是手工填的——所以这组用例能真正锁住算法参数（AES-256-CBC/PKCS7、Key/IV 取 UTF-8 字节）。
 */
class SmartClassCryptoTest {

    private fun loadConfigJson(): String =
        requireNotNull(javaClass.classLoader?.getResource("smartclass-config.json")) {
            "缺少 fixture: smartclass-config.json"
        }.readText(Charsets.UTF_8)

    /** 从 fixture 里取出 domainConfig 字段（不引 JSON 库，避免与实现解耦失败）。 */
    private fun domainConfigHex(): String {
        val m = Regex("\"domainConfig\"\\s*:\\s*\"([0-9a-fA-F]+)\"").find(loadConfigJson())
        return requireNotNull(m) { "fixture 里没有 domainConfig" }.groupValues[1]
    }

    // ——— 解密 ———

    @Test
    fun `解密真实 config - 取出 csrkKey`() {
        val key = SmartClassCrypto.extractCsrkKey(domainConfigHex())
        assertEquals("s0k6e5a1t3i46kglaz9", key)
    }

    @Test
    fun `解密结果与内置兜底 key 一致`() {
        // 内置兜底值来自同一次实测。两者一致说明"兜底可用"；
        // 若将来服务端轮换 key，这条会先失败，提示需要更新内置值。
        assertEquals(
            SmartClassCrypto.BUILT_IN_CSRK_KEY,
            SmartClassCrypto.extractCsrkKey(domainConfigHex()),
        )
    }

    @Test
    fun `非法密文安全返回 null`() {
        assertNull(SmartClassCrypto.extractCsrkKey(""))
        assertNull(SmartClassCrypto.extractCsrkKey("zzzz"))
        assertNull(SmartClassCrypto.extractCsrkKey("00"))
        // 奇数长度 hex：不能抛异常
        assertNull(SmartClassCrypto.extractCsrkKey("abc"))
        // 合法 hex 但内容不是密文
        assertNull(SmartClassCrypto.extractCsrkKey("00112233445566778899aabbccddeeff"))
    }

    @Test
    fun `hex 大小写均可解析`() {
        val lower = domainConfigHex().lowercase()
        val upper = domainConfigHex().uppercase()
        assertEquals(
            SmartClassCrypto.extractCsrkKey(lower),
            SmartClassCrypto.extractCsrkKey(upper),
        )
    }

    // ——— token 生成（逐位取字符）———

    @Test
    fun `token 长度恒为 13`() {
        val t = SmartClassCrypto.genToken("s0k6e5a1t3i46kglaz9", 1789564775269L)
        assertNotNull(t)
        assertEquals(13, t!!.length)
    }

    @Test
    fun `token 逐位取字符 - 手算对照`() {
        // 时间戳 1789564775269 的各位数字依次是 1,7,8,9,5,6,4,7,7,5,2,6,9
        // key = "s0k6e5a1t3i46kglaz9"，下标 0..9 分别是 s,0,k,6,e,5,a,1,t,3
        //  → 1→'0' 7→'1' 8→'t' 9→'3' 5→'5' 6→'a' 4→'e' 7→'1' 7→'1' 5→'5' 2→'k' 6→'a' 9→'3'
        val token = SmartClassCrypto.genToken("s0k6e5a1t3i46kglaz9", 1789564775269L)
        assertEquals("01t35ae115ka3", token)
    }

    @Test
    fun `token 只用到 key 的前 10 位`() {
        // 下标只会是 0..9，所以第 11 位及以后的字符不影响结果——
        // 这也解释了为什么 csrkKey 长度必须 ≥ 10。
        val a = SmartClassCrypto.genToken("0123456789AAAAAAAA", 1789564775269L)
        val b = SmartClassCrypto.genToken("0123456789ZZZZZZZZ", 1789564775269L)
        assertEquals(a, b)
    }

    @Test
    fun `key 过短返回 null`() {
        assertNull(SmartClassCrypto.genToken("", 1789564775269L))
        assertNull(SmartClassCrypto.genToken("012345678", 1789564775269L))   // 9 位
        assertNotNull(SmartClassCrypto.genToken("0123456789", 1789564775269L))
    }

    @Test
    fun `时间戳非法返回 null`() {
        assertNull(SmartClassCrypto.genToken("s0k6e5a1t3i46kglaz9", 0L))
        assertNull(SmartClassCrypto.genToken("s0k6e5a1t3i46kglaz9", -1L))
    }

    @Test
    fun `同一时间戳结果稳定 - 跨调用可复现`() {
        val ts = 1789564775269L
        val first = SmartClassCrypto.genToken("s0k6e5a1t3i46kglaz9", ts)
        repeat(5) {
            assertEquals(first, SmartClassCrypto.genToken("s0k6e5a1t3i46kglaz9", ts))
        }
    }

    // ——— URL 签名 ———

    @Test
    fun `无查询串时用问号拼接`() {
        val signed = SmartClassCrypto.sign(
            "https://ustb.smartclass.cn/general/api/open/building/listBuildings",
            "s0k6e5a1t3i46kglaz9",
            1789564775269L,
        )
        assertTrue(signed, signed.endsWith("?csrkToken=01t35ae115ka3"))
    }

    @Test
    fun `已有查询串时用与号拼接`() {
        val signed = SmartClassCrypto.sign(
            "https://ustb.smartclass.cn/x?a=1",
            "s0k6e5a1t3i46kglaz9",
            1789564775269L,
        )
        assertTrue(signed, signed.endsWith("&csrkToken=01t35ae115ka3"))
    }

    @Test
    fun `key 非法时原样返回 URL - 不抛异常`() {
        val url = "https://ustb.smartclass.cn/x"
        assertEquals(url, SmartClassCrypto.sign(url, "", 1789564775269L))
    }

    // ——— 服务端"签名被拒"识别 ———

    @Test
    fun `识别签名被拒的响应`() {
        // 实测原文；用于触发"重新拉取 config"的自动恢复路径
        assertTrue(
            SmartClassCrypto.isTokenRejected(
                """{"code":-1,"msg":"csrf key validate error","data":null}""",
            ),
        )
        assertTrue(
            SmartClassCrypto.isTokenRejected(
                """{"code":-1,"msg":"CSRF Key Validate Error","data":null}""",
            ),
        )
    }

    @Test
    fun `正常响应与其它错误不算签名被拒`() {
        assertFalse(SmartClassCrypto.isTokenRejected("""{"code":0,"msg":"success","data":[]}"""))
        // 不带 token 时服务端返回这个（是构造问题，不是 key 失效）——不应触发重取 key
        assertFalse(SmartClassCrypto.isTokenRejected("""{"code":-1,"msg":"服务端异常","data":null}"""))
        assertFalse(SmartClassCrypto.isTokenRejected(""))
    }

    // ——— 常量防误改 ———

    @Test
    fun `配置密钥指纹未变`() {
        // 这组 Key/IV 是逆向 + 实测确定的，改动会让解密立刻失效。
        // 用指纹而非明文断言，避免把密钥明文散落在测试里。
        assertEquals("ca4aa0ee2e54c7ff", SmartClassCrypto.configKeyFingerprint())
    }
}
