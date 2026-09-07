// Dedicated emulator only; never installs the higher-version fixture APK.
import assert from 'node:assert/strict';
import {app,run,requireEmulator} from './tv-test-driver.mjs';
await requireEmulator();
const previous=(await run('shell','appops','get',app,'REQUEST_INSTALL_PACKAGES')).match(/REQUEST_INSTALL_PACKAGES: (\w+)/)?.[1] || 'default';
async function tests(target, count){
 const output=await run('shell','am','instrument','-w','-e','class',target,`${app}.test/androidx.test.runner.AndroidJUnitRunner`);
 console.log(output); assert.ok(output.includes(`OK (${count} test`), 'Instrumentation did not complete successfully');
}
try {
 await run('shell','appops','set',app,'REQUEST_INSTALL_PACKAGES','default');
 await tests(`${app}.UpdateFlowTest#downloadAutomaticallyRequestsSystemInstallerButNeverConfirmsInstallation`,1);
 await run('shell','appops','set',app,'REQUEST_INSTALL_PACKAGES','allow');
 await tests(`${app}.UpdateFlowTest`,8);
} finally { await run('shell','appops','set',app,'REQUEST_INSTALL_PACKAGES',previous); }
console.log('PASS installer permission denied/granted/return paths; original AppOp restored after instrumentation');
