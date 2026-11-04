"""Student intake adapter. Trusted backend exchanges identity for a short-lived code."""
import os,json,sqlite3,secrets,time,hashlib,hmac,urllib.request,urllib.error,threading
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
ROOT=Path(__file__).parent
DB=ROOT/'data.sqlite3'
API='http://127.0.0.1:48080/admin-api'
LOCK=threading.Lock()
def db():
 c=sqlite3.connect(DB);c.row_factory=sqlite3.Row;return c
def remote(path,data=None,token=None,method=None):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(API+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method or ('POST' if data is not None else 'GET'))
 with urllib.request.urlopen(req,timeout=30) as r:x=json.load(r)
 if x['code']!=0:raise ValueError(x.get('msg','后端服务异常'))
 return x['data']
def service_token():return remote('/system/auth/login',{'username':os.environ['INTAKE_USER'],'password':os.environ['INTAKE_PASSWORD']})['accessToken']
def digest(v):return hashlib.sha256(v.encode()).hexdigest()
class Handler(BaseHTTPRequestHandler):
 def reply(self,status,data):
  raw=json.dumps(data,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
 def admin(self):
  token=self.headers.get('Authorization','').removeprefix('Bearer ')
  info=remote('/system/auth/get-permission-info',token=token)
  if not info or 'super_admin' not in info.get('roles',[]):raise PermissionError('仅管理员可查看申请信息')
  return token
 def do_GET(self):
  try:
   path=self.path.split('?')[0]
   if path=='/api/applications':
    self.admin()
    with db() as c:rows=[dict(r) for r in c.execute('select id,source,external_id,name,phone,school,creative,process_id,state,created from applications order by created desc limit 200')]
    return self.reply(200,rows)
   if path.startswith('/api/application/'):
    token=self.admin();rid=path.rsplit('/',1)[1]
    with db() as c:r=c.execute('select * from applications where id=?',(rid,)).fetchone()
    if not r:return self.reply(404,{'error':'申请不存在'})
    result=dict(r);result.pop('session_hash',None)
    if r['process_id']:
     result['process']=remote('/bpm/process-instance/get?id='+r['process_id'],token=token)
     result['tasks']=remote('/bpm/task/list-by-process-instance-id?processInstanceId='+r['process_id'],token=token)
    return self.reply(200,result)
   file='admin.html' if path=='/admin' else 'index.html' if path=='/' else None
   if not file:return self.reply(404,{'error':'不存在'})
   raw=(ROOT/file).read_bytes();self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
  except PermissionError as e:self.reply(403,{'error':str(e)})
  except Exception:self.reply(502,{'error':'服务暂不可用，请稍后重试'})
 def do_POST(self):
  try:
   size=int(self.headers.get('Content-Length','0'))
   if size>65536:return self.reply(413,{'error':'内容过长'})
   body=json.loads(self.rfile.read(size));now=int(time.time())
   if self.path=='/admin-api/system/auth/login':
    return self.reply(200,{'code':0,'data':remote('/system/auth/login',body)})
   if self.path=='/api/identity/exchange':
    supplied=self.headers.get('Authorization','')
    if not hmac.compare_digest(supplied,'Bearer '+os.environ['INTAKE_CLIENT_SECRET']):raise PermissionError('外部系统凭证无效')
    source=str(body.get('source','')).strip();eid=str(body.get('externalUserId','')).strip()
    if not source or not eid or len(source)>80 or len(eid)>160:raise ValueError('缺少来源系统或用户唯一编号')
    code=secrets.token_urlsafe(32)
    with db() as c:c.execute('insert into identities values(?,?,?,?,?,?)',(digest(code),source,eid,str(body.get('name',''))[:80],now+300,0))
    return self.reply(200,{'code':code,'expiresIn':300,'applicationUrl':os.environ.get('INTAKE_PUBLIC_URL','http://localhost:5670')+'/#code='+code})
   if self.path=='/api/session':
    with LOCK,db() as c:
     row=c.execute('select * from identities where code=?',(digest(str(body.get('code',''))),)).fetchone()
     if not row or row['used'] or row['expires']<now:raise PermissionError('授权链接已失效，请从原系统重新进入')
     sid=secrets.token_urlsafe(32);c.execute('update identities set used=1 where code=?',(row['code'],));c.execute('insert into sessions values(?,?,?,?,?)',(digest(sid),row['source'],row['external_id'],row['name'],now+3600))
    return self.reply(200,{'session':sid,'name':row['name']})
   if self.path=='/api/apply':
    sid=digest(self.headers.get('Authorization','').removeprefix('Bearer '))
    data={k:str(body.get(k,'')).strip() for k in ['name','phone','school','creative']}
    if not all(data.values()) or len(data['name'])>80 or len(data['school'])>200 or not (7<=len(data['phone'])<=25) or len(data['creative'])>12000:raise ValueError('请完整填写姓名、有效电话、学校和创意（最多12000字）')
    with LOCK,db() as c:
     user=c.execute('select * from sessions where hash=? and expires>?',(sid,now)).fetchone()
     if not user:raise PermissionError('身份已过期，请重新进入')
     old=c.execute('select id,state from applications where session_hash=?',(sid,)).fetchone()
     if old:return self.reply(200,dict(old))
     aid=secrets.token_hex(12)
     c.execute('insert into applications values(?,?,?,?,?,?,?,?,?,?,?,?)',(aid,sid,user['source'],user['external_id'],data['name'],data['phone'],data['school'],data['creative'],None,'pending',now,None))
    try:
     token=service_token();defs=remote('/bpm/process-definition/simple-list',token=token)
     candidates=[d for d in defs if d.get('key')=='screenwriting_production']
     definition=max(candidates,key=lambda x:x.get('version',0))
     variables={'projectName':data['school']+' · '+data['name']+'的创意申请','creative':data['creative'],'studentName':data['name'],'studentPhone':data['phone'],'studentSchool':data['school'],'applicationId':aid,'externalSource':user['source'],'externalUserId':user['external_id']}
     pid=remote('/bpm/process-instance/create',{'processDefinitionId':definition['id'],'variables':variables},token=token)
     with db() as c:c.execute('update applications set process_id=?,state=? where id=?',(pid,'submitted',aid))
    except Exception:
     with db() as c:c.execute('update applications set state=? where id=?',('needs_review',aid))
     return self.reply(202,{'id':aid,'state':'needs_review','message':'申请已保存，流程启动需管理员确认，请勿重复提交'})
    return self.reply(201,{'id':aid,'state':'submitted'})
   self.reply(404,{'error':'不存在'})
  except PermissionError as e:self.reply(403,{'error':str(e)})
  except (ValueError,KeyError) as e:self.reply(400,{'error':str(e)})
  except Exception:self.reply(502,{'error':'服务暂不可用，请稍后重试'})
if __name__=='__main__':
 for key in ['INTAKE_USER','INTAKE_PASSWORD','INTAKE_CLIENT_SECRET']:
  if not os.environ.get(key):raise RuntimeError('Missing '+key)
 with db() as c:c.executescript('CREATE TABLE IF NOT EXISTS identities(code TEXT PRIMARY KEY,source TEXT,external_id TEXT,name TEXT,expires INTEGER,used INTEGER); CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,source TEXT,external_id TEXT,name TEXT,expires INTEGER); CREATE TABLE IF NOT EXISTS applications(id TEXT PRIMARY KEY,session_hash TEXT UNIQUE,source TEXT,external_id TEXT,name TEXT,phone TEXT,school TEXT,creative TEXT,process_id TEXT,state TEXT,created INTEGER,error TEXT);')
 os.chmod(DB,0o600)
 ThreadingHTTPServer(('127.0.0.1',5670),Handler).serve_forever()
