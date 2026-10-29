// Dedicated local-fixture emulator only. Optional signed APK verifies overwrite retention.
import assert from 'node:assert/strict';
import {app, run, key, focus, nodes, waitText, launch, requireEmulator, setNumber, metrics, sleep} from './tv-test-driver.mjs';
await requireEmulator();
await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
const interval = '自动刷新索引（0 关闭，5–1440 分钟）';
async function dialog() {
  await focus('开始轮播', 19); await key(23); await waitText('播放方式');
  // Android can initially focus the scroll container. Its rectangle is not a
  // navigable button: explicitly enter the form before geometry-based navigation.
  if ((await nodes()).some(n => n.focused === 'true' && n.class === 'android.widget.ScrollView')) await key(20);
}
async function toggle(label, value) { if ((await focus(label)).checked !== String(value)) await key(23); }
async function finish() { await key(127); await key(4); await key(4); await waitText('9 项'); }
await dialog();
const previous = {
  mode: (await nodes()).find(n => n.class === 'android.widget.RadioButton' && n.checked === 'true').text,
  images: (await focus('仅图片')).checked === 'true',
  recursive: (await focus('包含子目录', 19)).checked === 'true',
  interval: (await focus(interval)).text,
};
await focus('全目录树随机', 19); await key(23);
await toggle('包含子目录', false); await toggle('仅图片', true);
await setNumber(interval, 5);
await focus('开始'); await key(23); await sleep(1200); await key(127);
await waitText('自动刷新 5 分钟'); await waitText('上次更新');
const before = (await metrics()).listRequests;
await focus('刷新索引', 22); await key(23); await sleep(800);
assert.equal((await metrics()).listRequests, before + 1);
await key(4); // Hide controls, even while paused.
await key(82); await sleep(800); // Menu refresh must not reveal UI or resume playback.
assert.equal((await metrics()).listRequests, before + 2);
assert.ok(!(await nodes()).some(n => n.text === '刷新索引'));
await key(20); await waitText('自动刷新 5 分钟'); await finish();
console.log('PASS D-pad settings, control refresh, menu silent refresh, index status');
if (process.argv[2]) console.log(await run('install', '-r', process.argv[2]));
await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
await dialog(); assert.equal((await focus(interval)).text, '5');
console.log('PASS restart/overwrite retains interval, site, mode and credentials');
await setNumber(interval, previous.interval);
await focus(previous.mode, 19); await key(23);
await toggle('包含子目录', previous.recursive); await toggle('仅图片', previous.images);
await focus('开始'); await key(23); await sleep(1000); await finish();
console.log('PASS original fixture preferences restored');
