package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ReviewItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReviewItem)

    @Query("SELECT * FROM review_items WHERE projectId = :projectId ORDER BY dueAt")
    fun observeByProject(projectId: String): Flow<List<ReviewItem>>

    @Query("SELECT * FROM review_items WHERE projectId = :projectId ORDER BY dueAt")
    suspend fun getByProject(projectId: String): List<ReviewItem>

    @Query("SELECT * FROM review_items WHERE dueAt <= :now AND status = :status ORDER BY dueAt LIMIT 100")
    suspend fun getDue(now: Long, status: ReviewStatus): List<ReviewItem>

    @Query("DELETE FROM review_items WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)
}
