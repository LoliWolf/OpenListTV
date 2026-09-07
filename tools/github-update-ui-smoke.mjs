// Public GitHub check via actual settings UI, with no automatic installation.
import assert from 'node:assert/strict';
import { app, run, key, focus, waitText, requireEmulator, launch, nodes } from './tv-test-driver.mjs';
await requireEmulator(); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
await focus('设置', 19); await key(23); await focus('检查 GitHub 更新'); await key(23);
const list = await waitText('没有可用更新');
assert.ok(list.some(n => /1\.0\.1-preview\.\d+/.test(n.text) && n.text.includes('1.0.0')));
await focus('确定'); await key(23);
assert.equal((await focus('检查 GitHub 更新')).enabled, 'true');
console.log('PASS actual GitHub latest stable 1.0.0 compared with local preview; settings check completes without offering a downgrade');
