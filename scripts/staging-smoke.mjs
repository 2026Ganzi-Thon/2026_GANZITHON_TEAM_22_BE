// The deployment profile must work against a fresh Flyway-managed MySQL schema.
// Run with verify-local.mjs or verify-ci.mjs, which create and destroy a private database.
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin', javaName) : javaName;
const jar = resolve(root, 'build/libs/ganzithon-backend-0.6.0-local.jar');
const db = process.env.GANZI_STAGING_SMOKE_DB_URL ?? '';
const dbUser = process.env.GANZI_STAGING_SMOKE_DB_USERNAME ?? '';
const dbPassword = process.env.GANZI_STAGING_SMOKE_DB_PASSWORD ?? '';
assert.match(db, /^jdbc:mysql:\/\/(?:127\.0\.0\.1|localhost):\d{1,5}\/ganzi_test_staging_[a-f0-9]{12}\?connectionTimeZone=UTC$/,
  'Only a disposable loopback staging database may be used');
assert.ok(dbUser && dbPassword, 'Missing dedicated staging database credentials');
const logDir = resolve(root, 'build/staging-smoke', randomUUID());
await mkdir(logDir, { recursive: true });
const listener = createServer();
await new Promise((ok, fail) => listener.once('error', fail).listen(0, '127.0.0.1', ok));
const port = listener.address().port;
await new Promise(ok => listener.close(ok));
const base = `http://127.0.0.1:${port}`;
const delay = ms => new Promise(ok => setTimeout(ok, ms));
let child;
let serverLog = '';
let requestCount = 0;

async function start() {
  child = spawn(java, ['-jar', jar, '--spring.profiles.active=staging',
    '--server.address=127.0.0.1', `--server.port=${port}`,
    '--ganzi.chat.enabled=false', '--ganzi.push.enabled=false',
    '--ganzi.chat.memory.archive-enabled=false',
    '--ganzi.rate-limit.auth-per-minute=100', '--ganzi.rate-limit.guest-per-hour=100'],
  { cwd: root, windowsHide: true, env: { ...process.env,
    OPENAI_API_KEY: '', GANZI_DB_URL: db, GANZI_DB_USERNAME: dbUser,
    GANZI_DB_PASSWORD: dbPassword,
    GANZI_ALLOWED_ORIGINS: 'http://localhost:7891' } });
  child.stdout.on('data', chunk => { serverLog += chunk; });
  child.stderr.on('data', chunk => { serverLog += chunk; });
  let spawnError;
  child.once('error', error => { spawnError = error; });
  for (let attempt = 0; attempt < 200; attempt++) {
    if (spawnError) throw spawnError;
    if (child.exitCode !== null) throw new Error(`staging server exited: ${child.exitCode}`);
    try {
      const health = await fetch(base + '/health', { signal: AbortSignal.timeout(500) });
      if (health.ok) return;
    } catch {}
    await delay(250);
  }
  throw new Error('staging server start timed out');
}
async function stop() {
  if (child && child.exitCode === null) {
    const closed = once(child, 'exit');
    child.kill();
    await closed;
  }
  child = undefined;
}
async function api(method, endpoint, token, body, expected = 200) {
  requestCount++;
  const response = await fetch(base + endpoint, { method, headers: {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, expected, `${method} ${endpoint}: HTTP ${response.status}`);
  if (endpoint.startsWith('/api/v1/')) assert.equal(response.headers.get('cache-control'), 'no-store');
  return response.status === 204 ? null : response.json();
}

try {
  await start();
  assert.equal((await fetch(base + '/demo/index.html')).status, 404);
  assert.equal((await api('GET', '/health', null)).status, 'UP');
  await api('GET', '/api/v1/me', null, null, 401);
  const guest = await api('POST', '/api/v1/guest-sessions', null, null, 201);
  const task = await api('POST', '/api/v1/tasks', guest.accessToken,
    { title: '스테이징 Flyway 시험 할 일' }, 201);
  assert.equal((await api('GET', '/api/v1/tasks', guest.accessToken)).items[0].id, task.id);
  const study = await api('POST', '/api/v1/study-sessions', guest.accessToken,
    { clientRequestId: randomUUID(), taskId: task.id }, 201);
  assert.equal((await api('GET', '/api/v1/study-sessions/current', guest.accessToken)).studySession.id, study.id);
  const paused = await api('PATCH', `/api/v1/study-sessions/${study.id}`, guest.accessToken,
    { action: 'PAUSE', expectedVersion: 0 });
  const ended = await api('PATCH', `/api/v1/study-sessions/${study.id}`, guest.accessToken,
    { action: 'END', expectedVersion: paused.version });
  const remindAt = new Date(Date.now() + 3600000).toISOString();
  const reminderPath = `/api/v1/study-sessions/${study.id}/next-start-reminder`;
  const reminder = await api('PUT', reminderPath, guest.accessToken,
    { remindAt, timeZone: 'Asia/Seoul' }, 201);
  assert.equal(reminder.reminder.status, 'ACTIVE');
  assert.equal((await api('GET', '/api/v1/start-reminders/current', guest.accessToken)).reminder.id,
    reminder.reminder.id);
  const email = `staging-${randomUUID()}@example.test`;
  const password = '스테이징 시험 비밀번호 2026';
  const linked = await api('POST', '/api/v1/auth/register', guest.accessToken, { email, password }, 201);
  assert.equal((await api('GET', '/api/v1/tasks', linked.accessToken)).items[0].id, task.id);
  await stop();
  await start();
  const login = await api('POST', '/api/v1/auth/login', null, { email, password });
  assert.equal(login.userId, linked.userId);
  assert.equal((await api('GET', `/api/v1/study-sessions/${study.id}`, login.accessToken)).id, ended.id);
  assert.equal((await api('GET', '/api/v1/start-reminders/current', login.accessToken)).reminder.id,
    reminder.reminder.id);
  await api('DELETE', `/api/v1/start-reminders/${reminder.reminder.id}`, login.accessToken, null, 204);
  await api('DELETE', '/api/v1/me', login.accessToken, null, 204);
  const report = { passed: true, profile: 'staging', checks: [
    'staging profile starts with Flyway on a fresh private MySQL schema',
    'database-backed health and disabled demo',
    'guest auth, tasks, study pause/end, and next-start reminder',
    'guest-to-email registration and data persistence after application restart',
    'login, reminder cancellation, and account deletion' ],
  requestCount, testedAt: new Date().toISOString() };
  await writeFile(resolve(root, 'build/staging-smoke-result.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await stop();
  await writeFile(resolve(logDir, 'server.log'), serverLog);
}
