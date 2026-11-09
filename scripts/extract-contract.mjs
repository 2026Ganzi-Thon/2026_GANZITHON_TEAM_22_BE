// Creates a local implementation reference; never edits the baseline contract.
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const source = process.argv[2];
if (!source) throw new Error('Usage: node scripts/extract-contract.mjs <baseline.openapi.json>');
const api = JSON.parse(await readFile(source, 'utf8'));
const implemented = new Set(['A01', 'A02', 'A03', 'A04', 'A05', 'A06', 'A07', 'A08',
  'A09', 'A10', 'A11', 'A12', 'A13', 'A14', 'A15', 'A21']);
const paths = {};
const tags = new Set();
let count = 0;
for (const [path, methods] of Object.entries(api.paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    if (!implemented.has(operation.operationId?.split('_')[0])) continue;
    const copy = structuredClone(operation);
    const id = copy.operationId.split('_')[0];
    if (id !== 'A21') copy.responses['429'] = { $ref: '#/components/responses/429' };
    if (id === 'A21') {
      copy.description = '로컬 기본은 UP을 반환한다. staging의 ganzi.health.check-database=true에서는 SELECT 1로 DB 연결을 확인하며 실패 시 503 DATABASE_NOT_READY를 반환한다. AI 제공자·모바일 알림의 동작 보증은 아니다.';
      copy.responses['503'] = { $ref: '#/components/responses/503' };
    }
    if (id === 'A03') {
      copy.responses['400'] = { $ref: '#/components/responses/400' };
      copy.description = '요청 본문 없이 본인 계정·모든 인증 세션·할 일·세부 항목·공부 기록·캐릭터 대화 원문과 검색 색인·푸시 기기 등록·앱 이탈 상태·다음 시작 알림 의도를 원자 삭제한다. 익명화한 AI 과금 사용량 이벤트와 집계 총액은 보존한다. 성공은 204, 삭제된 인증으로 재요청하면 401이다. 단말 예약·캐시는 앱이 따로 정리한다.';
    }
    if (id === 'A06') copy.description = '본인 할 일과 위치순 세부 항목을 조회한다. summary는 실제 세부 항목을 집계하며, 항목이 없으면 ratio는 null이다.';
    if (id === 'A08') copy.description = '본인 할 일 삭제. 미종료 공부가 연결돼 있으면 409, 종료 기록의 연결은 해제하고 세부 항목은 연쇄 삭제한다. AI 관련 보호는 AI 구현 시 추가한다.';
    (paths[path] ??= {})[method] = copy;
    copy.tags?.forEach(tag => tags.add(tag));
    count++;
  }
}
if (count !== 16) throw new Error(`Expected 16 operations, got ${count}`);
const schemaRef = name => ({ $ref: `#/components/schemas/${name}` });
const errorRef = status => ({ $ref: `#/components/responses/${status}` });
const jsonResponse = (description, name) => ({ description,
  content: { 'application/json': { schema: schemaRef(name) } } });
const jsonRequest = name => ({ required: true,
  content: { 'application/json': { schema: schemaRef(name) } } });
const commonErrors = { '400': errorRef('400'), '401': errorRef('401'),
  '429': errorRef('429'), '500': errorRef('500') };

// These four operations are implemented locally but absent from the v0.3 baseline.
// Keep their AUTH IDs separate from historical A16+ AI and reminder design IDs.
paths['/api/v1/auth/register'] = { post: {
  operationId: 'AUTH01_register', tags: ['identity'], summary: '이메일 계정 등록',
  description: 'Bearer를 생략하면 새 사용자에 이메일 계정을 만든다. 유효한 게스트 Bearer를 보내면 같은 userId에 이메일 계정을 연결하고 기존 세션을 폐기한다. 이미 등록된 계정에는 409를 반환한다. 이메일은 공백 제거·소문자 정규화하며 확인 메일 전송은 아직 구현하지 않았다. emailVerified는 false다.',
  security: [{}, { opaqueBearer: [] }], requestBody: jsonRequest('RegisterRequest'),
  responses: { '201': jsonResponse('이메일 계정 등록과 새 불투명 인증 토큰 발급', 'AuthSessionDTO'),
    ...commonErrors, '409': errorRef('409') }
} };
paths['/api/v1/auth/login'] = { post: {
  operationId: 'AUTH02_login', tags: ['identity'], summary: '이메일 계정 로그인',
  description: '이메일과 비밀번호를 확인하고 새 인증 세션을 발급한다. 기존 세션을 자동 폐기하지 않는다. 잘못된 자격 증명은 401 INVALID_CREDENTIALS다.',
  security: [], requestBody: jsonRequest('LoginRequest'),
  responses: { '200': jsonResponse('새 불투명 인증 토큰 발급', 'AuthSessionDTO'),
    ...commonErrors }
} };
paths['/api/v1/auth/logout'] = { post: {
  operationId: 'AUTH03_logout', tags: ['identity'], summary: '현재 세션 로그아웃',
  description: 'Bearer로 지정된 현재 인증 세션만 폐기한다. 요청 본문은 허용하지 않는다. 다른 기기의 세션과 사용자 데이터는 유지한다.',
  responses: { '204': { description: '현재 세션 폐기. 응답 본문 없음.' },
    ...commonErrors }
} };
paths['/api/v1/auth/password'] = { post: {
  operationId: 'AUTH04_changePassword', tags: ['identity'], summary: '이메일 계정 비밀번호 변경',
  description: '현재 Bearer와 기존 비밀번호를 확인한다. 성공하면 해당 사용자에게 발급된 모든 이전 인증 세션을 폐기하고 새 토큰을 발급한다. 게스트 계정이나 틀린 기존 비밀번호는 401 INVALID_CREDENTIALS다.',
  requestBody: jsonRequest('PasswordChangeRequest'),
  responses: { '200': jsonResponse('비밀번호 변경과 새 불투명 인증 토큰 발급', 'AuthSessionDTO'),
    ...commonErrors }
} };
count += 4;
const nullable = schema => ({ anyOf: [schema, { type: 'null' }] });
const uuid = { type: 'string', format: 'uuid' };
const dateTime = { type: 'string', format: 'date-time' };
const json = schema => ({ content: { 'application/json': { schema } } });
const clonedBaselineOperation = (path, method) => structuredClone(api.paths[path][method]);

