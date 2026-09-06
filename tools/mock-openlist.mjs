// Local-only OpenList contract fixture. Never use these credentials on a real server.
import http from 'node:http';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

const port = Number(process.env.OPENLIST_FIXTURE_PORT || 18765);
const showcase = process.env.OPENLIST_FIXTURE_SHOWCASE === '1';
const credentials = { username: 'demo', password: 'demo' };
const hash = crypto.createHash('sha256').update(`${credentials.password}-https://github.com/alist-org/alist`).digest('hex');
let tokenValid = true;
let credentialLeaks = 0;
let listRequests = 0;
let mediaRequests = 0;
let loginRequests = 0;
let thumbnailRequests = 0;
let thumbnailPaths = [];
const pngs = new Map();
function crc32(bytes) { let crc = 0xffffffff; for (const byte of bytes) { crc ^= byte; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1)); } return (crc ^ 0xffffffff) >>> 0; }
function chunk(type, body) { const label = Buffer.from(type); const out = Buffer.alloc(body.length + 12); out.writeUInt32BE(body.length, 0); label.copy(out, 4); body.copy(out, 8); out.writeUInt32BE(crc32(Buffer.concat([label, body])), body.length + 8); return out; }
function png(width, height, scene = 0) {
  const key = `${width}x${height}-${scene}`; if (pngs.has(key)) return pngs.get(key);
  const header = Buffer.alloc(13); header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 2;
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const i = y * (width * 3 + 1) + 1 + x * 3;
    let rgb = [15 + Math.floor(x / width * 45), 75 + Math.floor(y / height * 150), 150];
    if (scene) {
      const u = x / width, v = y / height;
      const palettes = [[[31, 53, 85], [247, 178, 122]], [[40, 72, 77], [194, 213, 180]], [[31, 34, 71], [171, 145, 188]], [[36, 72, 111], [210, 220, 226]]];
      const [top, bottom] = palettes[(scene - 1) % palettes.length];
      rgb = top.map((c, n) => Math.round(c + (bottom[n] - c) * Math.min(1, v * 1.65)));
      if ((u - .74) ** 2 + ((v - .27) * height / width) ** 2 < .003) rgb = [255, 225, 173];
      for (let layer = 0; layer < 3; layer++) {
        const ridge = .51 + layer * .13 + .09 * Math.sin(u * (5 + layer) + scene + layer * 2) + .035 * Math.sin(u * 19 + layer);
        if (v > ridge) rgb = [20 + layer * 5, 53 + layer * 7, 69 + layer * 9];
      }
      if (scene === 1 && v > .68) {
        const ripple = Math.sin(v * height * .19 + u * 8) * 9;
        rgb = [40 + ripple, 90 + ripple, 112 + ripple];
        if (Math.abs(u - .74) < (v - .63) * .22 && Math.sin(v * height * .2) > .15) rgb = [220, 158, 104];
      }
    }
    raw[i] = rgb[0]; raw[i + 1] = rgb[1]; raw[i + 2] = rgb[2];
  }
  const result = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]); pngs.set(key, result); return result;
}
function wav() { const sampleRate = 16000; const dataBytes = sampleRate * 2 * 12; const out = Buffer.alloc(44 + dataBytes); out.write('RIFF', 0); out.writeUInt32LE(36 + dataBytes, 4); out.write('WAVEfmt ', 8); out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22); out.writeUInt32LE(sampleRate, 24); out.writeUInt32LE(sampleRate * 2, 28); out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34); out.write('data', 36); out.writeUInt32LE(dataBytes, 40); for (let i = 0; i < dataBytes / 2; i++) out.writeInt16LE(Math.round(Math.sin(i / sampleRate * Math.PI * 2 * 220) * 1800), 44 + i * 2); return out; }
const audio = wav();
const file = (name, is_dir = false, size = 12345) => ({ name, is_dir, size, modified: '2026-09-06T04:00:00Z', sign: 'fixture-sign', extra_new_field: 'ignored' });
const lists = {
  '/': [file('Photos', true), file('Lots', true), file('Private', true), file('Empty', true), file('2.png'), file('10.png'), file('Tone.wav', false, audio.length), file('Unsupported.mp4', false, 5000000000), file('notes.txt')],
  '/Photos': [file('Nested', true), file('1.png'), file('4K.png'), file('Large6000.png')],
  '/Photos/Nested': [file('3.png')], '/Empty': [], '/Private': [file('secret.png')],
  '/Lots': Array.from({ length: 1001 }, (_, i) => file(`${1001 - i}.png`)),
};
// Original procedural demo artwork, never user photos or third-party screenshots.
if (showcase) {
  lists['/'] = [file('Photos', true), file('Albums', true), file('Music', true), file('Coastline.png', false, 2451200), file('Forest.png', false, 1815300), file('Mountains.png', false, 3081200), file('Nightfall.png', false, 2139000), file('Evening.wav', false, audio.length), file('Welcome.txt', false, 480)];
  lists['/Photos'] = [file('Coastline.png', false, 2451200), file('Forest.png', false, 1815300), file('Mountains.png', false, 3081200), file('Nightfall.png', false, 2139000)];
  lists['/Albums'] = [file('Summer', true)]; lists['/Albums/Summer'] = lists['/Photos']; lists['/Music'] = [file('Evening.wav', false, audio.length)];
}
const sceneFor = name => name.includes('Forest') ? 2 : name.includes('Nightfall') ? 3 : name.includes('Mountains') ? 4 : 1;
const json = (res, code, data = null) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code, message: code === 200 ? 'success' : 'fixture error', data })); };
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`);
  if (url.pathname === '/metrics') {
    if (url.searchParams.get('reset_thumbnails') === '1') { thumbnailRequests = 0; thumbnailPaths = []; }
    return json(res, 200, { listRequests, mediaRequests, loginRequests, credentialLeaks, thumbnailRequests, thumbnailPaths });
  }
  if (url.pathname === '/thumbnail') {
    thumbnailRequests++; thumbnailPaths.push(url.searchParams.get('path')); if (thumbnailPaths.length > 10000) thumbnailPaths.shift();
    if (req.headers.authorization || req.headers.cookie) credentialLeaks++;
    // Exercise a broken server preview on a visible item; original fallback stays bounded.
    if (url.searchParams.get('path') === '/Photos/4K.png') { res.writeHead(404); return res.end(); }
    const bytes = png(160, 100, showcase ? sceneFor(url.searchParams.get('path') || '') : 0); res.setHeader('Content-Type', 'image/png'); res.setHeader('Content-Length', bytes.length); return res.end(bytes);
  }
  if (url.pathname.startsWith('/media/')) {
    mediaRequests++; if (req.headers.authorization || req.headers.cookie) credentialLeaks++;
    const name = url.searchParams.get('name') || '';
    const bytes = name.endsWith('.wav') ? audio : name.endsWith('.mp4') ? Buffer.from('invalid media fixture') : showcase ? png(1920, 1080, sceneFor(name)) : name.includes('6000') ? png(6000, 4000) : name.includes('4K') ? png(3840, 2160) : png(960, 540);
    res.setHeader('Content-Type', name.endsWith('.wav') ? 'audio/wav' : name.endsWith('.mp4') ? 'video/mp4' : 'image/png');
    res.setHeader('Accept-Ranges', 'bytes');
    const range = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (range) { const start = Number(range[1]); const end = Math.min(range[2] ? Number(range[2]) : bytes.length - 1, bytes.length - 1); if (start >= bytes.length) { res.writeHead(416); return res.end(); } res.writeHead(206, { 'Content-Range': `bytes ${start}-${end}/${bytes.length}`, 'Content-Length': end - start + 1 }); return res.end(bytes.subarray(start, end + 1)); }
    res.setHeader('Content-Length', bytes.length); return res.end(bytes);
  }
  let body = ''; for await (const part of req) { body += part; if (body.length > 65536) { res.writeHead(413); return res.end(); } }
  let data; try { data = body ? JSON.parse(body) : {}; } catch { return json(res, 400); }
  const endpoint = url.pathname.replace(/^\/prefix/, '');
  if (endpoint === '/api/auth/login/hash') { loginRequests++; if (data.username !== credentials.username || data.password !== hash) return json(res, 401); tokenValid = true; return json(res, 200, { token: 'fixture-session' }); }
  if (req.headers.authorization !== 'fixture-session' || !tokenValid) return json(res, 401);
  if (endpoint === '/api/me') return json(res, 200, { username: 'demo', base_path: '/private-root', disabled: false });
  if (endpoint === '/api/auth/logout') { tokenValid = false; return json(res, 200); }
  if (String(data.path).startsWith('/Private') && data.password !== '1234') return json(res, 403);
  if (endpoint === '/api/fs/list') {
    listRequests++;
    const entries = lists[data.path]; if (!entries) return json(res, 404);
    if (!(data.per_page > 0) || data.refresh !== false) return json(res, 400);
    const start = (data.page - 1) * data.per_page;
    const content = entries.slice(start, start + data.per_page).map(e => ({ ...e, thumb: !e.is_dir && e.name.endsWith('.png') && e.name !== 'Large6000.png' && !(data.path === '/Photos/Nested' && e.name === '3.png') ? `/thumbnail?path=${encodeURIComponent(`${data.path === '/' ? '' : data.path}/${e.name}`)}` : '' }));
    return json(res, 200, { content, total: entries.length });
  }
  if (endpoint === '/api/fs/get') return json(res, 200, { raw_url: `http://10.0.2.2:${port}/media/file?name=${encodeURIComponent(data.path)}`, related: [] });
  return json(res, 404);
});
server.listen(port, '127.0.0.1', () => console.log(`OpenList fixture: emulator http://10.0.2.2:${port}/prefix/ ; demo/demo; directory password 1234 (local-only)`));
