// Mechanical extraction of dependency attribution; generated output is included in the APK.
import { readFile, writeFile, mkdir, readdir, mkdtemp, rm } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const input = JSON.parse(await readFile(new URL('../app/build/notices-input.json', import.meta.url), 'utf8'));
const assets = new URL('../app/src/main/assets/', import.meta.url); await mkdir(assets, { recursive: true });
const texts = new Map();
function keep(label, text) {
  if (!text.trim()) return;
  const hash = createHash('sha256').update(text.trim()).digest('hex');
  const previous = texts.get(hash);
  if (previous) previous.labels.push(label); else texts.set(hash, { labels: [label], text: text.trim() });
}
keep('Apache-2.0 license (Gradle distribution)', await readFile(input.gradleLicense, 'utf8'));
async function filesIn(directory) {
  const result = [];
  for (const hash of await readdir(directory, { withFileTypes: true })) if (hash.isDirectory()) for (const file of await readdir(join(directory, hash.name))) result.push(join(directory, hash.name, file));
  return result;
}
function zipNotices(file, label) {
  const names = execFileSync('unzip', ['-Z1', file], { encoding: 'utf8' }).split('\n');
  for (const name of names.filter(n => /(^|\/)(license|notice|copying)[^/]*$/i.test(n) && !n.endsWith('/'))) {
    keep(`${label}: ${name}`, execFileSync('unzip', ['-p', file, name], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }));
  }
  return names;
}
const inventory = [];
const temporary = await mkdtemp(join(tmpdir(), 'openlist-notices-'));
try {
  for (const dep of input.dependencies) {
    const files = await filesIn(dep.cacheDir);
    const pomPath = files.find(f => f.endsWith('.pom'));
    const pom = pomPath ? await readFile(pomPath, 'utf8') : '';
    const licenseSection = pom.match(/<licenses>([\s\S]*?)<\/licenses>/)?.[1] || '';
    const licenses = [...licenseSection.matchAll(/<name>([^<]+)<\/name>/g)].map(m => m[1]);
    const isDesugar = dep.coordinate.startsWith('com.android.tools:desugar_jdk_libs');
    // These resolved families are Apache-2.0; inherited POM / multiplatform aliases
    // may omit a local <licenses> declaration. Fail on any new unreviewed family.
    if (!isDesugar && !/^(androidx\.|org\.jetbrains|org\.jspecify:|com\.squareup\.|com\.google\.guava:)/.test(dep.coordinate)) throw new Error(`Review license family: ${dep.coordinate}`);
    inventory.push(`${dep.coordinate} — ${licenses.join('; ') || (isDesugar ? 'GPL-2.0 with Classpath Exception' : 'Apache-2.0 (project/inherited declaration)')}`);
    for (const file of files.filter(f => /\.(jar|aar)$/.test(f))) {
      const names = zipNotices(file, dep.coordinate);
      if (file.endsWith('.aar') && names.includes('classes.jar')) {
        const inner = join(temporary, 'classes.jar');
        await writeFile(inner, execFileSync('unzip', ['-p', file, 'classes.jar'], { maxBuffer: 32 * 1024 * 1024 }));
        zipNotices(inner, dep.coordinate); await rm(inner);
      }
    }
  }
} finally { await rm(temporary, { recursive: true }); }
const revision = '73170c345e6a762fc6a1f0301bb15218850023ef';
for (const name of ['LICENSE', 'ASSEMBLY_EXCEPTION', 'ADDITIONAL_LICENSE_INFO']) {
  const url = `https://raw.githubusercontent.com/google/desugar_jdk_libs/${revision}/${name}`;
  const response = await fetch(url, { signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new Error(`Cannot obtain upstream notice: ${name}`);
  keep(`desugar_jdk_libs 2.1.5: ${name}\n${url}`, await response.text());
}
const source = `https://github.com/google/desugar_jdk_libs/tree/${revision}`;
const text = ['OpenList TV — third-party notices', 'Resolved runtime/build-support components; R8 may package only a subset. Original project licensing is separate.', '', ...inventory, '', `Desugared OpenJDK source snapshot: ${source}`, 'Unmodified upstream sources and build scripts are also offered as a separate source archive alongside v1.0.0.', 'Desugaring/minification toolchain: AGP 9.4.0, Gradle 9.6.0, desugar_jdk_libs 2.1.5.', '', ...[...texts.values()].map(v => `\n${'='.repeat(72)}\n${v.labels.join('\n')}\n\n${v.text}`)].join('\n') + '\n';
await writeFile(new URL('third-party-notices.txt', assets), text);
await writeFile(new URL('../docs/THIRD_PARTY_NOTICES.md', import.meta.url), `# 第三方组件\n\n完整许可与原始 NOTICE 随 APK 打包，可在“设置 → 第三方许可”查看，也见 [许可文本](../app/src/main/assets/third-party-notices.txt)。以下为解析到的运行时 / 构建辅助依赖，R8 可能仅打包其中子集。\n\n${inventory.map(v => '- ' + v).join('\n')}\n\n用于兼容旧安卓的 desugar_jdk_libs 2.1.5 使用 GPL v2 + Classpath Exception。[对应上游源码及构建脚本](${source})另随 Release 提供源码归档。未修改库源码；由上述固定工具链进行 desugar / R8 处理。本项目自有代码的授权状态见 [NOTICE](../NOTICE.md)。\n`);
console.log(JSON.stringify({ dependencies: inventory.length, uniqueNotices: texts.size, bytes: Buffer.byteLength(text), sourceRevision: revision }));
