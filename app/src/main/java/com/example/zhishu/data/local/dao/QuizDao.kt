package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.Quiz
import kotlinx.coroutines.flow.Flow

@Dao
interface QuizDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(quizzes: List<Quiz>)

    @Query("SELECT * FROM quizzes WHERE chapterId = :chapterId ORDER BY [order]")
    fun observeByChapter(chapterId: String): Flow<List<Quiz>>

    @Query("SELECT * FROM quizzes WHERE chapterId = :chapterId ORDER BY [order]")
    suspend fun getByChapter(chapterId: String): List<Quiz>

    /**
     * 只取当前正文版本的自测题（第三阶段 3A）。
     *
     * 重生成正文后旧版本的题还在库里，但它们问的是已经被替换掉的情节，
     * 不能跟着新正文一起显示。versionId 为空串的是历史数据（旧备份导入），按章节算。
     */
    @Query(
        "SELECT quizzes.* FROM quizzes JOIN chapters ON chapters.id = quizzes.chapterId " +
            "WHERE quizzes.chapterId = :chapterId " +
            "AND (quizzes.versionId = '' OR quizzes.versionId = chapters.activeVersionId) " +
            "ORDER BY quizzes.[order]",
    )
    fun observeActiveByChapter(chapterId: String): Flow<List<Quiz>>

    @Query(
        "SELECT quizzes.* FROM quizzes JOIN chapters ON chapters.id = quizzes.chapterId " +
            "WHERE quizzes.chapterId = :chapterId " +
            "AND (quizzes.versionId = '' OR quizzes.versionId = chapters.activeVersionId) " +
            "ORDER BY quizzes.[order]",
    )
    suspend fun getActiveByChapter(chapterId: String): List<Quiz>

    @Query("SELECT * FROM quizzes WHERE projectId = :projectId ORDER BY [order]")
    suspend fun getByProject(projectId: String): List<Quiz>

    @Query("DELETE FROM quizzes WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: String)

    /**
     * 换掉某个版本的题目，别的版本的题一律保留（第三阶段 3A）。
     * 顺带清掉这一章未绑定版本的历史题——新题已经覆盖了同一段正文。
     */
    @Query("DELETE FROM quizzes WHERE chapterId = :chapterId AND (versionId = :versionId OR versionId = '')")
    suspend fun deleteForVersion(chapterId: String, versionId: String)

    @Query("DELETE FROM quizzes WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
