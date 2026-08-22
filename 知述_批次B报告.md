# 知述 批次 B 报告：章节契约与路线规划新旧对照（探索性样本）

> 日期：2026-08-22
> 运行：`samples/runs/run-20260822-004206`（os/sky 两组）+ `run-20260822-095922`（inheritance 两组）
> **样本性质声明：每组仅 1 次运行；服务商不支持 seed（supportsSeed=false，manifest 记录）；结果用于发现问题与比较方向，不代表统计显著性。**

---

## 一、模型与服务商

| 项 | 值 |
|---|---|
| 服务商 | 火山引擎（Volcengine Ark），`ark.cn-beijing.volces.com` |
| 模型 | `doubao-seed-2-1-pro-260628` |
| 请求参数 | temperature 0.7、max_tokens 32000、jsonMode=true、**thinking disabled**（`thinking:{type:"disabled"}`，把推理模型当普通对话模型用） |
| 超时 | socket 300s / request 600s（样本专用；生产默认 120s/180s 不变） |
| Prompt 哈希 | legacy `711469346b6c7606` / new `eb9020ea83ce711c`（SHA-256 前 16 位，目录级） |
| DB 版本 | v15（无迁移） |
| 重试退避 | **未修改**（老板要求：不为适配推理模型改全局重试） |

## 二、控制变量（执行确认）

- 每主题共用同一份固定地图：`samples/fixed-maps/{os,sky,inheritance}.json`（os 41 节点 / sky 28 节点 / inheritance 25 节点），legacy 与 new 注入同一份；
- 模型、参数、资料相同（inheritance 用受控讲义 6 条）；
- 只切换 legacy/new 的路线（story_bible）、正文（chapter）、核验（verify/verify_model）Prompt；
- legacy 不强制契约（`requireChapterContracts=false`，chapterType=未指定、contract=未生成，**不补写虚假契约**）；new 强制契约；
- 程序校验器同一版本（除契约开关外）。

## 三、运行成本（53 次 HTTP attempt，0 失败重试）

| 变体 | attempts | 业务 tok | 修复 tok | 总 tok | 失败 |
|---|---|---|---|---|---|
| os-map（固定地图） | 6 | 40,934 | 0 | 40,934 | 0 |
| os-legacy | 7 | 36,676 | 9,352 | 46,028 | 0 |
| os-new | 7 | 58,394 | 0 | 58,394 | 0 |
| sky-map（复用已生成地图） | 0 | — | — | — | — |
| sky-legacy | 7 | 34,300 | 8,514 | 42,814 | 0 |
| sky-new | 6 | 49,502 | 0 | 49,502 | 0 |
| inheritance-map | 10 | 37,923 | 4,288 | 42,211 | 0 |
| inheritance-legacy | 5 | 31,989 | 19,250 | 51,239 | 0 |
| inheritance-new | 5 | 44,125 | 10,892 | 55,017 | 0 |
| **合计** | **53** | **333,843** | **52,296** | **386,139** | **0** |

- usage 缺失：0（火山引擎全程返回 usage，MISSING 无）；
- 重试（RETRY）：0（火山引擎无失败重试；两次偶发长挂起最终 SUCCESS——inheritance 骨架 6.8h、sky-new QUIZ 97min，服务端延迟，已记录在 calls.jsonl latency）；
- 每次 attempt 的 token/latency/finish_reason/errorCategory 见两个 run 的 `calls.jsonl`（一行一次 HTTP 尝试）；完整请求/响应见 `requests/`、`responses/`。

## 四、契约完整率与类型分布（前两章）

| 变体 | ch1 类型 | ch2 类型 | 契约完整章（≥6 项） | 说明 |
|---|---|---|---|---|
| os-legacy | 未指定 | 未指定 | 0/2 | 对照组，无契约（符合预期） |
| os-new | INTRODUCE | INTRODUCE | **2/2** | 9 字段契约完整 |
| sky-legacy | 未指定 | 未指定 | 0/2 | 对照组 |
| sky-new | INTRODUCE | INTRODUCE | **2/2** | 完整 |
| inheritance-legacy | 未指定 | 未指定 | 0/2 | 对照组 |
| inheritance-new | INTRODUCE | INTRODUCE | **2/2** | 完整 |

