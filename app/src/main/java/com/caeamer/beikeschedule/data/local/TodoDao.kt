package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todo ORDER BY time, id")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todo ORDER BY time, id")
    suspend fun getAll(): List<TodoEntity>

    @Query("SELECT * FROM todo WHERE id = :id")
    suspend fun getById(id: Long): TodoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: TodoEntity): Long

    /** 批量插入（云恢复整包覆盖用；配合 clear() 在同一事务内完成清空+写入）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(todos: List<TodoEntity>)

    /**
     * 只改打卡日期，不动其它字段。
     *
     * 不能用 upsert 全行覆盖：那是"读旧行 → 改一个字段 → REPLACE 写回"，
     * 与并发编辑/删除交错时会丢掉刚写入的其它字段，甚至把已删除的行"复活"。
     */
    @Query("UPDATE todo SET lastDoneDate = :date WHERE id = :id")
    suspend fun setDoneDate(id: Long, date: String)

    @Query("DELETE FROM todo WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 云恢复整包覆盖用：清空全部日程（与插入同一事务）。 */
    @Query("DELETE FROM todo")
    suspend fun clear()
}
