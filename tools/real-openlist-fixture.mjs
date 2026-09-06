// Starts a NEW isolated loopback OpenList instance. Never targets an existing server.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
const directory = process.argv[2];
if (!directory || !directory.startsWith('/tmp/openlist-tv-server.') || directory.includes('..')) throw new Error('Use a dedicated mktemp directory under /tmp/openlist-tv-server.*');
const base = 'http://127.0.0.1:18766';
try { await fetch(`${base}/ping`, { signal: AbortSignal.timeout(500) }); throw new Error('Port already in use; refusing to modify an existing service'); } catch (e) { if (e.message.startsWith('Port already')) throw e; }
const data = path.join(directory, 'data');
const media = path.join(directory, 'media');
await fs.mkdir(data, { recursive: true }); await fs.mkdir(path.join(media, 'Locked'), { recursive: true });
try { await fs.access(path.join(data, 'data.db')); throw new Error('Existing database found; use a fresh fixture directory'); } catch (e) { if (e.code !== 'ENOENT') throw e; }
await fs.writeFile(path.join(data, 'config.json'), JSON.stringify({ scheme: { address: '127.0.0.1', http_port: 18766, https_port: -1 }, log: { enable: false }, s3: { enable: false }, ftp: { enable: false }, sftp: { enable: false }, mcp: { enable: false } }));
for (const name of ['2.png', '10.png', 'Tone.wav', 'Locked/secret.png']) {
  const response = await fetch(`http://127.0.0.1:18765/media/file?name=${encodeURIComponent(name)}`);
  await fs.writeFile(path.join(media, name), Buffer.from(await response.arrayBuffer()));
}
const adminPassword = crypto.randomBytes(24).toString('base64url');
const server = spawn(path.join(directory, 'openlist'), ['server', '--data', data], { cwd: directory, env: { ...process.env, OPENLIST_ADMIN_PASSWORD: adminPassword }, stdio: ['ignore', 'pipe', 'pipe'] });
const filter = chunk => { for (const line of chunk.toString().split('\n')) if (line && !/password|token|secret/i.test(line)) console.log(line); };
server.stdout.on('data', filter); server.stderr.on('data', filter);
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.kill('SIGTERM'));
server.on('exit', code => process.exit(code || 0));
for (let i = 0; i < 60; i++) { try { const response = await fetch(`${base}/ping`); if (response.ok) break; } catch {} await new Promise(r => setTimeout(r, 250)); }
const request = async (endpoint, body, token) => {
  const response = await fetch(`${base}/api/${endpoint}`, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: token } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const result = await response.json(); if (result.code !== 200) throw new Error(`Fixture setup ${endpoint} failed with code ${result.code}`); return result.data;
};
try {
  const hash = crypto.createHash('sha256').update(`${adminPassword}-https://github.com/alist-org/alist`).digest('hex');
  const { token } = await request('auth/login/hash', { username: 'admin', password: hash });
  await request('admin/storage/create', { mount_path: '/Test', driver: 'Local', addition: JSON.stringify({ root_folder_path: media, thumbnail: false, show_hidden: true }), cache_expiration: 1, order_by: 'name', order_direction: 'asc', extract_folder: 'front', enable_sign: true }, token);
  await request('admin/user/create', { username: 'tv-fixture', password: 'demo-fixture-only', base_path: '/Test', role: 0, permission: 0, disabled: false }, token);
  await request('admin/meta/create', { path: '/Test/Locked', password: '1234', p_sub: true }, token);
  console.log('READY: real OpenList v4.2.6 on loopback port 18766; restricted tv-fixture account; no user service changed');
} catch (e) { console.error(e.message); server.kill('SIGTERM'); process.exitCode = 1; }
