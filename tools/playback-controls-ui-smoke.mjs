import assert from 'node:assert/strict';
import {app,run,key,focus,nodes,waitText,requireEmulator,launch} from './tv-test-driver.mjs';
await requireEmulator();
try {
 await run('shell','wm','size','reset');await run('shell','am','force-stop',app);await launch();await waitText('9 项');
 await focus('打开 2.png');await key(23);await waitText('图片 2.png');await key(127);
 await focus('画面设置');await key(23);await waitText('图片缩放 / 旋转');
 await focus('放大 ＋');await key(23);await key(23);await focus('向右旋转');await key(23);await waitText('旋转 90°');
 await focus('移动画面');await key(23);await waitText('方向键移动');await key(20);await key(22);await waitText('图片 2.png');
 const pid=(await run('shell','pidof',app)).trim();
 await run('shell','wm','size','1080x1920');await waitText('旋转 90°');await waitText('图片 2.png');
 assert.equal((await run('shell','pidof',app)).trim(),pid);
 await key(4);await focus('画面设置');await key(23);await focus('恢复原图');await key(23);await waitText('缩放 1.00× / 旋转 0°');
 await focus('完成');await key(23);await focus('播放信息');await key(23);await waitText('手动画面：1.00× / 0°');await key(4);
 await focus('退出');await key(23);await waitText('9 项');
 console.log('PASS remote zoom/rotation/pan/reset, portrait reflow and original file focus; no image advance while panning');
} finally {await run('shell','wm','size','reset');}
