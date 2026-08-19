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

    @Query("SELECT * FROM knowledge_nodes WHERE projectId = :projectId ORDER BY depth, [order]")
    fun observeByProject(projectId: String): Flow<List<KnowledgeNode>>

    @Query("SELECT * FROM knowledge_nodes WHERE projectId = :projectId ORDER BY depth, [order]")
    suspend fun getByProject(projectId: String): List<KnowledgeNode>

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

    @Query("SELECT * FROM knowledge_nodes WHERE projectId = :projectId AND title = :query LIMIT 20")
    suspend fun searchExactTitle(projectId: String, query: String): List<KnowledgeNode>

    @Query("SELECT * FROM knowledge_nodes WHERE projectId = :projectId AND (title LIKE '%' || :query || '%' OR definition LIKE '%' || :query || '%') LIMIT 50")
    suspend fun searchFuzzy(projectId: String, query: String): List<KnowledgeNode>
}