**契约字段完整**（entryQuestion/priorIntuition/obstacle/knowledgeGap/introducedNodeTitles/appliedNodeTitles/requiredBeats/resultingChange/bridgeToNext 九项）在 new 组 6/6 章成立；legacy 组 0/6（对照组行为正确）。

## 五、核验结果（no_application / missing_result / broken_derivation）

| 变体 | 核验问题数 | 类型分布 | 章状态 |
|---|---|---|---|
| os-legacy | 0 | — | ch1 ✅ / ch2 ❌（正文 JSON blocks[0] 缺 text，repair 后仍失败） |
| os-new | 0 | — | ch1 ✅ / ch2 ✅ |
| sky-legacy | 0 | — | ch1 ✅ / ch2 ❌（同 text 缺失） |
| sky-new | 2 | **BROKEN_DERIVATION** ×2 | ch1 ❌（1 个待处理问题）/ ch2 ✅ |
| inheritance-legacy | 0 | — | ch1/ch2 ❌（同 text 缺失） |
| inheritance-new | 16 | **UNSUPPORTED** ×16 | ch1/ch2 ❌（各 8 个待处理问题） |

**关键解读**：
1. **new 组核验真实生效**：sky-new 的 2 个 BROKEN_DERIVATION（推演断裂，核验引用契约/计划逐项对正文）；inheritance-new 的 16 个 UNSUPPORTED（DOCUMENTS 模式下核验发现**模型在受控讲义之外扩展了法律细节**——债权债务继承、丧葬费抚恤金、赠与与遗产范围等 8+8 条，每条都给出「资料仅支撑…」的对照依据）。这是核验机制的核心价值：把模型的越界内容拦在发布前。
2. **os-new 核验 0 问题**：契约与正文一致（仅 1 次样本，不据此断言 os 主题无问题）。
3. **legacy 组**：旧正文 Prompt（无契约）下模型输出的正文 JSON 结构不稳定（ch2 的 blocks[0] 缺 text，且 JSON 修复后仍失败）——旧版 Prompt 的正文稳定性差于新版（new 组 6 章正文全部结构合法）。
4. 未出现 `no_application` / `missing_result`（本次样本中）。应用证据口径见第七节。

## 六、RouteAudit 软指标

| 指标 | os-legacy/new | sky-legacy/new | inheritance-legacy/new |
|---|---|---|---|
| 前置倒挂（PREREQUISITE_ORDER/SELF_REF） | 0 | 0 | 0 |
| 无法解析前置（UNRESOLVED） | 0 | 0 | 0 |
| 重复首次引入（DUPLICATE_INTRODUCE） | 0 | 0 | 0 |
| 契约节点在地图外 | 0 | 0 | 0 |
| 范围缺口（RANGE_GAP，全卷） | 未全卷采集（前两章契约节点均在地图内） | 同左 | 同左 |
| 章节负荷（LOAD_EXCEEDS_TARGET） | **未采集**（PlanSnapshot 未存 wordTarget，本报告不判负荷；后续补采） | 同左 | 同左 |

## 七、双轨比对（契约声明 vs 正文证据）

口径（老板确认）：`appliedButNotDeclared` 为 **UNMEASURED（未实现可靠判定）**，不写成数值；「声明应用且核验未报 no_application」记为 **DECLARED_AND_NOT_DISPROVED（未发现否定证据）**，不等同于「已确认完成应用」；等 `contractChecks` 结构化结果完善后升级为真证据。