// A22: read-only entry context. The task is a suggestion, never an implicit study start.
api.components.schemas.SuggestedTaskDTO = { type: 'object', additionalProperties: false,
  properties: { taskId: uuid, title: { type: 'string' }, completedStudySeconds: { type: 'integer', format: 'int64', minimum: 0 } },
  required: ['taskId', 'title', 'completedStudySeconds'] };
api.components.schemas.StudyStartContextDTO = { type: 'object', additionalProperties: false,
  properties: { currentSession: nullable(schemaRef('StudyDTO')),
    suggestedTask: nullable(schemaRef('SuggestedTaskDTO')), serverTime: dateTime },
  required: ['currentSession', 'suggestedTask', 'serverTime'] };
paths['/api/v1/study-sessions/start-context'] = { get: {
  operationId: 'A22_getStudyStartContext', tags: ['study'], summary: '공부 시작 화면의 현재 세션과 추천 할 일 조회',
  description: '미종료 공부 세션과 가장 최근에 공부한 사용 가능한 할 일을 조회한다. 추천 결과는 선택을 돕는 정보이며 공부를 자동 시작하지 않는다.',
  responses: { '200': jsonResponse('현재 세션과 추천 할 일', 'StudyStartContextDTO'),
    '401': errorRef('401'), '429': errorRef('429'), '500': errorRef('500') }
} };
count++;

// A16–17: one user turn may produce several character utterances. Completed turns persist.
api.components.schemas.ExchangeCreateRequest = { type: 'object', additionalProperties: false,
  properties: { clientRequestId: uuid, content: { type: 'string', minLength: 1, maxLength: 1000,
      description: 'Unicode 코드 포인트 기준 1~1000개. 공백만 있는 입력은 거부한다.' },
    studySessionId: nullable(uuid), previousRequestId: nullable(uuid),
    characterKeys: { type: 'array', minItems: 1, maxItems: 3, uniqueItems: true,
      items: { type: 'string', enum: ['ANGRY', 'JOY', 'SAD'] },
      description: '생략하면 세 캐릭터를 모두 지정한다. 응답 발화 수는 달라질 수 있다.' } },
  required: ['clientRequestId', 'content'] };
api.components.schemas.ChatUtteranceDTO = { type: 'object', additionalProperties: false,
  properties: { characterKey: { type: 'string', enum: ['ANGRY', 'JOY', 'SAD'] },
    content: { type: 'string', minLength: 1, maxLength: 300,
      description: 'Unicode 코드 포인트 기준 최대 300개이며 공백만 있는 발화는 허용하지 않는다.' } },
  required: ['characterKey', 'content'] };
