// UI hierarchy + screenshot evidence for populated persistent field captions.
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import { adb, serial, exec, app, run, key, focus, waitText, requireEmulator, launch, nodes } from './tv-test-driver.mjs';
await requireEmulator();
const folder = new URL('../artifacts/screenshots/', import.meta.url); await mkdir(folder, { recursive: true });
for (const theme of ['no', 'yes']) {
  await run('shell', 'cmd', 'uimode', 'night', theme); await launch(); await waitText('图片自动预览');
  await focus('开始轮播', 19); await key(23); await waitText('开始轮播');
  for (const [name, caption] of [['image', '图片停留秒数（1–3600）'], ['video', '视频秒数（0 表示播放到结束）'], ['audio', '音频秒数（0 表示播放到结束）']]) {
    const field = await focus(caption); assert.match(field.text, /^\d+$/);
    const buffer = (await exec(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { encoding: 'buffer', maxBuffer: 16 * 1024 * 1024 })).stdout;
    await writeFile(new URL(`alpha02-${theme === 'no' ? 'light' : 'dark'}-${name}-field.png`, folder), buffer);
  }
  await key(4);
  console.log(`PASS ${theme === 'no' ? 'light' : 'dark'} theme: all three populated fields reachable by D-pad with descriptive accessibility captions`);
}
await run('shell', 'cmd', 'uimode', 'night', 'no'); await launch();
