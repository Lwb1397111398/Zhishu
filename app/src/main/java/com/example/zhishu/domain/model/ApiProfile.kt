package com.example.zhishu.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一套完整的 API 配置方案：服务商地址 + 模型 + Key + 生成参数。
 *
 * 早期版本里每个角色（FAST/STORY/VERIFY）各存一份完整配置，想换个模型试一试
 * 就得把地址和 Key 在三张卡片里重填一遍。现在配置与「谁在用」分离：
 * 方案库里可以保存任意多套方案，三个角色各自指向其中一套（见 [RoleAssignment]）。
 * 换 LLM 只是换一个下拉选项。
 *
 * API Key 不明文存库：`keyRef` 是 AndroidKeyStore AES/GCM 加密后的 Base64 密文，
 * 只能在本机解密，因此也不随备份导出（见 TransferManager）。
 */
@Entity(tableName = "api_profiles")
data class ApiProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 方案名，如「DeepSeek 官方」「SiliconFlow-GLM」。只用于展示与选择 */
    val name: String = "",
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
