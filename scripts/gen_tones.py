#!/usr/bin/env python3
"""Compacts mozillazg/pinyin-data (MIT) into 字<TAB>读音1,读音2 for the 拼音模式."""
import sys
src, out = sys.argv[1], sys.argv[2]
rows = []
with open(src, encoding="utf-8") as f:
    for line in f:
        if not line.startswith("U+"):
            continue
        head, _, rest = line.partition(":")
        readings = rest.split("#")[0].strip()
        if not readings:
            continue
        ch = chr(int(head[2:], 16))
        rows.append(f"{ch}\t{readings}")
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(rows) + "\n")
print(f"{len(rows)} characters -> {out}")
