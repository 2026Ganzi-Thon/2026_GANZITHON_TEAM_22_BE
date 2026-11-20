// Usage: node scripts/public-preflight.mjs <HTTPS API origin> <browser origin> [--qa-edge] [--verify-chat]
// Optional: GANZI_PUBLIC_QA_TOKEN and GANZI_PUBLIC_QA_TOKEN_ORIGIN for a synthetic QA session.
// --verify-chat requires a dedicated QA session with only probe history and makes one potentially billable exchange.
import { randomUUID } from 'node:crypto';

const healthPath = '/health';
const mePath = '/api/v1/me';
const exchangePath = '/api/v1/chat/exchanges';
const usagePath = '/api/v1/chat/usage';
const historyPath = '/api/v1/chat/history';
const historyLimit = 50;
const probeContent = '오늘 10분 동안 단어를 복습했어요. 짧게 응원해 주세요.';
const blockedStates = new Set(['CHAT_NOT_CONFIGURED', 'AI_DAILY_USAGE_EXCEEDED', 'AI_BUDGET_EXCEEDED',
  'AI_CREDIT_EXHAUSTED', 'AI_UPSTREAM_QUOTA_EXCEEDED', 'AI_USAGE_EXCEEDED_RESERVATION']);

function requireCheck(condition, message) {
  if (!condition) throw new Error(message);
}

