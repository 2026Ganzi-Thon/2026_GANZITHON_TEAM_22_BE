// Re-run the backend checks against a fresh, private MySQL instance.
// All application source and runtime work is isolated under build/verification-local.
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = path.join(repo, 'build', 'verification-local');
const run = path.join(root, `run-${Date.now()}-${crypto.randomBytes(5).toString('hex')}`);
const work = path.join(run, 'source');
const mysqlData = path.join(run, 'mysql');
const bootstrapPath = path.join(run, 'bootstrap.sql');
const mysqlHome = process.env.GANZI_MYSQL_HOME || path.join(process.env.ProgramFiles || 'C:\\Program Files',
  'MySQL', 'MySQL Server 8.0');
const mysqld = path.join(mysqlHome, 'bin', 'mysqld.exe');
const mysql = path.join(mysqlHome, 'bin', 'mysql.exe');
const javaHome = process.env.JAVA_HOME || '';
const java = path.join(javaHome, 'bin', 'java.exe');
const gradleCache = path.resolve(process.env.GRADLE_USER_HOME || path.join(repo, '.gradle-home'));
const suffix = crypto.randomBytes(6).toString('hex');
const user = `verify_${suffix}`;
const password = crypto.randomBytes(32).toString('hex');
const rootPassword = crypto.randomBytes(32).toString('hex');
const databases = {
  integration: `ganzi_test_integration_${suffix}`,
  smoke: `ganzi_test_smoke_${suffix}`,
  migration: `ganzi_test_deploy_${suffix}_migration`,
  dirty: `ganzi_test_deploy_${suffix}_dirty`,
  upgrade: `ganzi_test_deploy_${suffix}_upgrade`,
};
let mysqlChild;
let activeCheckChild;
let port;
let mysqlSpawnError;
let lastPortProbe;
let abortSignal;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    abortSignal = signal;
    process.exitCode = signal === 'SIGINT' ? 130 : 143;
    if (activeCheckChild?.pid) activeCheckChild.kill();
  });
}
const result = { passed: false, startedAt: new Date().toISOString(), tests: null,
  smoke: null, mysqlStopped: false, runDirectory: run };