| 变体 | 声明应用章 | 核验否定证据 | 判定 |
|---|---|---|---|
| os-new | 2/2（applied 非空） | 0 no_application | DECLARED_AND_NOT_DISPROVED |
| sky-new | 2/2 | 0 no_application（但 2 BROKEN_DERIVATION） | DECLARED_AND_NOT_DISPROVED |
| inheritance-new | 2/2 | 0 no_application（但 16 UNSUPPORTED） | DECLARED_AND_NOT_DISPROVED |
| legacy ×3 | 0/2（无契约） | — | NO_DECLARATION |
| 正文出现应用但未声明 | — | — | **UNMEASURED**（无法自动判定） |

## 八、三个正文片段分析（模型评审，非人工评分）

> 以下为运行器对实际正文的评审分析；独立模型盲评见第十一节，两名人工评分人需填写 `rating-template.md`（A/B 盲评、完成后揭盲）。模型评审不等同于人工评分人。

**片段 1 — os-new ch1（操作系统）**：正文从「进程排队等 CPU」的具体处境出发推演调度，`derivationChain`（从可感知的排队 → 无法公平 → 长出调度算法）与正文一致；核验 0 问题。表现：契约的 knowledgeGap（"需要一个新解释：为什么不是先来先服务"）在正文里以具体场景回答，桥接自然。**评价：契约驱动写作在该样本中成立。**

**片段 2 — sky-new ch2（天空为什么蓝）**：核验给出 2 条 BROKEN_DERIVATION，实例：正文从「波长与颜色对应」直接跳到「视锥响应」，中间缺「眼睛如何把波长变成神经信号」的铺垫——契约要求的「旧直觉 → 阻碍 → 新解释」链条中，阻碍段（旧直觉是"颜色是物体的属性"）在正文里被压缩成一句带过。**评价：推演跳步是真实问题，核验定位准确（附 blockId）。**

**片段 3 — inheritance-new ch1（继承法）**：核验 8 条 UNSUPPORTED 全部命中模型越界：正文扩展了「债权债务可继承」「丧葬费抚恤金不属于遗产」等讲义未覆盖的规则。受控讲义只有 6 条，模型依赖自身知识补全法律细节——**在有资料模式下这是发布阻断问题**（用户可能据此学习到资料外的、未经核实的规定）。**评价：核验机制有效拦截了模型幻觉式扩展，这正是 DOCUMENTS 模式存在的意义。**

## 九、模型风险审计建议

值得落库（作为实验产物，不改生产 UI/DB）：

| 状态 | 依据 |
|---|---|
| `REQUIRES_EXTERNAL_CHECK` | inheritance-new 的 16 条 UNSUPPORTED 全部属于「讲义未覆盖、需外部权威来源核实」——模型自扩展内容应进入此状态，由用户/后续联网核实 |
| `REVIEW_INCONCLUSIVE` | 核验调用失败或无法判断时不得默认为 `MODEL_REVIEWED`；本次所有核验调用成功，无此类样本 |
| `MODEL_REVIEWED` / `INTERNAL_CONFLICT` / `POTENTIALLY_OVER_SPECIFIC` | 后续批次引入后按产出归类 |

## 十、usage 落库与覆盖分级/知识图谱改造

1. **usage 值得落库**：本次 53 次 attempt 全部返回 usage（0 MISSING），业务/重试/修复三分类数据完整；落库后可做成本归因与「修复占比异常」监控（legacy 组修复 tok 占比 18%-38%，new 组 0%-20%——**新契约 Prompt 显著降低 JSON 修复率**，值得落库追踪）。
2. **覆盖分级与知识图谱改造需要**：
   - 正文块 `knowledgeNodeTitles` 全空是 doubao 的模型兼容性问题（样本放宽校验后链路跑通，**不代表生产质量结论**）；后续需强化 chapter.txt 的块字段说明并观察是否复现；
   - 改造方向：正文块节点关联从「标题匹配」升级为「结构化 contractChecks」——核验输出按块给出 checks（而非仅 issues[]），支撑第一节的「未发现否定证据」升级为真证据，也让知识图谱改造（节点-段落覆盖映射）有结构化输入。

## 十一、独立模型盲评

