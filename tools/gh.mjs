// Use the user's existing GitHub Git credential without printing or saving the token.
import { spawn, spawnSync } from 'node:child_process';
const env = { ...process.env, GH_HOST: 'github.com', GIT_TERMINAL_PROMPT: '0' };
if (!env.GH_TOKEN && !env.GITHUB_TOKEN) {
  const result = spawnSync('git', ['credential', 'fill'], { env, input: 'protocol=https\nhost=github.com\n\n', encoding: 'utf8', timeout: 15000 });
  const fields = Object.fromEntries((result.stdout || '').trim().split('\n').map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)]; }));
  if (result.status !== 0 || !fields.password) throw new Error('No GitHub credential available. Authenticate gh or configure Git credentials first.');
  env.GH_TOKEN = fields.password;
}
const child = spawn('gh', process.argv.slice(2), { env, stdio: 'inherit' });
child.on('error', () => { console.error('GitHub CLI is unavailable'); process.exitCode = 1; });
child.on('close', code => { process.exitCode = code ?? 1; });