function fail(message) { throw new Error(message); }
function checkAbort() { if (abortSignal) fail(`사용자가 ${abortSignal} 신호로 검증을 중단했습니다.`); }
function mysqlRunning() {
  return Boolean(mysqlChild?.pid && mysqlChild.exitCode === null && mysqlChild.signalCode === null);
}
function safeEnv() {
  const env = { ...process.env };
  for (const name of Object.keys(env)) {
    if (/^(OPENAI|ANTHROPIC|GANZI_|MYSQL_|SPRING_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|NODE_OPTIONS$)/i.test(name)) delete env[name];
  }
  env.JAVA_HOME = javaHome;
  env.GRADLE_USER_HOME = gradleCache;
  env.MYSQL_TEST_LOGIN_FILE = path.join(run, 'no-login-file.cnf');
  env.OPENAI_API_KEY = '';
  env.GANZI_CHAT_ENABLED = 'false';
  env.SPRING_CONFIG_IMPORT = 'optional:classpath:/verification-no-env.properties';
  return env;
}
function checkTools() {
  if (process.platform !== 'win32') fail('이 실행기는 Windows 전용입니다.');
  if (!javaHome) fail('JAVA_HOME에 Java 21 JDK 경로를 지정하세요.');
  for (const [label, target] of [['MySQL server', mysqld], ['MySQL client', mysql],
    ['Java 21', java]]) {
    if (!fs.existsSync(target)) fail(`${label} 실행 파일을 찾지 못했습니다.`);
  }
  const version = spawnSync(java, ['-version'], { encoding: 'utf8', windowsHide: true, timeout: 10000 });
  if (version.status !== 0 || !/version ["']21(?:\.|["'])/.test(version.stdout + version.stderr))
    fail('설치된 Java 버전이 21이 아닙니다.');
}
function copySource() {
  fs.mkdirSync(path.join(work, 'scripts'), { recursive: true });
  fs.mkdirSync(path.join(work, 'docs'), { recursive: true });
  for (const name of ['build.gradle', 'bootstrap.gradle', 'settings.gradle', 'gradle.properties', 'gradlew.bat'])
    fs.copyFileSync(path.join(repo, name), path.join(work, name));
  fs.cpSync(path.join(repo, 'gradle'), path.join(work, 'gradle'), { recursive: true });
  fs.cpSync(path.join(repo, 'src'), path.join(work, 'src'), { recursive: true });
  fs.copyFileSync(path.join(repo, 'scripts', 'smoke.mjs'), path.join(work, 'scripts', 'smoke.mjs'));
  fs.copyFileSync(path.join(repo, 'docs', 'implemented.openapi.json'),
    path.join(work, 'docs', 'implemented.openapi.json'));
  // The allowlist above intentionally excludes .env, data/preview, logs and any old DB.
}
async function availablePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const selected = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return selected;
}
function ownedPort() {
  // The private startup file sets a random password before the DB is used.
  // A different server on this port cannot authenticate with that password.
  const check = mysqlCommand('SELECT @@port;');
  lastPortProbe = { status: check.status, errorCode: check.error?.code ?? null };
  return check.status === 0 && check.stdout.trim() === String(port);
}
function mysqlCommand(sql) {
  return spawnSync(mysql, ['--no-defaults', '--protocol=tcp', '--host=127.0.0.1',
    `--port=${port}`, '--user=root', '--connect-timeout=1', '--batch', '--skip-column-names'],
  { cwd: work, env: { ...safeEnv(), MYSQL_PWD: rootPassword }, input: sql,
    encoding: 'utf8', windowsHide: true, timeout: 5000 });
}
async function startMysql() {
  fs.mkdirSync(mysqlData, { recursive: true });
  const init = spawnSync(mysqld, ['--no-defaults', '--initialize-insecure',
    '--basedir=.', '--datadir=../mysql',
    `--plugin-dir=${path.join(mysqlHome, 'lib', 'plugin')}`,
    `--lc-messages-dir=${path.join(mysqlHome, 'share')}`],
  { cwd: work, env: safeEnv(), windowsHide: true, stdio: 'ignore', timeout: 90000 });
  if (init.status !== 0 || init.error) fail('전용 MySQL 데이터 폴더 생성에 실패했습니다.');
  port = await availablePort();
  writeBootstrap();
  const serverLog = fs.openSync(path.join(run, 'mysql-server.log'), 'a');
  try {
  mysqlChild = spawn(mysqld, ['--no-defaults', '--standalone', '--console',
    '--basedir=.', '--datadir=../mysql',
    `--plugin-dir=${path.join(mysqlHome, 'lib', 'plugin')}`,
    `--lc-messages-dir=${path.join(mysqlHome, 'share')}`,
    '--pid-file=../mysql.pid', '--init-file=../bootstrap.sql', '--skip-log-bin', `--port=${port}`,
    '--bind-address=127.0.0.1', '--mysqlx=OFF', '--named-pipe=OFF',
    '--shared-memory=OFF', '--skip-networking=OFF'],
  { cwd: work, env: safeEnv(), windowsHide: true, stdio: ['ignore', serverLog, serverLog] });
  } finally { fs.closeSync(serverLog); }
  mysqlChild.on('error', error => { mysqlSpawnError = error; });
  if (!mysqlChild.pid) fail('전용 MySQL을 시작하지 못했습니다.');
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    checkAbort();
    if (mysqlSpawnError) fail(`전용 MySQL 실행 오류: ${mysqlSpawnError.code ?? '알 수 없음'}`);
    if (!mysqlRunning()) fail('전용 MySQL이 시작 중 종료됐습니다.');
    if (ownedPort()) { fs.unlinkSync(bootstrapPath); return; }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  fail(`전용 MySQL의 고유 인증을 60초 안에 확인하지 못했습니다. 검사 상태=${lastPortProbe?.status ?? '없음'}, 오류=${lastPortProbe?.errorCode ?? '없음'}`);
}
function writeBootstrap() {
  const names = Object.values(databases);
  const sql = [
    ...names.map(name => `CREATE DATABASE \`${name}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`),
    `CREATE USER '${user}'@'127.0.0.1' IDENTIFIED BY '${password}';`,
    `CREATE USER '${user}'@'localhost' IDENTIFIED BY '${password}';`,
    ...names.flatMap(name => [
      `GRANT ALL PRIVILEGES ON \`${name}\`.* TO '${user}'@'127.0.0.1';`,
      `GRANT ALL PRIVILEGES ON \`${name}\`.* TO '${user}'@'localhost';`,
    ]),
    `ALTER USER 'root'@'localhost' IDENTIFIED BY '${rootPassword}';`,
  ].join('\n');
  fs.writeFileSync(bootstrapPath, sql + '\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' });
}
async function runChecked(executable, args, env, label) {
  const chunks = [];
  let captured = 0;
  const maxCapture = 16 * 1024 * 1024;
  const child = spawn(executable, args, { cwd: work, env, windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'] });
  activeCheckChild = child;
  const capture = chunk => {
    if (captured >= maxCapture) return;
    const kept = chunk.subarray(0, maxCapture - captured);
    chunks.push(kept);
    captured += kept.length;
  };
  child.stdout.on('data', capture);
  child.stderr.on('data', capture);
  let code;
  let launchError;
  const finished = new Promise(resolve => {
    child.once('error', error => { launchError = error; });
    child.once('close', exitCode => { code = exitCode; resolve(); });
  });
  const timeout = setTimeout(() => child.kill(), 600000);
  try { await finished; }
  finally { clearTimeout(timeout); activeCheckChild = null; }
  const scrub = value => String(value ?? '').replaceAll(password, '[test-password-redacted]')
    .replaceAll(rootPassword, '[root-password-redacted]')
    .replace(/\bsk-[A-Za-z0-9_-]{12,}/g, '[api-key-redacted]');
  const logName = label === 'Gradle' ? 'gradle.log' : 'http-smoke.log';
  fs.writeFileSync(path.join(run, logName), scrub(Buffer.concat(chunks).toString('utf8')));
  if (launchError || code !== 0) fail(`${label} 실패: 종료 코드 ${code ?? '알 수 없음'}`);
}
function summarizeTests() {
  const folder = path.join(work, 'build', 'test-results', 'test');
  const files = fs.readdirSync(folder).filter(name => /^TEST-.*\.xml$/.test(name));
  const totals = { suites: files.length, tests: 0, failures: 0, errors: 0, skipped: 0 };
  for (const name of files) {
    const text = fs.readFileSync(path.join(folder, name), 'utf8');
    const tag = text.match(/<testsuite\b([^>]*)>/)?.[1];
    if (!tag) fail('Gradle 시험 결과 XML을 읽지 못했습니다.');
    for (const field of ['tests', 'failures', 'errors', 'skipped']) {
      const value = tag.match(new RegExp(`\\b${field}="(\\d+)"`))?.[1];
      if (value === undefined) fail(`Gradle 시험 결과에 ${field} 필드가 없습니다.`);
      totals[field] += Number(value);
    }
  }
  if (totals.suites < 17 || totals.tests < 77 || totals.failures || totals.errors || totals.skipped)
    fail(`자동 시험 결과가 예상과 다릅니다: ${totals.tests}개 중 실패 ${totals.failures}, 오류 ${totals.errors}, 건너뜀 ${totals.skipped}`);
  return totals;
}
async function stopMysql() {
  if (!mysqlChild?.pid) return true;
  // Stop the serving process as well as a possible Windows monitor process.
  if (port && ownedPort()) mysqlCommand('SHUTDOWN;');
  if (mysqlRunning()) mysqlChild.kill();
  for (let i = 0; i < 40 && mysqlRunning(); i++)
    await new Promise(resolve => setTimeout(resolve, 250));
  if (mysqlRunning()) mysqlChild.kill('SIGKILL');
  for (let i = 0; i < 20 && mysqlRunning(); i++)
    await new Promise(resolve => setTimeout(resolve, 250));
  // The Windows monitor and serving process may have different PIDs.
  // Do not report success while a server still accepts connections on this port.
  for (let attempt = 0; attempt < 20; attempt++) {
    const listening = await new Promise(resolve => {
      const socket = net.connect({ host: '127.0.0.1', port });
      socket.setTimeout(800);
      socket.once('connect', () => { socket.destroy(); resolve(true); });
      socket.once('error', () => { socket.destroy(); resolve(false); });
      socket.once('timeout', () => { socket.destroy(); resolve(true); });
    });
    if (!listening) return !mysqlRunning();
    // The randomly generated credential prevents this from shutting down an unrelated DB.
    mysqlCommand('SHUTDOWN;');
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  return false;
}

try {
  checkTools();
  fs.mkdirSync(root, { recursive: true });
  fs.mkdirSync(run, { recursive: false });
  copySource();
  checkAbort();
  await startMysql();
  checkAbort();
  checkAbort();
  const url = name => `jdbc:mysql://127.0.0.1:${port}/${name}?connectionTimeZone=UTC`;
  const env = { ...safeEnv(),
    GANZI_TEST_DB_URL: url(databases.integration), GANZI_TEST_DB_USERNAME: user,
    GANZI_TEST_DB_PASSWORD: password,
    GANZI_MIGRATION_DB_URL: url(databases.migration),
    GANZI_MIGRATION_DIRTY_DB_URL: url(databases.dirty),
    GANZI_MIGRATION_UPGRADE_DB_URL: url(databases.upgrade),
    GANZI_SMOKE_DB_URL: url(databases.smoke), GANZI_SMOKE_DB_USERNAME: user,
    GANZI_SMOKE_DB_PASSWORD: password };
  console.log('격리된 MySQL에서 자동 시험과 JAR 빌드를 진행합니다.');
  const offline = process.env.GANZI_GRADLE_OFFLINE === 'true' ? ['--offline'] : [];
  await runChecked(java, ['-cp', path.join(work, 'gradle', 'wrapper', 'gradle-wrapper.jar'),
    'org.gradle.wrapper.GradleWrapperMain', ...offline, 'test', 'bootJar',
    '--no-daemon', '--rerun-tasks'], env, 'Gradle');
  checkAbort();
  result.tests = summarizeTests();
  console.log(`자동 시험 ${result.tests.tests}개 통과. HTTP 점검을 진행합니다.`);
  await runChecked(process.execPath, [path.join(work, 'scripts', 'smoke.mjs')], env, 'HTTP 점검');
  checkAbort();
  const smoke = JSON.parse(fs.readFileSync(path.join(work, 'build', 'smoke-result.json'), 'utf8'));
  if (!smoke.passed || smoke.implementedOperations !== 33) fail('HTTP 점검 결과가 예상과 다릅니다.');
  result.smoke = { passed: true, operations: smoke.implementedOperations, checks: smoke.checks.length };
  result.passed = true;
  console.log(`HTTP API ${result.smoke.operations}개 점검 완료.`);
} catch (error) {
  result.error = String(error?.message ?? error);
  console.error(result.error);
  if (!abortSignal) process.exitCode = 1;
} finally {
  result.mysqlStopped = await stopMysql();
  if (fs.existsSync(bootstrapPath)) fs.unlinkSync(bootstrapPath);
  if (!result.mysqlStopped) {
    result.passed = false;
    result.error ??= '전용 MySQL 종료를 확인하지 못했습니다.';
    console.error(result.error);
    process.exitCode = 1;
  }
  result.completedAt = new Date().toISOString();
  if (fs.existsSync(run)) fs.writeFileSync(path.join(run, 'result.json'), JSON.stringify(result, null, 2));
  console.log(`결과 위치: ${path.join(run, 'result.json')}`);
}
