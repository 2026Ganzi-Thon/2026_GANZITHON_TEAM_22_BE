import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const api = 'https://api.example.test';
const browser = 'https://app.example.test';
const token = 'synthetic-qa-token';
const reply = 'Synthetic QA reply';
const prompt = '오늘 10분 동안 단어를 복습했어요. 짧게 응원해 주세요.';
const script = fileURLToPath(new URL('./public-preflight.mjs', import.meta.url));

function mockFetch({ mode, api, browser, token, reply, prompt }) {
  let saved;
  globalThis.fetch = async (url, options) => {
    const { method, headers, body, redirect } = options;
    const { origin, pathname, searchParams } = new URL(url);
    console.log('CALL', method, pathname);
    if (origin !== api || redirect !== 'manual') throw new Error('Unexpected destination or redirect policy');
    const cors = { 'Access-Control-Allow-Origin': browser };
    const json = (object, status = 200, headers = cors) => Response.json(object, { status, headers });
    if (pathname === '/health') return mode === 'qa-edge' ? new Response(null, { status: 404 }) : json({ status: 'UP' });
    if (method === 'OPTIONS') return new Response(null, { status: 204, headers: {
      ...cors, 'Access-Control-Allow-Methods': 'POST', 'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    } });
    if (pathname === '/api/v1/me') return json({ code: 'AUTH_REQUIRED', message: 'Auth required',
      retryable: false, requestId: 'fixture-id' }, 401,
    { ...cors, 'Cache-Control': 'no-store', 'X-Request-ID': 'fixture-id' });
    if (headers.Authorization !== `Bearer ${token}` || headers.Origin !== browser) throw new Error('Wrong auth or origin');
    if (pathname === '/api/v1/chat/usage') return json({ availability: 'AVAILABLE' });
    if (pathname === '/api/v1/chat/history' && searchParams.get('limit') === '50') {
      const priorProbe = { clientRequestId: '924897b8-2b45-4ec6-bb88-8ffbc2c93817', studySessionId: null,
        content: prompt, status: 'SUCCEEDED', failureCode: null, utterances: [{ characterKey: 'JOY', content: reply }] };
      if (mode === 'cursor-cycle' || mode === 'paginated' && !searchParams.has('cursor')) {
        return json({ items: Array(50).fill(priorProbe), nextCursor: 'next-page' });
      }
      if (mode === 'paginated') return json({ items: [priorProbe], nextCursor: null });
      if (mode === 'history') return json({ items: [{ ...priorProbe, content: reply }], nextCursor: null });
      return json({ items: [], nextCursor: null });
    }
    if (pathname === '/api/v1/chat/exchanges' && method === 'POST') {
      if (saved) throw new Error('Duplicate POST');
      if (mode === 'provider-down') return json({ code: 'CHAT_NOT_CONFIGURED', message: reply }, 503);
      if (mode === 'redirect') return new Response(null, { status: 307, headers: { Location: 'http://other.example.test/' } });
      const { clientRequestId, characterKeys, content, ...rest } = JSON.parse(body);
      if (!clientRequestId || characterKeys.join() !== 'JOY' || content !== prompt || Object.keys(rest).length
        || headers['Content-Type'] !== 'application/json') throw new Error('Invalid synthetic request');
      saved = { id: 'fixture-exchange', status: 'SUCCEEDED', clientRequestId, failureCode: null,
        utterances: [{ characterKey: 'JOY', content: reply }] };
      return json(mode === 'bad-reply' ? { ...saved, utterances: [] } : saved);
    }
    if (pathname === '/api/v1/chat/exchanges' && searchParams.get('clientRequestId') === saved?.clientRequestId) {
      return json({ items: mode === 'recovery-mismatch' ? [{ ...saved, id: 'wrong-exchange' }] : [saved] });
    }
    throw new Error('Unexpected request');
  };
}

function run(mode, flags = [], withToken = true) {
  const fixture = `(${mockFetch})(${JSON.stringify({ mode, api, browser, token, reply, prompt })});`;
  const preload = `data:text/javascript;base64,${Buffer.from(fixture).toString('base64')}`;
  const env = { ...process.env };
  delete env.NODE_TLS_REJECT_UNAUTHORIZED;
  delete env.GANZI_PUBLIC_QA_TOKEN;
  delete env.GANZI_PUBLIC_QA_TOKEN_ORIGIN;
  if (withToken) Object.assign(env, { GANZI_PUBLIC_QA_TOKEN: token, GANZI_PUBLIC_QA_TOKEN_ORIGIN: api });
  const result = spawnSync(process.execPath, ['--import', preload, script, api, browser, ...flags],
    { env, encoding: 'utf8', timeout: 10000 });
  assert.ifError(result.error);
  const output = result.stdout + result.stderr;
  assert.ok(!output.includes(token) && !output.includes(reply) && !output.includes(prompt),
    'Credential and chat content stay out of output');
  const calls = result.stdout.split(/\r?\n/).filter(line => line.startsWith('CALL '));
  return { status: result.status, output, calls };
}

test('default token-enabled preflight makes no chat exchange and warns that usage is admission only', () => {
  const result = run('normal');
  assert.equal(result.status, 0, result.output);
  assert.match(result.output, /local admission check only/);
  assert.match(result.output, /SKIP live AI exchange and recovery/);
  assert.ok(!result.calls.some(call => call.startsWith('CALL POST')));
});

test('QA edge opt-in sends exactly one synthetic exchange and reads the saved result', () => {
  const result = run('qa-edge', ['--verify-chat', '--qa-edge']);
  assert.equal(result.status, 0, result.output);
  assert.deepEqual(result.calls.slice(-3), ['CALL GET /api/v1/chat/history', 'CALL POST /api/v1/chat/exchanges',
    'CALL GET /api/v1/chat/exchanges']);
  assert.match(result.output, /potentially billable/);
  assert.match(result.output, /saved and recovered/);
});

test('opt-in requires a dedicated token and rejects unrelated prior history before sending', () => {
  const missing = run('normal', ['--verify-chat'], false);
  assert.equal(missing.status, 1, missing.output);
  assert.deepEqual(missing.calls, []);
  const existing = run('history', ['--verify-chat']);
  assert.equal(existing.status, 1, existing.output);
  assert.match(existing.output, /only previous synthetic probe exchanges/);
  assert.ok(!existing.calls.some(call => call.startsWith('CALL POST')));
});

test('opt-in can reuse an identity after every saved history page is verified', () => {
  const result = run('paginated', ['--verify-chat']);
  assert.equal(result.status, 0, result.output);
  assert.deepEqual(result.calls.slice(-4), ['CALL GET /api/v1/chat/history', 'CALL GET /api/v1/chat/history',
    'CALL POST /api/v1/chat/exchanges', 'CALL GET /api/v1/chat/exchanges']);
});

test('opt-in rejects looping history pages before sending', () => {
  const result = run('cursor-cycle', ['--verify-chat']);
  assert.equal(result.status, 1, result.output);
  assert.match(result.output, /Could not verify all QA history pages/);
  assert.ok(!result.calls.some(call => call.startsWith('CALL POST')));
});

for (const [mode, message] of [['provider-down', /HTTP 503 \(CHAT_NOT_CONFIGURED\)/],
  ['redirect', /redirect refused/], ['bad-reply', /did not return a successful JOY response/],
  ['recovery-mismatch', /did not recover the same successful exchange/]]) {
  test(`opt-in fails without retrying when chat returns ${mode}`, () => {
    const result = run(mode, ['--verify-chat']);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, message);
    assert.deepEqual(result.calls.filter(call => call.startsWith('CALL POST')), ['CALL POST /api/v1/chat/exchanges']);
  });
}
