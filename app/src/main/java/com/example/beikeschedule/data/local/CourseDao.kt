package com.example.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM course ORDER BY dayOfWeek, startSection")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course WHERE source = :source")
    suspend fun getBySource(source: Int): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CourseEntity): Long

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM course WHERE source = :source")
    suspend fun deleteBySource(source: Int)

    @Query("SELECT COUNT(*) FROM course")
    suspend fun count(): Int
}