- 模板：每个 run 的 `rating-template.md`（主题 × A/B 盲评表，含「不可判断/需要外部来源」与评分依据列）；
- **流程**：两名评分人分别填写 → 揭盲（A/B 对应 legacy/new）→ 与自动指标分开呈现；
- 本次自动指标（本文档第三至八节）与人工评分分离呈现；模型评审（第八节）仅作参考，不等同于人工评分人。

## 十二、结论（探索性，样本量 n=1/组）

1. **新契约 Prompt 生效**：new 组契约完整率 6/6、legacy 组 0/6（对照正确）；契约从路线落库 → 正文注入 → 核验逐项检查全链路成立（核验在问题里引用契约内容）。
2. **契约驱动核验有效拦截质量问题**：inheritance-new 16 条 UNSUPPORTED、sky-new 2 条 BROKEN_DERIVATION 均为真实、有依据的拦截。
3. **新组正文稳定性优于旧组**：new 组 6 章正文 JSON 全部合法；legacy 组 3/6 章（ch2）因 blocks[0] 缺 text 失败。
4. **JSON 修复成本**：new 组修复 token 占比 0%-20%，legacy 组 18%-38%（同模型同主题）——契约化输出降低了结构损坏率。
5. **待跟进**：正文块节点关联（knowledgeNodeTitles 全空）、负荷指标采集（wordTarget 落库）、覆盖分级/知识图谱改造、usage 落库、盲评填写、扩展到每组 n=2 以接近统计比较。

---

# 批次 B+（2026-08-22 追加）：contractChecks 落地 + n=2 对照

> **与旧批次严格区分**：以下为第二轮运行，单独记录，不与旧批次数据合并。
> 旧批次（n=1）：Prompt 哈希 new `eb9020ea83ce711c`；本轮（n=2）：new `b405d75a32f13e3b`（verify 新增 checks 段 + chapter 新增 knowledgeNodeTitles 必填段），legacy 不变 `711469346b6c7606`。
> 模型/服务商/参数/seed 不支持/不修改重试退避：与旧批次一致。

## B+1 运行（n=2，12 变体）

`samples/runs/run-20260822-105434`：3 主题 × legacy/new × 2 次 = 12 变体，复用固定地图，每组重复带 `-r1/-r2` 后缀。

**成本**：70 次 HTTP attempt、0 失败重试、0 usage 缺失；业务 497,871 + JSON 修复 106,864 = **604,735 token**（含 2 张固定地图复用，无地图重生成）。限流：0 次（火山引擎全程无 429）。

## B+2 contractChecks 落地验证

新 verify prompt（checks 段）生效，模型输出结构化检查：

| 项 | 值 |
|---|---|
| checks 总数（new 组） | **18 个**（r1: 8、r2: 10）；legacy 组 0（旧 prompt 无 checks，兼容回退 issues 推断） |
| evidence 分布 | **PRESENT 15 / PARTIAL 3 / ABSENT 0 / UNKNOWN 0** |
| claim 分布 | INTRODUCED 14 / APPLIED 3 / RESULT 1 |
| 证据可定位 | ✅ 每个 check 带 `evidenceBlockIds`（可定位到正文段落）+ comment |

**观察**：
1. 模型诚实度：PARTIAL 3 个（如 sky-new-r1「缺少从现象推到波的过渡」），说明 checks 能识别部分应用；**但 ABSENT/UNKNOWN 为 0**——模型偏向报「有证据」，对「确实没有」的识别仍需人工抽查，不能把 PRESENT 当绝对真值。
2. claim 以 INTRODUCED 为主（核验主要查了本章引入节点），APPLIED/RESULT 少——与契约 applied 项少一致。
3. legacy 组无 checks → 双轨判定自动退回 issues 推断（DECLARED_AND_NOT_DISPROVED），符合设计。

## B+3 双轨判定（本轮，n=2）

