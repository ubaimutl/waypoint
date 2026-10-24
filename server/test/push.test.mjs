import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createECDH, randomBytes } from 'node:crypto';
import { publicAddress, endpointUrl, validateKeys, guardedLookup, createPushClient } from '../push.mjs';
const ecdh=createECDH('prime256v1');ecdh.generateKeys();
const keys={p256dh:ecdh.getPublicKey().toString('base64url'),auth:randomBytes(16).toString('base64url')};
const publicLookup=(host,options,cb)=>cb(null,[{address:'93.184.216.34',family:4}]);
test('push URL rejects credentials, cleartext, and fragments',()=>{
 for(const url of ['http://push.example.com/a','https://user:pass@push.example.com/a','https://push.example.com/a#fragment'])assert.throws(()=>endpointUrl(url));
 assert.equal(endpointUrl('https://push.example.com/custom/path').hostname,'push.example.com');
});
test('all private/reserved IPv4, IPv6 and mapped addresses are blocked',()=>{
 for(const ip of ['127.0.0.1','10.1.1.1','192.168.1.1','172.17.0.1','169.254.169.254','0.0.0.0','100.64.1.1','::1','fe80::1','fc00::1','::ffff:127.0.0.1','::ffff:10.1.1.1'])assert.equal(publicAddress(ip),false,ip);
 assert.equal(publicAddress('8.8.8.8'),true);assert.equal(publicAddress('2606:4700:4700::1111'),true);
});
test('public keys must be valid uncompressed P256 points',()=>{
 validateKeys(keys);assert.throws(()=>validateKeys({p256dh:'A'.repeat(87),auth:'A'.repeat(22)}));assert.throws(()=>validateKeys({...keys,auth:'invalid'}));
});
test('DNS guard checks every address and blocks rebinding at connection time',async()=>{
 const lookup=guardedLookup([],(_host,_options,cb)=>cb(null,[{address:'8.8.8.8',family:4},{address:'127.0.0.1',family:4}]));
 await assert.rejects(new Promise((resolve,reject)=>lookup('push.example.com',{all:true},(err,result)=>err?reject(err):resolve(result))));
 const client=createPushClient({lookup:publicLookup});await assert.rejects(client.validate('https://127.0.0.1/push'));
});
test('private providers require an exact administrator-configured hostname',async()=>{
 const lookup=(_h,_o,cb)=>cb(null,[{address:'10.0.0.2',family:4}]);
 await assert.rejects(createPushClient({lookup}).validate('https://push.internal/topic'));
 await createPushClient({lookup,privateHosts:['push.internal']}).validate('https://push.internal/topic');
 await assert.rejects(createPushClient({lookup,privateHosts:['push.internal']}).validate('https://other.internal/topic'));
});
test('provider allowlist is optional and configurable',async()=>{
 await createPushClient({lookup:publicLookup}).validate('https://custom-provider.example/topic');
 await assert.rejects(createPushClient({lookup:publicLookup,allowedHosts:['push.example']}).validate('https://other.example/topic'));
});
test('wake payload excludes command, token and location; send uses RFC8291 encryption',async()=>{
 let calls=0;
 const client=createPushClient({lookup:publicLookup,send:async(subscription,payload,options)=>{
  calls++;assert.deepEqual(JSON.parse(payload),{type:'wake',version:1});assert.equal(options.contentEncoding,'aes128gcm');assert.equal(options.TTL,120);assert.ok(options.agent);assert.equal(options.timeout,8000);
 }});
 assert.equal((await client.notify({endpoint:'https://push.example/topic',keys},{subject:'https://locator.example'})).state,'sent');assert.equal(calls,1);
});
test('expired endpoints and network failures have safe errors without capability URLs',async()=>{
 for(const code of [410,404,500]){
  const client=createPushClient({lookup:publicLookup,send:async()=>{throw Object.assign(new Error('secret endpoint'),{statusCode:code})}});
  const result=await client.notify({endpoint:'https://push.example/secret',keys},{});assert.equal(result.state,'failed');assert.equal(result.expired,code!==500);assert.ok(!result.error.includes('secret'));
 }
});
