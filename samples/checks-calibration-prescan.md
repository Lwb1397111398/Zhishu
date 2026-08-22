# contractChecks 预扫结果（模型预扫，非人工评分）

> 预扫人：小W（模型评审）｜材料：`samples/checks-calibration.md`（run-20260822-105434，n=2）
> 定位：给人工评分人做参考的第一道筛。**预扫结论不替代两名人工评分人。**

## 一、结论速览

| 项 | 结果 |
|---|---|
| 有正文可核实的 PRESENT | **15/15 全部证据确凿**（预扫 3 分） |
| 名不副实的 PRESENT | **0**（本批未发现"无证据却报 PRESENT"） |
| PARTIAL | 3/3 判定合理（含模型自我识别越界的诚实案例） |
| 无法核实的 checks | 3 个（inheritance-new-r2，正文快照为空，见第三节） |
| 对"ABSENT/UNKNOWN 为 0"风险的评估 | 风险**未在本批兑现**：有正文对照的 PRESENT 全部有据；但模型 0 ABSENT 不代表没有，需人工确认是否存在"该报 ABSENT 没报"的情况 |

## 二、逐条预扫

| # | 变体 | 节点 | claim | evidence | 预扫评分 | 依据（正文段） |
|---|---|---|---|---|---|---|
| 1 | os-new-r2 | 顺序执行与独占资源假设 | INTRODUCED | PRESENT | 3 | [0]-[5] 裸机加载、顺序执行、独占是能力不足的产物 |
| 2 | os-new-r2 | 多道程序设计与 CPU 空闲 | INTRODUCED | PRESENT | 3 | [7]-[26] 等待 I/O→CPU 空转→引入多道→微观串行 |
| 3 | sky-new-r1 | 光作为电磁波与可见光谱 | INTRODUCED | PARTIAL | 判定合理 | 正文讲波长/光谱但"电磁波"表述隐含 |
| 4 | sky-new-r1 | 太阳光多色混合 | INTRODUCED | PRESENT | 3 | [1][7] 棱镜拆白光、连续混合 |
| 5 | sky-new-r1 | 波长与颜色对应 | INTRODUCED | PRESENT | 3 | [1][5][6] 波长-颜色、单色光限定、非光谱色 |
| 6 | sky-new-r1 | 人眼三色感光 | INTRODUCED | PRESENT | 3 | [8]-[11] 三条曲线、重叠敏感、三响应值 |
| 7 | sky-new-r1 | 相加混色与主观色觉 | INTRODUCED | PRESENT | 3 | [14]-[20] 相加/相减、同色异谱、主观经验 |
| 8 | sky-new-r1 | 光作为电磁波（应用） | APPLIED | PARTIAL | 判定合理 | 用了光谱概念但没点"电磁波谱" |
| 9 | sky-new-r1 | 太阳光多色混合（应用） | APPLIED | PRESENT | 3 | [13][16][18] 白纸反射对照屏幕三像素 |
| 10 | sky-new-r1 | 波长颜色（应用） | APPLIED | PRESENT | 3 | [1][4][16] 旧直觉→打破→保留有限成立 |
| 11 | sky-new-r2 | 光作为电磁波 | INTRODUCED | PRESENT | 3 | [10][11][12] 水波类比、电磁波谱、可见光窄段 |
| 12 | sky-new-r2 | 太阳光多色混合 | INTRODUCED | PRESENT | 3 | [4][13][17][19] 棱镜分开、连续分布、白是总外观 |
| 13 | sky-new-r2 | 波长与颜色对应 | INTRODUCED | PRESENT | 3 | [18][20][21][22] 波长顺序、七色人为、单色光限定 |
| 14 | sky-new-r2 | 人眼三色感光 | INTRODUCED | PRESENT | 3 | [6]-[12] 视锥、重叠、三通道压缩 |
| 15 | sky-new-r2 | 相加混色与主观色觉 | INTRODUCED | PRESENT | 3 | [15][16][18][19] 完整覆盖 |
| 16 | inheritance-new-r2 | 继承开始与遗产范围 | INTRODUCED | PRESENT | **无法核实** | 正文快照为空（见第三节） |
| 17 | inheritance-new-r2 | 继承权的取得与丧失 | INTRODUCED | PARTIAL | 判定合理 | 模型自我识别"丧失继承权部分加入了资料未支持的内容" |
| 18 | inheritance-new-r2 | 周家不再默认（结果） | RESULT | PRESENT | **无法核实** | 正文快照为空 |

## 三、无法核实的 3 个 checks（inheritance-new-r2）

- 现象：`inheritance-new-r2-chapter{1,2}-blocks.json` 存在但为空数组；核验的 checks（3 个）基于管线内真实正文产生。
- 根因（预扫推断）：该变体章节版本未激活（正文生成后状态非 readable），运行器快照用 `getActiveBlocks` 读取返回空——**快照采集局限，不是核验谎报**。
- 处理：这 3 个 checks 在人工评分时须用完整正文核实（见盲评包/或重新生成该变体）；预扫不据此判定模型偏报。
- 教训：运行器后续（n=3 起）应对"正文块为空"的情况写状态标记（如 `blocksEmpty=true`），避免把"快照空"误读成"正文空"。

## 四、给人工评分人的建议

1. 预扫已确认 15/15 的 PRESENT 有据——重点抽查可集中在 inheritance-new-r2 的 3 个 + 随机抽 3 个 sky/os 的 PRESENT 做交叉验证即可；
2. ABSENT 识别仍是最薄弱环节：建议人工额外回答"这章契约声明应用/引入的节点里，有没有哪个其实没讲"——这是模型 0 ABSENT 无法覆盖的；
3. 预扫评分仅作参考，请独立打分。
