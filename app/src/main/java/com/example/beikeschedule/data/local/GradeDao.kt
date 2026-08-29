package com.example.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GradeDao {

    @Query("SELECT * FROM grade ORDER BY xnxq DESC, id ASC")
    fun observeAll(): Flow<List<GradeEntity>>

    @Query("SELECT COUNT(*) FROM grade")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(grades: List<GradeEntity>)

    @Query("DELETE FROM grade")
    suspend fun clear()
}
