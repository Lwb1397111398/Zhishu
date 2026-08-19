package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterBlockFts
import com.example.zhishu.search.SearchTokenizer
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterBlockDao {

    @Transaction
    suspend fun upsertAll(blocks: List<ChapterBlock>) {
        insertAll(blocks)
        // 同步维护 FTS 索引（P2）
        blocks.forEach { block ->
            deleteFtsForBlock(block.id)
            insertFts(
                ChapterBlockFts(
                    blockId = block.id,
                    projectId = block.projectId,
                    body = SearchTokenizer.indexBody(block.text),
                ),
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blocks: List<ChapterBlock>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: ChapterBlockFts)

    @Query("DELETE FROM chapter_block_fts WHERE blockId = :blockId")
    suspend fun deleteFtsForBlock(blockId: String)

    @Query("SELECT * FROM chapter_blocks WHERE chapterId = :chapterId ORDER BY [order]")
    fun observeByChapter(chapterId: String): Flow<List<ChapterBlock>>

    @Query("SELECT * FROM chapter_blocks WHERE chapterId = :chapterId ORDER BY [order]")
    suspend fun getByChapter(chapterId: String): List<ChapterBlock>

    /** 全项目正文，用于建立「知识点 → 所属章节的哪一块」的跳转锚点 */
    @Query("SELECT * FROM chapter_blocks WHERE projectId = :projectId ORDER BY chapterId, [order]")
    suspend fun getByProject(projectId: String): List<ChapterBlock>

    @Transaction
    suspend fun deleteByChapter(chapterId: String) {
        deleteFtsByChapter(chapterId)
        deleteBlocksByChapter(chapterId)
    }

    @Query("DELETE FROM chapter_blocks WHERE chapterId = :chapterId")
    suspend fun deleteBlocksByChapter(chapterId: String)

    @Query(
        "DELETE FROM chapter_block_fts WHERE blockId IN " +
            "(SELECT id FROM chapter_blocks WHERE chapterId = :chapterId)",
    )
    suspend fun deleteFtsByChapter(chapterId: String)

    /** 直接按 projectId 删除，不依赖 chapters 表是否仍存在（N6） */
    @Transaction
    suspend fun deleteByProject(projectId: String) {
        deleteFtsByProject(projectId)
        deleteBlocksByProject(projectId)
    }

    @Query("DELETE FROM chapter_blocks WHERE projectId = :projectId")
    suspend fun deleteBlocksByProject(projectId: String)

    @Query("DELETE FROM chapter_block_fts WHERE projectId = :projectId")
    suspend fun deleteFtsByProject(projectId: String)

    /** 走 FTS 倒排索引，避免前后通配符导致的全表扫描（P2） */
    @Query(
        "SELECT chapter_blocks.* FROM chapter_block_fts " +
            "JOIN chapter_blocks ON chapter_blocks.id = chapter_block_fts.blockId " +
            "WHERE chapter_block_fts.projectId = :projectId AND chapter_block_fts.body MATCH :match " +
            "ORDER BY chapter_blocks.[order] LIMIT 50",
    )
    suspend fun searchByProjectMatch(projectId: String, match: String): List<ChapterBlock>

    @Query("SELECT COUNT(*) FROM chapter_blocks WHERE projectId = :projectId")
    suspend fun countByProject(projectId: String): Int

    @Query("SELECT COUNT(*) FROM chapter_block_fts WHERE projectId = :projectId")
    suspend fun countFtsByProject(projectId: String): Int
}
