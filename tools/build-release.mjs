// Keep signing credentials in environment/Keychain, never Gradle arguments or Git.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
const env = { ...process.env };
if (!env.OPENLIST_RELEASE_STORE_FILE && process.platform === 'darwin') {
  env.OPENLIST_RELEASE_STORE_FILE = join(homedir(), 'Library', 'Application Support', 'OpenListTV', 'signing', 'release.p12');
  env.OPENLIST_RELEASE_KEY_ALIAS = 'openlisttv';
  const saved = spawnSync('security', ['find-generic-password', '-a', 'OpenListTV', '-s', 'io.github.loliwolf.openlisttv.release', '-w'], { encoding: 'utf8' });
  if (saved.status === 0) env.OPENLIST_RELEASE_STORE_PASSWORD = saved.stdout.trim();
}
if (!env.OPENLIST_RELEASE_STORE_FILE || !existsSync(env.OPENLIST_RELEASE_STORE_FILE) || !env.OPENLIST_RELEASE_STORE_PASSWORD) throw new Error('Release signing is missing. Restore the original store or run the documented one-time signing setup.');
env.OPENLIST_RELEASE_KEY_PASSWORD ||= env.OPENLIST_RELEASE_STORE_PASSWORD;
const args = process.argv.slice(2);
if (args.includes('--debug')) throw new Error('Do not enable Gradle debug logging in a signed release build');
const child = spawn(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [':app:assembleRelease', '--no-daemon', '--no-configuration-cache', ...args], { env, stdio: 'inherit' });
child.on('close', code => { process.exitCode = code ?? 1; });
child.on('error', () => { console.error('Cannot start Gradle'); process.exitCode = 1; });
