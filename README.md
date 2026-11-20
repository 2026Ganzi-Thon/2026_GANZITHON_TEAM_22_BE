# 해보양 백엔드

해보양은 할 일 관리와 공부 세션, 다음 시작 알림, 캐릭터 대화를 제공하는 서비스입니다. 백엔드는 Java 21, Spring Boot, Spring JDBC와 MySQL 8을 사용합니다. HTTP 연산의 요청·응답은 [OpenAPI 명세](docs/implemented.openapi.json)에 있습니다.

## 로컬에서 빌드하고 실행하기

Java 21 JDK를 설치하고 `JAVA_HOME`을 설정합니다. Gradle은 저장소의 Wrapper를 사용합니다.

```sh
./gradlew bootJar --no-daemon
```

Windows에서는 `gradlew.bat bootJar --no-daemon`을 실행합니다. 별도 MySQL 데이터베이스를 준비하고 아래 배포 환경변수를 넣은 뒤, `SPRING_PROFILES_ACTIVE=staging`으로 JAR를 실행합니다. Flyway는 빈 DB에 V1부터 V8까지 적용합니다. 기존 DB에는 마지막 적용 버전 다음부터 순서대로 적용합니다. V5는 6.2 캐시 읽기·쓰기의 단가와 사용량을 기록합니다. V6은 기존 푸시 기기·알림을 보존하면서 푸시 등록과 인증 세션을 연결합니다. V7은 할 일·세부 할 일 생성 요청의 중복 방지 기록을 추가합니다. V8은 할 일 편집 버전을 추가합니다.

`local` 프로필은 `schema-mysql.sql`로 빈 개발 DB를 초기화합니다. 기존 개발 DB를 유지하려면 백업 후 현재 구조와 적용 이력을 확인하고 누락된 변경을 [마이그레이션 파일](src/main/resources/db/migration) 순서대로 한 번씩 적용합니다. `staging`에서는 Flyway가 이 과정을 자동으로 수행합니다.

푸시를 쓰는 앱은 설치 UUID를 로컬에 보관하고 현재 Bearer로 `PUT /api/v1/push-devices/{installationId}`를 호출합니다. 세션 연결이 없거나 연결된 인증이 만료된 설치는 같은 계정으로 재인증한 뒤 기존 설치 UUID와 저장된 Expo 토큰을 다시 보내 연결할 수 있습니다. 계정을 바꿀 때는 기존 Bearer로 본문 없이 `POST /api/v1/auth/logout`을 호출하고 `204`를 받은 뒤 새 계정에 로그인해 동일한 설치 UUID와 Expo 토큰을 등록합니다. 만료된 인증 동작은 동일 계정으로 재인증과 재등록을 거쳐 복구합니다. 서버의 발송 직전 확인을 이미 통과한 푸시는 로그아웃 후에도 도착할 수 있습니다.

할 일과 세부 할 일 생성 시 선택적인 `clientRequestId` UUID를 보내면, 응답이 끊긴 뒤 같은 ID·같은 내용으로 재시도해도 중복 생성하지 않습니다. 최초 응답은 201, 재조회는 200입니다. 다른 내용으로 ID를 재사용하거나 이미 삭제된 생성 결과를 재시도하면 409입니다. 할 일 편집 화면에서는 조회 응답의 `version`을 PATCH 요청의 선택적인 `expectedVersion`으로 보내 경쟁 편집을 감지할 수 있습니다. 수정한 필드만 전송하고, 409 `TASK_VERSION_CONFLICT`가 나면 최신 할 일을 다시 조회해 사용자의 변경 내용을 확인해야 합니다. 성공한 PATCH를 같은 expectedVersion으로 재시도할 때도 409가 정상입니다. 세부 할 일 편집에는 이 버전이 적용되지 않습니다. 공부 세션의 할 일 누적 시간은 `totalTaskStudySeconds`를 사용합니다. 할 일 없이 시작한 세션에서는 이 값이 null이며 `studySeconds`가 해당 세션 시간입니다. 자세한 요청·응답은 [OpenAPI 명세](docs/implemented.openapi.json)를 참조합니다.

```sh
java -jar build/libs/ganzithon-backend-0.6.0-local.jar
curl -fsS http://localhost:8080/health
```

## 검증

[11월 20일 백엔드 QA](docs/verification/backend-qa-2026-11-20.md)에 생성 재시도·동시 편집·대화 이력·누적 공부 시간의 프론트 연동 조건과 배포 전 확인할 항목을 정리했습니다.

알림 예약과 AI 답변 저장·정산을 합친 버전의 시험 범위, 실행 결과, 팀 리뷰 항목은 [서비스 통합 검증 기록](docs/service-integration-qa.md)에 정리했습니다.