function parseOrigin(input, label, allowedProtocols) {
  const invalidOrigin = `${label} must be a bare ${allowedProtocols.join(' or ')} origin`;
  // Parse errors may include the raw input, which could contain pasted credentials.
  requireCheck(/^https?:\/\/[^/?#@\\\s]+\/?$/.test(input ?? ''), invalidOrigin);
  let url;
  try {
    url = new URL(input);
  } catch {
    throw new Error(invalidOrigin);
  }
  requireCheck(url && allowedProtocols.includes(url.protocol) && url.port !== '0', invalidOrigin);
  return url.origin;
}

function headerValues(response, name) {
  return (response.headers.get(name) ?? '').split(',').map(value => value.trim().toLowerCase());
}

async function request(apiOrigin, method, path, expectedStatuses, headers = {}, body) {
  const label = `${method} ${path.split('?')[0]}`;
  let response;
  try {
    response = await fetch(apiOrigin + path, {
      method, headers, body, redirect: 'manual', signal: AbortSignal.timeout(method === 'POST' ? 120000 : 10000),
    });
  } catch {
    throw new Error(`${label} could not complete over verified HTTPS (check DNS, TLS and connectivity)`);
  }
  if (!expectedStatuses.includes(response.status)) {
    let detail = response.status >= 300 && response.status < 400 ? ' (redirect refused)' : '';
    if (method === 'POST' && response.status >= 400) {
      const error = await jsonBody(response, label).catch(() => null);
      if (blockedStates.has(error?.code)) detail = ` (${error.code})`;
    } else {
      await response.body?.cancel();
    }
    throw new Error(`${label} returned HTTP ${response.status}${detail}`);
  }
  return response;
}

async function jsonBody(response, label) {
  requireCheck(/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? ''),
    `${label} must return JSON`);
  try {
    return await response.json();
  } catch {
    throw new Error(`${label} returned invalid or unreadable JSON`);
  }
}

function requireBrowserOrigin(response, origin, label) {
  requireCheck(response.headers.get('access-control-allow-origin') === origin,
    `${label} must allow the exact browser origin`);
}

function isJoyReply(utterances) {
  return Array.isArray(utterances) && utterances.length >= 1 && utterances.length <= 4
    && utterances.every(utterance => utterance?.characterKey === 'JOY'
      && typeof utterance.content === 'string' && utterance.content.trim().length > 0);
}

function isSyntheticHistoryItem(item) {
  if (item?.content !== probeContent || item.studySessionId !== null
    || !/^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(item.clientRequestId ?? '')) return false;
  return (item.status === 'SUCCEEDED' && item.failureCode === null && isJoyReply(item.utterances))
    || (item.status === 'FAILED' && typeof item.failureCode === 'string' && item.failureCode.length > 0
      && Array.isArray(item.utterances) && item.utterances.length === 0);
}

async function verifySyntheticHistory(apiOrigin, browserOrigin, headers) {
  const cursors = new Set();
  let cursor = null;
  do {
    const query = new URLSearchParams({ limit: String(historyLimit) });
    if (cursor !== null) query.set('cursor', cursor);
    const history = await request(apiOrigin, 'GET', `${historyPath}?${query}`, [200], headers);
    requireBrowserOrigin(history, browserOrigin, `GET ${historyPath}`);
    const page = await jsonBody(history, historyPath);
    requireCheck(Array.isArray(page?.items) && page.items.length <= historyLimit && page.items.every(isSyntheticHistoryItem),
      'The dedicated QA identity must have empty history or only previous synthetic probe exchanges');
    cursor = page.nextCursor;
    if (cursor !== null) {
      requireCheck(page.items.length === historyLimit && typeof cursor === 'string'
        && /^[\w-]{1,160}$/.test(cursor) && !cursors.has(cursor), 'Could not verify all QA history pages');
      cursors.add(cursor);
    }
  } while (cursor !== null);
}

async function verifyExchange(apiOrigin, browserOrigin, headers) {
  await verifySyntheticHistory(apiOrigin, browserOrigin, headers);

  const clientRequestId = randomUUID();
  const body = JSON.stringify({
    clientRequestId, characterKeys: ['JOY'], content: probeContent,
  });
  console.log('Running one potentially billable synthetic chat exchange. This creates saved QA data.');
  const response = await request(apiOrigin, 'POST', exchangePath, [200], {
    ...headers, 'Content-Type': 'application/json',
  }, body);
  requireBrowserOrigin(response, browserOrigin, `POST ${exchangePath}`);
  const exchange = await jsonBody(response, exchangePath);
  const failure = blockedStates.has(exchange?.failureCode) ? ` (${exchange.failureCode})` : '';
  requireCheck(exchange?.status === 'SUCCEEDED' && exchange.clientRequestId === clientRequestId
    && typeof exchange.id === 'string' && exchange.id.length > 0 && exchange.failureCode === null
    && isJoyReply(exchange.utterances),
    `POST ${exchangePath} did not return a successful JOY response${failure}`);

  const recoveredResponse = await request(apiOrigin, 'GET', `${exchangePath}?clientRequestId=${clientRequestId}`,
    [200], headers);
  requireBrowserOrigin(recoveredResponse, browserOrigin, `GET ${exchangePath}`);
  const recovered = await jsonBody(recoveredResponse, exchangePath);
  const saved = recovered?.items?.[0];
  requireCheck(Array.isArray(recovered?.items) && recovered.items.length === 1
    && saved?.id === exchange.id && saved.clientRequestId === clientRequestId && saved.status === 'SUCCEEDED'
    && Array.isArray(saved.utterances) && saved.utterances.length === exchange.utterances.length
    && saved.utterances.every((utterance, index) => utterance?.characterKey === 'JOY'
      && utterance.content === exchange.utterances[index].content),
    `GET ${exchangePath} did not recover the same successful exchange`);
  console.log(`PASS POST and GET ${exchangePath} (successful JOY exchange saved and recovered)`);
}

async function run() {
  const [apiInput, browserInput, ...flags] = process.argv.slice(2);
  requireCheck(apiInput && browserInput && new Set(flags).size === flags.length
    && flags.every(flag => flag === '--qa-edge' || flag === '--verify-chat'),
    'Usage: node scripts/public-preflight.mjs <HTTPS API origin> <browser origin> [--qa-edge] [--verify-chat]');
  const qaEdge = flags.includes('--qa-edge');
  const verifyChat = flags.includes('--verify-chat');
  requireCheck(process.env.NODE_TLS_REJECT_UNAUTHORIZED !== '0', 'TLS certificate verification must be enabled');
  const apiOrigin = parseOrigin(apiInput, 'API origin', ['https:']);
  const browserOrigin = parseOrigin(browserInput, 'Browser origin', ['http:', 'https:']);
  requireCheck(apiOrigin !== browserOrigin, 'Use a distinct browser origin to exercise cross-origin requests');

  const token = process.env.GANZI_PUBLIC_QA_TOKEN;
  const tokenOrigin = process.env.GANZI_PUBLIC_QA_TOKEN_ORIGIN;
  if (verifyChat || token !== undefined || tokenOrigin !== undefined) {
    requireCheck(/^[A-Za-z0-9._~+/-]+=*$/.test(token ?? ''), 'Provide a valid QA bearer token in GANZI_PUBLIC_QA_TOKEN');
    requireCheck(parseOrigin(tokenOrigin, 'GANZI_PUBLIC_QA_TOKEN_ORIGIN', ['https:']) === apiOrigin,
      'The QA token origin must match the target API origin');
  }

  const health = await request(apiOrigin, 'GET', healthPath, qaEdge ? [404] : [200]);
  if (qaEdge) {
    await health.body?.cancel();
    console.log('PASS verified HTTPS and GET /health (404 as required at the QA edge)');
    console.log('SKIP database readiness (check /health inside the QA deployment)');
  } else {
    requireCheck((await jsonBody(health, healthPath))?.status === 'UP', `${healthPath} did not report UP`);
    console.log('PASS verified HTTPS and GET /health (200 UP)');
  }

  const preflight = await request(apiOrigin, 'OPTIONS', exchangePath, [200, 204], {
    Origin: browserOrigin,
    'Access-Control-Request-Method': 'POST',
    'Access-Control-Request-Headers': 'authorization, content-type',
  });
  await preflight.body?.cancel();
  requireBrowserOrigin(preflight, browserOrigin, `OPTIONS ${exchangePath}`);
  requireCheck(headerValues(preflight, 'access-control-allow-methods').includes('post'),
    `OPTIONS ${exchangePath} must allow POST`);
  const allowedHeaders = headerValues(preflight, 'access-control-allow-headers');
  requireCheck(allowedHeaders.includes('authorization') && allowedHeaders.includes('content-type'),
    `OPTIONS ${exchangePath} must allow Authorization and Content-Type`);
  console.log(`PASS OPTIONS ${exchangePath} for a browser POST with bearer auth and JSON`);

  const me = await request(apiOrigin, 'GET', mePath, [401], { Origin: browserOrigin });
  const error = await jsonBody(me, mePath);
  const requestId = me.headers.get('x-request-id');
  requireCheck(error?.code === 'AUTH_REQUIRED' && typeof error.message === 'string'
    && error.message.length > 0 && error.retryable === false && requestId && requestId === error.requestId,
    `${mePath} did not return the AUTH_REQUIRED error contract`);
  requireCheck(headerValues(me, 'cache-control').includes('no-store'), `${mePath} must use Cache-Control: no-store`);
  requireBrowserOrigin(me, browserOrigin, `GET ${mePath} (401 response)`);
  console.log(`PASS GET ${mePath} (401 AUTH_REQUIRED with exact browser origin)`);

  if (token) {
    const headers = { Origin: browserOrigin, Authorization: `Bearer ${token}` };
    const usage = await request(apiOrigin, 'GET', usagePath, [200], headers);
    const result = await jsonBody(usage, usagePath);
    requireBrowserOrigin(usage, browserOrigin, `GET ${usagePath}`);
    const availability = blockedStates.has(result?.availability) ? result.availability : 'missing or unexpected status';
    requireCheck(result?.availability === 'AVAILABLE', `${usagePath} did not report AVAILABLE (${availability})`);
    console.log(`PASS GET ${usagePath} (200 AVAILABLE, local admission check only)`);
    if (verifyChat) await verifyExchange(apiOrigin, browserOrigin, headers);
  } else {
    console.log(`SKIP GET ${usagePath} (set GANZI_PUBLIC_QA_TOKEN and GANZI_PUBLIC_QA_TOKEN_ORIGIN to include it)`);
  }
  if (!verifyChat) console.log('SKIP live AI exchange and recovery (requires --verify-chat and a dedicated QA session)');
}

try {
  await run();
} catch (error) {
  console.error(`FAIL ${error.message}`);
  process.exitCode = 1;
}
