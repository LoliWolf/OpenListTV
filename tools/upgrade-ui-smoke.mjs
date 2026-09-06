// First seed alpha01, install alpha02 using adb install -r (no data clearing), then verify.
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { app, run, key, focus, nodes, waitText, requireEmulator, launch, setNumber, metrics, sleep } from './tv-test-driver.mjs';
await requireEmulator();
const statePath = new URL('./test-state/upgrade.json', import.meta.url);
const fields = ['图片停留秒数（1–3600）', '视频秒数（0 表示播放到结束）', '音频秒数（0 表示播放到结束）'];
if (process.argv.includes('--seed-alpha01')) {
  assert.match(await run('shell', 'dumpsys', 'package', app), /versionName=0\.1\.0-alpha01/);
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await focus('排序', 19); await key(23); await focus('文件大小'); await key(23);
  await focus('降序'); await key(23); await focus('应用'); await key(23); await waitText('文件大小降序');
  await focus('开始轮播', 19); await key(23); await focus('随机游走'); await key(23);
  await focus('仅图片'); await key(23);
  for (const [index, value] of [17, 23, 29].entries()) await setNumber(fields[index], value);
  await focus('开始'); await key(23); await sleep(1500); await key(127); await focus('退出'); await key(23); await waitText('9 项');
  // Save a non-zero manual position last; alpha01 clears it on its next cold activation.
  await focus('打开 Tone.wav'); await key(23); await sleep(4500); await key(127); await focus('退出'); await key(23); await waitText('9 项');
  const report = { version: 'alpha01', loginRequests: (await metrics()).loginRequests, numbers: [17, 23, 29], sort: '文件大小降序', mode: '随机游走' };
  await mkdir(new URL('./test-state/', import.meta.url), { recursive: true }); await writeFile(statePath, JSON.stringify(report));
  console.log('PASS seeded old APK preferences, encrypted session and playback position', report);
} else if (process.argv.includes('--verify-alpha02')) {
  const report = JSON.parse(await readFile(statePath, 'utf8'));
  assert.match(await run('shell', 'dumpsys', 'package', app), /versionName=0\.1\.0-alpha02/);
  await launch(); await waitText(report.sort);
  assert.equal((await metrics()).loginRequests, report.loginRequests, 'Upgrade required a new login');
  await focus('开始轮播', 19); await key(23);
  assert.equal((await nodes()).find(n => n.text === report.mode)?.checked, 'true');
  assert.equal((await nodes()).find(n => n.text === '仅图片')?.checked, 'true');
  for (const [index, expected] of report.numbers.entries()) { const field = await focus(fields[index]); assert.equal(field.text, String(expected)); }
  await key(4); await focus('打开 Tone.wav'); await key(23); await waitText('上次播放到'); await key(4);
  // Relaunch again: alpha02 must no longer erase progress during activation.
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText(report.sort);
  await focus('打开 Tone.wav'); await key(23); await waitText('上次播放到'); await key(4);
  assert.equal((await metrics()).loginRequests, report.loginRequests);
  console.log('PASS real alpha01 → alpha02 overwrite upgrade: session, site, sort, slideshow numbers/mode/filter and playback position; second cold launch also preserves progress');
} else throw new Error('Choose --seed-alpha01 or --verify-alpha02');
