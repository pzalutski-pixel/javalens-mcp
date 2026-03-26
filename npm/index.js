#!/usr/bin/env node
'use strict';

const { execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');
const https = require('https');

const PACKAGE_VERSION = require('./package.json').version;
const GITHUB_REPO = 'pzalutski-pixel/javalens-mcp';
const CACHE_DIR = path.join(os.homedir(), '.javalens', 'versions');
const WORKSPACE_DIR = path.join(os.tmpdir(), 'javalens-workspaces');

function log(msg) {
  process.stderr.write(`[javalens] ${msg}\n`);
}

function findJava() {
  try {
    const output = execSync('java -version 2>&1', { encoding: 'utf-8' });
    const match = output.match(/version "(\d+)/);
    if (match && parseInt(match[1]) >= 21) return 'java';
  } catch {}

  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaBin = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    try {
      const output = execSync(`"${javaBin}" -version 2>&1`, { encoding: 'utf-8' });
      const match = output.match(/version "(\d+)/);
      if (match && parseInt(match[1]) >= 21) return javaBin;
    } catch {}
  }

  return null;
}

function download(url) {
  return new Promise((resolve, reject) => {
    const request = (url) => {
      https.get(url, { headers: { 'User-Agent': 'javalens-mcp-npm' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          request(res.headers.location);
          return;
        }
        if (res.statusCode !== 200) {
          reject(new Error(`Download failed: HTTP ${res.statusCode}`));
          return;
        }

        const totalBytes = parseInt(res.headers['content-length'], 10);
        let downloaded = 0;
        const chunks = [];

        res.on('data', (chunk) => {
          chunks.push(chunk);
          downloaded += chunk.length;
          if (totalBytes) {
            const pct = Math.round(downloaded / totalBytes * 100);
            const mb = (downloaded / 1024 / 1024).toFixed(1);
            process.stderr.write(`\r[javalens] Downloading... ${pct}% (${mb} MB)`);
          }
        });

        res.on('end', () => {
          if (totalBytes) process.stderr.write('\n');
          resolve(Buffer.concat(chunks));
        });

        res.on('error', reject);
      }).on('error', reject);
    };
    request(url);
  });
}

async function ensureInstalled() {
  const version = PACKAGE_VERSION;
  const installDir = path.join(CACHE_DIR, version);
  const javalensDir = path.join(installDir, `javalens-v${version}`);
  const jarPath = path.join(javalensDir, 'javalens.jar');

  if (fs.existsSync(jarPath)) {
    return javalensDir;
  }

  log(`Installing JavaLens v${version}...`);

  const url = `https://github.com/${GITHUB_REPO}/releases/download/v${version}/javalens-v${version}.tar.gz`;
  const tarPath = path.join(os.tmpdir(), `javalens-v${version}.tar.gz`);

  try {
    const data = await download(url);
    fs.mkdirSync(installDir, { recursive: true });
    fs.writeFileSync(tarPath, data);

    execSync(`tar -xzf "${tarPath}" -C "${installDir}"`, { stdio: 'pipe' });

    if (!fs.existsSync(jarPath)) {
      throw new Error('javalens.jar not found after extraction');
    }

    log(`Installed to ${javalensDir}`);
    return javalensDir;
  } finally {
    try { fs.unlinkSync(tarPath); } catch {}
  }
}

function parseEclipseIni(javalensDir) {
  const iniPath = path.join(javalensDir, 'eclipse.ini');
  if (!fs.existsSync(iniPath)) return [];

  const lines = fs.readFileSync(iniPath, 'utf-8').split('\n');
  const vmargs = [];
  let inVmArgs = false;

  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed === '-vmargs') {
      inVmArgs = true;
      continue;
    }
    if (inVmArgs && trimmed) {
      vmargs.push(trimmed);
    }
  }

  return vmargs;
}

function launch(javaBin, javalensDir, userArgs) {
  const vmargs = parseEclipseIni(javalensDir);
  const jarPath = path.join(javalensDir, 'javalens.jar');

  const hasData = userArgs.some(a => a === '-data' || a === '--data');

  const javaArgs = [
    ...vmargs,
    '-jar', jarPath,
    ...(hasData ? [] : ['-data', WORKSPACE_DIR]),
    ...userArgs
  ];

  const child = spawn(javaBin, javaArgs, {
    stdio: 'inherit',
    windowsHide: true
  });

  child.on('exit', (code) => process.exit(code ?? 1));
  child.on('error', (err) => {
    log(`Failed to start Java: ${err.message}`);
    process.exit(1);
  });
}

async function main() {
  const javaBin = findJava();
  if (!javaBin) {
    log('Error: Java 21 or later is required.');
    log('Install from: https://adoptium.net/');
    process.exit(1);
  }

  const javalensDir = await ensureInstalled();
  const userArgs = process.argv.slice(2);
  launch(javaBin, javalensDir, userArgs);
}

main().catch((err) => {
  log(`Error: ${err.message}`);
  process.exit(1);
});