api.components.schemas.ExchangeDTO = { type: 'object', additionalProperties: false,
  properties: { id: uuid, clientRequestId: uuid, studySessionId: nullable(uuid),
    status: { type: 'string', enum: ['PROCESSING', 'SUCCEEDED', 'FAILED'] },
    utterances: { type: 'array', maxItems: 4, items: schemaRef('ChatUtteranceDTO'),
      description: 'SUCCEEDED면 요청 캐릭터 중 1~4개 발화를 순서대로 반환한다. FAILED와 PROCESSING은 빈 배열이다.' },
    failureCode: { ...nullable({ type: 'string', enum: [
      'AI_PROVIDER_FAILED', 'AI_RESPONSE_INVALID', 'AI_REFUSED',
      'AI_CREDIT_EXHAUSTED', 'AI_UPSTREAM_QUOTA_EXCEEDED', 'AI_UPSTREAM_RATE_LIMITED',
      'AI_BUDGET_EXCEEDED', 'AI_DAILY_USAGE_EXCEEDED', 'AI_MODEL_PRICE_NOT_CONFIGURED',
      'AI_REQUEST_TOO_LARGE', 'AI_USAGE_EXCEEDED_RESERVATION', 'AI_USAGE_RECORD_FAILED',
      'CHAT_REQUEST_FAILED', 'VALIDATION_FAILED', 'CHAT_MEMORY_WRITE_FAILED',
      'CHAT_RESULT_UNAVAILABLE', 'STUDY_SESSION_ENDED', 'CHAT_DEADLINE_EXCEEDED'
    ] }), description: 'FAILED 응답의 오류 코드다. AI 요청 본문 생성에 실패하면 CHAT_REQUEST_FAILED, 모델 호출 전 입력 검증에 실패하면 VALIDATION_FAILED가 반환될 수 있다. 컨트롤러의 일반 요청 검증 실패는 별도 HTTP 400 오류다.' },
    createdAt: dateTime, finishedAt: nullable(dateTime) },
  required: ['id', 'clientRequestId', 'studySessionId', 'status', 'utterances', 'failureCode', 'createdAt', 'finishedAt'] };
api.components.schemas.ExchangeListDTO = { type: 'object', additionalProperties: false,
  properties: { items: { type: 'array', items: schemaRef('ExchangeDTO') } }, required: ['items'] };
paths['/api/v1/chat/exchanges'] = {
  post: { operationId: 'A16_createExchange', tags: ['chat'], summary: '캐릭터 대화 한 턴 생성',
    description: '저장이 완료된 성공·실패 대화를 서버에 보존한다. 원문은 60일간 보관한 뒤 gzip으로 무손실 압축하며 자동 삭제하지 않는다. 동일 clientRequestId와 동일 입력의 저장된 결과는 서버 재시작·압축 뒤에도 모델 재호출 없이 반환한다. 동일 유료 요청의 예약이 5분 이내의 진행 중 상태면 다른 서버 프로세스에서도 409 REQUEST_IN_PROGRESS다. 완료 결과가 생기면 동시 재시도에도 그 결과를 반환한다. 이미 승인된 ID에서 더 이상 진행 중인 예약이나 결과를 찾지 못하면 새 AI 호출이나 대화 내역 생성 없이 200 FAILED CHAT_RESULT_UNAVAILABLE을 반환한다. 오래된 미정산 예약은 UNKNOWN으로 정리하며 예약 금액을 보수적으로 사용량에 유지한다. 대화 결과 저장과 사용량 정산은 한 트랜잭션에서 처리한다. 저장 또는 정산에 실패하면 각각 200 FAILED CHAT_MEMORY_WRITE_FAILED 또는 AI_USAGE_RECORD_FAILED를 반환한다. HTTP 409 코드는 CLIENT_REQUEST_ID_REUSED, REQUEST_IN_PROGRESS, CHAT_IN_PROGRESS, INVALID_PREVIOUS_EXCHANGE, INVALID_STUDY_STATE다. HTTP 429는 CHAT_RATE_LIMITED(사용자별 최근 60초에 10건, Retry-After 60) 또는 공통 요청 제한 RATE_LIMITED다. AI_MODEL_PRICE_NOT_CONFIGURED, AI_REQUEST_TOO_LARGE, AI_BUDGET_EXCEEDED, AI_DAILY_USAGE_EXCEEDED 등 AI 설정·과금 실패와 제공자 오류·응답 생성 중 공부방 종료는 200 FAILED의 failureCode다. 컨트롤러의 일반 요청 검증 실패는 별도 HTTP 400이다. HTTP 503은 CHAT_NOT_CONFIGURED, CHAT_CAPACITY_REACHED 또는 CHAT_HISTORY_UNAVAILABLE이다. 앱의 사용량 한도와 제공자 계정의 실제 잔액은 다르다.',
    requestBody: jsonRequest('ExchangeCreateRequest'),
    responses: { '200': jsonResponse('대화 생성 또는 이전 요청 결과', 'ExchangeDTO'),
      '400': errorRef('400'), '401': errorRef('401'), '404': errorRef('404'),
      '409': errorRef('409'), '429': errorRef('429'), '500': errorRef('500'), '503': errorRef('503') } },
  get: { operationId: 'A17_recoverExchange', tags: ['chat'], summary: '요청 ID로 대화 결과 복구',
    description: '본인 요청 한 건을 찾는다. 저장된 성공·실패 결과는 서버 재시작과 gzip 압축 뒤에도 복구한다. 유료 요청이 아직 처리 중이거나 완료 결과가 저장되지 않았다면 items=[]다. 저장 내역 손상·읽기 실패는 503 CHAT_HISTORY_UNAVAILABLE이다.',
    parameters: [{ name: 'clientRequestId', in: 'query', required: true, schema: uuid }],
    responses: { '200': jsonResponse('검색 결과', 'ExchangeListDTO'),
      '400': errorRef('400'), '401': errorRef('401'), '429': errorRef('429'), '500': errorRef('500'), '503': errorRef('503') } }
};
count += 2;

