"""Builds app/src/main/assets/wordfix/{ne_verbs.txt,hyphen.tsv} for WordFixer (rules 4 and 5).

pip install pymorphy3 pymorphy3-dicts-ru; FREQ = a "word<TAB>count" list of Russian book words
(VoiceTypeKeyboard's assets/dict/ru.tsv.gz was used, 11.2026).
  ne_verbs.txt — verb forms (VERB/INFN/GRND, met >= 30 times) whose «не» + form is no OpenCorpora word
                 (so «неделю», «нежить», «немой» are never split);
  hyphen.tsv   — OpenCorpora words with -то/-либо/-нибудь/-таки/кое- run together → hyphened,
                 when the run-together spelling is no word.
"""
import gzip, sys, pymorphy3
FREQ, OUT = sys.argv[1], sys.argv[2]
m = pymorphy3.MorphAnalyzer()
words, hy = set(), set()
for p in m.iter_known_word_parses():
    words.add(p.word)
    if '-' in p.word: hy.add(p.word)
E = {w.replace('ё', 'е') for w in words}
ne = set()
for line in gzip.open(FREQ, 'rt', encoding='utf-8'):
    w, f = line.rstrip('\n').split('\t')
    if int(f) < 30 or len(w) < 3 or w == 'при': continue
    if not any(p.tag.POS in ('VERB', 'INFN', 'GRND') and p.is_known for p in m.parse(w)): continue
    e = w.replace('ё', 'е')
    if 'не' + e not in E: ne.add(e)
open(f'{OUT}/ne_verbs.txt', 'w', encoding='utf-8').write('\n'.join(sorted(ne)) + '\n')
hp = {}
for w in hy:
    a, _, b = w.rpartition('-')
    if b in ('то', 'либо', 'нибудь', 'таки') and '-' not in a: hp[a + b] = w
    a, _, b = w.partition('-')
    if a == 'кое' and '-' not in b: hp[a + b] = w
out = {}
for k, v in hp.items():
    for kk in {k, k.replace('ё', 'е')}:
        if kk not in E: out[kk] = v
open(f'{OUT}/hyphen.tsv', 'w', encoding='utf-8').write(''.join(f'{k}\t{v}\n' for k, v in sorted(out.items())))
