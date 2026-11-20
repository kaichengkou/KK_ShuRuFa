#!/usr/bin/env python3
"""Compacts Unicode's emoji-test.txt into group<TAB>emoji emoji ... (fully-qualified, no skin tones).
Usage: curl -sO https://unicode.org/Public/emoji/latest/emoji-test.txt && gen_emoji.py emoji-test.txt out.txt"""
import sys
NAMES = {"Smileys & Emotion": "表情", "People & Body": "人物", "Animals & Nature": "动物", "Food & Drink": "食物",
         "Travel & Places": "旅行", "Activities": "活动", "Objects": "物品", "Symbols": "符号", "Flags": "旗帜"}
groups, cur = {}, None
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("# group:"):
        cur = line.split(":", 1)[1].strip()
        if cur == "Component": cur = None
        continue
    if cur is None or line.startswith("#") or ";" not in line:
        continue
    codes, rest = line.split(";", 1)
    if "fully-qualified" not in rest:
        continue
    cps = [int(c, 16) for c in codes.split()]
    if any(0x1F3FB <= c <= 0x1F3FF for c in cps):  # skin tone variants
        continue
    groups.setdefault(cur, []).append("".join(chr(c) for c in cps))
with open(sys.argv[2], "w", encoding="utf-8") as f:
    for g, items in groups.items():
        f.write(NAMES.get(g, g) + "\t" + " ".join(items) + "\n")
print({NAMES.get(g, g): len(v) for g, v in groups.items()})
