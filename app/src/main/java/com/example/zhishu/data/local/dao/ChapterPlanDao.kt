package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.QuizState
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plans: List<ChapterPlan>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: ChapterPlan)

    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY [order]")
    fun observeByProject(projectId: String): Flow<List<ChapterPlan>>

    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY [order]")
    suspend fun getByProject(projectId: String): List<ChapterPlan>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: String): ChapterPlan?

    @Query("DELETE FROM chapters WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)

    // --- 字段级更新（N13）：用户可编辑字段与生成状态分离，避免整行 upsert 覆盖 Worker 写入的状态 ---

    @Query("UPDATE chapters SET title = :title, plotGoal = :plotGoal, learningGoals = :learningGoals, wordTarget = :wordTarget WHERE id = :id")
    suspend fun updateEditableFields(
        id: String,
        title: String,
        plotGoal: String,
        learningGoals: List<String>,
        wordTarget: Int,
    )

    @Query("UPDATE chapters SET nodeIds = :nodeIds WHERE id = :id")
    suspend fun updateNodeIds(id: String, nodeIds: List<String>)

    @Query("UPDATE chapters SET status = :status, verificationNote = :note WHERE id = :id")
    suspend fun updateStatus(id: String, status: ChapterStatus, note: String)

    /**
     * 条件更新状态：只有当章节仍停在 [onlyIf] 里的某个状态时才改（第二阶段）。
     *
     * 取消生成时用它做收尾。无条件写状态会踩坏别人的结果：
     * 用户可能在取消动作落地之前就重新排了一次生成，那一次已经把章节推到 RUNNING 了；
     * 也可能这一章其实早就 PUBLISHED，取消的是排在它后面的章节。
     * 返回受影响行数，调用方据此判断收尾到底做没做。
     */
    @Query(
        "UPDATE chapters SET status = :status, verificationNote = :note " +
            "WHERE id = :id AND status IN (:onlyIf)",
    )
    suspend fun updateStatusIfIn(
        id: String,
        status: ChapterStatus,
        note: String,
        onlyIf: List<ChapterStatus>,
    ): Int

    /** 只写测验状态，正文状态一概不碰——出题失败不该让正文变得打不开（第二阶段） */
    @Query("UPDATE chapters SET quizState = :state, quizNote = :note WHERE id = :id")
    suspend fun updateQuizState(id: String, state: QuizState, note: String)

    @Query("UPDATE chapters SET status = :status, summary = :summary, verificationNote = '' WHERE id = :id")
    suspend fun updatePublished(id: String, status: ChapterStatus, summary: String)

    /**
     * 章节发布时一并写入滚动记忆字段（问题5）。
     * carryOver / coveredTitles 供下一章使用，unverifiedClaims 供无资料模式提示用户核对。
     */
    @Query(
        "UPDATE chapters SET status = :status, summary = :summary, carryOver = :carryOver, " +
            "coveredTitles = :coveredTitles, unverifiedClaims = :unverifiedClaims, " +
            "verificationNote = :note WHERE id = :id",
    )
    suspend fun updatePublishedWithMemory(
        id: String,
        status: ChapterStatus,
        summary: String,
        carryOver: String,
        coveredTitles: List<String>,
        unverifiedClaims: List<String>,
        note: String,
    )
}