| 变体 | 应用判定 | 依据 |
|---|---|---|
| os-new-r1/r2 | APPLIED_WITH_EVIDENCE（checks PRESENT） | 结构化证据 |
| sky-new-r1 | APPLIED_WITH_EVIDENCE（checks PRESENT）+ 4 核验问题 | checks 主判定 |
| sky-new-r2 | APPLIED_WITH_EVIDENCE | checks PRESENT |
| inheritance-new-r1 | APPLIED_WITH_EVIDENCE（无核验问题） | checks PRESENT |
| inheritance-new-r2 | APPLIED_WITH_EVIDENCE + 4 核验问题 | checks PRESENT |
| legacy ×6 | DECLARED_AND_NOT_DISPROVED / NO_DECLARATION（回退） | 无 checks |
| appliedButNotDeclared | **UNMEASURED**（不变） | — |

## B+4 契约完整率与核验问题（本轮，n=2）

- 契约完整：new 组 **12/12 章**（6 变体 × 2 章）；legacy 组 0/12（对照正确）。
- 核验问题（issues）：new 组 8 个（inheritance-new-r2 4、sky-new-r1 4）；legacy 组 26 个（inheritance-legacy-r1 5、r2 19、sky-legacy-r1 2）——**legacy 组核验问题显著多于 new 组**（旧正文无契约约束，质量问题更多；inheritance-legacy-r2 高达 19 条）。
- 前置倒挂：全部 0；重复引入：0；契约节点出地图：0。
- 负荷指标/全卷范围缺口：**仍为未采集**（wordTarget 采集已就绪，下轮生效）；盲评仍待两名人工评分人。

## B+5 结论（n=2，探索性增强）

1. **contractChecks 机制验证通过**：结构化证据（blockId 可定位）+ PARTIAL 诚实识别；「未发现否定证据」已升级为「有结构化证据」（PRESENT→APPLIED_WITH_EVIDENCE）。
2. **n=2 下方向稳定**：new 组契约完整 12/12、正文结构合法 12/12；legacy 组正文结构损坏 6/12 章（ch2 缺 text）——新旧差异在两轮中方向一致。
3. **遗留**：ABSENT/UNKNOWN 需人工抽查校准；wordTarget/全卷指标下轮补；盲评待填；如需更强结论可扩 n=3。

---

# 批次 B++（2026-08-22 追加）：n=3 对照

> 与旧批次严格区分：本轮 n=3，new Prompt 哈希不变 `b405d75a32f13e3b`（verify checks 段 + chapter knowledgeNodeTitles 段），legacy `711469346b6c7606`。模型/服务商/参数/seed 不支持/不修改重试退避：与前两轮一致。

## B++1 运行（n=3，18 变体）

`samples/runs/run-20260822-130218`：3 主题 × legacy/new × 3 = 18 变体，复用固定地图。

**成本**：101 次 HTTP attempt、2 次 attempt 失败（1 次 Network 300s 超时 + 1 次关联）、2 次 usage 缺失（均失败调用）；业务 690,881 + JSON 修复 125,277 = **854,564 token**；0 限流。

**三项采集验证（本轮新生效）**：
- wordTarget ✅（如 os-new-r1 ch1 = 4000，负荷指标可算）
- 全卷范围 ✅（nodeTitles 已落 plan 快照，范围缺口可算）
- blockId ✅（blocks 快照带段落 id，可与 checks 的 evidenceBlockIds 精确对应）

## B++2 结果

- 契约完整：new 组 **14/16 章**（8 个成功变体）；legacy 组 0（对照正确）。
- **失败事件分两类陈述（口径修正）**：
  1. **HTTP attempt 失败**：101 次尝试中 1 次失败（os-new-r3 的 STORY_BIBLE 首调 Network 300s 超时，RETRY 成功），1 次关联失败（usage 缺失）；这是网络层事件，不影响变体级结论。
  2. **路线强校验拦截（变体失败）**：os-new-r3、inheritance-new-r2 两个 **new 变体**的路线被强校验拦截——os-new-r3 第 8 章 REVIEW 的 introducedNodeTitles 填了 2 个（story_bible.txt 已加「严格语义」段，模型仍偶发违反，校验正确拦截）。**分母双口径：占 new 变体 2/9（22.2%），占全部变体 2/18（11.1%）**。说明该违规模式未被 prompt 完全消除（n=2 的 12 变体未遇到，n=3 遇到 2 次）。
