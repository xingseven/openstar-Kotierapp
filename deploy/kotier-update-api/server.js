const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');

const REPO = 'sevencnup/wotty-kotierapp';
const PORT = 8080;
const CACHE_TTL_MS = 60_000;
const CACHE_DIR = process.env.KOTIER_APK_CACHE_DIR || '/data/apk';
const DEFAULT_ORIGIN = 'https://kotier.wotty.app';
const OLD_ORIGIN = 'https://kotier.openstars.org';
let cache = null;
const downloadTasks = new Map();

function versionResponse(value, req) {
  const origin = getPublicOrigin(req);
  return {
    latest_version: value.latest_version,
    latest_version_code: value.latest_version_code,
    download_url: `${origin}/download/${encodeURIComponent(value.asset_name)}`,
    source_download_url: value.source_download_url,
    release_notes: value.release_notes,
  };
}

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

function safeAssetName(name) {
  return path.basename(name).replace(/[^A-Za-z0-9._-]/g, '-');
}

function getPublicOrigin(req) {
  const host = String(req.headers.host || '').split(':')[0].toLowerCase();
  if (host === 'kotier.openstars.org') {
    return OLD_ORIGIN;
  }
  return DEFAULT_ORIGIN;
}

function getLatestReleaseInfo() {
  return fetchLatestRelease().then((release) => {
    const apkAssets = (release.assets || []).filter((asset) =>
      typeof asset.name === 'string' && asset.name.toLowerCase().endsWith('.apk')
    );
    const apk = apkAssets
      .map((asset) => ({ asset, versionCode: extractVersionCode(asset.name) }))
      .sort((a, b) => b.versionCode - a.versionCode)[0]?.asset;
    if (!release.tag_name || !apk?.browser_download_url) {
      throw new Error('Latest GitHub Release has no APK asset');
    }
    return {
      release,
      apk,
      filename: safeAssetName(apk.name),
      versionCode: extractVersionCode(apk.name),
    };
  });
}

function extractVersionCode(name) {
  const match = /-(\d+)-release\.apk$/i.exec(name);
  return match ? Number(match[1]) : -1;
}

function requestFollowingRedirects(url, redirectCount = 0) {
  if (redirectCount > 5) {
    return Promise.reject(new Error('Too many GitHub download redirects'));
  }
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    if (target.protocol !== 'https:') {
      reject(new Error('APK download URL must use HTTPS'));
      return;
    }
    const request = https.get(target, {
      headers: { 'User-Agent': 'kotier-update-api' },
      timeout: 30_000,
    }, (response) => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        response.resume();
        requestFollowingRedirects(new URL(response.headers.location, target).toString(), redirectCount + 1)
          .then(resolve, reject);
        return;
      }
      resolve(response);
    });
    request.on('timeout', () => request.destroy(new Error('GitHub APK download timeout')));
    request.on('error', reject);
  });
}

async function downloadApk(url, destination, expectedSize) {
  await fsp.mkdir(CACHE_DIR, { recursive: true });
  const partPath = `${destination}.part`;
  await fsp.rm(partPath, { force: true });
  try {
    const response = await requestFollowingRedirects(url);
    if (response.statusCode !== 200) {
      response.resume();
      throw new Error(`GitHub APK HTTP ${response.statusCode}`);
    }
    await new Promise((resolve, reject) => {
      const output = fs.createWriteStream(partPath, { flags: 'wx' });
      const fail = (error) => {
        output.destroy();
        reject(error);
      };
      response.on('error', fail);
      output.on('error', fail);
      output.on('finish', resolve);
      response.pipe(output);
    });
    const stat = await fsp.stat(partPath);
    if (Number.isSafeInteger(expectedSize) && expectedSize > 0 && stat.size !== expectedSize) {
      throw new Error(`APK size mismatch: expected ${expectedSize}, got ${stat.size}`);
    }
    await fsp.rename(partPath, destination);
  } catch (error) {
    await fsp.rm(partPath, { force: true });
    throw error;
  }
}

async function ensureCached(apk) {
  const filename = safeAssetName(apk.name);
  const destination = path.join(CACHE_DIR, filename);
  try {
    const stat = await fsp.stat(destination);
    if (stat.isFile() && stat.size > 0 &&
        (!Number.isSafeInteger(apk.size) || apk.size <= 0 || stat.size === apk.size)) {
      return destination;
    }
  } catch (error) {
    if (error.code !== 'ENOENT') {
      throw error;
    }
  }

  if (!downloadTasks.has(filename)) {
    const task = downloadApk(apk.browser_download_url, destination, apk.size)
      .finally(() => downloadTasks.delete(filename));
    downloadTasks.set(filename, task);
  }
  await downloadTasks.get(filename);
  return destination;
}

