const http = require('node:http');
const https = require('node:https');

const REPO = 'sevencnup/wotty-kotierapp';
const PORT = 8080;
const CACHE_TTL_MS = 60_000;
let cache = null;

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function fetchLatestRelease() {
  return new Promise((resolve, reject) => {
    const request = https.request({
      hostname: 'api.github.com',
      path: `/repos/${REPO}/releases/latest`,
      method: 'GET',
      headers: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'kotier-update-api',
      },
      timeout: 10_000,
    }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => {
        if (response.statusCode !== 200) {
          reject(new Error(`GitHub API HTTP ${response.statusCode}`));
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch (error) {
          reject(error);
        }
      });
    });
    request.on('timeout', () => request.destroy(new Error('GitHub API timeout')));
    request.on('error', reject);
    request.end();
  });
}

async function buildVersionInfo() {
  const release = await fetchLatestRelease();
  const apk = (release.assets || []).find((asset) =>
    typeof asset.name === 'string' && asset.name.toLowerCase().endsWith('.apk')
  );
  if (!release.tag_name || !apk?.browser_download_url) {
    throw new Error('Latest GitHub Release has no APK asset');
  }
  return {
    latest_version: String(release.tag_name).replace(/^v/i, ''),
    download_url: apk.browser_download_url,
    release_notes: typeof release.body === 'string' ? release.body : '',
  };
}

async function handleVersion(res) {
  const now = Date.now();
  if (cache && now - cache.time < CACHE_TTL_MS) {
    sendJson(res, 200, cache.value);
    return;
  }
  try {
    const value = await buildVersionInfo();
    cache = { time: now, value };
    sendJson(res, 200, value);
  } catch (error) {
    console.error(error.message);
    if (cache) {
      sendJson(res, 200, cache.value);
      return;
    }
    sendJson(res, 503, { error: '暂时无法获取 GitHub Release 更新信息' });
  }
}

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'GET' && url.pathname === '/health') {
    sendJson(res, 200, { status: 'ok' });
    return;
  }
  if (req.method === 'GET' && url.pathname === '/version.json') {
    void handleVersion(res);
    return;
  }
  sendJson(res, 404, { error: 'Not Found' });
}).listen(PORT, '0.0.0.0', () => {
  console.log(`kotier update API listening on ${PORT} for ${REPO}`);
});
