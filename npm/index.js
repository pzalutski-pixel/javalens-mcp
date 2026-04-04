#!/usr/bin/env node
'use strict';

const { execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const DIST_DIR = path.join(__dirname, 'dist');
const WORKSPACE_DIR = path.join(os.tmpdir(), 'javalens-workspaces');

function log(msg) {
  process.stderr.write(`[javalens] ${msg}\n`);
}

function findJava() {
  // Try java on PATH
  try {
    const output = execSync('java -version 2>&1', { encoding: 'utf-8' });
    const match = output.match(/version "(\d+)/);
    if (match && parseInt(match[1]) >= 21) return 'java';
    if (match) {
      log(`Java ${match[1]} found but Java 21 or later is required.`);
      process.exit(1);
    }
  } catch {}

  // Try JAVA_HOME
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaBin = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    try {
      const output = execSync(`"${javaBin}" -version 2>&1`, { encoding: 'utf-8' });
      const match = output.match(/version "(\d+)/);
      if (match && parseInt(match[1]) >= 21) return javaBin;
      if (match) {
        log(`Java ${match[1]} found at JAVA_HOME but Java 21 or later is required.`);
        process.exit(1);
      }
    } catch {}
  }

  return null;
}

function parseEclipseIni() {
  const iniPath = path.join(DIST_DIR, 'eclipse.ini');
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

function cleanupOldCache() {
  const oldCacheDir = path.join(os.homedir(), '.javalens', 'versions');
  if (fs.existsSync(oldCacheDir)) {
    try {
      fs.rmSync(oldCacheDir, { recursive: true, force: true });
      log('Cleaned up old download cache.');
    } catch {}
  }
}

function launch(javaBin, userArgs) {
  const vmargs = parseEclipseIni();
  const jarPath = path.join(DIST_DIR, 'javalens.jar');

  if (!fs.existsSync(jarPath)) {
    log('Error: Bundled distribution not found. Reinstall the package.');
    process.exit(1);
  }

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

function main() {
  const javaBin = findJava();
  if (!javaBin) {
    log('Error: Java not found. Install Java 21 or later.');
    log('Install from: https://adoptium.net/');
    process.exit(1);
  }

  cleanupOldCache();

  const userArgs = process.argv.slice(2);
  launch(javaBin, userArgs);
}

main();
