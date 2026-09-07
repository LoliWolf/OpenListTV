import assert from 'node:assert/strict';
import { app, run, key, focus, nodes, waitText, requireEmulator, launch, setNumber } from './tv-test-driver.mjs';
await requireEmulator();
try {
  await run('shell', 'wm', 'size', 'reset'); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await focus('搜索 / 筛选', 19); await key(23); await setNumber('当前目录文件名搜索', 'PNG');
  await focus('图片'); await key(23); await focus('应用'); await key(23); await waitText('2 项');
  const pid = (await run('shell', 'pidof', app)).trim();
  await run('shell', 'wm', 'size', '1080x1920'); await waitText('当前目录 · 搜索：PNG'); await waitText('2 项');
  assert.equal((await run('shell', 'pidof', app)).trim(), pid);
  for (const item of (await nodes()).filter(n => n.class === 'android.widget.Button')) {
    const [l,t,r,b] = item.bounds.match(/\d+/g).map(Number); assert.ok(l>=0 && t>=0 && r<=1080 && b<=1920, item.text);
  }
  await focus('打开 2.png'); await key(23); await waitText('图片 2.png'); await key(127);
  await focus('播放信息'); await key(23); await waitText('显示位图'); await key(4);
  await run('shell', 'wm', 'size', 'reset'); await waitText('图片 2.png');
  assert.equal((await run('shell', 'pidof', app)).trim(), pid); assert.ok((await nodes()).some(n => n.text === '继续'));
  await focus('退出'); await key(23); await waitText('2 项');
  console.log('PASS portrait/landscape reflow retains query, process, current image and pause; controls and information dialog remain usable');
} finally { await run('shell', 'wm', 'size', 'reset'); }
