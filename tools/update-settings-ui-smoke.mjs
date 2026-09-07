import assert from 'node:assert/strict';
import {app,run,key,focus,nodes,waitText,launch,requireEmulator,setNumber} from './tv-test-driver.mjs';
await requireEmulator(); await run('shell','am','force-stop',app); await launch(); await waitText('9 项');
await focus('设置',19); await key(23);
async function toggle(label,desired){const n=await focus(label);if((n.checked==='true')!==desired)await key(23);}
await toggle('自动检测更新（每次启动）',false); await toggle('参与测试版本',true);
await focus('配置更新代理'); await key(23);
await setNumber('更新代理地址（留空为直连）','http://127.0.0.1:9'); await focus('保存'); await key(23);
await focus('检查 GitHub 更新',19); await key(23); await waitText('检查更新失败'); await key(4);
await focus('返回文件浏览'); await key(23); await waitText('9 项');
await focus('打开 2.png'); await key(23); await waitText('图片 2.png'); await key(4); await key(4);
console.log('PASS unreachable update-only proxy cannot break OpenList listing or media');
await run('shell','am','force-stop',app); await launch(); await waitText('9 项'); await focus('设置',19); await key(23);
let list=await nodes(); assert.ok(list.some(n=>n.text==='自动检测更新（每次启动）'&&n.checked==='false')); assert.ok(list.some(n=>n.text==='参与测试版本'&&n.checked==='true'));
await waitText('http://127.0.0.1:9');
await focus('配置更新代理'); await key(23); await focus('清除代理'); await key(23); await waitText('未配置（直连）');
await toggle('参与测试版本',false); await focus('检查 GitHub 更新',19); await key(23); await waitText('没有可用更新'); await key(4);
await toggle('自动检测更新（每次启动）',true);
console.log('PASS switches and proxy persist, proxy can be cleared, manual GitHub release-list check works');