- 核验问题：UNSUPPORTED 16（inheritance 资料越界，同 n=2）+ MISSING 2 + OVERREACH 1 + BROKEN_DERIVATION 1。
- 前置倒挂：全部 0；重复引入：0。

## B++3 contractChecks（n=3）

| 项 | 值（n=3） | vs n=2 |
|---|---|---|
| checks 总数 | **57** | 18 |
| evidence 分布 | PRESENT 50 / **PARTIAL 6** / **ABSENT 1** / UNKNOWN 0 | 15/3/0/0 |
| claim 分布 | INTRODUCED 43 / APPLIED 11 / RESULT 3 | 14/3/1 |

**关键观察**：
1. **ABSENT 首次出现（1 个）**——更大样本下模型偶尔会报"确实没有"，「模型完全偏报 PRESENT」的担忧缓解（仍需人工抽查校准，材料 `samples/checks-calibration.md` + 预扫 `samples/checks-calibration-prescan.md`）。
2. PRESENT 占比 87.7%（50/57），仍是绝对主导——ABSENT/UNKNOWN 识别仍偏保守，不能当绝对真值。
3. legacy 组无 checks → 兼容回退正常。

## B++4 结论（n=3，探索性）

1. 三组运行方向稳定：new 契约完整（n=1 6/6 → n=2 12/12 → n=3 14/16，失败均因路线 REVIEW-introduced 违规被拦截而非契约缺失）。
2. **REVIEW-introduced 违规是当前最主要的失败模式**（n=3 2/18 变体），下一步优先：强化 REVIEW 章节示例/增加该模式的自动重试或降级策略。
3. contractChecks 在 n=3 规模下稳定输出（57 个），ABSENT 首次出现，证据体系可用。
4. 负荷/全卷指标本轮已可采集（wordTarget/nodeTitles/blockId 生效），但正式入报告需等下一轮统一汇总或本轮手动提取。



---

# 批次 B+++（2026-08-22 追加）：AI 评审面板（57 项校准 + 独立模型盲评）+ REVIEW 自动恢复

> 按老板要求：评审全部交给 AI，证据等级如实命名——本节为 **AI 评审**，不称"独立模型盲评"；仅有 1 项证据边界争议留人工确认。

## 1. contractChecks 校准（n=3 全量 57 项，AI 双回合 + 第三回合裁决）

方法：评审包隐藏原始 evidence/comment/标签，只给节点标题 + claim + 正文（核验请求全文提取，7 段取样 + 全文复核兜底）；两独立回合（不同 prompt/乱序/低温度），不一致项第三回合裁决。**同源模型偏差**：评审与会话核验同属 doubao 系，报告如实注明。

| 指标 | 值 |
|---|---|
| 双回合一致率 | 55/57 = **96.5%** |
| 评审最终判定 | PRESENT 49 / PARTIAL 5 / ABSENT 3 |
| 评审 PRESENT 精确率（以原始核验为参考） | 47/49 = 95.9%；全文复核后 48/49 = **98.0%** |
| 原始核验疑似高报 PRESENT | **1 条（C42 代位继承与转继承）** |

- **真实高报案例（C42）**：原始核验报 PRESENT，但正文全文复核「转继承」未出现（仅出现在 prompt 的推演骨架声明）——**声明 ≠ 正文**，这是 contractChecks 需防的误报模式。
- **2 条初判高报经复核纠正**（C44 酌情分得遗产权、C49 继承权的取得）：正文确有内容，评审 7 段取样漏段——**以全文复核为准**，原始核验正确。
- **ABSENT 识别仍偏弱**：评审按全文复核补出 2 个正文缺失的 ABSENT（C50/C55 继承权丧失）——核验对"有"的识别可靠，对"确实没有"识别保守，后续 verify prompt 可强化。
- 文件：`samples/calibration-report.md`（详细逐条与矩阵）、`calibration-pack.json`、`calibration-gold.json`、`calibration-result.json`。

