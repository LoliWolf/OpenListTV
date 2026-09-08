// Run only against the dedicated emulator and the local mock-openlist fixture.
// Navigation is D-pad/OK/Back; text insertion stands in for the system IME.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
const adb = process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}/platform-tools/adb` : 'adb';
const serial = process.env.OPENLIST_TEST_SERIAL || 'emulator-5554';
const app = process.env.OPENLIST_TEST_APP || 'io.github.loliwolf.openlisttv';
const run = async (...args) => (await exec(adb, ['-s', serial, ...args], { maxBuffer: 2 * 1024 * 1024 })).stdout;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const key = async code => { await run('shell', 'input', 'dpad', 'keyevent', String(code)); await sleep(120); };
const decode = text => text.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
async function nodes() {
  await run('shell', 'uiautomator', 'dump', '/sdcard/openlist-smoke.xml');
  const xml = await run('shell', 'cat', '/sdcard/openlist-smoke.xml');
  return [...xml.matchAll(/<node\b([^>]+)>/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], decode(a[2])])));
}
async function waitFor(text) {
  for (let i = 0; i < 8; i++) { const list = await nodes(); if (list.some(n => n.text.includes(text) || n['content-desc'].includes(text))) return list; await sleep(250); }
  throw new Error(`UI did not show ${text}`);
}
async function focused(expected) {
  const list = await nodes(); const item = list.find(n => n.focused === 'true');
  if (!item || !(item.text.includes(expected) || item['content-desc'].includes(expected))) throw new Error(`Expected focus ${expected}, got ${item?.['content-desc'] || item?.text}`);
}
async function type(text) { await key(23); await sleep(180); await run('shell', 'input', 'text', text); await sleep(150); const ime = await run('shell', 'dumpsys', 'input_method'); if (ime.includes('mInputShown=true')) await key(4); }
async function pressUntilLabel(label, direction = 20, max = 15) {
  for (let i = 0; i < max; i++) { const list = await nodes(); const item = list.find(n => n.focused === 'true'); if (item && (item.text === label || item['content-desc'] === label)) return; await key(direction); }
  throw new Error(`Could not focus ${label}`);
}
const report = [];
function passed(test) { report.push(test); console.log(`PASS ${test}`); }
if ((await run('shell', 'getprop', 'ro.kernel.qemu')).trim() !== '1') throw new Error('Refusing to run fixture tests on a physical device');
if (process.argv.includes('--reset-fixture-app')) await run('shell', 'pm', 'clear', app);
await run('shell', 'am', 'force-stop', app);
await run('shell', 'am', 'start', '-W', '-n', `${app}/io.github.loliwolf.openlisttv.MainActivity`);
let list = await nodes();
if (list.some(n => n.text === '连接站点')) {
  await focused('服务器地址'); await type('http://10.0.2.2:18765/prefix/'); await key(20);
  await focused('账号'); await type('demo'); await key(20);
  await focused('密码'); await type('demo'); await key(20);
  await focused('允许此站点'); await key(23); await key(20); await focused('连接站点'); await key(23);
}
await waitFor('9 项'); await focused('打开 Empty'); passed('D-pad login including explicit HTTP and IME exit');
await key(23); await waitFor('目录为空'); await key(4); await waitFor('9 项'); await focused('打开 Empty'); passed('Empty directory and restored parent focus');
await key(20); await focused('打开 Lots'); await key(23); await waitFor('1001 项'); await focused('打开 1.png');
await key(22); await waitFor('第 2 页'); await focused('打开 101.png'); passed('1,001-entry global natural sort and D-pad pagination');
await key(4); await waitFor('9 项'); await focused('打开 Lots'); await key(20); await key(23); await waitFor('demo · /Photos');
await focused('打开 Nested'); await key(20); await focused('打开 1.png'); await key(23); await waitFor('图片 ·'); passed('Image loaded through resolved media URL');
await key(19); await pressUntilLabel('退出', 22, 10); await key(23); await waitFor('demo · /Photos'); await focused('打开 1.png'); passed('Player exit restores selected file');
await key(4); await waitFor('9 项');
await pressUntilLabel('打开 Tone.wav'); await key(23);
// Repeated runs retain playback progress; handle the normal resume prompt, never pm clear.
if ((await nodes()).some(n => n.text === '从头播放')) { await pressUntilLabel('从头播放', 21, 10); await key(23); }
await waitFor('0:12'); passed('WAV streaming playback and duration');
await key(3); await sleep(600); await run('shell', 'am', 'start', '-W', '-n', `${app}/io.github.loliwolf.openlisttv.MainActivity`); await waitFor('已暂停'); passed('Home releases playback and requires explicit resume');
const metrics = await (await fetch('http://127.0.0.1:18765/metrics')).json();
if (metrics.data.credentialLeaks !== 0) throw new Error('API credentials leaked into media traffic');
passed('No API Authorization/Cookie in media requests');
console.log(JSON.stringify({ passed: report.length, tests: report, metrics: { ...metrics.data, thumbnailPaths: undefined } }, null, 2));
