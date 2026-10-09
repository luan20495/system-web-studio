#!/usr/bin/env python3
"""Re-evaluates cases.json of a UI/UX run with the documented false-positive filters and prints the numbers used in the report.
Filters (each justified in the report): (1) 'icon svg with zero size' = the icon of a display:none button (mobile-menu toggle on desktop, settings button on phones; verified in the browser); (2) 'dialog footer outside the viewport' when the dialog scrolls internally (checked on the screenshot: a side drawer with its own scroll); (3) 'primary navigation unreachable' for an anonymous visit (the page is the login screen); (4) every failure of a BLOCKED case (App Creator refused by the Studio gate = H-C1-04)."""
import json,sys,collections,re
d=sys.argv[1]; c=json.load(open(d+'/cases.json'))
def keep(r,m):
    if 'icon svg with zero size' in m: return False
    if 'dialog footer/primary button is outside the viewport' in m and (r.get('dialog') or {}).get('scrollable'): return False
    if 'primary navigation unreachable' in m and r['role']=='anon': return False
    return True
for r in c:
    r['fail2']=[m for m in r.get('fail',[]) if keep(r,m)] if r['result']!='BLOCKED' else []
    if r['result']!='BLOCKED': r['res2']='FAIL' if r['fail2'] else 'PASS'
    else: r['res2']='BLOCKED'
tot=collections.Counter(r['res2'] for r in c)
routes={(r['portal'],r['role'],r['route']) for r in c if r['role']!='appCreator' and r.get('kind') in (None,'dialog')}
print('cases',len(c),dict(tot),'routes',len(routes),'knownNotReady',len({(r['portal'],r['role'],r['route']) for r in c if r.get('notReady')}))
print('\nby viewport:'); 
for v in (1440,1280,1024,768,430,390,360):
    x=[r for r in c if r['viewport']==v and r['res2']!='BLOCKED']; f=[r for r in x if r['res2']=='FAIL']; print(v,len(x),'fail',len(f))
print('\nby group:')
for g in sorted({r['group'] for r in c}):
    x=[r for r in c if r['group']==g and r['res2']!='BLOCKED']; f=[r for r in x if r['res2']=='FAIL']; print(g,len(x),'fail',len(f))
ax=collections.defaultdict(lambda:{'n':0,'routes':set(),'vps':set(),'impact':''})
for r in c:
    if r['res2']=='BLOCKED': continue
    for a in r.get('axe',[]):
        if a['impact'] in('critical','serious'):
            k=a['id']; ax[k]['n']+=a['count']; ax[k]['impact']=a['impact']; ax[k]['routes'].add(re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27}','{id}',r['route'])+(' ['+r['id'].split('·')[-1].strip()+']' if r.get('kind') or '·' in r['id'] else '')); ax[k]['vps'].add(r['viewport'])
print('\naxe critical/serious nodes (excluding blocked):', sum(v['n'] for v in ax.values()))
for k,v in ax.items(): print(k,v['impact'],v['n'],sorted(v['vps']),sorted(v['routes'])[:6])
cat=collections.Counter(); ex={}
for r in c:
    for m in r['fail2']:
        k=re.sub(r'[0-9]+','N',m)[:60]; cat[k]+=1; ex.setdefault(k,(r['portal'],re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27}','{id}',r['id'])[:60],r['viewport'],m[:170],r.get('screenshot')))
print('\nfailure groups:')
for k,v in cat.most_common(): print(v,'|',ex[k][0],ex[k][1],ex[k][2],'|',ex[k][3])
json.dump({'cases':len(c),'tot':tot,'routes':len(routes)},open(d+'/analysis.json','w'))
