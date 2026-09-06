// Additional same-signature update check. Establish and verify a resume point BEFORE install.
import assert from 'node:assert/strict';
import { app, run, key, focus, nodes, waitText, requireEmulator, launch, metrics, sleep } from './tv-test-driver.mjs';
await requireEmulator(); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
await focus('打开 Tone.wav'); await key(23); await sleep(400); await key(127);
// Freeze playback before UIAutomator's idle wait, otherwise the short fixture can end.
if ((await nodes()).some(n => n.text.includes('上次播放到'))) { await focus('从头播放'); await key(23); }
else await key(126);
await sleep(4000); await key(127);
const clock = (await nodes()).find(n => { const m = n.text.match(/^0:(\d+) \/ 0:12$/); return m && Number(m[1]) >= 3 && Number(m[1]) < 12; });
assert.ok(clock, 'Did not establish a non-zero paused position');
await focus('退出'); await key(23); await waitText('9 项');
await focus('打开 Tone.wav'); await key(23); const before = await waitText('上次播放到');
const resumeText = before.find(n => n.text.includes('上次播放到')).text; await key(4);
const logins = (await metrics()).loginRequests;
console.log(await run('install', '-r', 'artifacts/OpenListTV-0.1.0-alpha02.apk'));
await launch(); await waitText('9 项'); assert.equal((await metrics()).loginRequests, logins);
await focus('打开 Tone.wav'); await key(23); const after = await waitText('上次播放到');
assert.equal(after.find(n => n.text.includes('上次播放到')).text, resumeText); await key(4);
console.log('PASS final artifact overwrite: existing resume point preserved exactly and no new login required');
