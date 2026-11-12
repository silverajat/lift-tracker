import json,collections,sys
d=json.load(open(sys.argv[1]))
c=collections.Counter((v['severity'],v['type']) for v in d['violations'])
for k,n in c.most_common(): print(n,k)
print('unconnected',len(d['unconnected_items']))
for u in d['unconnected_items'][:12]: print('  ',[(i['description'][:50],round(i['pos']['x'],2),round(i['pos']['y'],2)) for i in u['items']])
print('parity',len(d['schematic_parity']))
for p in d['schematic_parity'][:10]: print('  ',p['description'][:90],[i['description'][:50] for i in p['items']])
for v in d['violations']:
    if v['severity']=='error': print('E',v['type'],v['description'][:55],[ (i['description'][:45],round(i['pos']['x'],2),round(i['pos']['y'],2)) for i in v['items']])
