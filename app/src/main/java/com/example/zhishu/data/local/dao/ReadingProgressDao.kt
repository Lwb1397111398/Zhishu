package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.ReadingProgress

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgress)

    @Query("SELECT * FROM reading_progress WHERE projectId = :projectId LIMIT 1")
    suspend fun getForProject(projectId: String): ReadingProgress?

    @Query("DELETE FROM reading_progress WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
