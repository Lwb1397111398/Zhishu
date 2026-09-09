# -*- coding: utf-8 -*-
import json, re, sys, glob, os

d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "req")
lines = []
for f in sorted(glob.glob(os.path.join(d, "*.req.json"))):
    r = json.load(open(f, encoding="utf-8"))
    u = r["user"]
    if "chapterType` =" not in u and "本章大纲" not in u:
        continue
    m = re.search(r"允许标为「首次引入」的编号：(.*)", u)
    a = re.search(r"已经在更早章节引入过、不能再算首次的编号：(.*)", u)
    t = re.search(r"`chapterType` = `(\w+)`", u)
    o = re.search(r"第 (\d+) 章《(.+?)》", u)
    lines.append("KEY %s | ch %s %s | type %s" % (
        os.path.basename(f).split('.')[0],
        o.group(1) if o else '?', o.group(2) if o else '?', t.group(1) if t else '?'))
    lines.append("  allowed: " + (m.group(1).strip()[:160].replace("\r","") if m else "?"))
    lines.append("  already: " + ((a.group(1).strip()[:160] if a else "?").replace("\r","").replace("\n"," ")))
open(os.path.join(os.path.dirname(d), "whitelist_view.txt"), "w", encoding="utf-8").write("\n".join(lines))
print("written", len(lines))
