// Real, unedited screenshots from the installed release APK and local procedural demo data.
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import { adb, serial, exec, app, run, key, focus, nodes, waitText, launch, sleep, setNumber, requireEmulator } from './tv-test-driver.mjs';
await requireEmulator();
const output = new URL('../docs/screenshots/', import.meta.url); await mkdir(output, { recursive: true });
async function type(value) {
  await key(23); await sleep(250); await run('shell', 'input', 'text', value.replaceAll(' ', '%s')); await sleep(200);
  if ((await run('shell', 'dumpsys', 'input_method')).includes('mInputShown=true')) await key(4);
}
async function shot(name, width, height) {
  await sleep(300);
  const result = await exec(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { encoding: 'buffer', maxBuffer: 16 * 1024 * 1024 });
  assert.equal(result.stdout.readUInt32BE(16), width); assert.equal(result.stdout.readUInt32BE(20), height);
  await writeFile(new URL(name + '.png', output), result.stdout); console.log(`Saved ${name}: ${width}×${height}`);
}
try {
  await run('shell', 'wm', 'size', 'reset'); await run('shell', 'cmd', 'uimode', 'night', 'no');
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await focus('站点', 19); await key(23); await focus('添加站点'); await key(23);
  await focus('站点名称', 19); await type('OpenList TV Demo');
  await focus('服务器地址（含 https://）'); await type('http://10.0.2.2:18768/prefix/');
  await focus('账号'); await type('demo'); await focus('密码'); await type('demo');
  await focus('允许此站点使用 HTTP（局域网明文传输）'); await key(23); await focus('连接站点'); await key(23); await waitText('9 项');
  await focus('打开 Photos'); await key(23); await waitText('4 项'); await waitText('缩略图 Coastline.png');
  await shot('landscape-browser', 1920, 1080);
  await focus('开始轮播', 19); await key(23); await focus('顺序深度遍历', 19); await key(23);
  if ((await nodes()).find(n => n.text === '仅图片')?.checked !== 'true') { await focus('仅图片'); await key(23); }
  await setNumber('图片停留秒数（1–3600）', 8); await focus('开始'); await key(23); await sleep(900); await key(127);
  await waitText('Coastline.png'); await shot('landscape-slideshow', 1920, 1080);
  await focus('退出'); await key(23); await waitText('4 项'); await key(4); await waitText('9 项');
  const beforePid = (await run('shell', 'pidof', app)).trim();
  await run('shell', 'wm', 'size', '1080x1920'); await sleep(700); await waitText('9 项');
  assert.equal((await run('shell', 'pidof', app)).trim(), beforePid, 'Rotation restarted the process');
  const toolbar = (await nodes()).filter(n => n.class === 'android.widget.Button' && ['上一级', '根目录', '排序', '开始轮播', '重新加载', '站点', '设置'].includes(n.text));
  assert.equal(toolbar.length, 7);
  for (const button of toolbar) { const bounds = button.bounds.match(/\d+/g).map(Number); assert.ok(bounds[0] >= 0 && bounds[2] <= 1080 && bounds[1] >= 0 && bounds[3] <= 1920); }
  await waitText('缩略图 Forest.png'); await shot('portrait-browser', 1080, 1920);
  await focus('开始轮播', 19); await key(23); await focus('图片停留秒数（1–3600）');
  const fields = (await nodes()).filter(n => ['图片停留秒数（1–3600）', '视频秒数（0 表示播放到结束）', '音频秒数（0 表示播放到结束）'].includes(n['content-desc']));
  assert.equal(fields.length, 3); await shot('portrait-slideshow-settings', 1080, 1920); await key(4);
} finally { await run('shell', 'wm', 'size', 'reset'); }
