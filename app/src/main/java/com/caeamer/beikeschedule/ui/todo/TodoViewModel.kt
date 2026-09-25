package com.caeamer.beikeschedule.ui.todo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.TodoPlanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 展示窗口天数：今天起未来 N 天。 */
private const val DISPLAY_DAYS = 14

/** 日期分组：某天要展示的日程列表。 */
typealias TodoDayGroup = Pair<LocalDate, List<TodoEntity>>

/** 日程页状态。 */
data class TodoUiState(
    val groups: List<TodoDayGroup> = emptyList(),
    /**
     * 已过期的一次性事项（日期在过去、当天没打卡）：主列表只展示今天起 14 天，
     * 没有这个出口它们就成了"幽灵数据"——永远不可见、不可编辑、不可删，却仍参与提醒重排。
     */
    val expired: List<TodoEntity> = emptyList(),
    /** 今日已打卡完成的事项 id 集合（由 lastDoneDate == 今天 派生）。 */
    val doneIds: Set<Long> = emptySet(),
    val loaded: Boolean = false,
)

class TodoViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)

    val uiState: StateFlow<TodoUiState> = repo.todos.map { todos ->
        val today = LocalDate.now()
        TodoUiState(
            groups = TodoPlanner.groupByDate(todos, today, DISPLAY_DAYS),
            expired = todos.filter { todo ->
                todo.repeatMode == TodoEntity.REPEAT_ONCE &&
                    runCatching { LocalDate.parse(todo.date) }.getOrNull()?.isBefore(today) == true &&
                    todo.lastDoneDate != todo.date
            }.sortedBy { it.date },
            doneIds = todos.filter { it.isDoneToday(today.toString()) }.map { it.id }.toSet(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodoUiState())

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
     * 每次都从 Room 流取**最新**值再翻转：此前读过一份异步维护的缓存，
     * 快速双击会落在同一次回写落地前，两次点击读到同一份旧行，
     * "打卡→立刻取消"静默变成两次打卡。
     */
    fun toggleDone(id: Long) {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val todo = repo.todos.first().firstOrNull { it.id == id } ?: return@launch
            repo.setTodoDone(id, if (todo.isDoneToday(today)) "" else today)
        }
    }
}
