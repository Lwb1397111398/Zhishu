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

    // --- 全部历史版本（3D 起一律带 AllVersions / ByVersion 前后缀）---
    //
    // 这几条查出来的是**一章的全部版本正文叠在一起**，绝不能拿给阅读器、搜索或出题用。
    // 唯一的合法用途是导出备份和按章清理。命名上带足警示，是因为 3A 之后
    // 一个含糊的 getByChapter() 在代码里看起来和「读这一章的正文」一模一样。

    @Query("SELECT * FROM chapter_blocks WHERE chapterId = :chapterId ORDER BY versionId, [order]")
    suspend fun getAllVersionsByChapter(chapterId: String): List<ChapterBlock>

    /** 全项目全部历史版本正文。只用于导出备份 */
    @Query("SELECT * FROM chapter_blocks WHERE projectId = :projectId ORDER BY chapterId, versionId, [order]")
    suspend fun getAllVersionsByProject(projectId: String): List<ChapterBlock>

    // --- 当前版本正文（第三阶段 3A）---
    //
    // 阅读器、知识点跳转、搜索一律走这几条。

    @Query(
        "SELECT chapter_blocks.* FROM chapter_blocks " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_blocks.chapterId = :chapterId " +
            "AND chapter_blocks.versionId = chapters.activeVersionId " +
            "ORDER BY chapter_blocks.[order]",
    )
    fun observeActiveBlocks(chapterId: String): Flow<List<ChapterBlock>>

    @Query(
        "SELECT chapter_blocks.* FROM chapter_blocks " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_blocks.chapterId = :chapterId " +
            "AND chapter_blocks.versionId = chapters.activeVersionId " +
            "ORDER BY chapter_blocks.[order]",
    )
    suspend fun getActiveBlocks(chapterId: String): List<ChapterBlock>

    /** 某个版本的正文，与章节当前指针无关。核验新版本、导出历史、局部重写时用 */
    @Query("SELECT * FROM chapter_blocks WHERE versionId = :versionId ORDER BY [order]")
    suspend fun getByVersion(versionId: String): List<ChapterBlock>

    /** 全项目当前版本正文。跳转锚点只能落在读者真的看得到的那一版上 */
    @Query(
        "SELECT chapter_blocks.* FROM chapter_blocks " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_blocks.projectId = :projectId " +
            "AND chapter_blocks.versionId = chapters.activeVersionId " +
            "ORDER BY chapter_blocks.chapterId, chapter_blocks.[order]",
    )
    suspend fun getActiveBlocksByProject(projectId: String): List<ChapterBlock>

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

    /**
     * 全部历史版本的全文检索（3D 起明确命名）。
     *
     * 不给业务用：它会命中读者已经看不到的旧版本正文。留着是因为
     * [searchActiveByProjectMatch] 的查询计划断言需要一个对照。
     */
    @Query(
        "SELECT chapter_blocks.* FROM chapter_block_fts " +
            "JOIN chapter_blocks ON chapter_blocks.id = chapter_block_fts.blockId " +
            "WHERE chapter_block_fts.projectId = :projectId AND chapter_block_fts.body MATCH :match " +
            "ORDER BY chapter_blocks.[order] LIMIT 50",
    )
    suspend fun searchAllVersionsByProjectMatch(projectId: String, match: String): List<ChapterBlock>

    /**
     * 只搜当前版本正文（第三阶段 3A）。走 FTS 倒排索引，避免前后通配符导致全表扫描（P2）。
     *
     * 被放弃/归档的版本仍留在 FTS 索引里（正文块一个都不删），靠这里的 versionId 条件过滤掉——
     * 否则搜索会命中一段读者已经看不到的旧正文，点进去还落不到任何位置。
     */
    @Query(
        "SELECT chapter_blocks.* FROM chapter_block_fts " +
            "JOIN chapter_blocks ON chapter_blocks.id = chapter_block_fts.blockId " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_block_fts.projectId = :projectId AND chapter_block_fts.body MATCH :match " +
            "AND chapter_blocks.versionId = chapters.activeVersionId " +
            "ORDER BY chapter_blocks.[order] LIMIT 50",
    )
    suspend fun searchActiveByProjectMatch(projectId: String, match: String): List<ChapterBlock>

    @Query("SELECT COUNT(*) FROM chapter_blocks WHERE projectId = :projectId")
    suspend fun countByProject(projectId: String): Int

    /**
     * 当前版本正文的块数与字数（3D）。
     *
     * 「本项目写了多少」必须只数当前版本：把历史版本一起算进去，
     * 重生成过一章就凭空多出一整章的字数，进度条会显示写了两倍的量。
     */
    @Query(
        "SELECT COUNT(*) FROM chapter_blocks " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_blocks.projectId = :projectId " +
            "AND chapter_blocks.versionId = chapters.activeVersionId",
    )
    suspend fun countActiveByProject(projectId: String): Int

    @Query(
        "SELECT COALESCE(SUM(LENGTH(chapter_blocks.text)), 0) FROM chapter_blocks " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_blocks.projectId = :projectId " +
            "AND chapter_blocks.versionId = chapters.activeVersionId",
    )
    suspend fun countActiveCharsByProject(projectId: String): Int

    @Query(
        "SELECT COALESCE(SUM(LENGTH(chapter_blocks.text)), 0) FROM chapter_blocks " +
            "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
            "WHERE chapter_blocks.chapterId = :chapterId " +
            "AND chapter_blocks.versionId = chapters.activeVersionId",
    )
    suspend fun countActiveCharsByChapter(chapterId: String): Int

    @Query("SELECT COUNT(*) FROM chapter_block_fts WHERE projectId = :projectId")
    suspend fun countFtsByProject(projectId: String): Int
}
