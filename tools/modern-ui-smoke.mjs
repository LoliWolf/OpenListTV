// Local fixture / dedicated emulator only. No private server, destructive reset or publication.
import assert from 'node:assert/strict';
import {mkdir, writeFile} from 'node:fs/promises';
import {app, adb, serial, exec, run, key, focus, nodes, waitText, launch, requireEmulator, setNumber, sleep} from './tv-test-driver.mjs';
await requireEmulator();
const output = new URL('../artifacts/modern-ui/', import.meta.url);
await mkdir(output, {recursive: true});
async function activate(label, direction = 20) { await focus(label, direction); await key(23); }
async function more(label) { await activate('更多', 19); await waitText('资源库操作'); await activate(label); }
async function setup() { await activate('开始轮播', 19); await waitText('播放节奏'); }
async function screenshot(name) {
  const result = await exec(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], {encoding: 'buffer', maxBuffer: 16 * 1024 * 1024});
  await writeFile(new URL(`${name}.png`, output), result.stdout);
}
async function insideScreen() {
  const size = (await run('shell', 'wm', 'size')).match(/(?:Override|Physical) size: (\d+)x(\d+)/g).at(-1).match(/(\d+)x(\d+)/);
  for (const n of (await nodes()).filter(n => n.focusable === 'true')) {
    const [l, t, r, b] = n.bounds.match(/\d+/g).map(Number);
    assert.ok(l >= 0 && t >= 0 && r <= +size[1] && b <= +size[2], `Off-screen target: ${n.text || n['content-desc']}`);
  }
}
try {
  await run('shell', 'wm', 'size', 'reset');
  await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  if (!process.argv.includes('--player-only')) {
  const toolbar = (await nodes()).filter(n => n.class === 'android.widget.Button' && ['上一级', '搜索', '轮播', '更多'].includes(n.text));
  assert.equal(toolbar.length, 4);
  await screenshot('landscape-browser');
  await more('排序'); await activate('排序依据'); await waitText('服务器顺序'); await key(4); await key(4);
  await activate('搜索 / 筛选', 19); await activate('文件类型'); await activate('图片');
  await setNumber('当前目录文件名搜索', 10); await activate('应用'); await waitText('1 项'); await waitText('打开 10.png');
  await activate('搜索 / 筛选', 19); await activate('清除'); await waitText('9 项');
  console.log('PASS compact browser, More sorting, nested search/type filter and clear');

  await setup();
  const seconds = (await nodes()).find(n => /^\d+ 秒$/.test(n.text))?.text;
  assert.ok(seconds);
  assert.equal((await nodes()).filter(n => n.class === 'android.widget.EditText').length, 0);
  await screenshot('landscape-slideshow-setup');
  await activate('图片停留时间'); await setNumber('图片停留秒数（1–3600）', +seconds.split(' ')[0] === 9 ? 10 : 9);
  await activate('应用'); await key(4); // Cancel complete draft.
  await setup(); assert.ok((await nodes()).some(n => n.text === seconds), 'Cancelled draft was persisted');
  await activate('索引与高级选项'); await waitText('自动刷新索引'); await insideScreen();
  const interval = (await focus('自动刷新索引（0 关闭，5–1440 分钟）')).text;
  await setNumber('自动刷新索引（0 关闭，5–1440 分钟）', interval === '5' ? 10 : 5);
  const pid = (await run('shell', 'pidof', app)).trim();
  await run('shell', 'wm', 'size', '1080x1920'); await sleep(500); await insideScreen();
  assert.equal((await run('shell', 'pidof', app)).trim(), pid);
  await screenshot('portrait-advanced');
  await key(4); await activate('索引与高级选项');
  assert.equal((await focus('自动刷新索引（0 关闭，5–1440 分钟）')).text, interval, 'Cancelled advanced settings were committed');
  await key(4); await screenshot('portrait-slideshow-setup');
  await key(4); await run('shell', 'wm', 'size', 'reset'); await waitText('9 项');
  console.log('PASS disclosed settings, labelled numeric editors, transactional cancel, portrait dialog reflow');

  await more('设置'); await waitText('关于 OpenList TV');
  assert.equal((await nodes()).filter(n => n.class === 'android.widget.Switch').length, 0);
  await screenshot('landscape-settings');
  await activate('应用更新'); await waitText('更新偏好');
  for (const label of ['自动检测更新（每次启动）', '参与测试版本', '使用浏览器下载']) assert.ok((await focus(label)).checkable === 'true');
  await activate('配置更新代理'); await waitText('仅更新使用的代理'); await key(4); await key(4);
  await activate('存储与缓存'); await activate('清理资源索引与图片缓存'); await waitText('清理缓存？'); await key(4);
  await key(4); await waitText('9 项');
  console.log('PASS grouped settings, all update controls/proxy reachable, destructive action has confirmation');
  }

  await setup(); await activate('开始'); await sleep(1200); await key(127);
  const buttons = (await nodes()).filter(n => n.class === 'android.widget.Button');
  assert.equal(buttons.length, 4); assert.ok(buttons.some(n => n.text === '继续'));
  await screenshot('landscape-player');
  await activate('更多', 22); await activate('播放信息'); await waitText('位图内存'); await key(4);
  await activate('更多', 22); await activate('画面设置'); await waitText('图片缩放 / 旋转'); await activate('完成');
  await run('shell', 'wm', 'size', '1080x1920'); await sleep(400); await insideScreen(); await screenshot('portrait-player');
  await run('shell', 'wm', 'size', '720x1280'); await sleep(400); await waitText('播放画面：确认暂停，方向键控制，返回退出');
  await insideScreen();
  assert.ok((await nodes()).some(n => n.class === 'android.widget.Button' && n.text === '继续'), 'Resize lost paused playback');
  await screenshot('narrow-player');
  await run('shell', 'wm', 'size', '1080x1920'); await sleep(400);
  await activate('更多', 22); await activate('退出'); await waitText('9 项'); await screenshot('portrait-browser');
  console.log('PASS four-control player, disclosed information/image tools, portrait focus bounds and return');
} finally { await run('shell', 'wm', 'size', 'reset'); }
