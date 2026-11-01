import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createRinging} from '../ring.mjs';
function fixture(t){const db=new DatabaseSync(':memory:');t.after(()=>db.close());let time=100000;return {ring:createRinging(db,{clock:()=>time}),advance:ms=>time+=ms};}
const start={action:'start',duration:30,volume:75,vibrate:true};
test('ring validation, per-device isolation, acknowledgements and bounded playback',t=>{
 const {ring,advance}=fixture(t);
 for(const input of [{...start,duration:3600},{...start,volume:101},{...start,vibrate:'yes'},{...start,action:'erase'}])assert.throws(()=>ring.request('a',input),{status:400});
 const a=ring.request('a',start);assert.equal(ring.pending('b'),null);assert.throws(()=>ring.acknowledge('b',{id:a.id,status:'playing'}),{status:409});
 assert.throws(()=>ring.request('a',start),{status:409});
 ring.acknowledge('a',{id:a.id,status:'playing'});assert.equal(ring.pending('a'),null);
 advance(10000);ring.acknowledge('a',{id:a.id,status:'playing'});advance(20000);
 assert.equal(ring.get('a').status,'unconfirmed','repeated ACK must not extend the deadline');
 ring.acknowledge('a',{id:a.id,status:'stopped'});assert.equal(ring.get('a').status,'stopped');
 assert.throws(()=>ring.acknowledge('a',{id:a.id,status:'playing'}),{status:409});
});
test('offline sound expires and stop supersedes start without rate-limit delay',t=>{
 const {ring,advance}=fixture(t);const first=ring.request('a',start);
 const stop=ring.request('a',{action:'stop'});assert.ok(stop.created_at>first.created_at);
 assert.equal(ring.request('a',{action:'stop'}).id,stop.id);
 assert.throws(()=>ring.acknowledge('a',{id:first.id,status:'playing'}),{status:409});
 assert.throws(()=>ring.acknowledge('a',{id:stop.id,status:'playing'}),{status:409});
 ring.acknowledge('a',{id:stop.id,status:'stopped'});advance(10001);
 const next=ring.request('a',start);advance(60001);assert.equal(ring.pending('a'),null);assert.equal(ring.get('a').status,'expired');
 assert.throws(()=>ring.acknowledge('a',{id:next.id,status:'playing'}),{status:409});
 ring.revoke('a');assert.equal(ring.get('a'),null);
});
