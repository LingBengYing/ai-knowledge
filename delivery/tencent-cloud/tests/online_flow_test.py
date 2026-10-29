import os, pathlib, tempfile, shutil, subprocess, json, pty
source=pathlib.Path('delivery/tencent-cloud/oneclick-online')
fake='''#!/usr/bin/env python3
import os,sys,json,pathlib
p=pathlib.Path(os.environ['FAKE_STATE']);s=json.loads(p.read_text());a=sys.argv[1:];s['calls'].append(a)
if a[:2]==['image','inspect']:
 ok=s.get('cached') or (s.get('downloaded') and ('@' in a[-1] or s.get('tagged')))
 if ok:print(('sha256:bad' if s.get('bad_identity') else 'sha256:manifest')+' linux/amd64')
 p.write_text(json.dumps(s));sys.exit(0 if ok else 1)
if a[0]=='pull':
 if s.get('network_error'):print('connection refused',file=sys.stderr);rc=1
 elif s.get('needs_login') and not s.get('logged'):print('unauthorized: authentication required',file=sys.stderr);rc=1
 elif s.get('always_denied'):print('denied: unauthorized',file=sys.stderr);rc=1
 else:s['downloaded']=True;rc=0
elif a[0]=='login':s['logged']=True;rc=0
elif a[0]=='tag':s['tagged']=True;rc=0
else:rc=99
p.write_text(json.dumps(s));sys.exit(rc)
'''
cases=[('cached',{'cached':True},False,0,0,0),('pull',{},False,0,1,0),('auth',{'needs_login':True},True,0,2,1),('noninteractive',{'needs_login':True},False,1,1,0),('network',{'network_error':True},True,1,1,0),('denied',{'always_denied':True},True,1,2,1),('identity',{'bad_identity':True},False,1,1,0)]
results=[]
for name,flags,tty,code,pulls,logins in cases:
 with tempfile.TemporaryDirectory(prefix='kb-online-test-') as folder:
  root=pathlib.Path(folder);(root/'scripts').mkdir();(root/'bin').mkdir()
  shutil.copy2(source/'scripts/ensure-images.sh',root/'scripts/ensure-images.sh')
  (root/'images.lock').write_text('ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-api:test sha256:manifest sha256:config\n')
  docker=root/'bin/docker';docker.write_text(fake);docker.chmod(0o755)
  state=root/'state.json';state.write_text(json.dumps({'calls':[],**flags}))
  env={**os.environ,'PATH':str(root/'bin')+':'+os.environ['PATH'],'FAKE_STATE':str(state)}
  master,slave=pty.openpty()
  try:r=subprocess.run(['sh',str(root/'scripts/ensure-images.sh')],env=env,stdin=slave if tty else subprocess.DEVNULL,capture_output=True,timeout=10)
  finally:os.close(master);os.close(slave)
  calls=json.loads(state.read_text())['calls']
  assert r.returncode==code,(name,r.returncode,r.stderr)
  assert sum(c[0]=='pull' for c in calls)==pulls,name
  assert sum(c[0]=='login' for c in calls)==logins,name
  if code:assert not any(c[0]=='tag' for c in calls),name
  for c in calls:
   if c[0]=='pull':assert c[-1].endswith('@sha256:manifest') and c[1:3]==['--platform','linux/amd64']
  results.append({'case':name,'passed':True})
pathlib.Path('delivery/tencent-cloud/evidence/online-flow-tests.json').write_text(json.dumps({'kind':'isolated fake Docker control-flow tests, not remote pull acceptance','results':results},indent=2)+'\n')
print('7/7 download control-flow checks passed; no real login or network calls')