api.components.schemas.ChatHistoryItemDTO = { type: 'object', additionalProperties: false,
  properties: { clientRequestId: uuid, studySessionId: nullable(uuid), content: { type: 'string' },
    status: { type: 'string', enum: ['SUCCEEDED', 'FAILED'] },
    utterances: { type: 'array', items: schemaRef('ChatUtteranceDTO'),
      description: 'FAILED일 때 빈 배열이다.' }, failureCode: nullable({ type: 'string' }),
    createdAt: dateTime, finishedAt: dateTime },
  required: ['clientRequestId', 'studySessionId', 'content', 'status', 'utterances', 'failureCode', 'createdAt', 'finishedAt'] };
api.components.schemas.ChatHistoryPageDTO = { type: 'object', additionalProperties: false,
  properties: { items: { type: 'array', items: schemaRef('ChatHistoryItemDTO') },
    nextCursor: nullable({ type: 'string' }) }, required: ['items', 'nextCursor'] };
paths['/api/v1/chat/history'] = { get: {
  operationId: 'CHAT01_listHistory', tags: ['chat'], summary: '내 캐릭터 대화 내역 조회',
  description: '본인의 완료된 성공·실패 대화를 최신순으로 페이지 조회한다. 같은 공부방과 이전 날짜의 대화를 포함하며 gzip 압축된 대화도 투명하게 복원한다. 일부 기록의 저장 본문이 손상되면 그 항목은 건너뛰고 정상 항목을 계속 반환한다. AI 문맥에는 성공한 대화만 사용한다. 다른 사용자의 기록은 노출하지 않는다. 잘못된 커서는 400, 조회 자체가 실패하면 503 CHAT_HISTORY_UNAVAILABLE이다.',
  parameters: [
    { name: 'limit', in: 'query', required: false, schema: { type: 'integer', minimum: 1, maximum: 50, default: 20 } },
    { name: 'cursor', in: 'query', required: false, schema: { type: 'string', maxLength: 256 }, description: '직전 응답의 불투명 nextCursor' }
  ], responses: { '200': jsonResponse('대화 내역 페이지', 'ChatHistoryPageDTO'),
    '400': errorRef('400'), '401': errorRef('401'), '429': errorRef('429'), '500': errorRef('500'), '503': errorRef('503') }
} };
count++;

// A25: authenticated, user-scoped accounting view; never exposes provider credit balance.
api.components.schemas.ChatPeriodUsageDTO = { type: 'object', additionalProperties: false,
  properties: { startsOn: { type: 'string', format: 'date' },
    requests: { type: 'integer', format: 'int64', minimum: 0 },
    inputTokens: { type: 'integer', format: 'int64', minimum: 0 },
    cachedInputTokens: { type: 'integer', format: 'int64', minimum: 0 },
    outputTokens: { type: 'integer', format: 'int64', minimum: 0 },
    estimatedUsd: { type: 'number', minimum: 0 },
    reservedUsd: { type: 'number', minimum: 0 } },
  required: ['startsOn', 'requests', 'inputTokens', 'cachedInputTokens', 'outputTokens',
    'estimatedUsd', 'reservedUsd'] };
api.components.schemas.ChatUsageDTO = { type: 'object', additionalProperties: false,
  properties: { timeZone: { type: 'string', const: 'UTC' },
    daily: schemaRef('ChatPeriodUsageDTO'), monthly: schemaRef('ChatPeriodUsageDTO'),
    dailyRequestLimit: { type: 'integer', minimum: 1 },
    availability: { type: 'string', enum: ['AVAILABLE', 'AI_BUDGET_EXCEEDED',
      'AI_DAILY_USAGE_EXCEEDED', 'AI_CREDIT_EXHAUSTED', 'AI_UPSTREAM_QUOTA_EXCEEDED',
      'AI_USAGE_EXCEEDED_RESERVATION'] } },
  required: ['timeZone', 'daily', 'monthly', 'dailyRequestLimit', 'availability'] };
paths['/api/v1/chat/usage'] = { get: {
  operationId: 'A25_getChatUsage', tags: ['chat'], summary: '내 AI 사용량과 요청 가능 상태 조회',
  description: 'UTC 일·달력 월 기준 본인 사용량이다. requests와 토큰은 저장된 본인 요청만 집계하고 estimatedUsd는 앱 scope의 보수적 추정치다. reservedUsd는 과금 결과가 아직 불확실한 예약액이다. 캐시 토큰 할인을 반영하지 않는다. 앱 전체 한도나 OpenAI 계정의 실시간 잔액은 이 응답에 없다.',
  responses: { '200': jsonResponse('내 일별·월별 AI 사용량', 'ChatUsageDTO'),
    '401': errorRef('401'), '429': errorRef('429'), '500': errorRef('500') }
} };
count++;

