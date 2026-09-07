// Verify new settings and explicit downloads across a same-signature overwrite.
import assert from 'node:assert/strict';
import { app, run, key, focus, nodes, waitText, requireEmulator, launch, metrics } from './tv-test-driver.mjs';
const apk = process.argv[2]; if (!apk) throw new Error('Pass the signed preview APK');
await requireEmulator();
async function snapshot() {
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await focus('开始轮播', 19); await key(23);
  const mode = (await nodes()).find(n=>n.class==='android.widget.RadioButton' && n.checked==='true')?.text;
  const values = [];
  for (const label of ['图片停留秒数（1–3600）','视频秒数（0 表示播放到结束）','音频秒数（0 表示播放到结束）','定时停止轮播（0 表示关闭）']) values.push((await focus(label)).text);
  await key(4); await focus('设置', 19); await key(23); await focus('本机下载'); await key(23); await waitText('下载文件 2.png');
  const saved = (await nodes()).filter(n=>n['content-desc']==='下载文件 2.png').map(n=>n.text);
  assert.ok(saved.length>0 && saved.every(s=>s.includes('已完成')));
  return { mode, values, saved };
}
const before = await snapshot(); const logins = (await metrics()).loginRequests;
console.log(await run('install','-r',apk));
assert.deepEqual(await snapshot(),before); assert.equal((await metrics()).loginRequests,logins);
await focus('下载文件 2.png'); await key(23); await focus('离线打开'); await key(23); await waitText('图片 2.png');
await key(4); await key(4); await focus('返回文件浏览',19); await key(23); await waitText('9 项');
await focus('设置',19); await key(23); await focus('清理资源索引与图片缓存'); await key(23);
await focus('本机下载',19); await key(23); await focus('下载文件 2.png'); await key(23); await focus('离线打开'); await key(23); await waitText('图片 2.png');
console.log('PASS signed overwrite retains session, mode, three durations, stop timer and downloaded files; cache clearing also keeps playable local copies', {mode:before.mode,values:before.values,downloads:before.saved.length});
