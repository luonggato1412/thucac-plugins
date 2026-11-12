# Fill missing type/locale/tag from each zip's plugin.json and normalize locale codes, so app filters work.
import json,zipfile,re,sys,os
root=sys.argv[1]; p=os.path.join(root,'plugin.json'); o=json.load(open(p,encoding='utf-8'))
M={'vi':'vi_VN','vi_vn':'vi_VN','zh':'zh_CN','zh_cn':'zh_CN','zh_tw':'zh_TW','en':'en_US','en_us':'en_US','en_en':'en_US','ja':'ja_JP','ja_jp':'ja_JP','ko':'ko_KR'}
def meta(f):
    s=zipfile.ZipFile(f).read('plugin.json').decode('utf-8-sig',errors='replace')
    try: return json.loads(s).get('metadata',{})
    except Exception: return {k:m.group(1) for k in ('type','locale','tag') for m in [re.search(r'"%s"\s*:\s*"([^"]*)"'%k,s)] if m}
for e in o['data']:
    m=meta(os.path.join(root,e['path']))
    for k in ('type','locale','tag'):
        if not e.get(k) and m.get(k): e[k]=m[k]
    if e.get('locale'): e['locale']=M.get(e['locale'].replace('-','_').lower(),e['locale'])
    elif e.get('type')=='chinese_novel': e['locale']='zh_CN'
json.dump(o,open(p,'w',encoding='utf-8'),ensure_ascii=False,indent=1)