Windows에서 Node.js와 MySQL 8을 설치한 뒤 `node scripts/verify-local.mjs`를 실행합니다. MySQL을 다른 경로에 설치했다면 `GANZI_MYSQL_HOME`에 설치 디렉터리를 지정합니다. 이미 내려받은 Gradle 의존성만 쓰려면 `GANZI_GRADLE_OFFLINE=true`를 설정합니다. 이 스크립트는 별도 로컬 MySQL을 임시 포트에서 시작하고, 자동 시험과 HTTP 점검을 마친 뒤 종료합니다. 로그와 결과는 `build/verification-local/`에 저장합니다.

배포된 API의 HTTPS·웹 Origin·인증 응답을 확인하려면 [서버 연결 검사](deploy/aws-ec2/README.md#공개-웹이-railway에-연결된-경우)를 따릅니다. `GET /api/v1/chat/usage`의 `AVAILABLE`은 서버가 알고 있는 설정·예산 검사를 통과했다는 뜻입니다. 실제 답변 생성·저장은 전용 QA 계정으로 `--verify-chat` 검사를 실행해 확인합니다. 검사 도구 자체는 `node --test scripts/public-preflight.test.mjs`로 시험합니다.

같은 명령이 빈 DB에 Flyway를 적용하고 `staging` 프로필로 JAR를 실행합니다. 시험용 DB 자격증명은 실행기가 생성합니다. 서버를 재시작한 뒤 게스트에서 이메일 계정으로 전환한 사용자의 할 일·공부·알림 기록을 확인합니다. 실행별 `result.json`에서 `tests`, `smoke`, `stagingSmoke`, `schemaParity`, `mysqlStopped`를 확인할 수 있습니다. `smoke`에는 OpenAPI 연산 수, 실제 호출한 연산과 호출하지 않은 연산 목록, 시나리오 점검 수를 기록합니다.

11월 18일 통합 검증 기록에는 자동 테스트 165개와 HTTP 시나리오 점검 24개가 통과한 결과가 있습니다. 실제 AI 대화 생성과 휴대전화 푸시 수신은 별도의 연동 환경에서 확인합니다.

## AWS 배포 시 설정

EC2·RDS와 단일 EC2에 배포하는 절차는 [EC2 배포 가이드](deploy/aws-ec2/README.md)에 있습니다. 2026년 11월 20일 확인한 팀 QA 인스턴스는 시드니(`ap-southeast-2`)에 있습니다. 가이드에서 서울 리전이 기본값인 명령은 실제 배포 대상의 리전을 지정해서 실행합니다.

`docker build -t haeboyang-backend .`로 Java 21 컨테이너를 만들 수 있습니다. 이미지 기본 프로필은 `staging`, 기본 포트는 `8080`입니다. 이 프로필은 외부 MySQL을 사용하고 `/health`에서 DB 연결을 확인합니다. 팀의 AWS 실행 서비스와 데이터베이스가 정해지면 아래 값을 실행 환경에서 주입합니다.

| 환경변수 | 설정 내용 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `PORT` | 애플리케이션 및 상태 확인 포트. 기본값 `8080` |
| `GANZI_DB_URL` | AWS에서 접근 가능한 MySQL JDBC 주소 |
| `GANZI_DB_USERNAME`, `GANZI_DB_PASSWORD` | 애플리케이션 전용 DB 계정 |
| `GANZI_ALLOWED_ORIGINS` | 팀 QA 웹은 `https://haeboyang.anhs-0218.workers.dev`, Expo 로컬 웹은 `http://localhost:8081`. 사용할 Origin을 쉼표로 구분 |
| `GANZI_CHAT_ENABLED`, `GANZI_PUSH_ENABLED` | 초기 배포에서는 `false`. 각 제공자 설정을 검증한 뒤 활성화 |
| `GANZI_CHAT_BUDGET_SCOPE` | 예산 장부를 구분하는 값. 기본값 `staging` |
| `GANZI_CHAT_DAILY_USD`, `GANZI_CHAT_MONTHLY_USD`, `GANZI_CHAT_USER_DAILY_REQUESTS` | `staging` 기본값은 전체 일간·월간 각각 0.25 USD와 사용자당 하루 30건 |
| `GANZI_OPENAI_MODEL`, `GANZI_OPENAI_MAX_OUTPUT_TOKENS` | 기본 `gpt-6.2-sol`(Thinking `medium`), 출력 상한 `2048` 토큰. 배포 환경에 모델이 지정돼 있으면 그 값이 우선한다. |
| `GANZI_OPENAI_REASONING_EFFORT` | 미지정 시 Sol은 `medium`, Luna·Terra는 `none`. 동일한 추론 조건으로 비교할 때는 `medium`을 명시 |
| `GANZI_CHAT_INTENT_ROUTING_ENABLED` | 기본 `true`. `false`로 설정하면 그룹 프롬프트로 대화한다. [캐릭터 대화 연동 가이드](docs/chat-intent-routing.md) |

DB 비밀번호와 외부 API 키는 AWS의 비밀값 관리 기능을 통해 실행 환경에 주입합니다. AWS 배포 후에는 `/health`와 Flyway 적용을 확인하고 프론트의 API 기본 주소를 새 HTTPS 주소로 연결합니다. AWS 실행 환경의 검증은 실제 배포 시 진행합니다.
