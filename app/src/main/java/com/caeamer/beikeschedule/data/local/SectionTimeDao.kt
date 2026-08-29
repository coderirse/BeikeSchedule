package com.caeamer.beikeschedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionTimeDao {

    @Query("SELECT * FROM section_time ORDER BY section")
    fun observeAll(): Flow<List<SectionTimeEntity>>

    @Query("SELECT * FROM section_time ORDER BY section")
    suspend fun getAll(): List<SectionTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sections: List<SectionTimeEntity>)

    @Query("DELETE FROM section_time")
    suspend fun clear()
}
