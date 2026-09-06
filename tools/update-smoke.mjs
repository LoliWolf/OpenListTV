// Same-signature upgrade without pm clear/uninstall; dedicated fixture emulator only.
import assert from 'node:assert/strict';
import { app, run, key, focus, nodes, waitText, launch, metrics, requireEmulator } from './tv-test-driver.mjs';
const apk = process.argv[2]; if (!apk) throw new Error('Pass the new APK path');
await requireEmulator();
const labels = ['图片停留秒数（1–3600）', '视频秒数（0 表示播放到结束）', '音频秒数（0 表示播放到结束）'];
async function snapshot() {
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('demo · /'); await waitText('9 项');
  await focus('开始轮播', 19); await key(23);
  const mode = (await nodes()).find(n => n.class === 'android.widget.RadioButton' && n.checked === 'true')?.text;
  const values = []; for (const label of labels) values.push((await focus(label)).text);
  await key(4); return { mode, values };
}
const before = await snapshot(); const loginCount = (await metrics()).loginRequests;
console.log(await run('install', '-r', apk));
const after = await snapshot(); assert.deepEqual(after, before); assert.equal((await metrics()).loginRequests, loginCount);
console.log('PASS overwrite retains saved session, slideshow mode and all three durations without login', after);
