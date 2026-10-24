import {randomBytes,createCipheriv,createDecipheriv} from 'node:crypto';
import {existsSync,readFileSync,writeFileSync,chmodSync} from 'node:fs';
import path from 'node:path';
export function createVault(directory) {
 const file=path.join(directory,'secrets.key');
 if(!existsSync(file))writeFileSync(file,randomBytes(32),{mode:0o600,flag:'wx'});
 chmodSync(file,0o600);
 const key=readFileSync(file);if(key.length!==32)throw new Error('Invalid data/secrets.key; restore the original key from your backup.');
 return {
  seal(value){const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);const bytes=Buffer.concat([cipher.update(value,'utf8'),cipher.final()]);return Buffer.concat([iv,cipher.getAuthTag(),bytes]).toString('base64');},
  open(value){const bytes=Buffer.from(value,'base64'),cipher=createDecipheriv('aes-256-gcm',key,bytes.subarray(0,12));cipher.setAuthTag(bytes.subarray(12,28));return Buffer.concat([cipher.update(bytes.subarray(28)),cipher.final()]).toString('utf8');}
 };
}
