#!/usr/bin/env python3
"""Builds the Russian word tables used for completion and next-word prediction.

Sources:
  ru_50k.txt   hermitdave/FrequencyWords, 2018 (OpenSubtitles word counts; CC-BY-SA 4.0):
               how common each word is in everyday speech
  rus_sentences.tsv.bz2   Tatoeba sentences in Russian (CC-BY 2.0 FR): which word follows which

Outputs (committed, so building the app does not need the sources), both sorted by the UTF-8
bytes of the key so the keyboard can binary-search them in place:
  app/src/main/assets/data/ru_words.txt   word<TAB>score   (score 1..250, higher = more common)
  app/src/main/assets/data/ru_next.txt    context<TAB>w1 w2 …   context is one word, two words
                                          ("я не"), or ^ for the start of a sentence

Usage: gen_predict_ru.py <ru_50k.txt> <rus_sentences.tsv.bz2> <assets/data dir>
"""
import bz2, math, re, sys
from collections import Counter, defaultdict

freq_path, tatoeba_path, out_dir = sys.argv[1:4]

WORD = re.compile(r"^[а-яё]+(?:-[а-яё]+)?$")
TOKEN = re.compile(r"[а-яёА-ЯЁ]+(?:-[а-яёА-ЯЁ]+)?|[.!?…]+|[,;:—–()\"«»]")

# ---- how common each word is ----
counts = {}
for line in open(freq_path, encoding="utf-8"):
    parts = line.split()
    if len(parts) != 2:
        continue
    w, n = parts[0].lower(), int(parts[1])
    if WORD.match(w) and n >= 20:
        counts[w] = max(counts.get(w, 0), n)

# ---- which word follows which ----
bi = defaultdict(Counter)
tri = defaultdict(Counter)
seen = Counter()
with bz2.open(tatoeba_path, "rt", encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        prev2, prev = None, "^"
        for tok in TOKEN.findall(parts[2]):
            if not tok[0].isalpha():
                # a comma or a dash breaks the run; the end of a sentence starts a new one
                prev2, prev = None, ("^" if tok[0] in ".!?…" else None)
                continue
            w = tok.lower()
            seen[w] += 1
            if prev is not None:
                bi[prev][w] += 1
                if prev2 is not None:
                    tri[prev2 + " " + prev][w] += 1
            prev2, prev = (prev if prev != "^" else None), w

# words Tatoeba uses often but the subtitles list lacks still deserve a place
for w, n in seen.items():
    if n >= 5 and WORD.match(w) and w not in counts:
        counts[w] = n * 20

top = max(counts.values())
def score(n):
    return max(1, min(250, int(250 * math.log(n) / math.log(top))))

words = {w: score(n) for w, n in counts.items()}

def best(counter, k, least):
    out = [w for w, n in counter.most_common() if n >= least and w in words]
    return out[:k]

nxt = {}
for ctx, c in bi.items():
    if ctx != "^" and ctx not in words:
        continue
    b = best(c, 10, 2)
    if b:
        nxt[ctx] = b
for ctx, c in tri.items():
    b = best(c, 6, 3)
    if b:
        nxt[ctx] = b

def write(name, rows):
    rows = sorted(rows, key=lambda kv: kv[0].encode("utf-8"))
    with open(f"{out_dir}/{name}", "w", encoding="utf-8", newline="\n") as f:
        for k, v in rows:
            f.write(f"{k}\t{v}\n")

write("ru_words.txt", [(w, str(s)) for w, s in words.items()])
write("ru_next.txt", [(k, " ".join(v)) for k, v in nxt.items()])
print(f"{len(words)} words, {sum(1 for k in nxt if ' ' not in k)} one-word contexts, "
      f"{sum(1 for k in nxt if ' ' in k)} two-word contexts")
