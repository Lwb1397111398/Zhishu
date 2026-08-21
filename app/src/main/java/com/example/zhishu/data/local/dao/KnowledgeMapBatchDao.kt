package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.KnowledgeMapBatch

/**
 * 知识地图批次生成的状态（批次化生成）。
 *
 * 一个项目同一时间只有一个「当前批次」；重新生成开新批次、旧批次整体作废。
 * 骨架、每条链、最终审计都落到这一行上，供断点续跑与重新生成隔离。
 * 这是过程状态，不随备份导出。
 */
@Dao
interface KnowledgeMapBatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(batch: KnowledgeMapBatch)

    /** 取该项目最新的批次。没有批次时返回 null（从未生成 / 全部完成清理） */
    @Query("SELECT * FROM knowledge_map_batches WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(projectId: String): KnowledgeMapBatch?

    @Query("SELECT * FROM knowledge_map_batches WHERE id = :id")
    suspend fun getById(id: String): KnowledgeMapBatch?

    @Query("DELETE FROM knowledge_map_batches WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)

    /** 把该项目所有批次置为非生效（切换事务第一步） */
    @Query("UPDATE knowledge_map_batches SET active = 0 WHERE projectId = :projectId")
    suspend fun deactivateAll(projectId: String)

    /** 把指定批次置为生效（切换事务第二步） */
    @Query("UPDATE knowledge_map_batches SET active = 1 WHERE id = :batchId AND projectId = :projectId")
    suspend fun activate(projectId: String, batchId: String)

    /** 删除除当前批次外的全部旧批次行（切换事务收尾，避免批次行无限累积） */
    @Query("DELETE FROM knowledge_map_batches WHERE projectId = :projectId AND id != :keepBatchId")
    suspend fun deleteExcept(projectId: String, keepBatchId: String)
}
