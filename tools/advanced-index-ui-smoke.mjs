// Dedicated fixture emulator only. Optional APK verifies signed overwrite, never uninstall.
import assert from 'node:assert/strict';
import {app,run,key,focus,nodes,waitText,launch,requireEmulator,setNumber,metrics,sleep} from './tv-test-driver.mjs';
await requireEmulator();
await run('shell','am','force-stop',app); await launch(); await waitText('9 项');
const reuse='复用轮播索引（6 小时）', force='本次重新扫描（忽略旧快照）', duration='图片停留秒数（1–3600）';
async function dialog(){await focus('开始轮播',19);await key(23);await waitText('播放方式');}
async function toggle(label,value){const n=await focus(label);if((n.checked==='true')!==value)await key(23);}
async function startAndStop(){
 await focus('开始');await key(23);await sleep(1800);await key(127);await key(4);await key(4);await waitText('9 项');
}
await dialog();
const mode=(await nodes()).find(n=>n.class==='android.widget.RadioButton'&&n.checked==='true')?.text;
assert.ok(mode);
const previous={mode,recursive:(await focus('包含子目录')).checked==='true',images:(await focus('仅图片')).checked==='true',reuse:(await focus(reuse)).checked==='true',duration:(await focus(duration)).text};
await focus('超大库公平抽样',19);await key(23);
await toggle('包含子目录',false);await toggle('仅图片',true);await toggle(reuse,true);await toggle(force,true);await setNumber(duration,1);
await startAndStop();
const afterCold=(await metrics()).listRequests;
if(process.argv[2])console.log(await run('install','-r',process.argv[2]));
await run('shell','am','force-stop',app);await launch();await waitText('9 项');await dialog();
assert.equal((await focus('超大库公平抽样',19)).checked,'true');
assert.equal((await focus(reuse)).checked,'true');assert.equal((await focus(force)).checked,'false');
await startAndStop();
assert.equal((await metrics()).listRequests,afterCold,'Warm snapshot should not request directory lists again');
console.log('PASS D-pad sampling/reuse/one-shot rescan; restart/overwrite preserves choices and avoids list re-scan');
await dialog();await focus(previous.mode,19);await key(23);
await toggle('包含子目录',previous.recursive);await toggle('仅图片',previous.images);await toggle(reuse,previous.reuse);await setNumber(duration,previous.duration);
await startAndStop();
console.log('PASS original fixture slideshow preferences restored');
