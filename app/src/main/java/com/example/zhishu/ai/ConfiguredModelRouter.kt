package com.example.zhishu.ai

import android.content.Context
import com.example.zhishu.domain.model.ApiProfile
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.repository.SettingsRepository
import com.example.zhishu.security.KeyCipher
import kotlin.coroutines.coroutineContext

/**
 * 按「角色 → 分配的配置方案」读取用户配置后构造 Provider。
 * 三档分工（FAST/STORY/VERIFY）各自指向方案库里的一套 [ApiProfile]，
 * 换模型 = 换一个分配，地址与 Key 不用重填。
 * maxTokens / temperature 由 [OpenAiModelProvider] 从 [ApiProfile] 取用，
 * 调用方只在必须覆盖时通过 [ModelRequest] 显式给值（B6）。
 *
 * [diagnosticsContext] 非空时把每次 HTTP 尝试记进 [GenerationLog]：
 * 卡住时界面只显示「已开始」，没有这份记录就只能靠猜。日志里不含 Key、
 * 不含 prompt 原文、不含完整响应，只有长度、用量、耗时与错误类别。
 */
class ConfiguredModelRouter(
    private val settingsRepository: SettingsRepository,
    private val keyCipherProvider: () -> KeyCipher,
    private val providerFactory: (ApiProfile, String) -> ModelProvider = { profile, key ->
        OpenAiModelProvider(profile = profile, apiKey = key)
    },
    private val diagnosticsContext: Context? = null,
) : ModelRouter {

    override suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
        onStats: (ModelCallStats) -> Unit,
        onAttempt: (CallAttempt) -> Unit,
    ): Result<T> {
        val provider = resolveProvider(role).getOrElse { return Result.failure(it) }
        val startedAt = System.currentTimeMillis()
        var totalUsage = UsageInfo.NONE
        val result = DefaultModelRouter(provider).generate(
            role = role,
            request = request,
            decoder = decoder,
            onStats = { stats ->
                totalUsage = stats.total
                onStats(stats)
            },
            onAttempt = { attempt ->
                logAttempt(role, request, attempt)
                onAttempt(attempt)
            },
        )
        // 挂了用量追踪器的调用方（生成 Worker）会在进度条与完成通知里显示累计值。
        // 没挂就什么都不发生——列表查询、连通性测试这些调用方零改动
        coroutineContext[UsageTrackerElement]?.accumulator?.add(totalUsage.totalTokens)
        logOutcome(role, request, result, totalUsage, System.currentTimeMillis() - startedAt)
        return result
    }

    /** HTTP 成功不等于业务成功：解码/修复失败必须另记一条终态。 */
    private fun <T> logOutcome(
        role: ModelRole,
        request: ModelRequest,
        result: Result<T>,
        usage: UsageInfo,
        elapsedMillis: Long,
    ) {
        val context = diagnosticsContext ?: return
        val error = result.exceptionOrNull()
        val stageName = request.stageLabel?.takeIf { it.isNotBlank() } ?: role.name
        GenerationLog.record(
            context = context,
            stage = "$stageName#FINAL",
            success = result.isSuccess,
            inputChars = request.system.length + request.user.length,
            estimatedInputTokens = TokenBudget.estimateTokens(request.system) +
                TokenBudget.estimateTokens(request.user),
            requestedMaxTokens = request.maxTokens,
            usage = usage,
            elapsedMillis = elapsedMillis,
            errorCategory = error?.let { it::class.simpleName },
            errorSummary = safeDiagnosticSummary(error),
        )
    }

    /**
     * 把一次 HTTP 尝试记进诊断日志。失败的尝试也记——「重试了三次每次都超时」
     * 和「一次就 400」看起来都是「卡住不动」，但下一步动作完全不同。
     */
    private fun logAttempt(role: ModelRole, request: ModelRequest, attempt: CallAttempt) {
        val context = diagnosticsContext ?: return
        val inputChars = request.system.length + request.user.length
        // 同一个 FAST 角色会跑骨架/逐链/审计三种步骤，只记角色名分不清卡在哪一步；
        // 有 stageLabel 就用它（MAP_SKELETON / MAP_CHAIN…），没有才退回角色名
        val stageName = request.stageLabel?.takeIf { it.isNotBlank() } ?: role.name
        GenerationLog.record(
            context = context,
            stage = "$stageName#${attempt.attempt}${if (attempt.kind == AttemptKind.BUSINESS) "" else "-${attempt.kind.name}"}",
            success = attempt.outcome == AttemptOutcome.SUCCESS,
            inputChars = inputChars,
            estimatedInputTokens = TokenBudget.estimateTokens(request.system) +
                TokenBudget.estimateTokens(request.user),
            requestedMaxTokens = request.maxTokens,
            usage = attempt.usage,
            elapsedMillis = attempt.latencyMillis,
            errorCategory = attempt.errorCategory,
            // 错误原文可能回显资料，只记录类别级安全提示；rawResponse 绝不写普通日志。
            errorSummary = safeDiagnosticSummary(attempt.errorCategory, attempt.errorSummary),
        )
    }

    /** 设置页连通性测试用：拉取模型列表（B18） */
    suspend fun listModels(profileId: Long): Result<List<String>> {
        val provider = resolveProfileProviderById(profileId).getOrElse { return Result.failure(it) }
        return provider.listModels()
    }

    /**
     * 设置页连通性测试用：发一次**真实的最小生成调用**（用户反馈：「测试连接说成功，
     * 一生成就失败」）。
     *
     * 只拉 `/models` 测不出三类最常见的失败：模型名有效但账号没额度、模型名写错
     * （/models 仍返回全量列表）、以及把推理模型配到了普通生成角色（只吐思考过程、
     * 正式内容为空）。这些都要一次真实 chat 才能暴露。
     *
     * 请求极小（一句 ping），费用可忽略；错误原样回传，让设置页
     * 直接显示「为什么这个模型不能用」。
     *
     * maxTokens 给到 512：推理模型（deepseek-v4 等）的思考也计入输出配额，
     * 64 的配额会被思考阶段整段吃掉（content 为空、finish_reason=length），
     * 让本来健康的配置被误报「输出被截断」。
     */
    suspend fun probe(profileId: Long): Result<Unit> {
        val provider = resolveProfileProviderById(profileId).getOrElse { return Result.failure(it) }
        val request = ModelRequest(
            role = ModelRole.FAST,
            system = "你是连通性测试助手。只输出一个 JSON 对象 {\"ok\":true}，不要任何解释或代码块标记。",
            user = "输出 {\"ok\":true}",
            maxTokens = 512,
            temperature = 0f,
            jsonMode = true,
            stageLabel = "PROBE",
        )
        // 只关心「能不能正常拿到非空 content」，不解析内容——截断/推理模型空返回
        // 会在 provider.complete 内部被判成对应错误回传
        return provider.complete(request).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * 用「页面上正在填的」地址与 Key 拉模型列表（问题1）。
     *
     * 设置页需要在保存之前就能搜索可用模型，否则用户得先盲填一个模型名才能保存、
     * 保存后才能查列表——正好是反过来的顺序。
     *
     * [plainApiKey] 为空时沿用 [savedKeyRef]（已存在方案里的 Key 密文）解出来的 Key：
     * 用户只改地址、不想重输 Key 的情况。
     */
    suspend fun listModelsWith(
        baseUrl: String,
        plainApiKey: String,
        savedKeyRef: String?,
    ): Result<List<String>> {
        val trimmedUrl = baseUrl.trim()
        if (trimmedUrl.isBlank()) {
            return Result.failure(ModelError.Auth("请先填写接口地址"))
        }
        val apiKey = plainApiKey.ifBlank {
            savedKeyRef?.let { ciphertext ->
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
        val probe = ApiProfile(
            name = "probe",
            baseUrl = trimmedUrl,
            modelName = PROBE_MODEL,
        )
        return providerFactory(probe, apiKey).listModels()
    }

    private suspend fun resolveProvider(role: ModelRole): Result<ModelProvider> {
        val profile = settingsRepository.getAssignedProfile(role)
            ?: return Result.failure(
                ModelError.Auth("还没有为「${role.displayName}」选择 API 配置方案，请到「我的」页选择一套"),
            )
        return resolveProfileProvider(profile, role.displayName)
    }

    private suspend fun resolveProfileProviderById(profileId: Long): Result<ModelProvider> {
        val profile = settingsRepository.getProfile(profileId)
            ?: return Result.failure(ModelError.Auth("配置方案不存在，可能已被删除"))
        return resolveProfileProvider(profile)
    }

    private fun resolveProfileProvider(profile: ApiProfile, displayName: String? = null): Result<ModelProvider> {
        val shownName = displayName ?: profile.name.ifBlank { "该配置方案" }
        if (profile.baseUrl.isBlank() || profile.modelName.isBlank()) {
            return Result.failure(
                ModelError.Auth("「$shownName」的接口地址或模型名未填写，请到「我的」页补全"),
            )
        }
        val apiKey = profile.keyRef?.let { ciphertext ->
            try {
                keyCipherProvider().decrypt(KEY_ALIAS, ciphertext)
            } catch (e: Exception) {
                return Result.failure(ModelError.Auth("API Key 无法解密，请重新填写"))
            }
        }.orEmpty()
        return Result.success(providerFactory(profile, apiKey))
    }

    companion object {
        const val KEY_ALIAS = "zhishu_api_key"

        /** 查列表时的占位模型名，不参与实际请求 */
        private const val PROBE_MODEL = "probe"
    }
}
