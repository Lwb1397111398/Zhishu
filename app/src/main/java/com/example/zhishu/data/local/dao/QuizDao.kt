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

    @Query("SELECT * FROM quizzes WHERE projectId = :projectId ORDER BY [order]")
    suspend fun getByProject(projectId: String): List<Quiz>

    @Query("DELETE FROM quizzes WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: String)

    @Query("DELETE FROM quizzes WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