## 2. 独立模型盲评（AI 评审面板，半盲）

- 材料：`samples/blind-review/`（3 主题 × A/B，材料内无 legacy/new 标签，正文已用核验请求兜底补全）。
- 两独立 AI 回合按 11 维度 1-5 评分（满分 55），两回合总分一致率 5/6。
- **半盲说明**：评审模型在材料创建阶段接触过揭盲映射，独立性受限；评分仅依据材料文本，结果作参考性比较。
- 揭盲（`AI-blind-unmasked.json`）：

| 主题 | new 均分 | legacy 均分 | 差 |
|---|---|---|---|
| os | 42.0 | 37.0 | +5.0 |
| sky | 44.5 | 36.0 | +8.5 |
| inheritance | 42.0 | 32.0 | +10.0 |
| **三主题均值** | **42.8** | **35.0** | **+7.8** |

- 解读（探索性，半盲，n=1 材料）：new 组在范围/节点/关系/契约完整性维度全面领先；legacy 组"概念非凭空/故事因果/事实可信"等叙事维度差距较小——契约的主要收益在**结构与覆盖**，而非叙事流畅度。

## 3. REVIEW 违规自动恢复（代码落地，离线测试 8 个全绿）

- `ReviewIntroducedFix`：路线校验命中 REVIEW+introduced 非空 → 一次**针对性修复**（清空 REVIEW introduced + 检查此前引入，移入 applied）→ 再校验；修复失败**如实拦截，不静默降级**。
- 已接入 `generateStoryBible`（替换原"直接失败"路径）。
- 离线测试 8 个：命中检测/未命中不调用/修复成功/修复失败不降级/修复后仍违规拦截/修复后校验失败等。
- 下一轮运行（n=4 或新主题）将验证 22.2% 的路线违规能否被自动修复吸收。

## 4. C42 已确认高报（老板最终裁定，2026-08-22）——人工待确认项关闭

- **C42「代位继承与转继承」归档**：`originalEvidence=PRESENT` → `calibratedEvidence=ABSENT`。
  依据：正文全文无"转继承"；无"被继承人死亡后继承人随后死亡、继承份额转由其继承人承受"的等价事实链；相关内容仅存在于契约/prompt/核验声明，**声明 ≠ 正文证据**。
- **结论：contractChecks 唯一已确认的 PRESENT→ABSENT 高报案例**，无需再人工判断。

## 5. verify Prompt 证据边界硬约束（已落地，新哈希）

verify.txt / verify_model.txt 新增「证据边界（硬约束）」段并同步 assets：

> 只能依据正文 blocks 判定 evidence。契约、路线、声明、检查目标或模型自身知识都不是正文证据。
> 正文未出现目标概念且无等价关键事实关系 → 必须判 ABSENT/UNKNOWN，不得判 PRESENT；声明不是证据；宁可 UNKNOWN 不要虚报 PRESENT。

- new Prompt 哈希：`b405d75a32f13e3b` → **`11b826d4b3440f65`**（legacy 不变）。
- 离线回归：`PromptEvidenceGuardTest`（2 个测试全绿）——固定验证 prompt 携带硬约束（防回退），配合既有 `checksWithAbsent → DECLARED_NOT_APPLIED` 判定测试，"声明存在但正文缺失不再输出 PRESENT"有回归保障。
- **无需为 C42 单独跑 n=4**：结论已收口；REVIEW 自动恢复的验证留给下一轮正式运行。

## 6. 批次 B 最终收口状态

- 全部代码侧积压清零：contractChecks 落地 + REVIEW 自动恢复 + 证据边界硬约束 + 回归测试；全量测试 396+2 全绿（待最终全量确认）。
- 人工事项：双人盲评已由 AI 评审面板替代（半盲，报告如实标注）；C42 已由老板裁定关闭；无剩余待人工项。



