package com.caeamer.beikeschedule.ui.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.BuildConfig
import com.caeamer.beikeschedule.data.backup.CloudSync
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.CloudApi
import com.caeamer.beikeschedule.data.remote.JwSessionTicket
import com.caeamer.beikeschedule.data.remote.QrAuthApi
import com.caeamer.beikeschedule.data.remote.QrTargetTracker
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.ExamsParser
import com.caeamer.beikeschedule.import.parser.GradesParser
import com.caeamer.beikeschedule.import.parser.JwParser
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 一键同步的界面状态。 */
sealed interface UnifiedSyncUiState {
    /** 登录阶段：WebView 展示扫码页（或正在静默跳回教务主页）。 */
    data object Login : UnifiedSyncUiState

    /** 正在执行 [current]，[results] 是已完成步骤。 */
    data class Running(val current: SyncStep, val results: List<SyncStepResult>) : UnifiedSyncUiState

    /** 需要用户决策（换学号 / 云端覆盖方向）。 */
    data class NeedDecision(val kind: DecisionKind, val title: String, val text: String) : UnifiedSyncUiState

    /** 全部步骤结束（可能含失败项）。 */
    data class Done(val results: List<SyncStepResult>, val note: String? = null) : UnifiedSyncUiState

    /** 登录页层面失败（页面加载失败等），整体无法继续。 */
    data class Failed(val message: String) : UnifiedSyncUiState
}

enum class DecisionKind { ACCOUNT_SWITCH, CLOUD_MODE }

/**
 * 一键同步：一次扫码把三件事跑完。
 *
 * ① 身份（`jw_identity.js`）→ ② 云 token（`/api/bs/auth/login`，带教务会话票据）
 * → ③ 课表（`jw_import.js`，直接入库）→ ④ 成绩/考试/学籍/学业进度（`jw_grades.js`，直接入库）
 * → ⑤ 必要时上传一次云备份。
 *
 * 三处历史修正原样保留（都是线上踩出来的，删一处就会复发）：
 * 1. 二维码 sid 由 `shouldInterceptRequest` 原生捕获，App 自己长轮询 `/connect/state`，
 *    不依赖页面自带轮询（慢网下它会超时 reload 换码）；
 * 2. `QR_POLL_SUPPRESS_JS` 禁用微认证页自带轮询（否则与 App 轮询并发互吃 205）；
 * 3. 授权回调跳转带看门狗（跳转被静默丢弃时复位重出码，见界面层）；
 * 4. 站内 http/https 白名单与 `PAGE_FIX_JS` 渲染修正（见 [com.caeamer.beikeschedule.import.JwWebView]）。
 */
class UnifiedSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val repo = ScheduleRepository(app)

    private val _state = MutableStateFlow<UnifiedSyncUiState>(UnifiedSyncUiState.Login)
    val state: StateFlow<UnifiedSyncUiState> = _state

    // ---- 扫码登录（沿用旧云登录页的实现）----

    private val tracker = QrTargetTracker()

    private val _qrSid = MutableStateFlow<String?>(null)
    val qrSid: StateFlow<String?> = _qrSid

    private val _qrHint = MutableStateFlow<String?>(null)
    val qrHint: StateFlow<String?> = _qrHint

    private val _authorizedUrl = MutableStateFlow<String?>(null)
    val authorizedUrl: StateFlow<String?> = _authorizedUrl

    private val pollingJobs = ConcurrentHashMap<String, Job>()
    private val pollingLock = Any()

    @Volatile
    private var pinnedSid: String? = null

    /** 需要界面在 WebView 上执行的脚本资源名（如 `import/jw_import.js`）。 */
    private val _jsRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val jsRequests: SharedFlow<String> = _jsRequests

    // ---- 执行引擎 ----

    private sealed interface ScriptOutcome {
        data class Ok(val args: List<String>) : ScriptOutcome
        data class Err(val message: String) : ScriptOutcome
    }

    private var runJob: Job? = null
    private var scriptGate: CompletableDeferred<ScriptOutcome>? = null
    private var currentStep: SyncStep? = null
    private var switchGate: CompletableDeferred<Boolean>? = null
    private var cloudModeGate: CompletableDeferred<CloudMode>? = null

    private val results = linkedMapOf<SyncStep, SyncStepResult>()
    private var mode = CloudMode.UNDECIDED
    private var xh: String? = null
    private var xm: String = ""
    private var token: String? = null
    private var runNote: String? = null

    init {
        // 行级解析失败接到 logcat：教务改字段格式导致整行/整表被静默跳过时有据可查
        JwParser.rowErrorLogger = { row, error ->
            Log.w(TAG, "课表行解析失败，已跳过: $row", error)
        }
    }

    // ---- 界面回调 ----

    /**
     * 进入页面时清掉上一次流程留下的终态。
     *
     * ViewModel 是 Activity 级的（离开组合不销毁）：不清的话，同一进程内再次进入本页
     * 会直接看到上一次的汇总，而 onPageReady 又会因终态被忽略，等于"进不去"。
     */
    fun resetIfFinished() {
        if (runJob?.isActive == true) return
        val finished = _state.value is UnifiedSyncUiState.Done || _state.value is UnifiedSyncUiState.Failed
        if (!finished) return
        results.clear()
        mode = CloudMode.UNDECIDED
        runNote = null
        xh = null
        xm = ""
        token = null
        _qrSid.value = null
        _qrHint.value = null
        _authorizedUrl.value = null
        _state.value = UnifiedSyncUiState.Login
    }

    /** WebView 到达教务主页（onMainPage）：自动开跑；执行中/已完成的重复回调忽略。 */
    fun onPageReady() {
        if (runJob?.isActive == true) return
        if (_state.value is UnifiedSyncUiState.Done) return
        startRun(retryOnlyFailed = false)
    }

    /** 「手动获取」：页面停在登录页或 onMainPage 没触发时，手动重跑一次全流程。 */
    fun manualRestart() {
        if (runJob?.isActive == true) return
        startRun(retryOnlyFailed = false)
    }

    /** 汇总页「重试失败项」：只重跑没成功的步骤。 */
    fun retryFailed() {
        if (runJob?.isActive == true) return
        startRun(retryOnlyFailed = true)
    }

    /** 登录页层面失败（页面加载失败/SSL 等）。 */
    fun onPageError(message: String) {
        if (runJob?.isActive == true) return
        if (_state.value is UnifiedSyncUiState.Failed) return
        _qrHint.value = null
        _authorizedUrl.value = null
        _state.value = UnifiedSyncUiState.Failed(message)
    }

    /** 失败态「重试」：复位到登录阶段（界面同时 reload 页面）。 */
    fun resetToLogin() {
        if (runJob?.isActive == true) return
        _qrHint.value = null
        _authorizedUrl.value = null
        _state.value = UnifiedSyncUiState.Login
    }

    /** 界面离开组合：取消在跑的流程（写库是单事务，取消不会留下半截数据）。 */
    fun cancel() {
        runJob?.cancel()
        runJob = null
        scriptGate = null
        switchGate?.cancel()
        cloudModeGate?.cancel()
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
    }

    // ---- 脚本桥回调（界面已切到主线程）----

    fun onIdentity(xh: String, xm: String) =
        completeScript(SyncStep.IDENTITY) { ScriptOutcome.Ok(listOf(xh, xm)) }

    fun onImportResult(
        semester: String,
        published: String,
        courses: String,
        sections: String,
        weekDates: String,
        calendar: String,
    ) = completeScript(SyncStep.TIMETABLE) {
        ScriptOutcome.Ok(listOf(semester, published, courses, sections, weekDates, calendar))
    }

    fun onGradesResult(
        gpaJson: String,
        gradesJson: String,
        userJson: String,
        xsxxJson: String,
        semJson: String,
        examsJson: String,
        xflbyqJson: String,
        bxkqkJson: String,
    ) = completeScript(SyncStep.GRADES) {
        ScriptOutcome.Ok(
            listOf(gpaJson, gradesJson, userJson, xsxxJson, semJson, examsJson, xflbyqJson, bxkqkJson),
        )
    }

    /** 脚本报错：只有当前步骤的报错才作数（切页/重入可能带来旧脚本的残留回调）。 */
    fun onScriptError(step: SyncStep, message: String) =
        completeScript(step) { ScriptOutcome.Err(message) }

    private fun completeScript(step: SyncStep, outcome: () -> ScriptOutcome) {
        if (currentStep != step) return
        scriptGate?.complete(outcome())
    }

    // ---- 用户决策 ----

    fun answerAccountSwitch(switchToNew: Boolean) {
        switchGate?.complete(switchToNew)
    }

    fun answerCloudMode(choice: CloudMode) {
        cloudModeGate?.complete(choice)
    }

    // ---- 执行 ----

    private fun startRun(retryOnlyFailed: Boolean) {
        if (runJob?.isActive == true) return
        runJob = viewModelScope.launch {
            if (!retryOnlyFailed) {
                results.clear()
                mode = CloudMode.UNDECIDED
                runNote = null
                xh = null
                xm = ""
                token = null
            }
            // 首轮先跑"身份 + 云账号"（后者包含云端探测与决策），再按最终方向补齐后续步骤；
            // 重试则只跑失败项（planner 保证顺序与跳过规则一致）
            val head = if (retryOnlyFailed) {
                SyncPlanner.retrySteps(mode, results)
            } else {
                listOf(SyncStep.IDENTITY, SyncStep.CLOUD_TOKEN)
            }
            head.forEach { executeStep(it) }
            if (!retryOnlyFailed) {
                SyncPlanner.steps(mode).drop(2).forEach { executeStep(it) }
                if (mode == CloudMode.RESTORE) {
                    // 抓取按设计跳过：汇总里标出来，避免用户以为"这两步没跑"
                    results[SyncStep.TIMETABLE] =
                        SyncStepResult(SyncStep.TIMETABLE, SyncStatus.SKIPPED, "已跳过（用云端数据）")
                    results[SyncStep.GRADES] =
                        SyncStepResult(SyncStep.GRADES, SyncStatus.SKIPPED, "已跳过（用云端数据）")
                }
            }
            // 按步骤固有顺序展示（RESTORE 模式下抓取两项被标记跳过，插在最后才自然）
            _state.value = UnifiedSyncUiState.Done(results.values.sortedBy { it.step.ordinal }, runNote)
        }
    }

    private suspend fun executeStep(step: SyncStep) {
        if (results[step]?.status == SyncStatus.OK) return
        val result = when (step) {
            SyncStep.IDENTITY -> stepIdentity()
            SyncStep.CLOUD_TOKEN -> stepCloudToken()
            SyncStep.TIMETABLE -> stepTimetable()
            SyncStep.GRADES -> stepGrades()
            SyncStep.BACKUP -> stepBackup()
            SyncStep.RESTORE -> stepRestore()
        }
        results[step] = result
        if (BuildConfig.DEBUG) Log.d(TAG, "step ${step.name} -> ${result.status} ${result.message.orEmpty()}")
    }

    private suspend fun awaitScript(step: SyncStep, asset: String): ScriptOutcome {
        val gate = CompletableDeferred<ScriptOutcome>()
        scriptGate = gate
        currentStep = step
        _state.value = UnifiedSyncUiState.Running(step, results.values.toList())
        _jsRequests.tryEmit(asset)
        val outcome = withTimeoutOrNull(STEP_TIMEOUT_MS) { gate.await() }
            ?: ScriptOutcome.Err("超时：${STEP_TIMEOUT_MS / 1000} 秒内没有返回结果")
        scriptGate = null
        currentStep = null
        return outcome
    }

    private suspend fun stepIdentity(): SyncStepResult = when (val out = awaitScript(SyncStep.IDENTITY, "import/jw_identity.js")) {
        is ScriptOutcome.Err -> failed(SyncStep.IDENTITY, humanize(out.message))
        is ScriptOutcome.Ok -> {
            val id = out.args.getOrElse(0) { "" }.trim()
            xm = out.args.getOrElse(1) { "" }.trim()
            if (id.isBlank()) {
                failed(SyncStep.IDENTITY, "未获取到学号，请确认已登录教务系统")
            } else {
                xh = id
                ok(SyncStep.IDENTITY, "学号 $id${if (xm.isBlank()) "" else " · $xm"}")
            }
        }
    }

    /**
     * 云账号：必要时先确认"换学号"，再换 token；随后探测云端备份并决定数据方向。
     * 探测失败一律按"不上传"处理——宁可这次不备份，也不能拿本机数据覆盖云端那份可能更全的备份。
     */
    private suspend fun stepCloudToken(): SyncStepResult {
        val id = xh ?: return failed(SyncStep.CLOUD_TOKEN, "未取得学号，无法登录云账号")

        val account = settings.cloudAccount.first()
        if (account.isLoggedIn && account.xh != id) {
            val title = "检测到不同学号"
            val text = "当前云账号是 ${account.xh}，这次登录的是 $id。" +
                "继续将切换云账号，本机课表/成绩会被 $id 的数据替换；旧账号的云端备份不会被删除。"
            val confirmed = askSwitch(title, text)
            if (!confirmed) return failed(SyncStep.CLOUD_TOKEN, "已取消切换云账号")
        }

        val login = try {
            withContext(Dispatchers.IO) { CloudApi.login(id, xm, jwTicket()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(SyncStep.CLOUD_TOKEN, e.message ?: "登录云账号失败")
        }
        settings.saveCloudAccount(
            SettingsStore.CloudAccount(xh = login.xh, name = login.name, token = login.token),
        )
        token = login.token

        // 云端探测 + 方向决策
        val envelope = try {
            withContext(Dispatchers.IO) { CloudApi.getBackup(login.token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val localHasData = repo.courses.first().isNotEmpty() || repo.grades.first().isNotEmpty()
        val initial = defaultCloudMode(
            probeFailed = envelope == null,
            cloudHasBackup = envelope?.exists == true,
            localHasData = localHasData,
        )
        mode = if (needsCloudDecision(initial)) {
            runNote = "云端已有备份，已按你的选择处理"
            askCloudMode()
        } else {
            initial
        }
        if (mode == CloudMode.NO_UPLOAD && envelope == null) {
            runNote = "云端状态未知（网络异常），本次未自动上传；可在「我的 → 云同步」手动备份"
        }
        val tail = when (mode) {
            CloudMode.RESTORE -> " · 将用云端数据恢复本机"
            CloudMode.UPLOAD -> " · 将上传本机数据"
            else -> ""
        }
        return ok(SyncStep.CLOUD_TOKEN, "已登录 ${login.xh}$tail")
    }

    private suspend fun askSwitch(title: String, text: String): Boolean {
        val gate = CompletableDeferred<Boolean>()
        switchGate = gate
        _state.value = UnifiedSyncUiState.NeedDecision(DecisionKind.ACCOUNT_SWITCH, title, text)
        val answer = gate.await()
        switchGate = null
        return answer
    }

    private suspend fun askCloudMode(): CloudMode {
        val gate = CompletableDeferred<CloudMode>()
        cloudModeGate = gate
        _state.value = UnifiedSyncUiState.NeedDecision(
            DecisionKind.CLOUD_MODE,
            "云端已有备份",
            "云端已有这个学号的备份，本机也有课表/成绩。请选择本次以哪边为准：\n" +
                "· 从云端恢复：用云端数据覆盖本机，并跳过本次抓取\n" +
                "· 用本机覆盖云端：继续抓取课表/成绩，然后上传覆盖云端\n" +
                "· 暂不上传：只抓取，不改动云端",
        )
        val choice = gate.await()
        cloudModeGate = null
        return choice
    }

    private suspend fun stepTimetable(): SyncStepResult {
        val out = awaitScript(SyncStep.TIMETABLE, "import/jw_import.js")
        if (out is ScriptOutcome.Err) return failed(SyncStep.TIMETABLE, "抓取失败：${out.message}")
        val args = (out as ScriptOutcome.Ok).args
        return try {
            val published = args.getOrElse(1) { "" }
            if (published.trim() == "0") return failed(SyncStep.TIMETABLE, "本学期课表尚未发布")
            val (xn, xq, name) = JwParser.parseCurrentSemester(args.getOrElse(0) { "" })
            val courses = JwParser.parseCourses(args.getOrElse(2) { "" })
            if (courses.isEmpty()) return failed(SyncStep.TIMETABLE, "未解析到课程，请确认已进入教务系统课表页")
            val weekCalendar = JwParser.parseWeekCalendar(args.getOrElse(5) { "" })
            // 节次时间缺失必须中止：commitImport 会先清空 section_time 再写入，
            // 空表落库后所有课程都算不出上课时间，上课提醒会静默全部失效。
            val sectionTimes = JwParser.parseSectionTimes(args.getOrElse(3) { "" })
            if (sectionTimes.isEmpty()) {
                return failed(SyncStep.TIMETABLE, "未获取到节次时间（queryKbjg 返回异常），请重试")
            }
            repo.commitImport(courses, sectionTimes)
            val previous = repo.settings.semester.first()
            repo.settings.saveSemester(
                previous.copy(
                    xn = xn,
                    xq = xq,
                    name = name.ifBlank { "$xn-$xq" },
                    firstMonday = JwParser.parseFirstMonday(args.getOrElse(4) { "" })
                        ?: weekCalendar.weekMondays.firstOrNull().orEmpty(),
                    totalWeeks = weekCalendar.totalWeeks.takeIf { it > 0 } ?: 20,
                    weekMondays = weekCalendar.weekMondays,
                ),
            )
            ok(SyncStep.TIMETABLE, "已导入 ${courses.count { !it.isUnscheduled }} 门课 · ${name.ifBlank { "$xn-$xq" }}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(SyncStep.TIMETABLE, "保存失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun stepGrades(): SyncStepResult {
        val out = awaitScript(SyncStep.GRADES, "import/jw_grades.js")
        if (out is ScriptOutcome.Err) return failed(SyncStep.GRADES, "抓取失败：${out.message}")
        val a = (out as ScriptOutcome.Ok).args
        return try {
            val gpaJson = a.getOrElse(0) { "" }
            val gradesJson = a.getOrElse(1) { "" }
            val userJson = a.getOrElse(2) { "" }
            val xsxxJson = a.getOrElse(3) { "" }
            val semJson = a.getOrElse(4) { "" }
            val examsJson = a.getOrElse(5) { "" }
            val xflbyqJson = a.getOrElse(6) { "" }
            val bxkqkJson = a.getOrElse(7) { "" }

            val grades = GradesParser.parseGrades(gradesJson)
            if (grades.isNotEmpty()) {
                repo.replaceGrades(grades)
                settings.saveGradesMeta(gpaJson, System.currentTimeMillis())
            }
            // 成绩为空是合法的（新生/评教未完成）：不能因此丢掉同一次已抓到的学籍/考试/学业进度
            GradesParser.parseStudentProfile(userJson, xsxxJson)?.let { settings.saveStudentProfile(it) }
            val (semXn, semXq, _) = JwParser.parseCurrentSemester(semJson)
            val examsFromServer = examsJson.isNotBlank()
            if (examsFromServer) repo.replaceExams(ExamsParser.parseExams(examsJson, semXn + semXq))
            if (xflbyqJson.isNotBlank() || bxkqkJson.isNotBlank()) {
                settings.saveCreditMeta(xflbyqJson, bxkqkJson)
            }
            // 考试请求成功时无论如何重排（空列表=取消未来提醒）；失败时不动闹钟，
            // 否则会把仍然有效的考试提醒一并取消。
            if (examsFromServer) ExamReminderScheduler.reschedule(getApplication())

            val notes = buildList {
                if (grades.isEmpty()) add("未解析到成绩（可能未评教或成绩未发布）")
                if (!examsFromServer) add("考试安排获取失败，已保留上次数据")
            }
            val savedSomething = grades.isNotEmpty() || examsFromServer ||
                xflbyqJson.isNotBlank() || bxkqkJson.isNotBlank()
            val detail = listOfNotNull(
                grades.size.takeIf { it > 0 }?.let { "成绩 $it 门" },
                notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
            ).joinToString(" · ")
            if (savedSomething) ok(SyncStep.GRADES, detail) else failed(SyncStep.GRADES, detail.ifBlank { "未解析到成绩" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(SyncStep.GRADES, "解析失败：${e.message}")
        }
    }

    private suspend fun stepBackup(): SyncStepResult {
        token ?: return failed(SyncStep.BACKUP, "未登录云账号")
        return try {
            settings.setCloudSyncEnabled(true)
            val result = withContext(Dispatchers.IO) { CloudSync.manualBackup(getApplication()) }
            result.fold(
                onSuccess = { ok(SyncStep.BACKUP, "已上传云备份") },
                onFailure = { failed(SyncStep.BACKUP, "上传失败：${it.message ?: "未知错误"}") },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(SyncStep.BACKUP, "上传失败：${e.message}")
        }
    }

    private suspend fun stepRestore(): SyncStepResult {
        token ?: return failed(SyncStep.RESTORE, "未登录云账号")
        return try {
            val snapshot = withContext(Dispatchers.IO) { CloudSync.restore(getApplication()) }
            // 恢复完把同步开关打开（本次不追加上传，避免刚恢复就被覆盖；之后本机改动照常自动备份）
            settings.setCloudSyncEnabled(true)
            ok(SyncStep.RESTORE, "已从云端恢复：课表 ${snapshot.courses.size} · 成绩 ${snapshot.grades.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(SyncStep.RESTORE, "恢复失败：${e.message}")
        }
    }

    private fun ok(step: SyncStep, message: String?) = SyncStepResult(step, SyncStatus.OK, message)

    private fun failed(step: SyncStep, message: String) = SyncStepResult(step, SyncStatus.FAILED, message)

    // ---- 扫码（沿用旧云登录页：sid 原生捕获 + App 长轮询）----

    /** WebView 子资源请求（WebView IO 线程调用）。 */
    fun onSubresource(url: String) {
        val changed = tracker.onRequest(url)
        if (!changed) return
        _qrSid.value = tracker.latestSid
        if (_authorizedUrl.value == null && _qrHint.value == null) {
            _qrHint.value = "等待扫码 · 也可点「复制授权链接」在微信里打开"
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "qr sid captured: ${tracker.latestSid}")
        val keep = tracker.snapshot().takeLast(MAX_POLL_TARGETS)
        val keepSids = keep.map { it.sid }.toSet() + listOfNotNull(pinnedSid, _qrSid.value)
        synchronized(pollingLock) {
            pollingJobs.entries.filter { it.key !in keepSids }.forEach { (sid, _) ->
                pollingJobs.remove(sid)?.cancel()
            }
            keep.forEach(::ensurePolling)
            pinnedSid?.let { pin -> tracker.snapshot().find { it.sid == pin }?.let(::ensurePolling) }
        }
    }

    /** 复制授权链接时锁定该 sid：页面换码时继续轮询，微信里授权不会丢。 */
    fun pinSidForWeChat(sid: String) {
        pinnedSid = sid
        tracker.snapshot().find { it.sid == sid }?.let(::ensurePolling)
    }

    private fun ensurePolling(target: QrAuthApi.QrTarget) {
        if (_authorizedUrl.value != null) return
        synchronized(pollingLock) {
            if (_authorizedUrl.value != null) return
            if (pollingJobs.containsKey(target.sid)) return
            pollingJobs[target.sid] = viewModelScope.launch(Dispatchers.IO) {
                try {
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
                            if (netFailures >= 3) _qrHint.value = "网络异常，正在重试…（请检查网络/VPN）"
                            if (BuildConfig.DEBUG) Log.d(TAG, "qr poll sid=${target.sid} error=${e.message}")
                            delay(POLL_INTERVAL_MS * 2)
                            continue
                        }
                        when (qrState.code) {
                            1 -> {
                                val code = qrState.authCode
                                if (code.isNullOrBlank()) {
                                    _qrHint.value = "微信已确认授权，正在进入教务系统…"
                                    delay(POLL_INTERVAL_MS)
                                    continue
                                }
                                _qrHint.value = "授权成功，正在进入教务系统…"
                                _authorizedUrl.value = QrAuthApi.authorizeUrl(target, code)
                                break
                            }
                            3, 101, 102, 202, 203 -> {
                                _qrHint.value = if (target.sid != pinnedSid) {
                                    "二维码已失效，请点页面上的刷新或重进本页"
                                } else {
                                    "授权链接已失效，请重新复制链接到微信确认"
                                }
                                break
                            }
                            2 -> {
                                _qrHint.value = "微信已扫码，请在微信里点确认授权"
                                delay(POLL_INTERVAL_MS)
                            }
                            else -> delay(POLL_INTERVAL_MS)
                        }
                    }
                } finally {
                    pollingJobs.remove(target.sid)
                }
            }
        }
    }

    /**
     * 授权回调导航失败（authCode 一次性，WebView 重建等导致没接住）：
     * 复位授权地址并提示重扫，而不是静默丢掉微信侧已确认的结果。
     */
    fun onAuthorizedNavigationLost() {
        if (_authorizedUrl.value == null) return
        _authorizedUrl.value = null
        _qrHint.value = "授权回调超时，请重新扫码或复制授权链接到微信确认"
    }

    /**
     * 取教务 SESSION cookie 并密封成 jwTicket：服务端据此向教务系统核实"登录的人确实是这个学号"。
     * 只在 byyt 域取（`/user/me` 是它的同源接口）；取不到就返回 null，服务端会明确要求重新登录。
     */
    private fun jwTicket(): String? {
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(JW_SESSION_ORIGIN)
        }.getOrNull().orEmpty()
        return JwSessionTicket.seal(cookie)
    }

    private fun humanize(message: String): String = when {
        message.isBlank() -> "获取学号失败，请重试"
        message.contains("当前不在教务页") -> message
        message.contains("HTTP 404") && message.contains("教务接口") -> "$message（可能尚未登录或不在教务主页）"
        message == "Error: HTTP 404" || message == "HTTP 404" -> "教务接口返回 404，请先进入教务主页再获取"
        message.startsWith("Error: ") -> message.removePrefix("Error: ")
        else -> message
    }

    private companion object {
        const val TAG = "BeikeUnifiedSync"

        /** 与 [com.caeamer.beikeschedule.import.JwWebView] 的 JW_HOME 一致。 */
        const val JW_SESSION_ORIGIN = "https://byyt.ustb.edu.cn"

        /** 单个脚本步骤的超时：教务接口在慢网下可能十几秒才回。 */
        const val STEP_TIMEOUT_MS = 30_000L

        const val MAX_POLL_TARGETS = 3
        const val POLL_INTERVAL_MS = 600L
        const val MAX_POLL_DURATION_MS = 5 * 60_000L
    }
}
