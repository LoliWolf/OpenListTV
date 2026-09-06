import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
const adb = `${process.env.ANDROID_HOME}/platform-tools/adb`;
const serial = process.env.OPENLIST_TEST_SERIAL || 'emulator-5554';
const app = process.env.OPENLIST_TEST_APP || 'io.github.loliwolf.openlisttv';
const run = async (...args) => (await exec(adb, ['-s', serial, ...args])).stdout;
if ((await run('shell', 'getprop', 'ro.kernel.qemu')).trim() !== '1') throw new Error('Dedicated emulator required');
const samples = [];
for (let i = 0; i < 30; i++) {
  await run('shell', 'am', 'force-stop', app);
  const output = await run('shell', 'am', 'start', '-W', '-n', `${app}/io.github.loliwolf.openlisttv.MainActivity`);
  const time = output.match(/TotalTime:\s+(\d+)/)?.[1];
  if (!time || !output.includes('LaunchState: COLD')) throw new Error('Not a valid cold-start sample');
  samples.push(Number(time));
}
const sorted = [...samples].sort((a, b) => a - b);
console.log(JSON.stringify({ samplesMs: samples, n: samples.length, p50Ms: sorted[Math.ceil(samples.length * .5) - 1], p95Ms: sorted[Math.ceil(samples.length * .95) - 1], maxMs: sorted.at(-1), metric: 'adb am start TotalTime, cold app process to first draw; not directory-load completion or physical-device performance' }, null, 2));
