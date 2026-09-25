package com.caeamer.beikeschedule.ui.cloud

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.caeamer.beikeschedule.BuildConfig
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.backup.CloudSync
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.CloudApi
import com.caeamer.beikeschedule.data.remote.JwSessionTicket
import com.caeamer.beikeschedule.data.remote.QrAuthApi
import com.caeamer.beikeschedule.data.remote.QrTargetTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 云登录流程状态：Browsing（登录教务）→ SigningIn（换 token）→ Done / Failed。 */
sealed interface CloudLoginUiState {
    data object Browsing : CloudLoginUiState
    data class SigningIn(val xh: String) : CloudLoginUiState
    data object Done : CloudLoginUiState
    data class Failed(val message: String) : CloudLoginUiState
}

/**
 * 云登录：教务统一认证成功后抓到学号 → 向服务端换 token（账号 = 学号，登录即注册）。
 * 服务端 401/网络异常都可重试（重新注入身份脚本即可，教务会话还在）。
 *
 * **扫码的可靠路径**：SSO 页面的二维码 iframe 自带轮询在慢网下会 abort→reload→换码
 * （见 [QrAuthApi] 注释），这里不依赖它——WebView 的子资源请求经 [onSubresource]
 * 原生捕获二维码 sid，App 自己长轮询，授权到达后由界面把主框架导航到 SSO 回调。
 * 同时对外提供"最新 sid"给"复制微信授权链接"用（同机扫码的替代入口）。
 */
class CloudLoginViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow<CloudLoginUiState>(CloudLoginUiState.Browsing)
    val state: StateFlow<CloudLoginUiState> = _state

    private val tracker = QrTargetTracker()

    /** 当前展示二维码的 sid（空 = 还没有二维码）。 */
    private val _qrSid = MutableStateFlow<String?>(null)
    val qrSid: StateFlow<String?> = _qrSid

    /** 二维码被微信授权后，应导航到的 SSO 回调地址（非空即已授权）。 */
    private val _authorizedUrl = MutableStateFlow<String?>(null)
    val authorizedUrl: StateFlow<String?> = _authorizedUrl

    /** 原生轮询看到的扫码进度提示（页面自带轮询已被禁用，状态由 App 提供）。 */
    private val _qrHint = MutableStateFlow<String?>(null)
    val qrHint: StateFlow<String?> = _qrHint

    private val pollingJobs = ConcurrentHashMap<String, Job>()

    /** 串行化 ensurePolling 的 check-then-put（WebView IO 线程与主线程并发入口）。 */
    private val pollingLock = Any()

    /**
     * 用户通过「复制授权链接」锁定的 sid：页面刷新换码时**不得**停掉对它的轮询，
     * 否则微信里刚点完授权，App 这边已经没人听结果了（表现为「授权了没反应」）。
     */
    @Volatile
    private var pinnedSid: String? = null

    /** WebView 子资源请求（WebView IO 线程调用）。 */
    fun onSubresource(url: String) {
        val changed = tracker.onRequest(url)
        if (!changed) return
        _qrSid.value = tracker.latestSid
        if (_authorizedUrl.value == null && _qrHint.value == null) {
            // 有码可扫时给一点存在感，避免用户以为卡死
            _qrHint.value = "等待扫码 · 也可点「复制授权链接」在微信里打开"
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "qr sid captured: ${tracker.latestSid}")
        val keep = tracker.snapshot().takeLast(MAX_POLL_TARGETS)
        val keepSids = keep.map { it.sid }.toSet() + listOfNotNull(pinnedSid, _qrSid.value)
        // 页面会不断刷新二维码，被淘汰的 sid 不再轮询；**已锁定/正在展示的 sid 除外**
        synchronized(pollingLock) {
            pollingJobs.entries.filter { it.key !in keepSids }.forEach { (sid, _) ->
                pollingJobs.remove(sid)?.cancel()
            }
            keep.forEach(::ensurePolling)
            pinnedSid?.let { pin -> tracker.snapshot().find { it.sid == pin }?.let(::ensurePolling) }
        }
    }

    /** 复制授权链接时锁定该 sid，保证微信侧授权结果一定有人轮询。 */
    fun pinSidForWeChat(sid: String) {
        pinnedSid = sid
        tracker.snapshot().find { it.sid == sid }?.let(::ensurePolling)
    }

    private fun ensurePolling(target: QrAuthApi.QrTarget) {
        if (_authorizedUrl.value != null) return
        // containsKey→put 必须原子：本方法会被 WebView IO 线程（onSubresource）与主线程
        // （pinSidForWeChat）并发调用，竞态下同一 sid 双轮询会互吃 205（并发冲突），
        // 恰好复现"授权了没反应"
        synchronized(pollingLock) {
            if (_authorizedUrl.value != null) return
            if (pollingJobs.containsKey(target.sid)) return
            pollingJobs[target.sid] = viewModelScope.launch(Dispatchers.IO) {
                try {
                    // 总时长上限：pinned 会话在用户一直不扫时不能永续轮询
                    val deadline = System.currentTimeMillis() + MAX_POLL_DURATION_MS
                    var netFailures = 0
                    while (isActive && _authorizedUrl.value == null) {
                        if (System.currentTimeMillis() > deadline) {
                            _qrHint.value = if (target.sid == pinnedSid) {
                                "授权链接已超时失效，请重新复制链接到微信确认"
                            } else {
                                "二维码已超时失效，请点页面上的刷新或重进本页"
                            }
                            break
                        }
                        val qrState = try {
                            QrAuthApi.pollState(target.sid).also { netFailures = 0 }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            netFailures++
                            if (netFailures >= 3) {
                                _qrHint.value = "网络异常，正在重试…（请检查网络/VPN）"
                            }
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "qr poll sid=${target.sid} error=${e.message}")
                            }
                            delay(POLL_INTERVAL_MS * 2)
                            continue
                        }
                        when (qrState.code) {
                            1 -> {
                                val code = qrState.authCode
                                if (code.isNullOrBlank()) {
                                    // 已授权但还没吐出 code：继续要，绝不能 break（否则永久卡住）
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "qr poll sid=${target.sid} code=1 missing authCode")
                                    }
                                    _qrHint.value = "微信已确认授权，正在进入教务系统…"
                                    delay(POLL_INTERVAL_MS)
                                    continue
                                }
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "qr poll sid=${target.sid} code=1 -> navigate")
                                }
                                _qrHint.value = "授权成功，正在进入教务系统…"
                                _authorizedUrl.value = QrAuthApi.authorizeUrl(target, code)
                                break
                            }
                            // 二维码失效/sid 非法/方法不允许等：该 sid 到此为止
                            3, 101, 102, 202, 203 -> {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "qr poll sid=${target.sid} dead code=${qrState.code}")
                                }
                                if (target.sid != pinnedSid) {
                                    _qrHint.value = "二维码已失效，请点页面上的刷新或重进本页"
                                } else {
                                    _qrHint.value = "授权链接已失效，请重新复制链接到微信确认"
                                }
                                break
                            }
                            // 2=已扫码待确认：给用户一个明确提示（页面不再自己显示状态）
                            2 -> {
                                _qrHint.value = "微信已扫码，请在微信里点确认授权"
                                delay(POLL_INTERVAL_MS)
                            }
                            // 4=等待超时、205=并发冲突：继续等（受总时长上限约束）
                            else -> {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "qr poll sid=${target.sid} code=${qrState.code}")
                                }
                                delay(POLL_INTERVAL_MS)
                            }
                        }
                    }
                } finally {
                    pollingJobs.remove(target.sid)
                }
            }
        }
    }

    /** 身份脚本回传学号：调 /api/bs/auth/login 换 token 并落盘。 */
    fun onIdentity(xh: String, xm: String) {
        if (_state.value is CloudLoginUiState.SigningIn) return // 防重复提交
        if (xh.isBlank()) {
            _state.value = CloudLoginUiState.Failed("未获取到学号，请确认已登录教务系统")
            return
        }
        _state.value = CloudLoginUiState.SigningIn(xh)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { CloudApi.login(xh, xm, jwTicket()) }
                settings.saveCloudAccount(
                    SettingsStore.CloudAccount(xh = result.xh, name = result.name, token = result.token),
                )
                _state.value = CloudLoginUiState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CloudLoginUiState.Failed(e.message ?: "登录失败，请重试")
            }
        }
    }

    fun onError(message: String) {
        if (_state.value is CloudLoginUiState.SigningIn) return // 换 token 失败有自己的错误展示
        _state.value = CloudLoginUiState.Failed(humanizeIdentityError(message))
    }

    /**
     * 授权回调导航失败（authCode 一次性，WebView 重建等导致没接住）：
     * 复位授权地址并明示用户重新走授权，而不是静默丢掉微信侧已确认的结果。
     */
    fun onAuthorizedNavigationLost() {
        if (_authorizedUrl.value == null) return
        _authorizedUrl.value = null
        _qrHint.value = "授权回调超时，请重新扫码或复制授权链接到微信确认"
    }

    /** 从失败态重试：回 Browsing 重新注入身份脚本（教务会话通常仍在）。 */
    fun retry() {
        _state.value = CloudLoginUiState.Browsing
    }

    /**
     * 取教务 SESSION cookie 并密封成 jwTicket，交给服务端去教务系统核实身份。
     *
     * 只在 byyt 域取（`/user/me` 是它的同源接口）；取不到就返回 null——服务端此时会
     * 明确要求"在教务系统登录后重试/更新 App"，而不是退回到"只认学号+姓名"的弱校验。
     * 调用发生在 IO 线程：CookieManager.getCookie 可能阻塞。
     */
    private fun jwTicket(): String? {
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(JW_SESSION_ORIGIN)
        }.getOrNull().orEmpty()
        return JwSessionTicket.seal(cookie)
    }

    /**
     * 身份脚本/桥错误 → 中文。
     * 脚本已改为 `教务接口 /user/me 返回 HTTP 404` 这类带路径文案；
     * 兜底把历史的 `Error: HTTP 404` 也翻译掉，避免界面出现英文裸错误。
     */
    private fun humanizeIdentityError(message: String): String = when {
        message.isBlank() -> "获取学号失败，请重试"
        message.contains("当前不在教务页") -> message
        message.contains("HTTP 404") && message.contains("教务接口") -> "$message（可能尚未登录或不在教务主页）"
        message == "Error: HTTP 404" || message == "HTTP 404" ->
            "教务接口返回 404，请先进入教务主页再获取"
        message.startsWith("Error: ") -> message.removePrefix("Error: ")
        else -> message
    }

    private companion object {
        const val TAG = "BeikeCloud"

        /** 教务 SESSION 所在 origin（与 [com.caeamer.beikeschedule.import.JwWebView] 的 JW_HOME 一致）。 */
        const val JW_SESSION_ORIGIN = "https://byyt.ustb.edu.cn"

        /** 并发长轮询的 sid 上限（每个 sid 同一时刻只有一条在途请求）。 */
        const val MAX_POLL_TARGETS = 3
        const val POLL_INTERVAL_MS = 600L

        /** 单个 sid 轮询总时长上限（覆盖"复制链接后迟迟不扫"的 pinned 会话）。 */
        const val MAX_POLL_DURATION_MS = 5 * 60_000L
    }
}
