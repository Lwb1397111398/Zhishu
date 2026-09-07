package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeGapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(gaps: List<KnowledgeGap>)

    /** 单条更新（PATCH 成功标 RESOLVED / 用户点「知道了」标 DISMISSED） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(gap: KnowledgeGap)

    @Query("SELECT * FROM knowledge_gaps WHERE projectId = :projectId ORDER BY [order]")
    fun observeByProject(projectId: String): Flow<List<KnowledgeGap>>

    @Query("SELECT * FROM knowledge_gaps WHERE projectId = :projectId ORDER BY [order]")
    suspend fun getByProject(projectId: String): List<KnowledgeGap>

    @Query("SELECT * FROM knowledge_gaps WHERE id = :id")
    suspend fun getById(id: String): KnowledgeGap?

    @Query("DELETE FROM knowledge_gaps WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)
}

@Dao
interface KnowledgeConflictDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conflicts: List<KnowledgeConflict>)

    /** 单条更新（「知道了」关闭） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conflict: KnowledgeConflict)

    @Query("SELECT * FROM knowledge_conflicts WHERE projectId = :projectId ORDER BY [order]")
    fun observeByProject(projectId: String): Flow<List<KnowledgeConflict>>

    @Query("SELECT * FROM knowledge_conflicts WHERE projectId = :projectId ORDER BY [order]")
    suspend fun getByProject(projectId: String): List<KnowledgeConflict>

    @Query("DELETE FROM knowledge_conflicts WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)
}