// A18–20 retain historical IDs, with response shapes aligned to the implemented Java records.
api.components.schemas.ReminderDTO = { type: 'object', additionalProperties: false,
  properties: { id: uuid, sourceSessionId: uuid, remindAt: dateTime,
    timeZone: { type: 'string', minLength: 1, maxLength: 64, description: 'IANA time zone, e.g. Asia/Seoul' },
    status: { type: 'string', enum: ['ACTIVE', 'CANCELED', 'EXPIRED'] },
    canceledReason: nullable({ type: 'string', enum: ['USER_CANCELED', 'NEW_STUDY'] }),
    createdAt: dateTime, updatedAt: dateTime },
  required: ['id', 'sourceSessionId', 'remindAt', 'timeZone', 'status', 'canceledReason', 'createdAt', 'updatedAt'],
  description: '서버가 저장한 예약 의도다. 단말 OS 알림 전송 성공을 뜻하지 않는다.' };
const reminderPutPath = '/api/v1/study-sessions/{sessionId}/next-start-reminder';
paths[reminderPutPath] = { put: clonedBaselineOperation(reminderPutPath, 'put') };
paths[reminderPutPath].put.description = '본인의 가장 최근 ENDED 공부에서만 7일 이내 미래 시각을 지정한다. 같은 입력 재시도는 200, 다른 입력·다른 활성 의도는 409다. 앱이 응답을 기준으로 단말 OS 알림을 별도로 예약한다.';
paths[reminderPutPath].put.responses['201'] = jsonResponse('새 알림 의도 저장', 'ReminderCurrentDTO');
paths[reminderPutPath].put.responses['200'] = jsonResponse('동일 요청 재조회', 'ReminderCurrentDTO');
const currentReminderPath = '/api/v1/start-reminders/current';
paths[currentReminderPath] = { get: clonedBaselineOperation(currentReminderPath, 'get') };
paths[currentReminderPath].get.responses['429'] = errorRef('429');
const deleteReminderPath = '/api/v1/start-reminders/{reminderId}';
paths[deleteReminderPath] = { delete: clonedBaselineOperation(deleteReminderPath, 'delete') };
paths[deleteReminderPath].delete.responses['429'] = errorRef('429');
api.components.schemas.ReminderChangeRequest = { type: 'object', additionalProperties: false,
  properties: { expectedRemindAt: dateTime,
    expectedTimeZone: { type: 'string', minLength: 1, maxLength: 64, description: '마지막으로 조회한 IANA 시간대' },
    remindAt: dateTime,
    timeZone: { type: 'string', minLength: 1, maxLength: 64, description: '변경할 IANA 시간대' } },
  required: ['expectedRemindAt', 'expectedTimeZone', 'remindAt', 'timeZone'] };
paths[deleteReminderPath].patch = {
  operationId: 'A24_changeStartReminderTime', tags: ['reminders'], summary: '활성 다음 시작 알림 시각 변경',
  description: '본인의 ACTIVE 알림 ID를 유지하면서 7일 이내 미래 시각과 IANA 시간대를 변경한다. expectedRemindAt·expectedTimeZone은 마지막 조회값과 일치해야 하며 어긋나면 409 REMINDER_PRECONDITION_FAILED다. 이미 목표값과 동일한 ACTIVE 상태면 재시도 200이다. CANCELED/EXPIRED는 409 REMINDER_NOT_ACTIVE다. 앱은 새 응답을 기준으로 단말 OS 예약을 교체한다.',
  parameters: [{ name: 'reminderId', in: 'path', required: true, schema: uuid }],
  requestBody: jsonRequest('ReminderChangeRequest'),
  responses: { '200': jsonResponse('변경되거나 이미 목표값과 일치하는 알림', 'ReminderCurrentDTO'),
    '400': errorRef('400'), '401': errorRef('401'), '404': errorRef('404'),
    '409': errorRef('409'), '429': errorRef('429'), '500': errorRef('500') }
};
api.components.schemas.TaskReminderCandidateDTO = { type: 'object', additionalProperties: false,
  properties: { taskId: uuid, dueDate: { type: 'string', format: 'date' },
    reminderDate: { type: 'string', format: 'date' } },
  required: ['taskId', 'dueDate', 'reminderDate'] };
api.components.schemas.TaskReminderCandidatesDTO = { type: 'object', additionalProperties: false,
  properties: { items: { type: 'array', items: schemaRef('TaskReminderCandidateDTO') },
    nextOffset: nullable({ type: 'integer', minimum: 0 }), serverTime: dateTime },
  required: ['items', 'nextOffset', 'serverTime'] };
