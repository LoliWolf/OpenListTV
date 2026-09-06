// macOS one-time migration: preserve the installed certificate, protect its storage.
// Does not replace the source key, print passwords, or upload keys to GitHub.
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash, randomBytes } from 'node:crypto';
if (process.platform !== 'darwin') throw new Error('Use externally supplied release-signing environment variables on other systems.');
const service = 'io.github.loliwolf.openlisttv.release';
const account = 'OpenListTV';
const directory = join(homedir(), 'Library', 'Application Support', 'OpenListTV', 'signing');
const target = join(directory, 'release.p12');
const source = resolve(process.argv[2] || join(homedir(), '.android', 'debug.keystore'));
const expected = readFileSync(new URL('../gradle/alpha-certificate.sha256', import.meta.url), 'utf8').trim();
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { ...options, maxBuffer: 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${command} failed; no signing passwords are logged`);
  return result.stdout;
}
function fingerprint(file, alias, password) {
  const cert = run('keytool', ['-exportcert', '-keystore', file, '-alias', alias, '-storepass:env', 'OPENLIST_KEYTOOL_PASSWORD'], { env: { ...process.env, OPENLIST_KEYTOOL_PASSWORD: password } });
  return createHash('sha256').update(cert).digest('hex');
}
let password;
const saved = spawnSync('security', ['find-generic-password', '-a', account, '-s', service, '-w'], { encoding: 'utf8' });
if (saved.status === 0) password = saved.stdout.trim();
if (existsSync(target)) {
  if (!password || fingerprint(target, 'openlisttv', password) !== expected) throw new Error('Existing release store cannot be verified; restore it manually, never overwrite it.');
} else {
  if (!existsSync(source) || fingerprint(source, 'androiddebugkey', 'android') !== expected) throw new Error('The source is not the original installed alpha signing identity.');
  if (!password) {
    password = randomBytes(32).toString('base64url');
    // Interactive command input keeps the secret out of process arguments and shell history.
    run('security', ['-i'], { input: `add-generic-password -a ${account} -s ${service} -w ${password}\n`, encoding: 'utf8' });
    const verify = run('security', ['find-generic-password', '-a', account, '-s', service, '-w'], { encoding: 'utf8' }).trim();
    if (verify !== password) throw new Error('Keychain write could not be verified');
  }
  mkdirSync(directory, { recursive: true, mode: 0o700 }); chmodSync(directory, 0o700);
  run('keytool', ['-importkeystore', '-srckeystore', source, '-srcalias', 'androiddebugkey', '-srcstorepass:env', 'OPENLIST_OLD_STORE_PASSWORD', '-srckeypass:env', 'OPENLIST_OLD_STORE_PASSWORD', '-destkeystore', target, '-destalias', 'openlisttv', '-deststoretype', 'PKCS12', '-deststorepass:env', 'OPENLIST_NEW_STORE_PASSWORD', '-destkeypass:env', 'OPENLIST_NEW_STORE_PASSWORD', '-noprompt'], { env: { ...process.env, OPENLIST_OLD_STORE_PASSWORD: 'android', OPENLIST_NEW_STORE_PASSWORD: password } });
  chmodSync(target, 0o600);
  if (fingerprint(target, 'openlisttv', password) !== expected) throw new Error('Imported signing identity did not match');
}
console.log(JSON.stringify({ keyStore: target, keyAlias: 'openlisttv', certificateSha256: expected, passwordStorage: 'macOS Keychain; no password file in repository' }));