async function buildVersionInfo(req) {
  const { release, apk, filename, versionCode } = await getLatestReleaseInfo();
  await ensureCached(apk);
  const origin = getPublicOrigin(req);
  return {
    latest_version: String(release.tag_name).replace(/^v/i, ''),
    latest_version_code: versionCode,
    download_url: `${origin}/download/${encodeURIComponent(filename)}`,
    source_download_url: apk.browser_download_url,
    release_notes: typeof release.body === 'string' ? release.body : '',
  };
}

async function prewarmLatest() {
  const { apk, filename } = await getLatestReleaseInfo();
  await ensureCached(apk);
  console.log(`cached latest APK ${filename}`);
}

function parseRange(rangeHeader, size) {
  if (!rangeHeader || !rangeHeader.startsWith('bytes=')) {
    return null;
  }
  const [range] = rangeHeader.slice('bytes='.length).split(',');
  const match = /^(\d*)-(\d*)$/.exec(range.trim());
  if (!match || (!match[1] && !match[2])) {
    return 'invalid';
  }
  let start;
  let end;
  if (!match[1]) {
    const suffixLength = Number(match[2]);
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0) {
      return 'invalid';
    }
    start = Math.max(size - suffixLength, 0);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || start >= size) {
      return 'invalid';
    }
    end = Math.min(end, size - 1);
  }
  return { start, end };
}

async function handleDownload(req, res, filename) {
  if (!/^[A-Za-z0-9._-]+\.apk$/i.test(filename)) {
    sendJson(res, 404, { error: 'Not Found' });
    return;
  }
  let filePath = path.join(CACHE_DIR, filename);
  try {
    let stat;
    try {
      stat = await fsp.stat(filePath);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      const { apk, filename: latestFilename } = await getLatestReleaseInfo();
      if (latestFilename !== filename) {
        sendJson(res, 404, { error: 'APK Not Found' });
        return;
      }
      filePath = await ensureCached(apk);
      stat = await fsp.stat(filePath);
    }
    if (!stat.isFile() || stat.size <= 0) {
      sendJson(res, 404, { error: 'APK Not Found' });
      return;
    }

    const range = parseRange(req.headers.range, stat.size);
    if (range === 'invalid') {
      res.writeHead(416, {
        'Content-Range': `bytes */${stat.size}`,
        'Accept-Ranges': 'bytes',
      });
      res.end();
      return;
    }
    const start = range ? range.start : 0;
    const end = range ? range.end : stat.size - 1;
    const headers = {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': end - start + 1,
      'Content-Disposition': `attachment; filename="${filename}"`,
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'public, max-age=31536000, immutable',
    };
    if (range) {
      headers['Content-Range'] = `bytes ${start}-${end}/${stat.size}`;
    }
    res.writeHead(range ? 206 : 200, headers);
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    fs.createReadStream(filePath, { start, end }).on('error', (error) => {
      console.error(`APK stream failed: ${error.message}`);
      if (!res.headersSent) res.writeHead(500);
      res.destroy();
    }).pipe(res);
  } catch (error) {
    console.error(error.message);
    sendJson(res, 503, { error: '暂时无法准备 APK 安装包' });
  }
}

async function handleVersion(req, res) {
  const now = Date.now();
  if (cache && now - cache.time < CACHE_TTL_MS) {
    sendJson(res, 200, versionResponse(cache.value, req));
    return;
  }
  try {
    const value = await buildVersionInfo(req);
    const assetName = new URL(value.download_url).pathname.split('/').pop();
    cache = { time: now, value: { ...value, asset_name: assetName } };
    sendJson(res, 200, value);
  } catch (error) {
    console.error(error.message);
    if (cache) {
      sendJson(res, 200, versionResponse(cache.value, req));
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
  if ((req.method === 'GET' || req.method === 'HEAD') && url.pathname === '/version.json') {
    void handleVersion(req, res);
    return;
  }
  if ((req.method === 'GET' || req.method === 'HEAD') && url.pathname.startsWith('/download/')) {
    let filename;
    try {
      filename = decodeURIComponent(url.pathname.slice('/download/'.length));
    } catch {
      sendJson(res, 404, { error: 'Not Found' });
      return;
    }
    if (filename.includes('/') || filename.includes('\\')) {
      sendJson(res, 404, { error: 'Not Found' });
      return;
    }
    void handleDownload(req, res, filename);
    return;
  }
  sendJson(res, 404, { error: 'Not Found' });
}).listen(PORT, '0.0.0.0', () => {
  console.log(`kotier update API listening on ${PORT} for ${REPO}`);
  void prewarmLatest().catch((error) => {
    console.error(`initial APK cache warmup failed: ${error.message}`);
  });
});
