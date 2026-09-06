// D-pad-only navigation smoke test for all three real PlaybackPanel strategies.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
const adb = `${process.env.ANDROID_HOME}/platform-tools/adb`;
const serial = process.env.OPENLIST_TEST_SERIAL || 'emulator-5554';
const app = process.env.OPENLIST_TEST_APP || 'io.github.loliwolf.openlisttv';
const run = async (...args) => (await exec(adb, ['-s', serial, ...args], { maxBuffer: 2 * 1024 * 1024 })).stdout;
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function key(code) { await run('shell', 'input', 'dpad', 'keyevent', String(code)); await sleep(180); }
async function nodes() {
  await run('shell', 'uiautomator', 'dump', '/sdcard/openlist-slideshow-test.xml');
  const xml = await run('shell', 'cat', '/sdcard/openlist-slideshow-test.xml');
  return [...xml.matchAll(/<node\b([^>]+)>/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2].replaceAll('&amp;', '&')])));
}
function rectangle(node) { const a = node.bounds.match(/\d+/g).map(Number); return { x: (a[0] + a[2]) / 2, y: (a[1] + a[3]) / 2 }; }
async function focus(label, fallback = 20) {
  for (let i = 0; i < 24; i++) {
    const list = await nodes(); const current = list.find(n => n.focused === 'true');
    const target = list.find(n => n.focusable === 'true' && (n.text === label || n['content-desc'] === label));
    if (current && (current.text === label || current['content-desc'] === label)) return;
    let direction = fallback;
    if (current && target) { const a = rectangle(current); const b = rectangle(target); direction = Math.abs(b.y - a.y) > 25 ? b.y > a.y ? 20 : 19 : b.x > a.x ? 22 : 21; }
    await key(direction);
  }
  throw new Error(`Cannot reach ${label} with D-pad`);
}
async function waitText(text) { for (let i = 0; i < 8; i++) { const list = await nodes(); if (list.some(n => n.text.includes(text))) return; await sleep(200); } throw new Error(`Missing ${text}`); }
if ((await run('shell', 'getprop', 'ro.kernel.qemu')).trim() !== '1') throw new Error('Emulator required');
await run('shell', 'am', 'force-stop', app);
await run('shell', 'am', 'start', '-W', '-n', `${app}/io.github.loliwolf.openlisttv.MainActivity`);
await waitText('9 项'); await focus('打开 Photos'); await key(23); await waitText('demo · /Photos');
const report = [];
for (const [mode, marker] of [['顺序深度遍历', '顺序深度遍历'], ['随机游走', '随机游走'], ['全目录树随机', '全树随机']]) {
  await focus('开始轮播', 19); await key(23); await waitText('从 /Photos 开始轮播');
  await focus(mode, 19); await key(23);
  await focus('图片停留秒数（1–3600）'); await key(23); await sleep(200);
  await run('shell', 'input', 'keyevent', '67'); await run('shell', 'input', 'text', '2');
  if ((await run('shell', 'dumpsys', 'input_method')).includes('mInputShown=true')) await key(4);
  await focus('开始');
  const before = (await (await fetch('http://127.0.0.1:18765/metrics')).json()).data;
  await key(23); await waitText(marker); await sleep(6500);
  const after = (await (await fetch('http://127.0.0.1:18765/metrics')).json()).data;
  if (after.mediaRequests - before.mediaRequests < 2) throw new Error(`${mode} did not advance media`);
  const memory = await run('shell', 'dumpsys', 'meminfo', app);
  const pss = memory.match(/TOTAL PSS:\s+(\d+)/)?.[1];
  report.push({ mode, mediaRequests: after.mediaRequests - before.mediaRequests, pssKiB: pss ? Number(pss) : null });
  console.log(`PASS ${mode}, ${after.mediaRequests - before.mediaRequests} media requests, PSS ${pss} KiB`);
  // Pause before hierarchy polling: periodic slide changes otherwise keep UIAutomator busy
  // until its idle timeout and the normal 5-second overlay timeout can expire during polling.
  await key(23); await focus('退出'); await key(23); await waitText('demo · /Photos');
}
console.log(JSON.stringify({ passed: report.length, samples: report, note: 'Short emulator checks, not a P95 or endurance benchmark' }, null, 2));
