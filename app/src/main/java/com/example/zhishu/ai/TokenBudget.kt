package com.example.zhishu.ai

/**
 * 输出上限的统一预算（用户反馈：「最大 token 为什么不尽量调大」）。
 *
 * 用户是对的：`max_tokens` 是**输出上限**而不是收费额度，模型没写那么长就不会
 * 产生那么多费用；调大的唯一收益是「不会被截断」，而截断的代价是整轮白费。
 * 主流模型的上下文窗口已经是 20 万起、上百万常见，把单链输出压在 8000
 * 属于给 2023 年的模型做的保守设定，现在是纯粹的限制。
 *
 * 但也不能无条件写死一个大数：服务商普遍按
 * `prompt_tokens + max_tokens ≤ 上下文窗口` 校验请求，越界返回 400 →
 * [ModelError.InvalidRequest] 的 `retryable=false` → 该步**永久**失败。
 * 所以这里的做法是：
 *
 * ```
 * 有效 max_tokens = min(阶段上限, 上下文窗口 - 估算输入 - 协议预留 - 安全余量)
 * ```
 *
 * 输入短（正常情况）→ 拿到该阶段实际需要的上限（知识链 8K、路线 16K）；
 * 输入意外变长 → 自动让位给输入，绝不发出注定 400 的请求。
 *
 * [estimateTokens] 只用于**防超窗**，不作为计费数据——真实用量一律取服务商
 * 返回的 `usage`（见 [UsageInfo]）。
 */
object TokenBudget {

    /**
     * 未知模型的上下文窗口保守估计。
     *
     * 用户的判断是准的：现在能拿到 OpenAI 兼容接口的模型，最差也有 20 万上下文
     * （DeepSeek 128K、通义/豆包 256K 起、Kimi 128K~1M）。取 20 万而不是取
     * 更大的值，是因为这个数只用来**收紧**输出上限：估低了顶多少写一点，
     * 估高了会发出注定 400 的请求。
     */
    const val DEFAULT_CONTEXT_WINDOW = 200_000

    /** JSON 协议/角色标记等固定开销 */
    const val PROTOCOL_RESERVE = 1_024

    /**
     * 无论输入多长都保底给的输出额度。
     *
     * 低于这个值说明输入本身已经把窗口占满，请求注定失败——这时候不该悄悄
     * 发一个只能吐 200 token 的请求让模型输出被截断，而是要让调用方看到
     * [exceedsWindow] 为真并给出可行动的错误。
     */
    const val MIN_OUTPUT = 2_000

    /**
     * 服务商普遍的**单次输出**上限。
     *
     * 这是最容易踩的一个坑：上下文窗口（20 万~100 万）和「一次回复最多能吐多少 token」
     * 是两个不同的限制。DeepSeek、通义、Kimi 等主流 OpenAI 兼容接口的窗口都在
     * 12.8 万以上，但单次回复普遍只允许 8192。给 `max_tokens=50000` 的后果不是
     * 「模型可以写更长」，而是：
     *
     * - 服务商直接 400（`max_tokens is too large`），走降档重发，白花调用次数；
     * - 或者服务商接受了，但**推理模型会把这 5 万预算全花在思考上**——
     *   一次调用要跑好几分钟，还可能只吐 `reasoning_content`、正式 `content` 为空，
     *   被判成失败丢弃。用户看到的就是「明明有调用记录，结果却没用上」。
     *
     * 所以阶段上限一律压在这个值以内；真有更大需要（讲述路线）时才越过它，
     * 并由 [DefaultModelRouter] 的降档重发兜住个别低上限服务商。
     */
    const val COMMON_PROVIDER_OUTPUT_CAP = 8_192

    /**
     * 各阶段的输出上限：按 prompt 里写明的字数限制反推**实际需要**，再留数倍余量。
     *
     * 曾经这里一律写 5 万（「反正 max_tokens 只是上限，模型没写那么长就不收费」）。
     * 那个推理对**计费**成立，对**延迟和兼容性**不成立，见
     * [COMMON_PROVIDER_OUTPUT_CAP]：它让整个知识主干慢了一个数量级。
     *
     * 另外，当初调大的动机是「怕被截断」，而那次截断的真正原因是本地剪裁
     * （`repairTruncatedJson`）遇到空数组就判定坏 JSON 直接放弃——单链输出里
     * `sourceRefs: []` 几乎必然出现，于是剪裁形同不存在。那个 bug 已经修好，
     * 截断现在能就地救回已完整的节点，不需要靠一个夸张的 max_tokens 来回避。
     */
    object Stage {
        /**
         * 单链展开：一轮最多 6 个节点，每个字段在 prompt 里都有硬字数上限
         * （definition ≤80 字、arisesFrom/leadsTo ≤40 字、confusions ≤3 条、examples ≤2 条），
         * 折算约 500 token/节点 → 一轮约 3000。给 8000 是 2.6 倍余量，
         * 且刚好落在 [COMMON_PROVIDER_OUTPUT_CAP] 内。
         */
        const val CHAIN = 8_000

