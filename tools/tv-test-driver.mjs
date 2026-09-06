// Shared D-pad-only driver for the dedicated Android TV emulator.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
export const exec = promisify(execFile);
export const adb = `${process.env.ANDROID_HOME}/platform-tools/adb`;
export const serial = process.env.OPENLIST_TEST_SERIAL || 'emulator-5554';
export const app = 'io.github.loliwolf.openlisttv';
export const run = async (...args) => (await exec(adb, ['-s', serial, ...args], { maxBuffer: 4 * 1024 * 1024 })).stdout;
export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export async function key(code) { await run('shell', 'input', 'dpad', 'keyevent', String(code)); await sleep(100); }
export async function requireEmulator() {
  if ((await run('shell', 'getprop', 'ro.kernel.qemu')).trim() !== '1') throw new Error('Dedicated emulator required');
}
export async function nodes() {
  await run('shell', 'uiautomator', 'dump', '/sdcard/openlist-check.xml');
  const xml = await run('shell', 'cat', '/sdcard/openlist-check.xml');
  return [...xml.matchAll(/<node\b([^>]+)>/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2].replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&lt;', '<').replaceAll('&gt;', '>')])));
}
export async function waitText(text) {
  for (let i = 0; i < 10; i++) { const list = await nodes(); if (list.some(n => n.text.includes(text) || n['content-desc'].includes(text))) return list; await sleep(200); }
  throw new Error(`Missing ${text}`);
}
function point(node) { const a = node.bounds.match(/\d+/g).map(Number); return { x: (a[0] + a[2]) / 2, y: (a[1] + a[3]) / 2 }; }
export async function focus(label, fallback = 20, max = 24) {
  for (let i = 0; i < max; i++) {
    const list = await nodes();
    const focused = list.find(n => n.focused === 'true');
    const current = focused?.class === 'android.widget.ListView' ? list.find(n => n.selected === 'true' && n.text) || focused : focused;
    if (current && (current.text === label || current['content-desc'] === label)) return current;
    const target = list.find(n => n.text === label || n['content-desc'] === label);
    let direction = fallback;
    if (current && target) { const a = point(current), b = point(target); direction = Math.abs(b.y - a.y) > 25 ? b.y > a.y ? 20 : 19 : b.x > a.x ? 22 : 21; }
    await key(direction);
  }
  throw new Error(`Cannot focus ${label}; current=${JSON.stringify((await nodes()).find(n => n.focused === 'true'))}`);
}
export async function setNumber(label, value) {
  const field = await focus(label); await key(23);
  await run('shell', 'input', 'keyevent', '123');
  for (let i = 0; i < Math.max(4, field.text.length); i++) await run('shell', 'input', 'keyevent', '67');
  await run('shell', 'input', 'text', String(value));
  if ((await run('shell', 'dumpsys', 'input_method')).includes('mInputShown=true')) await key(4);
}
export async function launch() { await run('shell', 'am', 'start', '-W', '-n', `${app}/io.github.loliwolf.openlisttv.MainActivity`); }
export async function metrics() { return (await (await fetch('http://127.0.0.1:18765/metrics')).json()).data; }
