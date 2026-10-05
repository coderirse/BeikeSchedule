package com.caeamer.beikeschedule.ui.todo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.TodoPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** 展示窗口天数：今天起未来 N 天。 */
private const val DISPLAY_DAYS = 14

/** 日期分组：某天要展示的日程列表。 */
typealias TodoDayGroup = Pair<LocalDate, List<TodoEntity>>

/** 日程页状态。 */
data class TodoUiState(
    val groups: List<TodoDayGroup> = emptyList(),
    /** 已过期的一次性事项（规则见 [TodoPlanner.expiredOnce]：打过卡就离开这里）。 */
    val expired: List<TodoEntity> = emptyList(),
    /** 今日已打卡完成的事项 id 集合（由 lastDoneDate == 今天 派生）。 */
    val doneIds: Set<Long> = emptySet(),
    val loaded: Boolean = false,
)

class TodoViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)

    /**
     * 时间基准独立成流：分组、打卡态、过期判定全按"今天"算，而 Room 只在 todo 表变化时发射——
     * 前台跨过午夜（或长时间停留不重建收集）时不会重算，昨日打卡的每日事项今天仍显示已完成。
     * 由界面 ON_RESUME 与打卡动作触发 [refreshToday] 推进，不引后台时钟。
     */
    private val today = MutableStateFlow(LocalDate.now())

    /** 打卡的读-改-写串行化（见 [toggleDone]）。 */
    private val toggleMutex = Mutex()

    val uiState: StateFlow<TodoUiState> = combine(repo.todos, today) { todos, day ->
        TodoUiState(
            groups = TodoPlanner.groupByDate(todos, day, DISPLAY_DAYS),
            expired = TodoPlanner.expiredOnce(todos, day),
            doneIds = todos.filter { it.isDoneToday(day.toString()) }.map { it.id }.toSet(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodoUiState())

    /** 日期变了才推进（回到前台、打卡前调用），避免无谓重算。 */
    fun refreshToday() {
        val now = LocalDate.now()
        if (today.value != now) today.value = now
    }

    /** 新增或更新（id=0 为新增）。 */
    fun save(todo: TodoEntity) {
        viewModelScope.launch { repo.upsertTodo(todo) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteTodo(id) }
    }

    /**
     * 切换打卡：今天已打卡则清除（取消完成），否则记为今天完成。
     *
     * 读-改-写全程持锁：每次点击各自 launch、各自从 Room 冷流重新查库，A 协程挂在查询上时
     * B 随即启动，两次读到同一份旧行 → "打卡→立刻取消"变成两次打卡。只"读最新值"关不掉这个
     * 窗口（读和写之间仍有挂起点），必须串行化。
     */
    fun toggleDone(id: Long) {
        refreshToday()
        viewModelScope.launch {
            toggleMutex.withLock {
                val day = today.value.toString()
                val todo = repo.todos.first().firstOrNull { it.id == id } ?: return@withLock
                repo.setTodoDone(id, if (todo.isDoneToday(day)) "" else day)
            }
        }
    }
}
