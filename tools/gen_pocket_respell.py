"""
Homographs for the Pocket (Shtorm) voice written "as heard" (owner, 28.11.2026: «у стены́» was read
«сте́ны» whatever the stress mark said). The model takes the stress mark as a hint; in a common word
its habit wins. In a spelling it has not seen («стины́») it follows the mark. So the unstressed vowels
before the stress are written as they sound: е/я after a consonant → и, о → а.

A respelling that is a common word («дома́» → «дама», «полки́» → «палки») would bring that word's habit
instead: such variants are left as they are (a rare word, «систры», has no habit to bring). Forms with ё are left too (ё marks the stress itself).

Out: rust/src/pocket_respell.txt — "variant respelled" per line, '+' before the stressed vowel in both.

    python3 tools/gen_pocket_respell.py   (needs wordfreq)
"""
import json, os
from wordfreq import zipf_frequency

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMMON = 2.3  # Zipf: ~5 per 10 million words and more
VOWELS = set('аеёиоуыэюя')
SOFT_SIGNS = set('ьъ')


def respell(plain, k):
    out = []
    for i, c in enumerate(plain):
        if i < k:
            prev = plain[i - 1] if i > 0 else ''
            consonant_before = prev != '' and prev not in VOWELS and prev not in SOFT_SIGNS and prev != '-'
            if c in 'ея' and consonant_before:
                c = 'и'
            elif c == 'о':
                c = 'а'
        out.append(c)
    return ''.join(out)


def main():
    d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/homosolver/homodict.json'), encoding='utf-8'))
    rows, clash = [], 0
    for w, vs in d.items():
        for v in vs:
            if 'ё' in v or v.count('+') != 1:
                continue
            k = v.index('+')
            plain = v.replace('+', '')
            r = respell(plain, k)
            if r == plain:
                continue
            # the respelling must not be a common word (its own stress would come with it)
            if r in d or zipf_frequency(r, 'ru') >= COMMON:
                clash += 1
                continue
            rows.append((v, r[:k] + '+' + r[k:]))
    rows.sort()
    with open(os.path.join(ROOT, 'rust/src/pocket_respell.txt'), 'w', encoding='utf-8') as f:
        for v, r in rows:
            f.write(f'{v} {r}\n')
    print(len(rows), 'respelled,', clash, 'left (a common word)')


if __name__ == '__main__':
    main()
