// Run after real-video-ui-smoke against the same isolated fixture, with 0-broken.mp4.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
const adb = `${process.env.ANDROID_HOME}/platform-tools/adb`;
const run = async (...args) => (await exec(adb, ['-s', 'emulator-5554', ...args], { maxBuffer: 2 * 1024 * 1024 })).stdout;
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function nodes() { await run('shell', 'uiautomator', 'dump', '/sdcard/openlist-skip-test.xml'); const xml = await run('shell', 'cat', '/sdcard/openlist-skip-test.xml'); return [...xml.matchAll(/<node\b([^>]+)>/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2]]))); }
async function tap(label) { const item = (await nodes()).find(n => n.text === label || n['content-desc'] === label); if (!item) throw new Error(`Missing ${label}`); const b = item.bounds.match(/\d+/g).map(Number); await run('shell', 'input', 'tap', String((b[0] + b[2]) / 2), String((b[1] + b[3]) / 2)); await sleep(250); }
if ((await run('shell', 'getprop', 'ro.kernel.qemu')).trim() !== '1') throw new Error('Dedicated emulator required');
await run('shell', 'am', 'force-stop', 'io.github.loliwolf.openlisttv');
await run('shell', 'am', 'start', '-W', '-n', 'io.github.loliwolf.openlisttv/.MainActivity'); await sleep(700);
if (!(await nodes()).some(n => n.text === 'tv-fixture · /')) throw new Error('Requires the isolated real fixture account');
await tap('开始轮播'); await tap('顺序深度遍历'); await tap('开始'); await sleep(4500);
await run('shell', 'input', 'keyevent', '127'); await sleep(200);
const list = await nodes();
if (!list.some(n => n.text.includes('已跳过 1 项'))) throw new Error(`No skip confirmation: ${list.filter(n => n.text).map(n => n.text).join(' | ')}`);
if (!list.some(n => n.text === '2.png' || n.text === '10.png')) throw new Error('Did not continue to an image after the broken video');
console.log('PASS invalid MP4 without an alternative is automatically skipped; slideshow continues to an image');
await tap('退出');
