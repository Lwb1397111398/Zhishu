package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.zhishu.domain.model.ReadingProgress

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgress)

    @Query("SELECT * FROM reading_progress WHERE projectId = :projectId LIMIT 1")
    suspend fun getForProject(projectId: String): ReadingProgress?

    @Query("DELETE FROM reading_progress WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    /**
     * 只写阅读位置，不动 [ReadingProgress.bodyUpdatedAt] 与 progressNoticeShown（3D）。
     *
     * 阅读器每停一秒就写一次位置。如果整行 upsert 回去，就会把「正文换过版本」
     * 这个痕迹一起冲掉——用户滚动一下，那条「本章已更新」的提示就再也不会出现了。
     */
    @Transaction
    suspend fun saveAnchor(progress: ReadingProgress) {
        val affected = updateAnchor(
            projectId = progress.projectId,
            chapterId = progress.chapterId,
            blockId = progress.blockId,
            blockOffset = progress.blockOffset,
            updatedAt = progress.updatedAt,
        )
        if (affected == 0) upsert(progress)
    }

    @Query(
        "UPDATE reading_progress SET chapterId = :chapterId, blockId = :blockId, " +
            "blockOffset = :blockOffset, updatedAt = :updatedAt WHERE projectId = :projectId",
    )
    suspend fun updateAnchor(
        projectId: String,
        chapterId: String,
        blockId: String?,
        blockOffset: Int,
        updatedAt: Long,
    ): Int

    /**
     * 「本章已更新」提示已经给用户看过（3D）。
     *
     * 单独一条字段更新而不是整行 upsert：这一步是在阅读器里发生的，
     * 而阅读器同时还在按滚动位置写进度，整行写回会把刚保存的位置覆盖掉。
     */
    @Query("UPDATE reading_progress SET progressNoticeShown = 1 WHERE projectId = :projectId")
    suspend fun markNoticeShown(projectId: String)
}