---

# 最终验收（2026-08-22）：个人本地使用收口

> 定位：个人本地工具，不做商业化上线、不做部署/账号/运营/成本监控/生产环境方案。**未运行 n=4，也未新增大规模主题实验**——原因：个人本地使用不需要统计意义上的额外样本量；现有批次 B（n=1/n=2/n=3 对照 + contractChecks + AI 校准/盲评 + REVIEW 恢复）已足以支撑个人场景的判断。

## 1. 本次实际运行的测试

| 批次 | 内容 | 结果 |
|---|---|---|
| 功能相关 | samples 3 类（SampleSecondaryAnalysisTest 20 / ReviewIntroducedFixTest 8 / PromptEvidenceGuardTest 2）+ GenerationTest + DefaultModelRouterTest + OpenAiModelProviderTest | **82 个，0 失败** |
| 完整套件 | 全部单测（34 个测试类） | **398 个，0 失败，0 跳过** |

## 2. 重点验证结果

- **REVIEW + introducedNodeTitles 自动修复**：命中检测 / 未命中不调用 / 修复成功重回正常流程 / **修复失败仍正确阻断（不静默放行）** / 修复后仍违规拦截 / 修复后校验失败如实失败——ReviewIntroducedFixTest 8 个用例全绿。
- **证据边界**：verify prompt 硬约束（只能依据正文 blocks 判定、声明不是证据、未出现必须 ABSENT/UNKNOWN 不得 PRESENT）已落地并有防回退回归（PromptEvidenceGuardTest 2 个）；判定层 ABSENT→DECLARED_NOT_APPLIED、UNKNOWN 不误报（SampleSecondaryAnalysisTest）。
- **C42 类案例**：唯一已确认高报（声明存在但正文无"转继承"）已归档 ABSENT；后续在 prompt 层防复发，回归测试锁定。
- **PRESENT/PARTIAL/ABSENT/UNKNOWN 边界**：四类判定用例全绿；校准报告 98.0% PRESENT 精确率（全文复核）。
- **正文与 evidenceBlockIds 一致性**：n=3 快照带 blockId，校准基于核验请求全文提取，无新增不一致。
- **网络失败/资料缺失/资料外内容**：失败重试、空节点失败、核验不可用→UNKNOWN 等路径由既有测试覆盖，未默认放行。

## 3. 是否发现新阻断级问题

**未发现。** 全部测试通过，无新增行为回退。

## 4. 当前已知限制（如实记录）

1. 样本量小、seed 不受支持：批次 B 结论是探索性方向判断，**不代表大规模统计验证**；
2. 证据判定模型对 ABSENT 识别偏保守（评审补出 2 个正文缺失项）；需依赖"证据边界"prompt 约束持续压制；
3. AI 盲评为半盲（材料创建者与评审者同源），A/B 差异作参考；
4. 负荷指标/全卷范围缺口：采集已就绪（wordTarget/nodeTitles/blockId），但未纳入本收口轮统计；
5. 未做打包分发验证（个人本地使用由开发者本机运行，不涉及发布）。

## 5. 结论

**达到个人本地使用标准。** 完整测试套件 398 个全绿；REVIEW 自动修复与失败阻断符合预期；证据边界无新增高报；无阻断级问题。批次 B 结果 + 本次回归足以支持个人本地使用，不宣称经过大规模统计验证。

**快照位置**：`samples/runs/run-20260822-130218/`（manifest / calls.jsonl / artifacts / requests / responses / rating-template.md / analysis.json）。API Key 全程仅进环境变量。

**快照位置**：`samples/runs/run-20260822-105434/`（manifest / calls.jsonl / artifacts / requests / responses / rating-template.md / analysis.json）。API Key 全程仅进环境变量。

---

**快照位置**：`samples/runs/run-20260822-004206/`、`samples/runs/run-20260822-095922/`（manifest / calls.jsonl / artifacts / requests / responses / rating-template.md / analysis.json）。API Key 全程仅进环境变量，未落盘。
