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

    @Query("DELETE FROM todo WHERE id = :id")
    suspend fun deleteById(id: Long)
}
