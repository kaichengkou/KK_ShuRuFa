#!/usr/bin/env python3
"""Builds the word-association index used for 联想 (prediction after committing a word).

From rime-ice's dictionaries: whenever a dictionary word W starts with another dictionary
word P (or a single character), W[len(P):] becomes a continuation of P, weighted by W's
frequency. The output is sorted by key so the app can binary-search it in place:

    key<TAB>cont1 cont2 cont3 ...
"""
import os
import sys
from collections import defaultdict

src, out = sys.argv[1], sys.argv[2]
files = ["8105", "base", "ext", "tencent"]
weights = {}
for name in files:
    path = os.path.join(src, "cn_dicts", name + ".dict.yaml")
    body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not body:
                body = line.startswith("...")
                continue
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            word = parts[0]
            try:
                w = int(parts[2])
            except ValueError:
                continue
            if w > weights.get(word, -1):
                weights[word] = w

MAX_PER_KEY = 8
MAX_KEY_LEN = 4
assoc = defaultdict(dict)
for word, w in weights.items():
    n = len(word)
    if n < 2 or n > 8 or w <= 0:
        continue
    for k in range(1, min(n, MAX_KEY_LEN + 1)):
        prefix, rest = word[:k], word[k:]
        if k > 1 and prefix not in weights:
            continue
        # prefer continuations that are words of their own
        score = w * (3 if rest in weights else 1)
        if score > assoc[prefix].get(rest, 0):
            assoc[prefix][rest] = score

os.makedirs(os.path.dirname(out), exist_ok=True)
keys = sorted(assoc, key=lambda s: s.encode("utf-8"))
with open(out, "w", encoding="utf-8", newline="\n") as f:
    for key in keys:
        conts = sorted(assoc[key].items(), key=lambda kv: -kv[1])[:MAX_PER_KEY]
        f.write(key + "\t" + " ".join(c for c, _ in conts) + "\n")
print(f"{len(keys)} keys from {len(weights)} words -> {out}")
