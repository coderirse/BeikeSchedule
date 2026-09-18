package com.caeamer.beikeschedule.data.remote

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * 单独一个 DataStore 文件存 smartclass 相关的少量配置。
 *
 * corruptionHandler 必加（与 `SettingsStore` 同一教训）：settings.preferences_pb 一旦损坏
 * （写中断/存储写满/恢复异常），默认实现会抛 CorruptionException，让收集协程每次启动都崩且无法自愈，
 * 用户只能清应用数据。这里只存一个 csrkKey，损坏时重置为默认空值即可，下次会重新从服务端拉。
 */
private val Context.smartClassStore by preferencesDataStore(
    name = "smartclass",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * `csrkKey` 与"服务器时间基准"的来源。
 *
 * 抽成接口是为了让 [com.caeamer.beikeschedule.data.repo.FreeRoomRepository] 可以在单测里
 * 替换掉它（它依赖 `Context`/DataStore，纯 JVM 测试跑不起来）。
 */
interface SmartClassKeySource {

    /**
     * 取一个可用的 `csrkKey`，并确保时钟已校正。
     *
     * @param forceRefresh true 时跳过后两层，强制重新拉取 `/config.json`（用于签名被拒后的重试）
     */
    suspend fun csrkKey(forceRefresh: Boolean = false): String

    /** 用于签名的时间戳：本机时间 + 服务器时间差 + 安全余量。 */
    fun signingTimeMillis(): Long

    /** 校正服务器时钟；失败时保留上次的值。 */
    suspend fun syncClock()

    /** 丢弃缓存，下次 [csrkKey] 会重新拉取。 */
    suspend fun invalidate()
}

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
 * 上层应调用 [invalidate] 后重试一次（会强制走第 1 层重新拉取）——
 * 注意**每个接口都要走那次重试**，只覆盖一半会让自愈整条失效。
 *
 * 时间戳必须用**服务器时间**：实测 token 的有效窗口是 `[现在, 现在+5分钟]`，
 * 设备时钟慢 1 分钟就会全部失败。见 [syncClock]。
 */
class SmartClassKeyProvider(
    private val context: Context,
    private val api: SmartClassDataSource,
) : SmartClassKeySource {

    private val key = stringPreferencesKey("csrk_key")

    /** 服务器时间与本机时间的差值（毫秒）；0 表示尚未校正或校正失败。 */
    @Volatile
    private var clockOffsetMs: Long = 0L

    override suspend fun csrkKey(forceRefresh: Boolean): String {
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
    override fun signingTimeMillis(): Long =
        System.currentTimeMillis() + clockOffsetMs + SmartClassCrypto.TOKEN_TIME_SKEW_MS

    /**
     * 校正服务器时钟；失败时保持上次的值（不要归零，否则会把已校正的结果丢掉）。
     *
     * 首选 `/config.json` 的 `Date` 响应头：该请求不需要签名，所以**不存在
     * "要签名才能校时、要校时才能签名"的循环依赖**（设备时钟偏得越远，签名越不可能通过，
     * 越需要校时）。服务端不返回 Date 头时才退回签名调 `/Home/GettimeDif`。
     *
     * 此前这里的调用漏了签名参数，`serverTimeMillis()` 直接返回 null，
     * 于是 clockOffsetMs 永远是 0 —— 校时功能实际从未生效。
     */
    override suspend fun syncClock() {
        var server = runCatching { api.serverDateMillis() }.getOrNull()
        if (server == null) {
            server = runCatching {
                api.serverTimeMillis(SignedRequest(csrkKey(), signingTimeMillis()))
            }.getOrNull()
        }
        server?.takeIf { it > 0L }?.let { clockOffsetMs = it - System.currentTimeMillis() }
    }

    /** 丢弃缓存，下次 [csrkKey] 会重新拉取。 */
    override suspend fun invalidate() {
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
