package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.KnowledgeNode
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<KnowledgeNode>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: KnowledgeNode)

    /**
     * 只返回当前生效地图的节点：空串（迁移前旧数据）或属于 active 批次的节点。
     * 重新生成时新批次节点先落库但不可见，整批成功后原子切换才可见（v14）。
     */
    @Query(
        "SELECT * FROM knowledge_nodes WHERE projectId = :projectId " +
            "AND (batchId = '' OR batchId = (SELECT id FROM knowledge_map_batches " +
            "WHERE projectId = :projectId AND active = 1)) ORDER BY depth, [order]",
    )
    fun observeByProject(projectId: String): Flow<List<KnowledgeNode>>

    @Query(
        "SELECT * FROM knowledge_nodes WHERE projectId = :projectId " +
            "AND (batchId = '' OR batchId = (SELECT id FROM knowledge_map_batches " +
            "WHERE projectId = :projectId AND active = 1)) ORDER BY depth, [order]",
    )
    suspend fun getByProject(projectId: String): List<KnowledgeNode>

    /** 切换后清理：删除不属于当前批次的所有节点（含迁移前空批次旧数据），供批次切换事务调用 */
    @Query("DELETE FROM knowledge_nodes WHERE projectId = :projectId AND batchId != :keepBatchId")
    suspend fun deleteByBatchNotEqual(projectId: String, keepBatchId: String)

    /**
     * 全量查询（含未生效的新批次节点）。仅供生成流水线内部使用——
     * 重新生成时新批次节点还没切换 active，过滤版查不到它们。
     */
    @Query("SELECT * FROM knowledge_nodes WHERE projectId = :projectId ORDER BY depth, [order]")
    suspend fun getByProjectAll(projectId: String): List<KnowledgeNode>

    @Query("SELECT * FROM knowledge_nodes WHERE id = :id")
    suspend fun getById(id: String): KnowledgeNode?

    @Query("UPDATE knowledge_nodes SET mastery = :mastery WHERE id = :id")
    suspend fun updateMastery(id: String, mastery: Float)

    /** 字段级更新：用户确认节点时不覆盖生成流程写入的 mastery/chapterIds（N13） */
    @Query("UPDATE knowledge_nodes SET confirmed = :confirmed WHERE id = :id")
    suspend fun updateConfirmed(id: String, confirmed: Boolean)

    /** 章节发布后回写节点出现的章节，供知识页跳转（B16） */
    @Query("UPDATE knowledge_nodes SET chapterIds = :chapterIds WHERE id = :id")
    suspend fun updateChapterIds(id: String, chapterIds: List<String>)

    @Query("DELETE FROM knowledge_nodes WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)

    @Query(
        "SELECT * FROM knowledge_nodes WHERE projectId = :projectId AND title = :query " +
            "AND (batchId = '' OR batchId = (SELECT id FROM knowledge_map_batches " +
            "WHERE projectId = :projectId AND active = 1)) LIMIT 20",
    )
    suspend fun searchExactTitle(projectId: String, query: String): List<KnowledgeNode>

    @Query(
        "SELECT * FROM knowledge_nodes WHERE projectId = :projectId " +
            "AND (title LIKE '%' || :query || '%' OR definition LIKE '%' || :query || '%') " +
            "AND (batchId = '' OR batchId = (SELECT id FROM knowledge_map_batches " +
            "WHERE projectId = :projectId AND active = 1)) LIMIT 50",
    )
    suspend fun searchFuzzy(projectId: String, query: String): List<KnowledgeNode>

    /** 字段级状态更新：展开失败/重试时只动生成状态，不覆盖已有内容（批次化生成） */
    @Query("UPDATE knowledge_nodes SET generateStatus = :status WHERE id = :id")
    suspend fun updateGenerateStatus(id: String, status: String)
}
