#!/usr/bin/env python3
"""Builds the 古诗文 extension pack from chinese-poetry (MIT, github.com/chinese-poetry/chinese-poetry).

Outputs (committed, so building the app does not need the source data):
  rime-data/packs/kk_poetry.dict.yaml   lines with pinyin, for typing a line in one go
  app/src/main/assets/data/poetry_next.txt   line<TAB>next line, for 联想 of the following line

Usage: gen_poetry.py <chinese-poetry checkout> <opencc dictionary dir>
Needs: pip install pypinyin
"""
import json, os, re, sys
from pypinyin import lazy_pinyin

src, ocdir = sys.argv[1], sys.argv[2]

def load_ts():
    m = {}
    for name in ("TSPhrases.txt", "TSCharacters.txt"):
        for line in open(os.path.join(ocdir, name), encoding="utf-8"):
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 2 or line.startswith("#"):
                continue
            k, v = parts
            m.setdefault(k, v.split(" ")[0])
    return m
TS = load_ts()
MAXK = max(len(k) for k in TS)

def simp(s):
    out, i = [], 0
    while i < len(s):
        for n in range(min(MAXK, len(s) - i), 0, -1):
            v = TS.get(s[i:i + n])
            if v is not None:
                out.append(v); i += n; break
        else:
            out.append(s[i]); i += 1
    return "".join(out)

def poems_nested(path):
    d = json.load(open(path, encoding="utf-8"))
    for group in d["content"]:
        for p in group["content"]:
            yield p.get("paragraphs", [])

def guwen(path):
    d = json.load(open(path, encoding="utf-8"))
    for group in d["content"]:
        for p in group["content"]:
            yield p.get("paragraphs", [])

def flat(path, key):
    for p in json.load(open(path, encoding="utf-8")):
        yield p.get(key, [])

collections = [
    ("唐诗三百首", 100, poems_nested(os.path.join(src, "蒙学/tangshisanbaishou.json"))),
    ("千家诗", 100, poems_nested(os.path.join(src, "蒙学/qianjiashi.json"))),
    ("宋词三百首", 100, flat(os.path.join(src, "宋词/宋词三百首.json"), "paragraphs")),
    ("诗经", 80, flat(os.path.join(src, "诗经/shijing.json"), "content")),
    ("论语", 80, flat(os.path.join(src, "论语/lunyu.json"), "paragraphs")),
    ("楚辞", 70, flat(os.path.join(src, "楚辞/chuci.json"), "content")),
    ("古文观止", 60, guwen(os.path.join(src, "蒙学/guwenguanzhi.json"))),
]

def mark(sep):
    """The one mark kept from what separates two clauses: the first real one, full width."""
    for ch in sep:
        ch = {",": "，", ".": "。", "!": "！", "?": "？", ";": "；", ":": "："}.get(ch, ch)
        if ch in "，。！？；：、": return ch
    return "，" if sep.strip() else ""

SPLIT = re.compile(r"[，。！？；：、,.!?;:“”‘’「」『』《》（）()\s—…·]+")
HAN = re.compile(r"^[一-鿿]+$")
entries, nexts = {}, {}
for name, weight, poems in collections:
    for paragraphs in poems:
        def strings(x):
            if isinstance(x, str): yield x
            elif isinstance(x, dict):
                for v in x.values(): yield from strings(v)
            elif isinstance(x, list):
                for v in x: yield from strings(v)
        text = simp("".join(strings(paragraphs)))
        # clauses with the punctuation after each (，苔深不能扫。): the 联想 offers the next line
        # with its marks, so a poem can be written on without typing them
        parts = re.split("(" + SPLIT.pattern + ")", text)
        clauses, marks = [], []
        for j in range(0, len(parts), 2):
            if parts[j]:
                clauses.append(parts[j]); marks.append(mark(parts[j + 1] if j + 1 < len(parts) else ""))
        for i, c in enumerate(clauses):
            ok = HAN.match(c) and 4 <= len(c) <= 12
            if ok and c not in entries:
                entries[c] = (" ".join(lazy_pinyin(c, errors="ignore")), weight)
            if ok and i + 1 < len(clauses):
                n = clauses[i + 1]
                if HAN.match(n) and 2 <= len(n) <= 14 and c not in nexts:
                    nexts[c] = marks[i] + n + marks[i + 1]

os.makedirs("rime-data/packs", exist_ok=True)
with open("rime-data/packs/kk_poetry.dict.yaml", "w", encoding="utf-8") as f:
    f.write("# KK输入法 古诗文扩展词库，来自 chinese-poetry（MIT），由 scripts/gen_poetry.py 生成\n---\n")
    f.write('name: kk_poetry\nversion: "1"\nsort: by_weight\n...\n')
    for c, (py, w) in entries.items():
        if py.count(" ") + 1 == len(c):
            f.write(f"{c}\t{py}\t{w}\n")
os.makedirs("app/src/main/assets/data", exist_ok=True)
# sorted by the UTF-8 bytes of the line, so the keyboard can search the file in place
with open("app/src/main/assets/data/poetry_next.txt", "w", encoding="utf-8") as f:
    for c in sorted(nexts, key=lambda k: k.encode("utf-8")):
        f.write(f"{c}\t{nexts[c]}\n")
print(len(entries), "lines,", len(nexts), "next-line pairs")
