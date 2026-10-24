import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createVault} from '../vault.mjs';
import {createDevices} from '../devices.mjs';
test('single-device migration preserves history, push registration, renames and revocation across restart',()=>{
 const directory=mkdtempSync(path.join(tmpdir(),'waypoint-migrate-')),db=new DatabaseSync(':memory:');
 try{
  db.exec(`CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);CREATE TABLE reports(id INTEGER PRIMARY KEY,lat REAL);CREATE TABLE commands(id TEXT PRIMARY KEY,status TEXT);CREATE TABLE push_subscription(id INTEGER PRIMARY KEY,endpoint TEXT,p256dh TEXT,auth TEXT,updated_at TEXT,state TEXT,last_error TEXT,last_sent_at TEXT);
  INSERT INTO reports VALUES(1,52);INSERT INTO commands VALUES('existing','queued');INSERT INTO settings VALUES('transport','unifiedpush');INSERT INTO push_subscription VALUES(1,'https://example.test/push','key','auth','time','registered',NULL,NULL)`);
  const vault=createVault(directory),options={bootstrapToken:'a'.repeat(64),bootstrapName:'Existing phone'};
  const devices=createDevices(db,vault,options);
  assert.equal(devices.authenticate({headers:{authorization:'Bearer '+options.bootstrapToken}}).id,'legacy');
  assert.equal(db.prepare('SELECT device_id FROM reports').get().device_id,'legacy');assert.equal(db.prepare('SELECT device_id FROM commands').get().device_id,'legacy');
  assert.equal(devices.get('legacy').transport,'unifiedpush');assert.equal(db.prepare('SELECT endpoint FROM push_subscriptions').get().endpoint,'https://example.test/push');
  db.exec("UPDATE devices SET name='Renamed phone',revoked_at='time';DELETE FROM push_subscriptions");
  const restarted=createDevices(db,vault,options);assert.equal(restarted.get('legacy').name,'Renamed phone');assert.equal(restarted.authenticate({headers:{authorization:'Bearer '+options.bootstrapToken}}),null);assert.equal(db.prepare('SELECT count(*) AS n FROM push_subscriptions').get().n,0);
 }finally{db.close();rmSync(directory,{recursive:true,force:true});}
});
