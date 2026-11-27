import pymorphy3,sys
m=pymorphy3.MorphAnalyzer()
lem={}
for line in open('hard_lemmas.txt',encoding='utf-8'):
    a,b=line.split(); assert len(a)==len(b) and a==b.replace('Э','е').replace('Е','е'), (a,b); lem[a]=[i for i,c in enumerate(b) if c=='Э']
out={}; dropped=[]
BAD={'тесто','тесть'}
KEEP={'отели','отелю','отеле'}
for a,pos in lem.items():
    ps=[p for p in m.parse(a) if p.normal_form==a] or m.parse(a)[:1]
    forms={a}
    for p in ps:
        for f in p.lexeme: forms.add(f.word.replace('ё','е'))
    for f in forms:
        if any(i>=len(f) or f[i]!='е' for i in pos):
            if any(i<len(f) and f[i]!='е' for i in pos): dropped.append(f); continue
            pos2=[i for i in pos if i<len(f) and f[i]=='е']
        else: pos2=pos
        # a form of another word too («теста» — тесто)
        others={q.normal_form.replace('ё','е') for q in m.parse(f) if q.is_known} - set(lem)
        others={o for o in others if o in BAD or not o.startswith(a[:max(pos)+2])}
        if others and f!=a and f not in KEEP: dropped.append(f+'('+','.join(others)+')'); continue
        h=list(f)
        for i in pos2: h[i]='Э'
        out[f]=''.join(h)
print(len(out),'dropped',dropped[:80])
open('hard_e.txt','w',encoding='utf-8').write('\n'.join(out[k] for k in sorted(out))+'\n')
