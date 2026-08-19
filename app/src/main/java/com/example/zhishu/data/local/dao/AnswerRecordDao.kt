package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.AnswerRecord

@Dao
interface AnswerRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AnswerRecord)

    @Query("SELECT COUNT(*) FROM answer_records WHERE quizId = :quizId AND isCorrect = 1")
    suspend fun countCorrect(quizId: String): Int

    @Query("SELECT COUNT(*) FROM answer_records WHERE quizId = :quizId")
    suspend fun countAnswers(quizId: String): Int

    /** 恢复答题历史，保证掌握度只计一次（N12） */
    @Query("SELECT * FROM answer_records WHERE quizId IN (:quizIds)")
    suspend fun getByQuizIds(quizIds: List<String>): List<AnswerRecord>

    @Query("SELECT * FROM answer_records WHERE quizId = :quizId LIMIT 1")
    suspend fun getByQuizId(quizId: String): AnswerRecord?

    @Query("DELETE FROM answer_records WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
