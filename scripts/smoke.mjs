// Real loopback HTTP with an isolated MySQL database. Uses disposable synthetic records.
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const javaExecutable = process.platform === 'win32' ? 'java.exe' : 'java';
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin', javaExecutable) : javaExecutable;
const runDir = resolve(root, 'build', 'smoke', randomUUID());
await mkdir(runDir, { recursive: true });
const jar = resolve(root, 'build/libs/ganzithon-backend-0.6.0-local.jar');
const base = 'http://127.0.0.1:18081';
const db = process.env.GANZI_SMOKE_DB_URL ?? '';
const dbUser = process.env.GANZI_SMOKE_DB_USERNAME ?? '';
const dbPassword = process.env.GANZI_SMOKE_DB_PASSWORD ?? '';
const localTestDb = /^jdbc:mysql:\/\/(?:127\.0\.0\.1|localhost)(?::\d{1,5})?\/ganzi_test_smoke(?:_[a-zA-Z0-9_]+)?(?:\?[^#]*)?$/;
assert.match(db, localTestDb, 'Set GANZI_SMOKE_DB_URL to a disposable loopback ganzi_test_smoke database');
assert.ok(dbUser && dbPassword, 'Set dedicated GANZI_SMOKE_DB_USERNAME and GANZI_SMOKE_DB_PASSWORD');

const probe = createServer();
await new Promise((ok, fail) => probe.once('error', fail).listen(18081, '127.0.0.1', ok));
await new Promise(ok => probe.close(ok));
let child;
let log = '';
const delay = ms => new Promise(ok => setTimeout(ok, ms));
async function start({demo = false, guestLimit = 30, chatEnabled = false} = {}) {
  child = spawn(java, ['-jar', jar, '--server.address=127.0.0.1', '--server.port=18081',
    `--spring.profiles.active=${demo ? 'demo' : 'local'}`,
    `--ganzi.chat.enabled=${chatEnabled}`,
    '--ganzi.push.enabled=false', '--ganzi.chat.memory.archive-enabled=false',
    '--ganzi.rate-limit.auth-per-minute=100',
    `--ganzi.rate-limit.guest-per-hour=${guestLimit}`,
    '--ganzi.cors.allowed-origins=http://127.0.0.1:7891,http://localhost:7891',
    '--spring.sql.init.mode=always',
    `--spring.datasource.url=${db}`], { cwd: root, windowsHide: true,
    env: { ...process.env, OPENAI_API_KEY: '', SPRING_DATASOURCE_USERNAME: dbUser, SPRING_DATASOURCE_PASSWORD: dbPassword } });
  child.stdout.on('data', chunk => { log += chunk; });
  child.stderr.on('data', chunk => { log += chunk; });
  let spawnError;
  child.on('error', error => { spawnError = error; });
  for (let attempt = 0; attempt < 160; attempt++) {
    if (spawnError) throw spawnError;
    if (child.exitCode !== null) throw new Error(`Server exited: ${child.exitCode}`);
    try { if ((await fetch(base + '/health', { signal: AbortSignal.timeout(500) })).ok) return; } catch {}
    await delay(250);
  }
  throw new Error('Server start timed out');
}
async function stop() {
  if (child && child.exitCode === null) {
    const closed = once(child, 'exit');
    child.kill(); // Windows process stop; verifies committed MySQL data survives the application restart.
    await closed;
  }
  child = undefined;
}
async function api(method, path, token, body, expected = 200) {
  const response = await fetch(base + path, { method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, expected, `${method} ${path}: HTTP ${response.status}`);
  if (path.startsWith('/api/v1/')) assert.equal(response.headers.get('cache-control'), 'no-store');
  return response.status === 204 ? null : response.json();
}
try {
  await start();
  assert.equal((await fetch(base + '/demo/index.html')).status, 404);
  await api('GET', '/api/v1/me', null, null, 401);
  const guest = await api('POST', '/api/v1/guest-sessions', null, null, 201);
  const token = guest.accessToken; // Kept in memory only; never written to results or logs.
  await api('GET', '/api/v1/chat/history', null, null, 401);
  assert.deepEqual((await api('GET', '/api/v1/chat/history?limit=1', token)).items, []);
  await api('GET', '/api/v1/chat/history?limit=0', token, null, 400);
  const installationId = randomUUID();
  await api('PUT', `/api/v1/push-devices/${installationId}`, token,
    { expoPushToken: `ExpoPushToken[${randomUUID().replaceAll('-', '')}]` });
  const unsupported = await fetch(base + '/api/v1/tasks', {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'text/plain' },
    body: 'synthetic-invalid-body', signal: AbortSignal.timeout(5000) });
  assert.equal(unsupported.status, 415);
  assert.equal((await unsupported.json()).code, 'UNSUPPORTED_MEDIA_TYPE');
  const unacceptable = await fetch(base + '/api/v1/me', {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/xml' },
    signal: AbortSignal.timeout(5000) });
  assert.equal(unacceptable.status, 406);
  assert.equal((await unacceptable.json()).code, 'NOT_ACCEPTABLE');
  const wrongMethod = await fetch(base + '/api/v1/guest-sessions', {
    signal: AbortSignal.timeout(5000) });
  assert.equal(wrongMethod.status, 405);
  assert.ok(wrongMethod.headers.get('allow')?.split(/,\s*/).includes('POST'));
  assert.equal((await wrongMethod.json()).code, 'METHOD_NOT_ALLOWED');
  await api('GET', '/api/v1/chat/usage', null, null, 401);
  const usage = await api('GET', '/api/v1/chat/usage', token);
  assert.equal(usage.timeZone, 'UTC');
  assert.equal(usage.daily.requests, 0);
  assert.equal(usage.dailyRequestLimit, 100);
  assert.equal(usage.availability, 'AVAILABLE');
  assert.equal((await api('GET', '/api/v1/me', token)).userId, guest.userId);
  const dueDate = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  const task = await api('POST', '/api/v1/tasks', token, { title: '합성 시험용 할 일', dueDate }, 201);
  const candidates = await api('GET', '/api/v1/task-reminders?timeZone=UTC', token);
  assert.equal(candidates.items[0].taskId, task.id);
  assert.equal(candidates.items[0].dueDate, dueDate);
  assert.equal((await api('GET', `/api/v1/tasks/${task.id}`, token)).title, task.title);
  assert.equal((await api('GET', '/api/v1/tasks?status=TODO', token)).items.length, 1);
  const childTask = await api('POST', '/api/v1/tasks/' + task.id + '/subtasks', token,
    { title: '합성 시험용 세부 항목' }, 201);
  await api('PATCH', '/api/v1/tasks/' + task.id + '/subtasks/' + childTask.id,
    token, { status: 'DONE' });
  const taskDetail = await api('GET', '/api/v1/tasks/' + task.id, token);
  assert.equal(taskDetail.subtasks.length, 1);
  assert.equal(taskDetail.subtaskSummary.ratio, 1);
  assert.equal(taskDetail.status, 'TODO');
  const key = randomUUID();
  let study = await api('POST', '/api/v1/study-sessions', token,
    { clientRequestId: key, taskId: task.id }, 201);
  const departureId = randomUUID();
  const departure = { installationId, eventId: departureId, sequence: 1, state: 'BACKGROUND' };
  const away = await api('PUT', `/api/v1/study-sessions/${study.id}/presence`, token, departure);
  assert.equal(away.deliveryStatus, 'PENDING');
  assert.equal(away.deliveryEnabled, false);
  assert.equal(Date.parse(away.dueAt) - Date.parse(away.awayAt), 300000);
  assert.equal((await api('PUT', `/api/v1/study-sessions/${study.id}/presence`, token, departure)).dueAt, away.dueAt);
  await delay(2100);
  study = await api('PATCH', `/api/v1/study-sessions/${study.id}`, token,
    { action: 'PAUSE', expectedVersion: 0 });
  assert.ok(study.studySeconds >= 2);
  const pausedSeconds = study.studySeconds;
  await stop();
  await delay(1100);
  await start();
  const current = await api('GET', '/api/v1/study-sessions/current', token);
  assert.equal(current.studySession.id, study.id);
  assert.equal(current.studySession.status, 'PAUSED');
  assert.equal(current.studySession.studySeconds, pausedSeconds);
  const returned = await api('PUT', `/api/v1/study-sessions/${study.id}/presence`, token,
    { installationId, eventId: randomUUID(), sequence: 2, state: 'FOREGROUND' });
  assert.equal(returned.deliveryStatus, 'CANCELED');
  assert.equal(returned.dueAt, null);
  await api('DELETE', `/api/v1/push-devices/${installationId}`, token, null, 204);
  assert.equal((await api('GET', '/api/v1/tasks/' + task.id, token)).subtasks[0].status, 'DONE');
  const denied = await fetch(base + '/api/v1/tasks', {
    headers: { Origin: 'https://unlisted.example.test' }, signal: AbortSignal.timeout(5000) });
  assert.equal(denied.status, 403);
  const unauthorized = await fetch(base + '/api/v1/tasks', {
    headers: { Origin: 'http://localhost:7891' }, signal: AbortSignal.timeout(5000) });
  assert.equal(unauthorized.status, 401);
  assert.equal(unauthorized.headers.get('access-control-allow-origin'), 'http://localhost:7891');
  assert.equal((await unauthorized.json()).code, 'AUTH_REQUIRED');
  const retry = await api('POST', '/api/v1/study-sessions', token,
    { clientRequestId: key, taskId: task.id });
  assert.equal(retry.id, study.id);
  study = await api('PATCH', `/api/v1/study-sessions/${study.id}`, token,
    { action: 'RESUME', expectedVersion: study.version });
  await delay(1100);
  study = await api('PATCH', `/api/v1/study-sessions/${study.id}`, token,
    { action: 'END', expectedVersion: study.version, endReason: 'USER_FINISHED' });
  assert.ok(study.studySeconds >= pausedSeconds + 1);
  const context = await api('GET', '/api/v1/study-sessions/start-context', token);
  assert.equal(context.currentSession, null);
  assert.equal(context.suggestedTask.taskId, task.id);
  assert.equal(context.suggestedTask.completedStudySeconds, study.studySeconds);
  const reminderPath = `/api/v1/study-sessions/${study.id}/next-start-reminder`;
  const reminderInput = { remindAt: new Date(Date.now() + 3600000).toISOString(), timeZone: 'Asia/Seoul' };
  const planned = await api('PUT', reminderPath, token, reminderInput, 201);
  assert.equal(planned.reminder.status, 'ACTIVE');
  await stop();
  await start();
  assert.equal((await api('GET', '/api/v1/start-reminders/current', token)).reminder.id, planned.reminder.id);
  assert.equal((await api('PUT', reminderPath, token, reminderInput)).reminder.id, planned.reminder.id);
  const change = { expectedRemindAt: reminderInput.remindAt, expectedTimeZone: reminderInput.timeZone,
    remindAt: new Date(Date.now() + 7200000).toISOString(), timeZone: reminderInput.timeZone };
  const updated = await api('PATCH', `/api/v1/start-reminders/${planned.reminder.id}`, token, change);
  assert.equal(updated.reminder.id, planned.reminder.id);
  assert.equal(Date.parse(updated.reminder.remindAt), Date.parse(change.remindAt));
  assert.equal((await api('PATCH', `/api/v1/start-reminders/${planned.reminder.id}`, token, change)).reminder.id, planned.reminder.id);
  const stale = await api('PATCH', `/api/v1/start-reminders/${planned.reminder.id}`, token,
    { ...change, remindAt: new Date(Date.now() + 10800000).toISOString() }, 409);
  assert.equal(stale.code, 'REMINDER_PRECONDITION_FAILED');
  const newer = await api('POST', '/api/v1/study-sessions', token, { clientRequestId: randomUUID() }, 201);
  assert.equal((await api('GET', '/api/v1/start-reminders/current', token)).reminder, null);
  assert.equal((await api('PUT', reminderPath, token, { remindAt: change.remindAt, timeZone: change.timeZone })).reminder.canceledReason, 'NEW_STUDY');
  await api('DELETE', `/api/v1/start-reminders/${planned.reminder.id}`, token, null, 204);
  await api('PATCH', `/api/v1/study-sessions/${newer.id}`, token, { action: 'END', expectedVersion: 0 });
  const chatId = randomUUID();
  const disabled = await api('POST', '/api/v1/chat/exchanges', token,
    { clientRequestId: chatId, content: '합성 채팅 입력' }, 503);
  assert.equal(disabled.code, 'CHAT_NOT_CONFIGURED');
  assert.deepEqual((await api('GET', '/api/v1/chat/exchanges?clientRequestId=' + chatId, token)).items, []);
  await api('DELETE', '/api/v1/tasks/' + task.id + '/subtasks/' + childTask.id, token, null, 204);
  assert.equal((await api('GET', '/api/v1/tasks/' + task.id, token)).subtaskSummary.ratio, null);
  await api('PATCH', `/api/v1/tasks/${task.id}`, token, { status: 'DONE' });
  await api('DELETE', `/api/v1/tasks/${task.id}`, token, null, 204);
  assert.equal((await api('GET', `/api/v1/study-sessions/${study.id}`, token)).taskId, null);
  const remainingTask = await api('POST', '/api/v1/tasks', token, { title: '전체 삭제 검증' }, 201);
  await api('POST', '/api/v1/tasks/' + remainingTask.id + '/subtasks', token,
    { title: '함께 지울 세부 항목' }, 201);
  const retained = await api('POST', '/api/v1/guest-sessions', null, null, 201);
  await api('DELETE', '/api/v1/me', token, null, 204);
  await api('GET', '/api/v1/me', token, null, 401);
  assert.equal((await api('GET', '/api/v1/me', retained.accessToken)).userId, retained.userId);
  await stop();
  await start();
  await api('GET', '/api/v1/me', token, null, 401);
  assert.equal((await api('GET', '/api/v1/me', retained.accessToken)).userId, retained.userId);
  const email = `smoke-${randomUUID()}@example.test`;
  const password = '합성시험 비밀번호 첫 번째 2026';
  const nextPassword = '합성시험 비밀번호 두 번째 2026';
  await api('POST', '/api/v1/tasks', retained.accessToken, {title:'이메일 가입 뒤 유지할 합성 기록'}, 201);
  const linked = await api('POST', '/api/v1/auth/register', retained.accessToken, {email,password}, 201);
  assert.equal(linked.userId, retained.userId);
  assert.equal(linked.accountType, 'EMAIL');
  assert.equal(linked.emailVerified, false);
  await api('GET', '/api/v1/me', retained.accessToken, null, 401);
  assert.equal((await api('GET', '/api/v1/tasks', linked.accessToken)).items.length, 1);
  await stop();
  await start({demo:true, chatEnabled:true});
  const page = await fetch(base + '/demo/index.html');
  assert.equal(page.status, 200);
  assert.ok(page.headers.get('content-security-policy').includes("script-src 'self'"));
  for (const path of ['/demo/app.js','/demo/styles.css','/demo/assets/reference-flow.png']) {
    assert.equal((await fetch(base + path)).status, 200);
  }
  await api('GET', '/api/v1/me', null, null, 401);
  const login = await api('POST', '/api/v1/auth/login', null, {email,password});
  assert.equal(login.userId, retained.userId);
  const missingKey = await api('POST', '/api/v1/chat/exchanges', login.accessToken,
    { clientRequestId: randomUUID(), content: '키 없는 합성 시험' }, 503);
  assert.equal(missingKey.code, 'CHAT_NOT_CONFIGURED');
  await api('POST', '/api/v1/auth/password', login.accessToken,
    {currentPassword:'의도적으로 잘못된 비밀번호',newPassword:nextPassword}, 401);
  assert.equal((await api('GET', '/api/v1/me', login.accessToken)).accountType, 'EMAIL');
  const changed = await api('POST', '/api/v1/auth/password', login.accessToken,
    {currentPassword:password,newPassword:nextPassword});
  await api('GET', '/api/v1/me', login.accessToken, null, 401);
  await api('GET', '/api/v1/me', linked.accessToken, null, 401);
  await api('POST', '/api/v1/auth/login', null, {email,password}, 401);
  await api('POST', '/api/v1/auth/logout', changed.accessToken, null, 204);
  await api('GET', '/api/v1/me', changed.accessToken, null, 401);
  const relogin = await api('POST', '/api/v1/auth/login', null, {email,password:nextPassword});
  assert.equal((await api('GET', '/api/v1/tasks', relogin.accessToken)).items.length, 1);
  await api('DELETE', '/api/v1/me', relogin.accessToken, null, 204);
  await api('POST', '/api/v1/auth/login', null, {email,password:nextPassword}, 401);
  await stop();
  await start({guestLimit:1});
  await api('POST', '/api/v1/guest-sessions', null, null, 201);
  const limited = await fetch(base + '/api/v1/guest-sessions', {
    method:'POST', headers:{Origin:'http://localhost:7891'}
  });
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get('retry-after')) > 0);
  assert.equal(limited.headers.get('access-control-allow-origin'), 'http://localhost:7891');
  assert.equal((await limited.json()).code, 'RATE_LIMITED');
  const report = { passed: true, applicationVersion: '0.6.0-local', databaseEngine: 'MySQL',
    implementedOperations: 33, checks: ['real HTTP: all 33 routes, AI and push external delivery disabled',
    'private durable chat history route and cursor limit validation',
    'push device registration/removal and durable five-minute departure intent with exact-event replay',
    'foreground return after app restart clears departure delivery',
    'start context finds previous task and accumulated study time',
    'next-start intent persists across restart, supports safe rescheduling, and is canceled when new study begins',
    'unfinished deadline candidate and disabled chat behavior',
    'owner-authenticated UTC usage view without global spending disclosure',
    'enabled OpenAI bean rejects missing credentials without external requests',
    'guest authentication and no-store', 'MySQL records survive application stop/restart',
    'paused state and elapsed seconds restored', 'start replay returns same session',
    'resume/end and ended task unlink', 'subtask CRUD, actual summary and persistence',
    'allowed-origin 401 visible; unlisted origin rejected',
    'full deletion persists across restart; another user retained',
    'guest-to-email linking preserves records and replaces guest token',
    'email account survives application restart; login restores records',
    'wrong current password preserves session; password change rotates all sessions',
    'logout revokes current token; deleted email cannot log in',
    'demo disabled by default; enabled static assets have CSP and API remains protected',
    'HTTP 429, Retry-After and allowed CORS origin',
    'unsupported Content-Type 415, Accept 406 and method 405 with Allow'], testedAt: new Date().toISOString() };
  await writeFile(resolve(root, 'build/smoke-result.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await stop();
  await writeFile(resolve(runDir, 'server.log'), log);
}
