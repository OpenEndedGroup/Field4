import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const text=await readFile(new URL('../src/commonMain/resources/output_requests.js',import.meta.url),'utf8');
const {outputRequests}=await import('data:text/javascript;base64,'+Buffer.from(text).toString('base64'));
class Socket extends EventTarget {
 static OPEN=1;static all=[];
 constructor(url){super();this.url=String(url);this.readyState=0;this.sent=[];Socket.all.push(this);queueMicrotask(()=>{this.readyState=1;this.dispatchEvent(new Event('open'))});}
 send(data){this.sent.push(JSON.parse(data));}
 reply(i,data,ok=true){this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({name:this.sent[i].replyTo,payload:{ok,data,error:ok?null:'Invalid opening'}})}));}
 close(){this.readyState=3;this.dispatchEvent(new Event('close'));}
}
globalThis.WebSocket=Socket;
const client=outputRequests('binding',{base:'https://field.test/project/doc/files/plan/',timeout:50});
const a=client.request({edit:1}),b=client.request({edit:2});await new Promise(r=>setTimeout(r,0));
const ws=Socket.all[0];assert.equal(ws.url,'wss://field.test/ws');assert.equal(ws.sent.length,2);
ws.reply(1,'second');ws.reply(0,'first');assert.equal(await a,'first');assert.equal(await b,'second');
const invalid=client.request({bad:true});await new Promise(r=>setTimeout(r,0));ws.reply(2,null,false);await assert.rejects(invalid,/Invalid opening/);
const uncertain=client.request({edit:3});const lost=assert.rejects(uncertain,/connection lost/);await new Promise(r=>setTimeout(r,0));ws.close();await lost;
const snapshot=client.request();await new Promise(r=>setTimeout(r,0));const next=Socket.all[1];assert.equal(next.sent.length,1);assert.equal(next.sent[0].payload.data,null);next.reply(0,'current');assert.equal(await snapshot,'current');
await assert.rejects(client.request({slow:true}),/timed out/);client.close();await assert.rejects(client.request(),/closed/);
console.log('PASS: shared origin, correlated replies, failures, lost-connection no-replay, timeout, disposal');
