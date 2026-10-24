import { randomBytes, scryptSync } from 'node:crypto';
let input = '';
for await (const chunk of process.stdin) input += chunk;
const password = input.replace(/\r?\n$/, '');
if (password.length < 16) throw new Error('Use a password of at least 16 characters. Supply it through stdin.');
const salt = randomBytes(16).toString('hex');
console.log(`${salt}:${scryptSync(password, salt, 64).toString('hex')}`);