paths['/api/v1/task-reminders'] = { get: {
  operationId: 'A23_listTaskReminderCandidates', tags: ['reminders'], summary: '할 일 마감 하루 전 로컬 알림 후보 조회',
  description: 'TODO 상태이고 요청 시간대의 내일 이후에 마감되는 할 일만 조회한다. reminderDate는 dueDate 하루 전이다. 앱이 단말 OS에 예약하고, 완료·삭제·마감일 변경 때 다시 동기화한다. 서버 푸시는 구현하지 않았다.',
  parameters: [
    { name: 'timeZone', in: 'query', required: true, schema: { type: 'string', minLength: 1, maxLength: 64 }, description: 'IANA 시간대' },
    { name: 'limit', in: 'query', required: false, schema: { type: 'integer', minimum: 1, maximum: 100, default: 50 } },
    { name: 'offset', in: 'query', required: false, schema: { type: 'integer', minimum: 0, default: 0 } }
  ], responses: { '200': jsonResponse('마감 하루 전 후보 목록', 'TaskReminderCandidatesDTO'),
    '400': errorRef('400'), '401': errorRef('401'), '429': errorRef('429'), '500': errorRef('500') }
} };
count += 5;

// App lifecycle reports and Expo Push Token registration. Delivery is opt-in at the server.
api.components.schemas.PushDevicePutRequest = { type: 'object', additionalProperties: false,
  properties: { expoPushToken: { type: 'string', minLength: 23,
    description: 'ExpoPushToken[...] 또는 ExponentPushToken[...] 형식. 서버는 소유권을 검사하고 원문 토큰을 응답에 넣지 않는다.' } },
  required: ['expoPushToken'] };
api.components.schemas.PushDeviceDTO = { type: 'object', additionalProperties: false,
  properties: { installationId: uuid, serverTime: dateTime },
  required: ['installationId', 'serverTime'] };
api.components.schemas.StudyPresencePutRequest = { type: 'object', additionalProperties: false,
  properties: { installationId: uuid, eventId: uuid,
    sequence: { type: 'integer', format: 'int64', minimum: 0 },
    state: { type: 'string', enum: ['FOREGROUND', 'BACKGROUND'] } },
  required: ['installationId', 'eventId', 'sequence', 'state'] };
api.components.schemas.StudyPresenceDTO = { type: 'object', additionalProperties: false,
  properties: { sessionId: uuid, installationId: uuid,
    state: { type: 'string', enum: ['FOREGROUND', 'BACKGROUND'] },
    awayAt: nullable(dateTime), dueAt: nullable(dateTime),
    deliveryStatus: { type: 'string', enum: ['NONE', 'PENDING', 'SENDING', 'SENT',
      'CANCELED', 'FAILED', 'DELIVERY_UNKNOWN'] },
    deliveryEnabled: { type: 'boolean', description: '현재 서버의 원격 푸시 전송 활성화 여부. false여도 PENDING 예약 데이터는 저장될 수 있다.' },
    serverTime: dateTime },
  required: ['sessionId', 'installationId', 'state', 'awayAt', 'dueAt', 'deliveryStatus', 'deliveryEnabled', 'serverTime'] };
