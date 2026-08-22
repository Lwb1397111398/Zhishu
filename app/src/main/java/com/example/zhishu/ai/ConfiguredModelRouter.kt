package com.example.zhishu.ai

import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.repository.SettingsRepository
import com.example.zhishu.security.KeyCipher

/**
 * 按角色读取用户配置后构造 Provider。
 * maxTokens / temperature 由 [OpenAiModelProvider] 从 [ModelConfig] 取用，
 * 调用方只在必须覆盖时通过 [ModelRequest] 显式给值（B6）。
 */
class ConfiguredModelRouter(
    private val settingsRepository: SettingsRepository,
    private val keyCipherProvider: () -> KeyCipher,
    private val providerFactory: (ModelConfig, String) -> ModelProvider = { config, key ->
        OpenAiModelProvider(config = config, apiKey = key)
    },
) : ModelRouter {

    override suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
        onStats: (ModelCallStats) -> Unit,
        onAttempt: (CallAttempt) -> Unit,
    ): Result<T> {
        val provider = resolveProvider(role).getOrElse { return Result.failure(it) }
        return DefaultModelRouter(provider).generate(role, request, decoder, onStats, onAttempt)
    }

    /** 设置页连通性测试用：拉取模型列表（B18） */
    suspend fun listModels(role: ModelRole): Result<List<String>> {
        val provider = resolveProvider(role).getOrElse { return Result.failure(it) }
        return provider.listModels()
    }

    /**
     * 用「页面上正在填的」地址与 Key 拉模型列表（问题1）。
     *
     * 设置页需要在保存之前就能搜索可用模型，否则用户得先盲填一个模型名才能保存、
     * 保存后才能查列表——正好是反过来的顺序。
     *
     * [plainApiKey] 为空时沿用已保存的 Key（用户只改地址、不想重输 Key 的情况）。
     */
    suspend fun listModelsWith(
        role: ModelRole,
        baseUrl: String,
        plainApiKey: String,
    ): Result<List<String>> {
        val trimmedUrl = baseUrl.trim()
        if (trimmedUrl.isBlank()) {
            return Result.failure(ModelError.Auth("请先填写接口地址"))
        }
        val saved = settingsRepository.getConfig(role)
        val apiKey = plainApiKey.ifBlank {
            saved?.keyRef?.let { ciphertext ->
                try {
                    keyCipherProvider().decrypt(KEY_ALIAS, ciphertext)
                } catch (e: Exception) {
                    return Result.failure(ModelError.Auth("已保存的 API Key 无法解密，请重新填写"))
                }
            }.orEmpty()
        }
        if (apiKey.isBlank()) {
            return Result.failure(ModelError.Auth("请先填写 API Key"))
        }
        // modelName 与列表查询无关，用占位值即可通过配置校验
        val probe = (saved ?: ModelConfig(role = role.name)).copy(
            baseUrl = trimmedUrl,
            modelName = saved?.modelName?.ifBlank { PROBE_MODEL } ?: PROBE_MODEL,
        )
        return providerFactory(probe, apiKey).listModels()
    }

    private suspend fun resolveProvider(role: ModelRole): Result<ModelProvider> {
        val config = settingsRepository.getConfig(role)
            ?: return Result.failure(
                ModelError.Auth("还没有配置「${role.displayName}」模型，请到「我的」页填写接口地址与模型名"),
            )
        if (config.baseUrl.isBlank() || config.modelName.isBlank()) {
            return Result.failure(
                ModelError.Auth("「${role.displayName}」模型的接口地址或模型名未填写"),
            )
        }
        val apiKey = config.keyRef?.let { ciphertext ->
            try {
                keyCipherProvider().decrypt(KEY_ALIAS, ciphertext)
            } catch (e: Exception) {
                return Result.failure(ModelError.Auth("API Key 无法解密，请重新填写"))
            }
        }.orEmpty()
        return Result.success(providerFactory(config, apiKey))
    }

    companion object {
        const val KEY_ALIAS = "zhishu_api_key"

        /** 查列表时的占位模型名，不参与实际请求 */
        private const val PROBE_MODEL = "probe"
    }
}
