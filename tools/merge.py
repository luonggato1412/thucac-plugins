import json,os,re,shutil,zipfile,io,sys,unicodedata
from urllib.parse import urlparse
DL='/tmp/claude-0/dl'; OUT='/tmp/claude-0/out/thucac-repo'
shutil.rmtree(OUT,ignore_errors=True); os.makedirs(OUT+'/plugins'); os.makedirs(OUT+'/icons')
order=[l.split()[0] for l in open('/tmp/claude-0/repos.txt')]
def host(u):
    h=(urlparse(u if '//' in u else 'http://'+u).hostname or u).lower()
    return re.sub(r'^(www|m|wap|h5)\.','',h)
def slug(s):
    s=unicodedata.normalize('NFKD',s).encode('ascii','ignore').decode().lower()
    return re.sub(r'[^a-z0-9]+','-',s).strip('-') or 'x'
def meta_of(b):
    z=zipfile.ZipFile(io.BytesIO(b)); return json.loads(z.read('plugin.json').decode('utf-8-sig')).get('metadata',{})
own=[]
for p in sys.argv[1:]:
    b=open(p,'rb').read(); m=meta_of(b); icon=os.path.join(os.path.dirname(p),'icon.png')
    own.append(dict(m=m,b=b,icon=icon if os.path.exists(icon) else None))
items=json.load(open(DL+'/items.json'))
best={}; bad=[]
DEAD={(x['name'].strip().lower(),host(x.get('source',''))) for x in json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'dead-removed.json'),encoding='utf-8'))}
for e in sorted(items,key=lambda e:order.index(e['_repo'])):
    f=f"{DL}/zips/{e['_id']}.zip"
    if not os.path.exists(f): bad.append((e['_repo'],e['name'],'không tải được')); continue
    b=open(f,'rb').read()
    try: zipfile.ZipFile(io.BytesIO(b)).getinfo('plugin.json')
    except Exception as ex: bad.append((e['_repo'],e['name'],'zip hỏng/không có plugin.json')); continue
    if re.search(r'"encrypt"\s*:\s*"?true',zipfile.ZipFile(io.BytesIO(b)).read('plugin.json').decode('utf-8-sig','ignore')): bad.append((e['_repo'],e['name'],'mã hoá, Thư Các không chạy được')); continue  # Lương 2026-11-12: bỏ plugin mã hoá
    k=(e['name'].strip().lower(),host(e.get('source','')))
    if k in DEAD: bad.append((e['_repo'],e['name'],'trang đã chết')); continue  # Lương 2026-11-13: bỏ plugin có domain chết
    v=int(e.get('version') or 1)
    if k not in best or v>best[k][0]: best[k]=(v,e,b)
ownhosts={host(o['m'].get('source','')) for o in own}
data=[]; used=set(); replaced=[]
def put(e,b,iconfile=None):
    s=slug(e['name']+'-'+(e.get('author') or '')); i=2
    while s in used: s=f"{s}-{i}"; i+=1
    used.add(s); open(f"{OUT}/plugins/{s}.zip",'wb').write(b)
    ent={k:e[k] for k in ('name','author','version','source','regexp','description','type','locale','tag','encrypt') if k in e and e[k] not in (None,'')}
    ent['path']=f"plugins/{s}.zip"
    if iconfile: shutil.copy(iconfile,f"{OUT}/icons/{s}.png"); ent['icon']=f"icons/{s}.png"
    elif e.get('icon'): ent['icon']=e['icon']
    data.append(ent)
for o in own: put(dict(o['m'],author='luonggato'),o['b'],o['icon'])  # Lương: our plugins use author luonggato
for k,(v,e,b) in best.items():
    if k[1] in ownhosts: replaced.append(f"{e['name']} ({e.get('author')}) v{v}"); continue
    e=dict(e); e.setdefault('author',e['_repo']); put(e,b)
data.sort(key=lambda x:(x.get('author')!='Thư Các' and x.get('author') not in ('Thư Các',), x['name'].lower()))
idx={"metadata":{"author":"Thư Các","description":f"Kho plugin tổng hợp của Thư Các ({len(data)} plugin)"},"data":data}
json.dump(idx,open(OUT+'/plugin.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
print('total',len(data),'own',len(own),'community unique',len(best),'raw',len(items))
print('replaced by own:',replaced); print('bad:'); [print(' ',x) for x in bad]
json.dump(bad,open('/tmp/claude-0/out/bad.json','w'),ensure_ascii=False)
