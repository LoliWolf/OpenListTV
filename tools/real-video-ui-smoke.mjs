// Video/subtitle integration against the isolated real OpenList fixture (not a user server).
// Uses observed UI bounds for setup; D-pad navigation is covered separately by ui-smoke.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
const adb = `${process.env.ANDROID_HOME}/platform-tools/adb`;
const app = 'io.github.loliwolf.openlisttv';
const run = async (...args) => (await exec(adb, ['-s', 'emulator-5554', ...args], { maxBuffer: 2 * 1024 * 1024 })).stdout;
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function nodes() { await run('shell', 'uiautomator', 'dump', '/sdcard/openlist-video-test.xml'); const xml = await run('shell', 'cat', '/sdcard/openlist-video-test.xml'); return [...xml.matchAll(/<node\b([^>]+)>/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2].replaceAll('&amp;', '&')]))); }
async function tap(label) { const list = await nodes(); const item = list.find(n => n.text === label || n['content-desc'] === label); if (!item) throw new Error(`Missing ${label}`); const b = item.bounds.match(/\d+/g).map(Number); await run('shell', 'input', 'tap', String((b[0] + b[2]) / 2), String((b[1] + b[3]) / 2)); await sleep(250); }
async function fill(label, text) { await tap(label); await run('shell', 'input', 'text', text); await sleep(150); if ((await run('shell', 'dumpsys', 'input_method')).includes('mInputShown=true')) { await run('shell', 'input', 'keyevent', '4'); await sleep(200); } }
async function wait(text) { for (let i = 0; i < 8; i++) { const list = await nodes(); if (list.some(n => n.text.includes(text))) return list; await sleep(200); } throw new Error(`Missing ${text}`); }
if ((await run('shell', 'getprop', 'ro.kernel.qemu')).trim() !== '1') throw new Error('Dedicated emulator required');
await run('shell', 'am', 'force-stop', app); await run('shell', 'am', 'start', '-W', '-n', `${app}/.MainActivity`);
await wait('9 项'); await tap('站点'); await tap('添加站点');
await fill('服务器地址（含 https://）', 'http://10.0.2.2:18766/');
await fill('账号', 'tv-fixture'); await fill('密码', 'demo-fixture-only');
await tap('允许此站点使用 HTTP（局域网明文传输）'); await tap('连接站点');
await wait('tv-fixture · /'); await wait('Silent25s.mp4'); await tap('打开 Silent25s.mp4');
await sleep(2000); await run('shell', 'input', 'keyevent', '85');
const list = await wait('0:25');
if (list.some(n => n.text.includes('无法硬件解码'))) throw new Error('Video decoder unavailable');
console.log('PASS real OpenList signed MP4 playback and duration');
await tap('音轨 / 字幕'); await tap('选择同目录外挂字幕（SRT / VTT）'); await tap('Silent25s.srt'); await sleep(700);
// Subtitle registration is checked through the track chooser; visual cue QA is separate.
await run('shell', 'input', 'keyevent', '19'); await tap('音轨 / 字幕');
await wait('Silent25s.srt'); console.log('PASS same-directory external SRT registration');
await run('shell', 'input', 'keyevent', '4');
console.log(JSON.stringify({ passed: 2, server: 'OpenList v4.2.6 Local', media: 'AndroidX silent-black-25s.mp4 fixture', limitations: 'Not a real projector, 4K, all-format, or visual-caption certification' }));