        /** 骨架：只列链主题与标题清单，不写任何节点内容。长链多链也就三四千 */
        const val SKELETON = 8_000

        /**
         * 骨架降级重试的输出上限。
         *
         * 第一次失败（多为输出被截断或 JSON 解析不了）后，配合「精简要求」的降级
         * prompt 再问一次：这时要求模型少列节点，输出量本就更小，4000 足够；
         * 同时压到更低水位也能绕开单次上限偏低的兼容服务商。
         */
        const val SKELETON_FALLBACK = 4_000

        /** 全局审计：只出缺口/冲突清单，输出比骨架还短 */
        const val AUDIT = 6_000

        /**
         * 讲述路线：一卷 8 章 × 完整因果契约，是全流程最长的 JSON（估算约 8500）。
         * 这一项确实需要越过 [COMMON_PROVIDER_OUTPUT_CAP]；个别低上限服务商由
         * 降档重发兜底（只多一次调用，不再像以前那样永久失败）。
         */
        const val STORY_BIBLE = 16_000

        /**
         * 讲述路线大纲（三段式）：一卷 4~8 章，只出大纲不出契约。
         * 契约被拆走后输出量降到旧整卷的四分之一左右，8K 足够且落在
         * [COMMON_PROVIDER_OUTPUT_CAP] 内——绝大多数服务商一次就能吐完，
         * 不需要靠降档重发兜底。
         */
        const val ROUTE_OUTLINE = 8_000

        /** 单章契约（三段式）：九个字段，输出极短。给 4K 只为不被截断 */
        const val CHAPTER_CONTRACT = 4_000

        /** 续卷规划：与讲述路线同量级 */
        const val NEXT_VOLUME = 16_000

        /**
         * 单章正文：2000~4000 字正文 + 分块 JSON + 摘要/结尾态/滚动记忆/待核实清单。
         * 折算约 6000~8000 token，给 12000 留足余量。
         *
         * 这一路以前不走预算、直接吃 [ApiProfile] 默认的 5 万——同样会被推理模型
         * 拿去当思考预算，是「整个流程都慢」的另一半原因。
         */
        const val CHAPTER = 12_000

        /** 核验：逐块问题清单 + contractChecks，中等长度 */
        const val VERIFY = 8_000

        /** 出题：3~5 道题带选项与解析 */
        const val QUIZ = 6_000

        /** 局部重写：只重写一个段落 */
        const val REWRITE = 4_000
    }

    /**
     * 估算一段文本的 token 数。**故意估高**——估高会让输出额度更保守，
     * 而估低会让请求越窗（后者是不可重试的硬失败）。
     *
     * 口径：中日韩字符按 1 token/字（真实分词器约 0.6~1），其余按 3 字符/token
     * （真实约 3.5~4）。不引入分词器依赖：这里只需要一个不会低估的上界。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk += 1 else other += 1
        }
        return cjk + (other + 2) / 3
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3000..0x303F || // 中文标点
            code in 0x3400..0x4DBF || // 扩展 A
            code in 0x4E00..0x9FFF || // 基本汉字
            code in 0xF900..0xFAFF || // 兼容汉字
            code in 0xFF00..0xFFEF || // 全角字符
            code in 0x3040..0x30FF || // 日文
            code in 0xAC00..0xD7AF // 韩文
    }

    /**
     * 本次请求实际该发的 `max_tokens`。
     *
     * @param stageCap 该阶段的上限（见 [Stage]）
     * @param contextWindow 模型上下文窗口，未知时用 [DEFAULT_CONTEXT_WINDOW]
     */
    fun outputLimit(
        system: String,
        user: String,
        stageCap: Int,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    ): Int = outputLimit(
        inputTokens = estimateTokens(system) + estimateTokens(user),
        stageCap = stageCap,
        contextWindow = contextWindow,
    )

    fun outputLimit(
        inputTokens: Int,
        stageCap: Int,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    ): Int {
        val available = contextWindow - inputTokens - PROTOCOL_RESERVE - safetyMargin(contextWindow)
        return available.coerceIn(MIN_OUTPUT, stageCap.coerceAtLeast(MIN_OUTPUT))
    }

    /** 输入是否已经把窗口占满（此时 [outputLimit] 返回的 [MIN_OUTPUT] 也发不出去） */
    fun exceedsWindow(
        inputTokens: Int,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    ): Boolean = inputTokens + PROTOCOL_RESERVE + safetyMargin(contextWindow) + MIN_OUTPUT > contextWindow

    /** 安全余量：窗口的 5%，至少 512。估算误差与服务商的隐式开销都靠它兜 */
    fun safetyMargin(contextWindow: Int): Int = (contextWindow / 20).coerceAtLeast(512)
}
