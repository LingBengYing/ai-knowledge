// Run explicitly in the isolated installer project, never as part of normal startup.
import assert from 'node:assert/strict';
import https from 'node:https';
import fs from 'node:fs';
const origin=new URL(process.env.RAG_PUBLIC_ORIGIN);
const fixture=Buffer.from('SYNTHETIC INSTALLER ACCEPTANCE\n离线安装验证：青竹项目，编号 INSTALL-20261028。\n');
const store='/tmp/installer-acceptance.json';
const mode=process.argv[2]??'create';
function request(path,{method='GET',body,headers={}}={}) {
 return new Promise((resolve,reject)=>{
  const req=https.request({host:'127.0.0.1',port:8443,servername:origin.hostname,ca:fs.readFileSync('/run/config/tls.crt'),path,method,headers:{host:origin.host,origin:origin.origin,'sec-fetch-site':'same-origin',...(body?{'content-length':body.length}:{}),...headers}},r=>{
   const out=[];r.on('data',b=>out.push(b));r.on('end',()=>resolve({status:r.statusCode,bytes:Buffer.concat(out)}));
  });req.setTimeout(30000,()=>req.destroy(new Error('request timeout')));req.on('error',reject);req.end(body);
 });
}
async function json(path,options){const r=await request(path,options);assert(r.status>=200&&r.status<300,`${path}: ${r.status}`);return JSON.parse(r.bytes);}
const env=Object.fromEntries(fs.readFileSync('/run/config/common.env','utf8').trim().split('\n').map(l=>{const i=l.indexOf('=');return[l.slice(0,i),l.slice(i+1)];}));
async function milvus(path,body){const r=await fetch('http://127.0.0.1:19530/v2/vectordb/'+path,{method:'POST',headers:{authorization:'Bearer '+env.RAG_MILVUS_TOKEN,'content-type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(30000)});assert.equal(r.status,200);const j=await r.json();assert.equal(j.code,0,`${path}: ${j.code}`);return j;}
let record;
if(mode==='create'){
 const task=await json('/v1/documents?filename=installer-acceptance.txt',{method:'POST',body:fixture,headers:{'content-type':'application/octet-stream'}});
 record={task_id:task.task_id,document_id:task.document_id,revision_id:task.revision_id,collection:'installer_acceptance_'+Date.now()};
 fs.writeFileSync(store,JSON.stringify(record));
 for(let i=0;i<60;i++){const t=await json('/v1/ingestions/'+record.task_id);if(t.state==='parsed')break;assert(!['failed','cancelled'].includes(t.state),`parse state ${t.state}`);if(i===59)throw new Error('parse deadline');await new Promise(r=>setTimeout(r,1000));}
 await milvus('collections/create',{collectionName:record.collection,dimension:2,metricType:'COSINE',consistencyLevel:'Strong',autoId:false});
 await milvus('entities/insert',{collectionName:record.collection,data:[{id:1,vector:[1,0]}]});
}else{record=JSON.parse(fs.readFileSync(store,'utf8'));}
const content=await request(`/v1/documents/${record.document_id}/revisions/${record.revision_id}/content`);
assert.equal(content.status,200);assert.deepEqual(content.bytes,fixture);
const q=await milvus('entities/query',{collectionName:record.collection,filter:'id == 1',outputFields:['id'],consistencyLevel:'Strong',limit:10});
assert.deepEqual(q.data.map(x=>x.id),[1]);
const session=await json('/v1/session');assert.equal(session.status,'authenticated');
console.log(JSON.stringify({mode,original_sha_match:true,strong_query:true,session:true,record}));
