# -*- coding: utf-8 -*-
"""修好 # 归一后，契约/大纲 prompt 文本变了、键漂移。
把旧答案按「同一阶段+同一章」搬到新键上；ch2 契约保持 no-# 变体（正是要测的输入）。"""
import json, os, re

ROOT = os.path.dirname(os.path.abspath(__file__))
REQ, RESP = os.path.join(ROOT, "req"), os.path.join(ROOT, "responses")

# 旧答案: key -> 文件
def load_old(key):
    p = os.path.join(RESP, key + ".json")
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else None

CONTRACT_OLD = {1: "edf149cf8f18", 2: "30a9374e5534", 3: "737554025179", 4: "db64f3a6140a", 5: "51914c240b23"}

migrated = []
for f in sorted(os.listdir(REQ)):
    k = f.split(".")[0]
    rp = os.path.join(RESP, k + ".json")
    if os.path.isfile(rp): continue
    r = json.load(open(os.path.join(REQ, f), encoding="utf-8"))
    u, s = r["user"], r["system"]
    m = re.search(r"第 (\d+) 章《(.+?)》", u)
    if "本章大纲" in u and m:
        old = CONTRACT_OLD.get(int(m.group(1)))
        body = load_old(old)
        if body:
            open(rp, "w", encoding="utf-8").write(body)
            migrated.append((k, "CONTRACT-ch" + m.group(1), "<-" + old))
            continue
    # 兜底：按 stage 名找 _stage_ 文件
for k, tag, src in migrated:
    print(k, tag, src)
print("total:", len(migrated))
