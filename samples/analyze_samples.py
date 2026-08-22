"""批次 B 样本分析：从 run 快照提取结构化指标（不依赖 gradle）。
用法: python analyze_samples.py <run-dir>
输出: <run-dir>/analysis.json
"""
import json, os, sys, glob
from collections import Counter, defaultdict

RUN = sys.argv[1] if len(sys.argv) > 1 else sorted(glob.glob(r"E:\AI Agent\work area\知识学习小说工具\samples\runs\run-*"))[-1]
ART = os.path.join(RUN, "artifacts")
FM = r"E:\AI Agent\work area\知识学习小说工具\samples\fixed-maps"
SUBJECTS = ["os", "sky", "inheritance"]
GROUPS = ["legacy", "new"]

def discover_variants(art_dir):
    variants = []
    for f in sorted(os.listdir(art_dir)):
        if f.endswith("-status.json") and "-chapter" not in f and "-route-audit" not in f:
            v = f[: -len("-status.json")]
            if v not in ("bible",):  # 排除 bible-status 之类（实际不存在）
                variants.append(v)
    return variants
CONTRACT_FIELDS = ["entryQuestion", "priorIntuition", "obstacle", "knowledgeGap",
                   "introducedNodeTitles", "appliedNodeTitles", "requiredBeats",
                   "resultingChange", "bridgeToNext"]

def load(name):
    p = os.path.join(ART, name)
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None

def fixed_map_nodes(subject):
    p = os.path.join(FM, subject + ".json")
    if not os.path.exists(p):
        return []
    return json.load(open(p, encoding="utf-8")).get("nodes", [])

def analyze_variant(variant):
    subject = variant.split("-")[0]
    out = {"variant": variant, "status": load(f"{variant}-status.json"),
           "bible": load(f"{variant}-bible.json"), "chapters": []}
    # 章节
    for i in (1, 2):
        plan = load(f"{variant}-chapter{i}-plan.json")
        blocks = load(f"{variant}-chapter{i}-blocks.json") or []
        issues = load(f"{variant}-chapter{i}-issues.json") or []
        st = load(f"{variant}-chapter{i}-status.json")
        ch = {"no": i, "status": st, "title": plan.get("title") if plan else None,
              "chapterType": (plan or {}).get("chapterType"),
              "blocks": len(blocks), "issues": issues}
        # 契约完整率
        ch["wordTarget"] = plan.get("wordTarget", 0) if plan else 0
        ch["nodeTitles"] = (plan or {}).get("nodeTitles") or []
        if plan and plan.get("contractJson"):
            try:
                c = json.loads(plan["contractJson"])
                filled = [f for f in CONTRACT_FIELDS if (c.get(f) if not isinstance(c.get(f), list) else len(c.get(f) or []))]
                ch["contractFields"] = len([f for f in CONTRACT_FIELDS if c.get(f) not in (None, "", [], "")])
                ch["contractFieldsTotal"] = len(CONTRACT_FIELDS)
                ch["contractEmpty"] = not any(c.get(f) not in (None, "", []) for f in CONTRACT_FIELDS)
                ch["introduced"] = c.get("introducedNodeTitles") or []
                ch["applied"] = c.get("appliedNodeTitles") or []
            except Exception:
                ch["contractFields"] = 0; ch["contractFieldsTotal"] = 0; ch["contractEmpty"] = True
        else:
            ch["contractFields"] = 0; ch["contractFieldsTotal"] = 0; ch["contractEmpty"] = True
        # 核验问题统计
        ch["issueTypes"] = Counter(it.get("type") for it in issues)
        ch["noApplication"] = int(ch["issueTypes"].get("no_application", 0))
        ch["missingResult"] = int(ch["issueTypes"].get("missing_result", 0))
        ch["brokenDerivation"] = int(ch["issueTypes"].get("broken_derivation", 0))
        ch["missing"] = int(ch["issueTypes"].get("MISSING", 0)) + int(ch["issueTypes"].get("missing", 0))
        # 正文块节点关联率
        node_titles = {n["title"] for n in fixed_map_nodes(subject)}
        linked = 0
        for b in blocks:
            if b.get("nodeIds"):
                linked += 1
        ch["blockNodeLinkRatio"] = (linked / len(blocks)) if blocks else None
        out["chapters"].append(ch)
    # 前置倒挂（python 重算）：chapter[i] 的 prerequisiteNodeTitles 首次出现章是否 >= 本章
    all_plans = []
    for i in (1, 2):
        p = load(f"{variant}-chapter{i}-plan.json")
        if p: all_plans.append((i, p))
    first_seen = {}
    for i, p in all_plans:
        for t in (p.get("nodeTitles") or []):
            t = t.strip()
            if t and t not in first_seen: first_seen[t] = i
    prereq_violations = []
    for i, p in all_plans:
        for pr in (p.get("prerequisiteNodeTitles") or []):
            pr = pr.strip()
            if not pr: continue
            fs = first_seen.get(pr)
            if fs is None or fs > i:
                prereq_violations.append({"chapter": i, "prereq": pr, "firstSeen": fs})
    out["prereqViolations"] = prereq_violations
    # 重复引入
    introduced_by_chapter = {i: set((p.get("contract") or {}).get("introducedNodeTitles") or []) for i, p in all_plans}
    dup = []
    seen = set()
    for i in sorted(introduced_by_chapter):
        for t in introduced_by_chapter[i]:
            if t in seen: dup.append(t)
            seen.add(t)
    out["duplicateIntroduce"] = dup
    return out

def analyze_calls():
    calls = [json.loads(l) for l in open(os.path.join(RUN, "calls.jsonl"), encoding="utf-8") if l.strip()]
    per_variant = defaultdict(lambda: {"attempts": 0, "failed": 0, "business": 0, "retry": 0, "repair": 0,
                                       "tokens": 0, "usageMissing": 0, "latency": 0,
                                       "finishLength": 0, "repairCount": 0})
    for c in calls:
        key = c["variant"]
        v = per_variant[key]
        v["attempts"] += 1
        if c["outcome"] == "FAILED": v["failed"] += 1
        if c["kind"] == "BUSINESS": v["business"] += 1
        if c["kind"] == "RETRY": v["retry"] += 1
        if c["kind"] == "JSON_REPAIR": v["repair"] += 1
        v["tokens"] += c["totalTokens"]
        if c["usageStatus"] == "MISSING": v["usageMissing"] += 1
        v["latency"] += c["latencyMillis"]
        if c.get("finishReason") == "length": v["finishLength"] += 1
    return {k: dict(v) for k, v in per_variant.items()}

def main():
    result = {"run": os.path.basename(RUN), "variants": {}, "calls": analyze_calls()}
    for v in discover_variants(ART):
        result["variants"][v] = analyze_variant(v)
    out_path = os.path.join(RUN, "analysis.json")
    json.dump(result, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("written:", out_path)
    # 摘要
    for v, d in result["variants"].items():
        chs = d["chapters"]
        types = Counter(ch.get("chapterType") for ch in chs)
        contracts = sum(1 for ch in chs if ch.get("contractFields", 0) >= 6)
        issues = sum(len(ch["issues"]) for ch in chs)
        print(f"{v}: status={d['status'].get('ok')} 类型={dict(types)} 契约>=6项章={contracts}/{len(chs)} 核验问题={issues} 前置倒挂={len(d['prereqViolations'])}")

main()
