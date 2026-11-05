#!/usr/bin/env python3
"""按 PRD-ui 统计 AC 覆盖：用例里注释的 AC 编号 vs PRD 里非【Web】的 AC + 3.6 节对照表里的【Web】AC。
用法：python3 -I tools/ac_coverage/coverage.py <PRD-ui-v1.0.md 路径>。只读，不写文件。"""
import pathlib
import re
import sys

prd = pathlib.Path(sys.argv[1]).read_text(encoding="utf8")
root = pathlib.Path(__file__).resolve().parents[2]
tests = "\n".join(p.read_text(encoding="utf8") for d in ("app/src/androidTest", "app/src/test") for p in (root / d).rglob("*.kt"))

# 测试注释里的写法：AC-UI-04-1、04-2、40-6（省略前缀的继承）
covered = set()
for m in re.finditer(r"AC-UI-[0-9、\-]+(?:、[0-9]{2}-[0-9]+)*", tests):
    for part in re.findall(r"(\d{2}-\d+)", m.group(0)):
        covered.add("AC-UI-" + part)
for m in re.finditer(r"//[^\n]*", tests):
    for part in re.findall(r"(?:AC-UI-|、)(\d{2}-\d+)", m.group(0)):
        covered.add("AC-UI-" + part)

mapped = set()
for row in re.findall(r"^\| (AC-UI-[^|]*)\|.*$", prd, re.M):
    pass
for line in prd.splitlines():
    if line.startswith("| AC-UI-") and "| 不适用" not in line:
        cell = line.split("|")[1]
        mapped |= {"AC-UI-" + x for x in re.findall(r"(\d{2}-\d+)", cell)}

rows = []
for line in prd.splitlines():
    m = re.match(r"- (AC-UI-\d+-\d+)(.*)", line)
    if not m:
        continue
    ac, rest = m.groups()
    rows.append((ac, rest))

want, skip = [], []
for ac, rest in rows:
    head = rest[:60]
    wide = "【宽屏】" in head
    web = "【Web】" in head
    p1 = "【P1】" in head
    desktop = re.match(r"AC-UI-34-", ac)
    if wide or p1 or desktop or "（M4 作废" in rest or (web and ac not in mapped) or ac.startswith("AC-UI-44"):
        skip.append(ac)
    else:
        want.append(ac)

hit = [a for a in want if a in covered]
miss = [a for a in want if a not in covered]
print(f"适用 AC {len(want)}，有自动化用例标注 {len(hit)}，未标注 {len(miss)}；不适用（【Web】未映射 / 宽屏 / 桌面 / P1 / F-UI-44）{len(skip)}")
print("未标注：", " ".join(miss))
