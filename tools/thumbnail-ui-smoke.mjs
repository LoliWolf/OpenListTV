// Bounded-preview integration check against mock-openlist; never run against user servers.
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import { adb, serial, exec, app, run, key, focus, nodes, waitText, requireEmulator, launch, metrics, sleep } from './tv-test-driver.mjs';
await requireEmulator();
await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
await focus('排序', 19); await key(23); await focus('名称', 19); await key(23);
if ((await nodes()).find(n => n.text === '降序')?.checked === 'true') { await focus('降序'); await key(23); }
await focus('应用'); await key(23); await waitText('名称升序');
await focus('打开 Lots');
await fetch('http://127.0.0.1:18765/metrics?reset_thumbnails=1');
const originalBeforeList = (await metrics()).mediaRequests;
await key(23); await waitText('1001 项');
const indices = m => m.thumbnailPaths.filter(p => p?.startsWith('/Lots/')).map(p => Number(p.match(/\/(\d+)\.png$/)[1]));
async function loadedThrough(max) {
  for (let i = 0; i < 80; i++) { const m = await metrics(); if (indices(m).includes(max)) return m; await sleep(250); }
  throw new Error(`Preview ${max} did not load`);
}
await loadedThrough(200); await sleep(1000);
let m = await metrics(); assert.equal(new Set(indices(m)).size, 200); assert.equal(Math.max(...indices(m)), 200);
assert.equal(m.mediaRequests, originalBeforeList);
const mediaAtStart = m.mediaRequests;
console.log('PASS initial previews restricted to 1–200, with no original-image downloads');
function checkRecycling(list) {
  let current;
  let count = 0;
  for (const n of list) {
    if (n['content-desc'].startsWith('打开 ')) current = n['content-desc'].slice(3);
    if (n['content-desc'].startsWith('缩略图 ')) { assert.equal(n['content-desc'].slice(4), current, 'Recycled thumbnail belongs to wrong row'); count++; }
  }
  assert.ok(count >= 2, 'Visible thumbnails are missing');
}
checkRecycling(await nodes());
await mkdir(new URL('../artifacts/screenshots/', import.meta.url), { recursive: true });
async function screenshot(name) {
  const result = await exec(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { encoding: 'buffer', maxBuffer: 16 * 1024 * 1024 });
  await writeFile(new URL(`../artifacts/screenshots/${name}.png`, import.meta.url), result.stdout);
}
await screenshot('alpha02-browser');
// Walk across a page boundary using only Down, not the next-page button.
for (let i = 0; i < 110; i++) await key(20);
await waitText('第 2 页'); await loadedThrough(300); await sleep(1000);
m = await metrics(); assert.equal(Math.max(...indices(m)), 300); assert.equal(new Set(indices(m)).size, 300);
assert.equal(m.mediaRequests, mediaAtStart, 'Unexpected original-image prefetch');
checkRecycling(await nodes());
const pss = (await run('shell', 'dumpsys', 'meminfo', app)).match(/TOTAL PSS:\s+(\d+)/)?.[1];
console.log(`PASS downward continuous scrolling advances preview window to pages 2+3; PSS ${pss} KiB`);
await key(21); await waitText('第 1 页'); await focus('打开 1.png', 19); await sleep(500); checkRecycling(await nodes());
console.log('PASS backward page jump rehydrates matching previews');
await key(23); await waitText('图片 ·'); await key(127); await focus('退出'); await key(23); await waitText('1001 项');
await focus('打开 1.png'); await sleep(500); checkRecycling(await nodes());
console.log('PASS player exit restores focus and thumbnails');
await key(3); await sleep(700); const stopped = await metrics(); await sleep(1200);
assert.equal((await metrics()).thumbnailRequests, stopped.thumbnailRequests, 'Background preview requests continued');
assert.equal((await metrics()).credentialLeaks, 0);
await launch(); await waitText('1001 项');
console.log('PASS background stops preview traffic; no Authorization/Cookie leak');
console.log(JSON.stringify({ initialWindow: [1, 200], scrolledWindow: [101, 300], pssKiB: Number(pss), credentialLeaks: 0 }));
