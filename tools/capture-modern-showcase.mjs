// Real installed UI and original procedural fixture art. No crops or private server content.
import assert from 'node:assert/strict';
import {mkdir, writeFile} from 'node:fs/promises';
import {app, adb, serial, exec, run, key, focus, nodes, waitText, launch, requireEmulator, setNumber, sleep} from './tv-test-driver.mjs';
await requireEmulator();
const output = new URL('../artifacts/modern-ui/showcase/', import.meta.url);
await mkdir(output, {recursive: true});
async function activate(label, direction = 20) { await focus(label, direction); await key(23); }
async function type(label, value) {
  await focus(label); await key(23); await run('shell', 'input', 'text', value.replaceAll(' ', '%s'));
  if ((await run('shell', 'dumpsys', 'input_method')).includes('mInputShown=true')) await key(4);
}
async function screenshot(name, width, height) {
  await sleep(300);
  const result = await exec(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], {encoding: 'buffer', maxBuffer: 16 * 1024 * 1024});
  assert.equal(result.stdout.readUInt32BE(16), width); assert.equal(result.stdout.readUInt32BE(20), height);
  await writeFile(new URL(`${name}.png`, output), result.stdout);
  console.log(`Saved ${name}: ${width}x${height}`);
}
const night = (await run('shell', 'cmd', 'uimode', 'night')).match(/\b(yes|no|auto)\b/)?.[1];
if (!night) throw new Error('Cannot safely restore the current emulator night mode');
try {
  await run('shell', 'wm', 'size', 'reset'); await run('shell', 'cmd', 'uimode', 'night', 'no');
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await activate('更多', 19); await activate('设置'); await activate('应用更新');
  const automatic = (await focus('自动检测更新（每次启动）')).checked === 'true';
  if (automatic) await key(23); // Only the synthetic read-only AVD; restore after capture.
  await key(4); await key(4); await waitText('9 项');
  await activate('更多', 19); await activate('站点'); await activate('添加站点');
  await type('站点名称', 'OpenList Demo');
  await type('服务器地址（含 https://）', 'http://10.0.2.2:18768/prefix/');
  await type('账号', 'demo'); await type('密码', 'demo');
  await activate('允许此站点使用 HTTP（局域网明文传输）'); await activate('连接站点'); await waitText('9 项');
  await activate('打开 Photos'); await waitText('4 项'); await waitText('缩略图 Coastline.png');
  await screenshot('landscape-browser', 1920, 1080);
  await activate('开始轮播', 19); await activate('播放方式'); await activate('顺序深度遍历');
  await activate('图片停留时间'); await setNumber('图片停留秒数（1–3600）', 8); await activate('应用');
  await activate('定时停止'); await setNumber('定时停止轮播（0 表示关闭）', 0); await activate('应用');
  await screenshot('landscape-setup', 1920, 1080);
  await activate('开始'); await sleep(900); await key(127); await waitText('图片 Coastline.png');
  await screenshot('landscape-player', 1920, 1080);
  await run('shell', 'wm', 'size', '1080x1920'); await sleep(400); await waitText('图片 Coastline.png');
  await screenshot('portrait-player', 1080, 1920);
  await activate('更多', 22); await activate('退出'); await waitText('4 项'); await key(4); await waitText('9 项');
  await screenshot('portrait-browser', 1080, 1920);
  await activate('开始轮播', 19); await screenshot('portrait-setup', 1080, 1920); await key(4);
  await run('shell', 'wm', 'size', 'reset'); await run('shell', 'cmd', 'uimode', 'night', 'yes');
  await waitText('9 项'); await screenshot('night-browser', 1920, 1080);
  await activate('开始轮播', 19); await screenshot('night-setup', 1920, 1080); await key(4);
  if (automatic) {
    await activate('更多', 19); await activate('设置'); await activate('应用更新');
    if ((await focus('自动检测更新（每次启动）')).checked !== 'true') await key(23);
    await key(4); await key(4);
  }
  console.log('PASS real light/dark and portrait/landscape screenshots from synthetic showcase only');
} finally {
  await run('shell', 'wm', 'size', 'reset'); await run('shell', 'cmd', 'uimode', 'night', night);
}
