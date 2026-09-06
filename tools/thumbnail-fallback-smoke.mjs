// Fixture /Photos/4K.png has a 404 thumb; Large6000 and Nested/3 omit thumb.
import assert from 'node:assert/strict';
import { app, run, key, focus, waitText, requireEmulator, launch, metrics, sleep } from './tv-test-driver.mjs';
await requireEmulator(); await run('shell', 'am', 'force-stop', app); await launch(); await waitText('9 项');
await focus('打开 Photos'); const before = await metrics(); await key(23); await waitText('demo · /Photos');
await waitText('缩略图 4K.png'); await waitText('缩略图 Large6000.png');
assert.equal((await metrics()).mediaRequests - before.mediaRequests, 2);
await sleep(1000); assert.equal((await metrics()).mediaRequests - before.mediaRequests, 2, 'Original fallback keeps re-fetching');
console.log('PASS visible missing/404 previews use two bounded original fallbacks, with no retry loop');
await focus('打开 Nested', 19); await key(23); await waitText('缩略图 3.png');
assert.equal((await metrics()).mediaRequests - before.mediaRequests, 3);
assert.equal((await metrics()).credentialLeaks, 0);
console.log('PASS missing server thumb is generated on the list without opening the image; no media credentials leaked');
