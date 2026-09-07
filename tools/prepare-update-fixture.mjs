// Builds a private validation archive; does not install it or access GitHub.
import {execFileSync} from 'node:child_process';
import {existsSync,readFileSync,mkdirSync,copyFileSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
if (!existsSync(join(homedir(),'.android','debug.keystore'))) throw new Error('Restore the original compatible keystore; do not generate a new identity');
if (!process.env.ANDROID_HOME) throw new Error('Configure ANDROID_HOME and JDK 17 first');
execFileSync('./gradlew',['-p','tools/update-fixture','--no-daemon','--no-configuration-cache','-q','assembleRelease'],{stdio:'inherit'});
const apk='tools/update-fixture/build/outputs/apk/release/PrivateUpdateValidationFixture-release.apk';
const output=execFileSync(join(process.env.ANDROID_HOME,'build-tools','36.0.0','apksigner'),['verify','--print-certs',apk],{encoding:'utf8',stdio:['ignore','pipe','pipe']});
if (!output.includes(`SHA-256 digest: ${readFileSync('gradle/alpha-certificate.sha256','utf8').trim()}`)) throw new Error('Fixture signing identity differs from installed app; stop');
mkdirSync('artifacts/update-fixture-assets',{recursive:true});
copyFileSync(apk,'artifacts/update-fixture-assets/update-validation.apk');
console.log('Prepared androidTest-only update archive. NEVER install or publish this fixture.');
