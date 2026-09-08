import assert from 'node:assert/strict';
import {app,run,key,focus,nodes,waitText,launch,requireEmulator,setNumber} from './tv-test-driver.mjs';
const apk=process.argv[2]; if(!apk)throw new Error('Pass the signed preview APK');
await requireEmulator();
async function settings(){await run('shell','am','force-stop',app);await launch();await waitText('9 项');await focus('设置',19);await key(23);}
async function toggle(label,value){const n=await focus(label);if((n.checked==='true')!==value)await key(23);}
await settings();await toggle('自动检测更新（每次启动）',false);await toggle('参与测试版本',true);
await toggle('使用浏览器下载',true);
await focus('配置更新代理');await key(23);await setNumber('更新代理地址（留空为直连）','http://127.0.0.1:9');await focus('保存');await key(23);
async function snapshot(){const list=await nodes();return list.filter(n=>n.class==='android.widget.Switch').map(n=>({text:n.text,checked:n.checked}));}
const before=await snapshot();await waitText('http://127.0.0.1:9');
console.log(await run('install','-r',apk));await settings();assert.deepEqual(await snapshot(),before);await waitText('http://127.0.0.1:9');
await focus('配置更新代理');await key(23);await focus('清除代理');await key(23);await toggle('参与测试版本',false);await toggle('自动检测更新（每次启动）',true);
await toggle('使用浏览器下载',false);
console.log('PASS signed overwrite retains automatic/test/browser switches and update-only proxy; synthetic settings restored afterwards');
