import https from 'node:https';
import fs from 'node:fs';
const origin=new URL(process.env.RAG_PUBLIC_ORIGIN);
for(const path of ['/','/v1/session','/v1/config','/v1/knowledge-agent/config']) {
  await new Promise((resolve,reject)=>{
    const req=https.get({host:'127.0.0.1',port:8443,servername:origin.hostname,ca:fs.readFileSync('/run/config/tls.crt'),path,headers:{host:origin.host,origin:origin.origin,'sec-fetch-site':'same-origin'}},r=>{
      let text='';r.on('data',b=>text+=b);r.on('end',()=>{if(r.statusCode!==200)return reject(new Error(`${path}: ${r.statusCode}`));if(path==='/v1/session'&&JSON.parse(text).status!=='authenticated')return reject(new Error('session not authenticated'));resolve();});
    });req.setTimeout(10000,()=>req.destroy(new Error('entry check timeout')));req.on('error',reject);
  });
}
console.log('页面、会话、后端及 Agent 配置检查通过。');
