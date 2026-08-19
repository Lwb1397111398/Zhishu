package com.example.zhishu.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "model_configs")
data class ModelConfig(
    @PrimaryKey val role: String,
    val baseUrl: String = "",
    val modelName: String = "",
    /**
     * 单次回复的最大 Token。默认给足（5 万）：本项目一章正文动辄两三千字，
     * 加上 JSON 结构与核验字段，4096 这种默认值几乎必然触发「输出被截断」。
     * 主流服务商对超出自身上限的值会自行下调或报错，给大值比给小值安全。
     */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float = 0.7f,
    val supportsJson: Boolean = true,
    val keyRef: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 50_000

        /** 允许的填写范围。上限放到 100 万，容纳长上下文模型（原来 32768 的上限会把 5 万砍掉） */
        const val MIN_MAX_TOKENS = 256
        const val MAX_MAX_TOKENS = 1_000_000
    }
}
