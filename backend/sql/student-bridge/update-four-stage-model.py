#!/usr/bin/env python3
"""Publish student handoffs through official SIMPLE APIs, retaining old instance versions.
Credentials: WORKFLOW_ADMIN_USER / WORKFLOW_ADMIN_PASSWORD, never front-end or runtime service credentials.
Defaults to a dry-run; backups are written outside Git before applying.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request

PROTECTED = {'projectName','creative','outline','script','scriptDraft','studentName','studentPhone','studentSchool','externalSource','externalUserId','applicationId'}

def nodes(root):
    out = []
    while root:
        out.append(root)
        root = root.get('childNode')
    return out

def transform(original):
    root=copy.deepcopy(original); chain=nodes(root); by={n['id']:n for n in chain}
    expected=['CreativeReview','StudentDraft','OutlineReview','StudentRevision','ScriptReview']
    if 'FinalReview' in by:
        ids=[n['id'] for n in chain]
        assert ids[ids.index('CreativeReview'):ids.index('FinalReview')+1]==['CreativeReview','StudentDraft','OutlineReview','StudentScript','ScriptReview','StudentRevision','FinalReview'], 'Unexpected four-stage chain'
        for review,target in [('CreativeReview','StartUserNode'),('OutlineReview','StudentDraft'),('ScriptReview','StudentScript'),('FinalReview','StudentRevision')]:
            assert by[review].get('rejectHandler')=={'type':2,'returnNodeId':target}, 'Unexpected return policy'
        return root,False
    ids=[n['id'] for n in chain]
    assert ids[ids.index('CreativeReview'):ids.index('ScriptReview')+1]==expected, 'Expected existing three-stage model'
    assert 'StartUserNode' in by and 'StudentScript' not in by
    creative,card,script=(by[k] for k in ['CreativeReview','OutlineReview','ScriptReview'])
    final=copy.deepcopy({k:v for k,v in script.items() if k!='childNode'})
    final.update(id='FinalReview',name='完善剧本审核：Sam / 宇哥',childNode=script['childNode'])
    student_script=copy.deepcopy({k:v for k,v in by['StudentRevision'].items() if k!='childNode'})
    student_script.update(id='StudentScript',name='学生填写剧本',childNode=script)
    by['StudentRevision']['childNode']=final
    script['childNode']=by['StudentRevision']
    card['childNode']=student_script
    by['StudentDraft']['name']='学生填写一卡'
    card['name']='一卡审核：Sam / 宇哥';script['name']='剧本审核：Sam / 宇哥'
    # A negative content review returns to its own student editing task, not process termination.
    for review,target in [(creative,'StartUserNode'),(card,'StudentDraft'),(script,'StudentScript'),(final,'StudentRevision')]:
        review['rejectHandler']={'type':2,'returnNodeId':target}
    # Returning to the initiator must allow editing only the creative body.
    for perm in by['StartUserNode'].get('fieldsPermission',[]):
        if perm['field'] in PROTECTED:perm['permission']='write' if perm['field']=='creative' else 'read'
    return root,True

def main():
    p=argparse.ArgumentParser();p.add_argument('--base-url',required=True);p.add_argument('--model-id',required=True);p.add_argument('--backup-dir',required=True);p.add_argument('--apply',action='store_true');args=p.parse_args()
    base=args.base_url.rstrip('/');u=urllib.parse.urlparse(base)
    assert u.scheme=='https' or (u.scheme=='http' and u.hostname in ['127.0.0.1','localhost']), 'HTTPS or loopback required'
    token=None
    def api(path, method='GET', data=None):
        h={'Content-Type':'application/json'}
        if token:h['Authorization']='Bearer '+token
        req=urllib.request.Request(base+path,data=None if data is None else json.dumps(data,ensure_ascii=False).encode(),headers=h,method=method)
        with urllib.request.urlopen(req,timeout=30) as r:res=json.load(r)
        assert res['code']==0, (path,res.get('code'),res.get('msg'))
        return res['data']
    token=api('/system/auth/login','POST',{'username':os.environ['WORKFLOW_ADMIN_USER'],'password':os.environ['WORKFLOW_ADMIN_PASSWORD']})['accessToken']
    model=api('/bpm/model/get?id='+urllib.parse.quote(args.model_id));original=api('/bpm/model/simple/get?id='+urllib.parse.quote(args.model_id))
    updated,changed=transform(original)
    print(json.dumps({'changed':changed,'nodes':[{k:n.get(k) for k in ['id','name','type','candidateStrategy']} for n in nodes(updated)]},ensure_ascii=False))
    if not args.apply or not changed:return
    directory=Path(args.backup_dir);directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    for name,obj in [('model-before.json',model),('simple-before.json',original)]:
        path=directory/name
        if path.exists():raise RuntimeError('Backup exists; use a fresh release backup directory')
        path.write_text(json.dumps(obj,ensure_ascii=False,indent=2));path.chmod(0o600)
    form=api('/bpm/form/get?id='+str(model['formId']))
    path=directory/'form-before.json';path.write_text(json.dumps(form,ensure_ascii=False,indent=2));path.chmod(0o600)
    fields=[json.loads(v) for v in form['fields']]
    if not any(f.get('field')=='scriptDraft' for f in fields):
        draft=copy.deepcopy(next(f for f in fields if f.get('field')=='script'));draft['field']='scriptDraft';draft['title']='一卡内容';draft.setdefault('props',{})['maxlength']=20000
        fields.insert(next(i for i,f in enumerate(fields) if f.get('field')=='script'),draft)
    for f in fields:
        if f.get('field') in ['script','scriptDraft']:f.setdefault('props',{})['maxlength']=20000
    payload={k:form.get(k) for k in ['id','name','conf','status','remark']};payload['fields']=[json.dumps(f,ensure_ascii=False) for f in fields]
    api('/bpm/form/update','PUT',payload)
    api('/bpm/model/simple/update','POST',{'id':args.model_id,'simpleModel':updated})
    api('/bpm/model/deploy?id='+urllib.parse.quote(args.model_id),'POST')
    print('Published new SIMPLE definition; existing instances retain their original definition.')

if __name__=='__main__':main()
