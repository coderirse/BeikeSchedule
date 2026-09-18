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
            doneIds = todos.filter { it.isDoneToday(today.toString()) }.map { it.id }.toSet(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodoUiState())

    /** 最新事项列表缓存，供 toggleDone 同步读取当前行。 */
    private val todosCache = MutableStateFlow<List<TodoEntity>>(emptyList())

    init {
        viewModelScope.launch {
            repo.todos.collect { todosCache.value = it }
        }
    }

    /** 新增或更新（id=0 为新增）。 */
    fun save(todo: TodoEntity) {
        viewModelScope.launch { repo.upsertTodo(todo) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteTodo(id) }
    }

    /** 切换打卡：今天已打卡则清除（取消完成），否则记为今天完成。 */
    fun toggleDone(id: Long) {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val todo = todosCache.value.firstOrNull { it.id == id } ?: return@launch
            repo.setTodoDone(id, if (todo.isDoneToday(today)) "" else today)
        }
    }
}
