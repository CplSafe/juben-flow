#!/usr/bin/env python3
"""Explicit live acceptance test on localhost or PRO/test only. Creates labeled synthetic applications.
Supply WORKFLOW_ADMIN_USER/PASSWORD, --secret-file (private env), and --accounts-file (private staff fixtures).
"""
import argparse
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

def run(base, secret, accounts):
    def request(path,method='GET',data=None,token=None,headers=None):
        h={'Content-Type':'application/json'};h.update(headers or {})
        if token:h['Authorization']='Bearer '+token
        raw=None if data is None else json.dumps(data,ensure_ascii=False,separators=(',',':')).encode()
        req=urllib.request.Request(base+path,data=raw,method=method,headers=h)
        try:
            with urllib.request.urlopen(req,timeout=30) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:return e.code,json.load(e)
    def api(path,method='GET',data=None,token=None):
        status,obj=request(path,method,data,token)
        assert status==200 and obj['code']==0,(path,status,obj.get('msg'))
        return obj['data']
    def signed(path,data,expected=200):
        raw=json.dumps(data,ensure_ascii=False,separators=(',',':')).encode();ts=str(int(time.time()));nonce=secrets.token_hex(16)
        msg='\n'.join([ts,nonce,path,hashlib.sha256(raw).hexdigest()]).encode()
        status,obj=request(path,'POST',data,headers={'X-Bridge-Timestamp':ts,'X-Bridge-Nonce':nonce,'X-Bridge-Signature':hmac.new(secret.encode(),msg,hashlib.sha256).hexdigest()})
        assert status==expected,(path,status,obj.get('msg'))
        if expected==200:assert obj['code']==0
        return obj.get('data')
    def login(username,password):return api('/system/auth/login','POST',{'username':username,'password':password})['accessToken']
    admin=login(os.environ['WORKFLOW_ADMIN_USER'],os.environ['WORKFLOW_ADMIN_PASSWORD'])
    tokens={}
    for account in accounts:
        if account['username'] in ['sam01','fangdao','yangzong']:tokens[int(account['id'])]=login(account['username'],account['password'])
    external=str(int(time.time()*1000000));application=external
    identity={'externalUserId':external,'applicationId':application}
    pid=signed('/bpm/student-bridge/submit',dict(identity,name='[TEST]FourStage',phone='13800000000',school='[TEST]Integration',creative='[TEST]Original creative'))['processId']
    def progress():return signed('/bpm/student-bridge/progress',identity)
    def act(node,action='approve',person=143):
        tasks=api('/bpm/task/list-by-process-instance-id?processInstanceId='+pid,token=admin)
        candidates=[t for t in tasks if t['taskDefinitionKey']==node and t.get('status')==1 and (t.get('assigneeUser') or {}).get('id')==person]
        assert len(candidates)==1,(node,action,[(t['taskDefinitionKey'],t.get('status')) for t in tasks])
        api('/bpm/task/'+action,'PUT',{'id':candidates[0]['id'],'reason':'[TEST]Four-stage '+action+' '+node,'variables':{}},tokens[person])
    def summary():
        v=signed('/bpm/student-bridge/summaries',{'externalUserId':external,'applicationIds':[application]})[application]
        p=progress()
        assert all(v[k]==p[k] for k in ['current_stage','review_status','flow_version','stage_statuses'])
        return v
    try:
        p=progress();assert p['current_stage']=='creative' and p['review_status']=='under_review' and p['flow_version']==4
        signed('/bpm/student-bridge/summaries',{'externalUserId':str(int(external)+1),'applicationIds':[application]},404)
        signed('/bpm/student-bridge/stage-submit',dict(identity,requestKey='early-stage-test',taskId='not-a-task',stage='card',content='no skip'),409)
        # Creative rejection returns to starter, then all other content phases return to their own student node.
        act('CreativeReview','reject');p=progress();assert p['review_status']=='needs_changes' and p['current_task']['stage']=='creative'
        number=1;receipts=[]
        def submit(stage,content):
            nonlocal number
            p=progress();task=p['current_task'];assert task and task['stage']==stage,(stage,p)
            request=dict(identity,requestKey=stage+'-'+str(number)+'-'+external,taskId=task['task_id'],stage=stage,content=content)
            if stage=='card' and number==2:
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:vs=list(pool.map(lambda _:signed('/bpm/student-bridge/stage-submit',request),range(8)))
                assert all(v==vs[0] for v in vs)
                v=vs[0]
            else:v=signed('/bpm/student-bridge/stage-submit',request)
            number+=1;assert v['version']==number
            signed('/bpm/student-bridge/stage-submit',dict(request,content=content+'changed'),409)
            assert signed('/bpm/student-bridge/stage-submit',request)==v
            receipts.append(request);assert summary()['review_status']=='under_review'
        submit('creative','[TEST]Revised creative');act('CreativeReview')
        for stage,node in [('card','OutlineReview'),('script','ScriptReview'),('final','FinalReview')]:
            p=progress();assert p['current_stage']==stage and p['review_status']=='awaiting_submission'
            if stage!='card':assert p['current_task']['content']==receipts[-1]['content']
            submit(stage,'[TEST]'+stage+' first draft')
            act(node,'reject');p=progress();assert p['status']==1 and p['review_status']=='needs_changes' and p['current_task']['stage']==stage
            assert p['current_task']['content']==receipts[-1]['content'];assert summary()['review_status']=='needs_changes'
            submit(stage,'[TEST]'+stage+' corrected');act(node)
        p=progress();assert p['current_stage']=='production' and p['review_status']=='approved' and p['status']==1
        assert all(v=='approved' for v in p['stage_statuses'].values())
        for node,person in [('DirectorReview',142),('Storyboard',145),('Production',145),('Editing',145),('Release',145)]:act(node,person=person)
        p=progress();assert p['status']==2 and p['current_task'] is None
        assert len(p['versions'])==8 and len(p['reviews'])==8
        assert [r['version'] for r in p['reviews']]==list(range(1,9)),p['reviews']
        assert signed('/bpm/student-bridge/stage-submit',receipts[0])['version']==2
        result={'passed':True,'process_id':pid,'external_user_id':external,'application_id':application,'versions':8,'review_versions':[r['version'] for r in p['reviews']],'four_return_resubmissions':True,'batch_summaries':True,'concurrent_card_replays':8,'same_script_prefilled':True,'content_approved_before_production_end':True,'full_process_completed':True}
        print(json.dumps(result));return result
    finally:
        try:
            if progress()['status']==1:api('/bpm/process-instance/cancel-by-admin','DELETE',{'id':pid,'reason':'[TEST]Four-stage cleanup'},admin)
        except Exception:pass

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base-url',required=True);parser.add_argument('--secret-file',required=True);parser.add_argument('--accounts-file',required=True);parser.add_argument('--result-file');a=parser.parse_args()
    u=urllib.parse.urlparse(a.base_url)
    assert u.hostname in ['localhost','127.0.0.1','flow-dev.zhongdahengrui.com'],'Live test restricted to localhost/PRO test environment'
    env=dict(line.split('=',1) for line in Path(a.secret_file).read_text().splitlines() if '=' in line)
    result=run(a.base_url.rstrip('/'),env['ZZH_WORKFLOW_BRIDGE_SECRET'],json.loads(Path(a.accounts_file).read_text()))
    if a.result_file:
        path=Path(a.result_file);path.write_text(json.dumps(result,ensure_ascii=False,indent=2));path.chmod(0o600)
