#!/usr/bin/env python3
"""Builds the emoji data shipped in the APK (the keyboard can download newer data itself).

  emoji.txt          group<TAB>emoji emoji ...   (fully-qualified; one entry per emoji)
  emoji_variants.txt emoji<TAB>variant variant ...  (skin tones, man/woman forms: long-press)
  emoji_names.txt    emoji<TAB>中文关键词|...<TAB>english|keywords   (CLDR annotations, for 查找)
  emoji_version.txt  the Unicode emoji version

Usage (inputs from unicode.org and the cldr-json npm packages):
  gen_emoji.py emoji-test.txt zh.json zh-derived.json en.json en-derived.json OUTDIR
The app's EmojiData.kt parses the same sources the same way when it updates."""
import json, sys, os
NAMES = {"Smileys & Emotion": "表情", "People & Body": "人物", "Animals & Nature": "动物", "Food & Drink": "食物",
         "Travel & Places": "旅行", "Activities": "活动", "Objects": "物品", "Symbols": "符号", "Flags": "旗帜"}
test, zh, zhd, en, end, out = sys.argv[1:7]
TONES = range(0x1F3FB, 0x1F400)
GENDER = (0x2640, 0x2642)

def base_key(cps):
    """Skin tones and a trailing ‍♀️/‍♂️ removed: every variant of an emoji shares this key."""
    c = [x for x in cps if x not in TONES and x != 0xFE0F]
    if len(c) >= 3 and c[-2] == 0x200D and c[-1] in GENDER:
        c = c[:-2]
    return tuple(c)

groups, variants, owner, cur, version = {}, {}, {}, None, ""
for line in open(test, encoding="utf-8"):
    if line.startswith("# Version:"):
        version = line.split(":", 1)[1].strip()
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
    e = "".join(chr(c) for c in cps)
    k = base_key(cps)
    if k in owner:
        variants[owner[k]].append(e)
    else:
        owner[k] = e
        variants[e] = []
        groups.setdefault(cur, []).append(e)

def ann(*paths):
    m = {}
    for p in paths:
        d = json.load(open(p, encoding="utf-8"))
        a = d.get("annotations", d.get("annotationsDerived"))["annotations"]
        for k, v in a.items():
            words = v.get("tts", []) + [w for w in v.get("default", []) if w not in v.get("tts", [])]
            m[k.replace("️", "")] = words
    return m
zm, em = ann(zh, zhd), ann(en, end)
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "emoji.txt"), "w", encoding="utf-8") as f:
    for g, items in groups.items():
        f.write(NAMES.get(g, g) + "\t" + " ".join(items) + "\n")
with open(os.path.join(out, "emoji_names.txt"), "w", encoding="utf-8") as f:
    for items in groups.values():
        for e in items:
            k = e.replace("️", "")
            z, n = zm.get(k, []), em.get(k, [])
            if z or n:
                f.write(e + "\t" + "|".join(z) + "\t" + "|".join(n) + "\n")
with open(os.path.join(out, "emoji_variants.txt"), "w", encoding="utf-8") as f:
    for e, v in variants.items():
        if v:
            f.write(e + "\t" + " ".join(v) + "\n")
open(os.path.join(out, "emoji_version.txt"), "w").write(version + "\n")
print(version, {NAMES.get(g, g): len(v) for g, v in groups.items()})
