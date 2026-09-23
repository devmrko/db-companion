#!/usr/bin/env node
// Heuristic source-publication guard, not a comprehensive secret scanner or security audit.
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const ignored = new Set(['.git', 'target', '.run', 'logs', 'node_modules']);
const findings = [];
const rules = [
  ['private key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/],
  ['GitHub token', /\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b/],
  ['cloud access key', /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/],
  ['provider API key', /\bsk-(?:proj-)?[A-Za-z0-9_-]{40,}\b/],
  ['personal home path', /\/(?:Users|home)\/(?!example(?:\/|\b)|user(?:\/|\b)|demo(?:\/|\b))[^/\s"']+\//],
  ['long cloud identifier', /\bocid1\.[a-z0-9.]+\.[a-z0-9]{40,}\b/i]
];
function walk(dir) {
  return fs.readdirSync(path.join(root, dir), {withFileTypes: true}).flatMap(entry => {
    const name = path.posix.join(dir, entry.name);
    if (entry.isDirectory() && ignored.has(entry.name)) return [];
    return entry.isDirectory() ? walk(name) : [name];
  });
}
let files;
if (fs.existsSync(path.join(root, '.git'))) {
  files = execFileSync('git', ['ls-files', '-z'], {cwd: root, encoding: 'utf8'}).split('\0').filter(Boolean);
  if (!files.length) throw new Error('Stage the publication candidate before scanning a Git repository.');
} else {
  files = walk('');
}
for (const name of files) {
  const full = path.join(root, name);
  if (fs.lstatSync(full).isSymbolicLink()) { findings.push([name, 'symlink requires review']); continue; }
  if (/(?:^|\/)(?:\.env(?:\..*)?|Wallet_[^/]*|wallets?|credentials\.json|secrets)(?:\/|$)/i.test(name) && name !== '.env.example')
    findings.push([name, 'private configuration or wallet path']);
  if (/\.(?:pem|key|sso|p12|jks|keystore|jar|zip|pdf|log)$/i.test(name))
    findings.push([name, 'private/binary artifact requires review']);
  const buffer = fs.readFileSync(full);
  if (buffer.includes(0)) { findings.push([name, 'binary file requires review']); continue; }
  const text = buffer.toString('utf8');
  for (const [label, pattern] of rules) if (pattern.test(text)) findings.push([name, label]);
}
// Never print matching content, which could itself be a credential.
for (const [name, label] of findings) console.error(`${name}: ${label}`);
console.log(`Checked ${files.length} publication files; ${findings.length} findings.`);
if (findings.length) process.exitCode = 1;
