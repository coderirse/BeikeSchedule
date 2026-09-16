package com.caeamer.beikeschedule.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** 单独一个 DataStore 文件存 smartclass 相关的少量配置。 */
private val Context.smartClassStore by preferencesDataStore(name = "smartclass")

/**
 * `csrkKey` 的获取与缓存，三层兜底。
 *
 * ## 为什么需要三层
 *
 * `csrkKey` 由服务端下发（藏在加密的 `domainConfig` 里），**服务端可以轮换它**。
 * 一旦轮换，所有请求会返回 `csrf key validate error`。所以策略是：
 *
 * 1. **运行时解密** `/config.json` —— 正常路径。服务端换 key 也能自动跟上，用户无感。
 * 2. **本地缓存** —— `/config.json` 临时拉不到时用上次成功解出的 key，避免网络抖动导致不可用。
 * 3. **内置常量** —— 首次运行且网络不可用时的最后兜底。
 *
 * 缓存与内置值都可能过期。过期时服务端返回 `csrf key validate error`，
 * 上层应调用 [invalidate] 后重试一次（会强制走第 1 层重新拉取）。
 *
 * 时间戳必须用**服务器时间**：实测 token 的有效窗口是 `[现在, 现在+5分钟]`，
 * 设备时钟慢 1 分钟就会全部失败。所以这里先调 `/Home/GettimeDif` 取时间差。
 */
class SmartClassKeyProvider(
    private val context: Context,
    private val api: SmartClassApi,
) {

    private val key = stringPreferencesKey("csrk_key")

    /** 服务器时间与本机时间的差值（毫秒）；0 表示尚未校正或校正失败。 */
    @Volatile
    private var clockOffsetMs: Long = 0L

    /**
     * 取一个可用的 `csrkKey`，并确保时钟已校正。
     *
     * @param forceRefresh true 时跳过后两层，强制重新拉取 `/config.json`（用于签名被拒后的重试）
     */
    suspend fun csrkKey(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedKey()?.let { return it }
        }
        // 1) 运行时解密
        fetchFromServer()?.let {
            saveCached(it)
            return it
        }
        // 2) 本地缓存（forceRefresh 时也允许，避免"拉取失败 + 缓存可用"却仍然失败）
        cachedKey()?.let { return it }
        // 3) 内置兜底
        return SmartClassCrypto.BUILT_IN_CSRK_KEY
    }

    /**
     * 用于签名的时间戳：本机时间 + 服务器时间差 + 安全余量。
     *
     * [SmartClassCrypto.TOKEN_TIME_SKEW_MS] 的余量是必要的：窗口只向未来开放，
     * 当前时刻刚好落在边界上（或设备时钟略慢）就会失败。
     */
    fun signingTimeMillis(): Long =
        System.currentTimeMillis() + clockOffsetMs + SmartClassCrypto.TOKEN_TIME_SKEW_MS

    /** 校正时钟；失败时保持上次的值（不要归零，否则会把已校正的结果丢掉）。 */
    suspend fun syncClock() {
        api.serverTimeMillis()?.let { server ->
            clockOffsetMs = server - System.currentTimeMillis()
        }
    }

    /** 丢弃缓存，下次 [csrkKey] 会重新拉取。 */
    suspend fun invalidate() {
        runCatching { context.smartClassStore.edit { it.remove(key) } }
    }

    private suspend fun fetchFromServer(): String? = runCatching {
        val cfg = api.fetchDomainConfig() ?: return@runCatching null
        SmartClassCrypto.extractCsrkKey(cfg)
    }.getOrNull()

    private suspend fun cachedKey(): String? = runCatching {
        context.smartClassStore.data.first()[key]?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun saveCached(value: String) {
        runCatching { context.smartClassStore.edit { it[key] = value } }
    }
}
