// Verify layout reflow keeps directory and paused media; local showcase fixture only.
import assert from 'node:assert/strict';
import { app, run, key, focus, nodes, waitText, requireEmulator, launch, sleep } from './tv-test-driver.mjs';
await requireEmulator();
try {
  await run('shell', 'wm', 'size', 'reset'); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
  await focus('打开 Photos'); await key(23); await waitText('4 项');
  const pid = (await run('shell', 'pidof', app)).trim();
  await run('shell', 'wm', 'size', '1080x1920'); await waitText('demo · /Photos'); await waitText('4 项');
  assert.equal((await run('shell', 'pidof', app)).trim(), pid);
  await focus('打开 Coastline.png'); await key(23); await waitText('图片 Coastline.png'); await key(127);
  await run('shell', 'wm', 'size', 'reset'); await waitText('图片 Coastline.png');
  assert.equal((await run('shell', 'pidof', app)).trim(), pid);
  await key(19); await focus('退出'); await key(23); await waitText('demo · /Photos');
  await focus('开始轮播', 19); await key(23); await focus('开始'); await key(23); await sleep(600); await key(127); await waitText('图片 Coastline.png');
  await run('shell', 'wm', 'size', '1080x1920'); await waitText('图片 Coastline.png');
  const buttons = (await nodes()).filter(n => n.class === 'android.widget.Button');
  for (const button of buttons) { const b = button.bounds.match(/\d+/g).map(Number); assert.ok(b[0] >= 0 && b[2] <= 1080 && b[1] >= 0 && b[3] <= 1920); }
  assert.ok(buttons.some(n => n.text === '继续'), 'Rotation unexpectedly resumed the paused slideshow');
  await focus('退出'); await key(23); await waitText('demo · /Photos');
  console.log('PASS portrait/landscape: directory and process preserved, current image preserved, paused slideshow stays paused, all player controls remain on screen');
} finally { await run('shell', 'wm', 'size', 'reset'); }
