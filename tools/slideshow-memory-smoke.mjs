// One-minute observation on the dedicated fixture emulator; not an endurance benchmark.
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import { app, run, key, focus, waitText, requireEmulator, launch, metrics, sleep } from './tv-test-driver.mjs';
await requireEmulator(); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
await focus('打开 Photos'); await key(23); await waitText('demo · /Photos');
await focus('开始轮播', 19); await key(23); await focus('开始');
const before = await metrics(); await key(23);
const samples = [];
for (let i = 0; i < 12; i++) {
  await sleep(5000);
  const memory = await run('shell', 'dumpsys', 'meminfo', app);
  const pss = Number(memory.match(/TOTAL PSS:\s+(\d+)/)?.[1]); assert.ok(pss > 0, 'App is not running');
  samples.push(pss); console.log(`sample ${i + 1}: ${pss} KiB`);
}
const after = await metrics(); assert.ok(after.mediaRequests - before.mediaRequests >= 15); assert.equal(after.credentialLeaks, 0);
await key(127); await focus('退出'); await key(23); await waitText('demo · /Photos');
const result = { sampleIntervalSeconds: 5, samplesKiB: samples, minKiB: Math.min(...samples), maxKiB: Math.max(...samples), mediaRequests: after.mediaRequests - before.mediaRequests, note: '60-second emulator observation; no forced GC, not a peak/P95 or 24-hour claim' };
await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true }); await writeFile(new URL('../artifacts/alpha03-memory.json', import.meta.url), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result));