paths['/api/v1/push-devices/{installationId}'] = {
  put: { operationId: 'PUSH01_registerDevice', tags: ['reminders'], summary: '내 앱 설치의 Expo 푸시 토큰 등록 또는 갱신',
    description: 'installationId는 앱 설치마다 생성·보관하는 UUID다. 같은 사용자·설치에서 등록을 반복하거나 토큰을 갱신할 수 있다. 앱 재설치 등으로 같은 사용자의 Expo 토큰을 새로운 installationId로 등록하면 이전 설치 기록과 그 설치의 대기 중 앱 상태·알림을 삭제한 뒤 새 설치로 옮긴다. HTTP 409 코드는 다른 사용자 소유 설치 ID의 PUSH_DEVICE_OWNERSHIP_CONFLICT, 다른 사용자 소유 토큰의 PUSH_TOKEN_OWNERSHIP_CONFLICT, 동시 등록 충돌의 PUSH_DEVICE_CONFLICT다. 서버의 원격 푸시 전송은 GANZI_PUSH_ENABLED=false가 기본이며, 이 응답은 실제 알림 수신을 보증하지 않는다.',
    parameters: [{ name: 'installationId', in: 'path', required: true, schema: uuid }],
    requestBody: jsonRequest('PushDevicePutRequest'),
    responses: { '200': jsonResponse('등록 또는 갱신된 앱 설치', 'PushDeviceDTO'),
      '400': errorRef('400'), '401': errorRef('401'), '409': errorRef('409'),
      '429': errorRef('429'), '500': errorRef('500') } },
  delete: { operationId: 'PUSH02_removeDevice', tags: ['reminders'], summary: '내 앱 설치의 푸시 토큰 해제',
    description: '본인 소유의 설치 기록만 삭제한다. 존재하지 않거나 다른 사용자 소유인 설치 ID에도 존재 여부를 숨기기 위해 204를 반환한다.',
    parameters: [{ name: 'installationId', in: 'path', required: true, schema: uuid }],
    responses: { '204': { description: '푸시 토큰 해제. 응답 본문 없음.' },
      '400': errorRef('400'), '401': errorRef('401'), '429': errorRef('429'), '500': errorRef('500') } }
};
paths['/api/v1/study-sessions/{sessionId}/presence'] = { put: {
  operationId: 'PUSH03_reportStudyPresence', tags: ['study', 'reminders'], summary: '공부 중 앱 전경·배경 상태 보고',
  description: '앱이 공부 중 BACKGROUND를 보고하면 서버 시각 5분 뒤 확인 알림을 예약한다. 같은 eventId와 동일 입력의 재전송은 200이며, 새로운 BACKGROUND를 반복 보고해도 첫 이탈 시각은 유지된다. FOREGROUND 복귀 또는 공부 일시 정지·종료는 대기 중 알림을 취소한다. HTTP 409 코드는 동일 eventId에 다른 입력을 사용한 PRESENCE_EVENT_REUSED, 이전 이하의 sequence를 보낸 PRESENCE_SEQUENCE_CONFLICT, 공부가 STUDYING이 아닌 상태에서 BACKGROUND를 보낸 STUDY_NOT_RUNNING이다. 서버는 앱 강제 종료를 항상 감지할 수 없으므로 클라이언트가 앱 상태 전환을 보고해야 한다. deliveryEnabled=false에서는 PENDING이어도 원격 푸시는 발송하지 않는다. 원격 푸시 전송은 서버 설정으로 별도 활성화해야 한다.',
  parameters: [{ name: 'sessionId', in: 'path', required: true, schema: uuid }],
  requestBody: jsonRequest('StudyPresencePutRequest'),
  responses: { '200': jsonResponse('서버가 저장한 앱 상태와 알림 상태', 'StudyPresenceDTO'),
    '400': errorRef('400'), '401': errorRef('401'), '404': errorRef('404'),
    '409': errorRef('409'), '429': errorRef('429'), '500': errorRef('500') }
} };
count += 3;
api.components.schemas.MeDTO = {
  type: 'object', additionalProperties: false,
  properties: {
    userId: { type: 'string', format: 'uuid' },
    expiresAt: { type: 'string', format: 'date-time' },
    accountType: { type: 'string', enum: ['GUEST', 'EMAIL'] },
    email: { anyOf: [{ type: 'string', format: 'email', maxLength: 254 }, { type: 'null' }] },
    emailVerified: { type: 'boolean' }
  },
  required: ['userId', 'expiresAt', 'accountType', 'email', 'emailVerified'],
  description: '게스트는 accountType=GUEST, email=null, emailVerified=false다.'
};
api.components.schemas.AuthSessionDTO = {
  type: 'object', additionalProperties: false,
  properties: {
    userId: { type: 'string', format: 'uuid' },
    accessToken: { type: 'string', description: '최초 응답에서만 전달되는 불투명 Bearer 비밀값' },
    expiresAt: { type: 'string', format: 'date-time' },
    accountType: { type: 'string', const: 'EMAIL' },
    email: { type: 'string', format: 'email', maxLength: 254 },
    emailVerified: { type: 'boolean', description: '신규 등록은 false. 이메일 확인 기능은 현재 미구현.' }
  },
  required: ['userId', 'accessToken', 'expiresAt', 'accountType', 'email', 'emailVerified']
};
const emailProperty = { type: 'string', format: 'email', maxLength: 254,
  description: '서버는 공백 제거·소문자 정규화 후 제한된 이메일 형식을 검사한다.' };
