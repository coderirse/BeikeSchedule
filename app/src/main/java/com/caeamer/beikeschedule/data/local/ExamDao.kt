package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Query("SELECT * FROM exam ORDER BY ksrq, kssj")
    fun observeAll(): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exam ORDER BY ksrq, kssj")
    suspend fun getAll(): List<ExamEntity>

    @Insert
    suspend fun insertAll(exams: List<ExamEntity>)

    @Query("DELETE FROM exam")
    suspend fun clear()
}
