// Dedicated TV emulator, synthetic localhost fixture only. No pm clear or uninstall.
import assert from 'node:assert/strict';
import { app, run, key, focus, nodes, waitText, requireEmulator, launch, setNumber } from './tv-test-driver.mjs';
await requireEmulator(); await run('shell', 'wm', 'size', 'reset'); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
async function filter(text, type) {
  await focus('搜索 / 筛选', 19); await key(23);
  await setNumber('当前目录文件名搜索', text);
  await focus(type); await key(23); await focus('应用'); await key(23);
}
await filter('PNG', '图片'); await waitText('2 项');
let list = await nodes(); assert.ok(!list.some(n => n['content-desc'] === '打开 Photos'));
await focus('打开 2.png'); await key(23); await waitText('图片 2.png');
await key(19); await focus('播放信息'); await key(23); await waitText('显示位图');
list = await nodes(); assert.ok(!list.some(n => n.text.includes('fixture-sign') || n.text.includes('http://')));
await key(4); await focus('下一项'); await key(23); await waitText('图片 10.png');
await key(4); await key(4); await waitText('2 项');
console.log('PASS current-directory search/type intersection and filtered player next-item, sanitized playback snapshot');
await filter('no-matching-file', '全部类型'); await waitText('没有匹配的文件');
await focus('清除搜索与筛选'); await key(23); await waitText('9 项');
await focus('打开 Lots'); await key(23); await waitText('1001 项');
await filter('1', '图片'); await waitText('273 项');
// File rows reserve Up/Down for continuous scrolling; Left/Right are page shortcuts.
await key(22); await waitText('第 2 页');
await key(4); await waitText('9 项');
console.log('PASS no-result clear action, whole-directory filtered count and bounded pagination, no recursive search');
await focus('打开 2.png'); await key(82); await focus('下载文件'); await key(23);
await focus('开始下载'); await key(23); await waitText('本机下载'); await waitText('已完成');
await focus('下载文件 2.png'); await key(23); await focus('离线打开'); await key(23); await waitText('图片 2.png');
await key(4); await key(4); await waitText('本机下载');
await focus('返回文件浏览', 19); await key(23); await waitText('9 项');
console.log('PASS remote file menu download, persistent local record and offline image player');
await focus('开始轮播', 19); await key(23);
await setNumber('定时停止轮播（0 表示关闭）', 7);
await focus('开始'); await key(23);
// Like slideshow-smoke: pause before UIAutomator idle polling. Live progress updates
// can otherwise consume the five-second overlay timeout while dumping the hierarchy.
await key(127);
await key(19); await focus('定时停止'); await key(23);
await setNumber('从现在起停止轮播（0–1440 分钟）', 1);
await focus('应用'); await key(23); await focus('播放信息'); await key(23); await waitText('轮播将在约 1 分钟后停止');
await key(4); await focus('退出'); await key(23); await waitText('9 项');
console.log('PASS labeled stop-timer setting and in-session adjustment; initial 7-minute preference kept for upgrade check');