api.components.schemas.RegisterRequest = {
  type: 'object', additionalProperties: false,
  properties: { email: emailProperty, password: { type: 'string', minLength: 15, maxLength: 128,
    description: 'Unicode 코드 포인트 15~128개. 비밀번호 원문은 응답에 포함되지 않는다.' } },
  required: ['email', 'password']
};
api.components.schemas.LoginRequest = {
  type: 'object', additionalProperties: false,
  properties: { email: emailProperty, password: { type: 'string', minLength: 1, maxLength: 128,
    description: '로그인 입력은 Unicode 코드 포인트 1~128개다.' } },
  required: ['email', 'password']
};
api.components.schemas.PasswordChangeRequest = {
  type: 'object', additionalProperties: false,
  properties: {
    currentPassword: { type: 'string', minLength: 1, maxLength: 128 },
    newPassword: { type: 'string', minLength: 15, maxLength: 128,
      description: 'Unicode 코드 포인트 15~128개.' }
  },
  required: ['currentPassword', 'newPassword']
};
api.components.responses['429'] = {
  description: 'RATE_LIMITED. 현재 로컬 구현의 프로세스 내 요청 제한에 걸림.',
  headers: { 'Retry-After': { description: '다음 요청까지 기다릴 초 수', schema: { type: 'integer', minimum: 1 } } },
  content: { 'application/json': { schema: schemaRef('ErrorDTO') } }
};
api.components.responses['405'] = {
  description: '알려진 경로에서 지원하지 않는 HTTP 메서드. 코드 METHOD_NOT_ALLOWED. Allow 헤더는 해당 경로에서 허용하는 메서드를 알린다.',
  headers: { Allow: { description: '해당 경로에서 허용하는 HTTP 메서드', schema: { type: 'string' } } },
  content: { 'application/json': { schema: schemaRef('ErrorDTO') } }
};
api.components.responses['406'] = {
  description: 'JSON 응답을 제공하는 경로에서 Accept가 application/json을 허용하지 않음. 코드 NOT_ACCEPTABLE.',
  content: { 'application/json': { schema: schemaRef('ErrorDTO') } }
};
api.components.responses['415'] = {
  description: 'JSON 요청 본문이 필요한 경로에서 Content-Type이 지원되지 않음. 코드 UNSUPPORTED_MEDIA_TYPE.',
  content: { 'application/json': { schema: schemaRef('ErrorDTO') } }
};
if (count !== 33) throw new Error(`Expected 33 operations, got ${count}`);
for (const methods of Object.values(paths)) {
  for (const operation of Object.values(methods)) {
    const jsonSuccess = Object.entries(operation.responses).some(([status, response]) =>
      status.startsWith('2') && response.content?.['application/json']);
    if (jsonSuccess) operation.responses['406'] = errorRef('406');
    if (operation.requestBody?.content?.['application/json']) operation.responses['415'] = errorRef('415');
  }
}
api.paths = paths;
tags.add('study');
tags.add('chat');
tags.add('reminders');
api.tags = api.tags.filter(tag => tags.has(tag.name));
api.info = { title: '간지톤 22팀 백엔드 로컬 구현 참조 계약', version: '0.6.0-local',
  description: '현재 구현한 33개 HTTP 연산의 참조 계약. v0.3 설계의 16개 기본 연산과 인증 4개, 공부 시작 맥락 조회 1개, 캐릭터 대화 2개·사용량 조회 1개·장기 내역 조회 1개, 알림 의도·후보·시각 변경 5개, 푸시 기기 2개·공부 중 앱 상태 보고 1개를 포함한다. 완료된 대화 원문은 60일 뒤 gzip으로 무손실 압축하며 자동 삭제하지 않는다. OpenAI 대화는 기본 비활성이며 명시적 키와 모델 설정이 필요하다. 사용량·예산은 이 앱의 보수적 추정이며 제공자 잔액이 아니다. 서버 푸시는 기본 비활성이며 앱이 OS 단말 알림·권한과 앱 상태 전환 보고를 맡는다. 알려진 경로에 지원하지 않는 HTTP 메서드를 보내면 405 METHOD_NOT_ALLOWED와 Allow 헤더를 반환한다. JSON 응답을 제공하는 연산에서 Accept가 JSON을 허용하지 않으면 406 NOT_ACCEPTABLE, JSON 본문을 받는 연산에 지원하지 않는 Content-Type을 보내면 415 UNSUPPORTED_MEDIA_TYPE이 발생할 수 있다. 운영 배포를 뜻하지 않는다.' };
api.servers = [{ url: 'http://127.0.0.1:8081', description: '노트북에서만 접근하는 로컬 초기본' }];
api['x-baseline'] = '간지톤22팀-백엔드-API-v0.3.openapi.json';
const seen = new Set();
function visit(value) {
  if (!value || typeof value !== 'object') return;
  if (value.$ref) {
    const ref = value.$ref;
    if (!ref.startsWith('#/components/')) throw new Error(`Unexpected ref ${ref}`);
    if (!seen.has(ref)) {
      seen.add(ref);
      let target = api;
      for (const part of ref.slice(2).split('/')) target = target?.[part];
      if (!target) throw new Error(`Missing ${ref}`);
      visit(target);
    }
  }
  for (const item of Object.values(value)) visit(item);
}
visit(api.paths);
const components = { securitySchemes: api.components.securitySchemes };
for (const ref of seen) {
  const [, , section, name] = ref.split('/');
  (components[section] ??= {})[name] = api.components[section][name];
}
components.responses['405'] = api.components.responses['405'];
api.components = components;
await mkdir(resolve('docs'), { recursive: true });
await writeFile(resolve('docs/implemented.openapi.json'), JSON.stringify(api, null, 2) + '\n');
console.log(`Local OpenAPI: ${count} operations, ${seen.size} resolved component references`);
