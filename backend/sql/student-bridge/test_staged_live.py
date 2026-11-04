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
    pid=signed('/bpm/student-bridge/submit',dict(identity,name='【测试】分阶段学生',phone='13800000000',school='【测试】阶段联调',creative='【测试】同一申请的一卡初稿与完善剧本'))['processId']
    def progress():return signed('/bpm/student-bridge/progress',identity)
    def approve(node,person=143):
        tasks=api('/bpm/task/list-by-process-instance-id?processInstanceId='+pid,token=admin)
        candidates=[t for t in tasks if t['taskDefinitionKey']==node and t.get('status')==1 and (t.get('assigneeUser') or {}).get('id')==person]
        assert len(candidates)==1,(node,person,[(t['taskDefinitionKey'],t.get('status')) for t in tasks])
        api('/bpm/task/approve','PUT',{'id':candidates[0]['id'],'reason':'【测试】分阶段流程验收 '+node,'variables':{}},tokens[person])
    try:
        p=progress();assert p['stage']=='waiting_review' and p['current_task'] is None and [v['version'] for v in p['versions']]==[1]
        signed('/bpm/student-bridge/stage-submit',dict(identity,requestKey='early-stage-test',taskId='not-a-task',stage='draft',content='禁止跳过创意审核'),409)
        approve('CreativeReview')
        p=progress();assert p['stage']=='draft' and p['current_task']['content']==''
        draft=dict(identity,requestKey='draft-'+external,taskId=p['current_task']['task_id'],stage='draft',content='一卡初稿：初始人物与故事。')
        students=api('/bpm/student-bridge/students',token=admin)
        student=next(x for x in students if x['external_user_id']==external)
        api('/bpm/student-bridge/student-status?id='+str(student['id'])+'&status=1','PUT',token=admin)
        signed('/bpm/student-bridge/stage-submit',dict(draft,requestKey='disabled-'+external),403)
        assert progress()['current_task'] is None
        api('/bpm/student-bridge/student-status?id='+str(student['id'])+'&status=0','PUT',token=admin)
        signed('/bpm/student-bridge/stage-submit',dict(draft,stage='revision',requestKey='wrong-stage-'+external),409)
        signed('/bpm/student-bridge/stage-submit',dict(draft,externalUserId=str(int(external)+1)),404)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            versions=list(pool.map(lambda _:signed('/bpm/student-bridge/stage-submit',draft),range(8)))
        assert all(v==versions[0] for v in versions) and versions[0]['version']==2
        signed('/bpm/student-bridge/stage-submit',dict(draft,content='相同提交编号篡改内容'),409)
        p=progress();assert p['stage']=='waiting_review' and [v['version'] for v in p['versions']]==[1,2]
        approve('OutlineReview')
        p=progress();assert p['stage']=='revision' and p['current_task']['content']==draft['content']
        revision=dict(identity,requestKey='revision-'+external,taskId=p['current_task']['task_id'],stage='revision',content='完善剧本：保留原故事，补充对白与场次。')
        assert signed('/bpm/student-bridge/stage-submit',revision)['version']==3
        p=progress();assert [v['content'] for v in p['versions']][1:]==[draft['content'],revision['content']]
        approve('ScriptReview')
        p=progress();assert len(p['reviews'])==3 and [r['version'] for r in p['reviews']]==[1,2,3]
        for node,person in [('DirectorReview',142),('Storyboard',145),('Production',145),('Editing',145),('Release',145)]:approve(node,person)
        p=progress();assert p['status']==2 and p['stage']=='finished' and p['current_task'] is None
        assert [v['version'] for v in p['versions']]==[1,2,3]
        assert signed('/bpm/student-bridge/stage-submit',draft)['version']==2
        signed('/bpm/student-bridge/stage-submit',dict(revision,requestKey='after-finish-'+external),409)
        result={'passed':True,'process_id':pid,'external_user_id':external,'application_id':application,'versions':3,'review_versions':[r['version'] for r in p['reviews']],'concurrent_draft_replays':8,'same_script_prefilled':True,'student_handoffs':True,'full_process_completed':True}
        identity=dict(identity,applicationId=str(int(application)+1))
        pid=signed('/bpm/student-bridge/submit',dict(identity,name='【测试】分阶段学生',phone='13800000000',school='【测试】驳回联调',creative='【测试】驳回后保留初稿版本'))['processId']
        approve('CreativeReview')
        p=progress();rejected=dict(identity,requestKey='rejected-'+external,taskId=p['current_task']['task_id'],stage='draft',content='应被保留的驳回初稿')
        signed('/bpm/student-bridge/stage-submit',rejected)
        tasks=api('/bpm/task/list-by-process-instance-id?processInstanceId='+pid,token=admin)
        reviewer=next(t for t in tasks if t['taskDefinitionKey']=='OutlineReview' and t.get('status')==1 and (t.get('assigneeUser') or {}).get('id')==143)
        api('/bpm/task/reject','PUT',{'id':reviewer['id'],'reason':'【测试】初稿不通过，验证版本留存'},tokens[143])
        p=progress();assert p['status']==3 and p['current_task'] is None and len(p['versions'])==2
        assert p['versions'][1]['content']==rejected['content'] and p['reviews'][-1]['status']==3 and p['reviews'][-1]['version']==2
        signed('/bpm/student-bridge/stage-submit',dict(rejected,requestKey='after-reject-'+external),409)
        result.update(rejection_preserves_versions=True,rejection_process_id=pid,disabled_student_blocked=True)
        print(json.dumps(result,ensure_ascii=False));return result
    finally:
        # Never cancel a pre-existing/other person's application.
        try:
            if progress()['status']==1:api('/bpm/process-instance/cancel-by-admin','DELETE',{'id':pid,'reason':'【测试】阶段联调清理'},admin)
        except Exception:pass

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base-url',required=True);parser.add_argument('--secret-file',required=True);parser.add_argument('--accounts-file',required=True);parser.add_argument('--result-file');a=parser.parse_args()
    u=urllib.parse.urlparse(a.base_url)
    assert u.hostname in ['localhost','127.0.0.1','flow-dev.zhongdahengrui.com'],'Live test restricted to localhost/PRO test environment'
    env=dict(line.split('=',1) for line in Path(a.secret_file).read_text().splitlines() if '=' in line)
    result=run(a.base_url.rstrip('/'),env['ZZH_WORKFLOW_BRIDGE_SECRET'],json.loads(Path(a.accounts_file).read_text()))
    if a.result_file:
        path=Path(a.result_file);path.write_text(json.dumps(result,ensure_ascii=False,indent=2));path.chmod(0o600)
