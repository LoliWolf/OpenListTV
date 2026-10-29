// Same-signature overwrite, no uninstall or data reset. Dedicated local fixture only.
import assert from 'node:assert/strict';
import {app, run, launch, waitText, requireEmulator, metrics, focus, key, nodes} from './tv-test-driver.mjs';
const apk = process.argv[2]; if (!apk) throw new Error('Pass the signed APK');
await requireEmulator();
async function snapshot() {
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await focus('开始轮播', 19); await key(23); await waitText('播放节奏');
  const list = await nodes();
  const result = {
    text: list.map(n => n.text).filter(text => text && text !== '开始' && text !== '取消'),
    toggles: list.filter(n => n.class === 'android.widget.Switch').map(n => [n['content-desc'], n.checked]),
  };
  await key(4); return result;
}
const before = await snapshot(), login = (await metrics()).loginRequests;
console.log(await run('install', '-r', apk));
assert.deepEqual(await snapshot(), before);
assert.equal((await metrics()).loginRequests, login);
console.log('PASS signed overwrite retains saved session and visible slideshow mode/range/duration/index preferences');
