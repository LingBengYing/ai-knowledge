import https from 'node:https';
import http from 'node:http';
import fs from 'node:fs';
const origin = new URL(process.env.RAG_PUBLIC_ORIGIN);
const server = https.createServer({key: fs.readFileSync('/run/config/tls.key'), cert: fs.readFileSync('/run/config/tls.crt')}, (req,res)=>{
  if(req.headers.host!==origin.host){res.writeHead(421); res.end('Host mismatch'); return;}
  // Reject rather than trust externally supplied forwarding/identity headers.
  if(Object.keys(req.headers).some(k=>k==='forwarded'||k.startsWith('x-forwarded-')||['x-real-ip','x-principal-id','x-workspace-id'].includes(k))){res.writeHead(400);res.end();return;}
  const upstream=http.request({hostname:'127.0.0.1',port:18085,path:req.url,method:req.method,headers:req.headers}, response=>{
    res.writeHead(response.statusCode,response.headers);response.pipe(res);
  });
  upstream.on('error',()=>{if(!res.headersSent){res.writeHead(503,{'content-type':'text/plain;charset=utf-8'});res.end('知识库正在启动，请稍后刷新。');}else res.destroy();});
  res.on('close',()=>upstream.destroy());
  req.pipe(upstream);
});
server.requestTimeout=0;
server.headersTimeout=30000;
server.listen(8443,'0.0.0.0',()=>console.log('HTTPS entry ready'));
for(const signal of ['SIGTERM','SIGINT']) process.on(signal,()=>{server.close(()=>process.exit(0));setTimeout(()=>process.exit(0),10000).unref();});
