# 해보양 백엔드

해보양은 할 일 관리와 공부 세션, 다음 시작 알림, 캐릭터 대화를 제공하는 서비스입니다. 백엔드는 Java 21, Spring Boot, Spring JDBC와 MySQL 8을 사용합니다. 구현된 HTTP 연산 33개의 요청·응답은 [OpenAPI 명세](docs/implemented.openapi.json)에 있습니다.

## 로컬에서 빌드하고 실행하기

Java 21 JDK를 설치하고 `JAVA_HOME`을 설정합니다. Gradle은 저장소의 Wrapper를 사용합니다.

```sh
./gradlew bootJar --no-daemon
```

Windows에서는 `gradlew.bat bootJar --no-daemon`을 실행합니다. 별도 MySQL 데이터베이스를 준비하고 아래 배포 환경변수를 넣은 뒤, `SPRING_PROFILES_ACTIVE=staging`으로 JAR를 실행합니다. 새 데이터베이스에 Flyway가 `V1`부터 `V4`까지 적용합니다.

```sh
java -jar build/libs/ganzithon-backend-0.6.0-local.jar
curl -fsS http://localhost:8080/health
```

## 검증

Windows에서 Node.js와 MySQL 8을 설치한 뒤 `node scripts/verify-local.mjs`를 실행합니다. MySQL을 다른 경로에 설치했다면 `GANZI_MYSQL_HOME`에 설치 디렉터리를 지정합니다. 이미 내려받은 Gradle 의존성만 쓰려면 `GANZI_GRADLE_OFFLINE=true`를 설정합니다. 이 스크립트는 별도 로컬 MySQL을 임시 포트에서 시작하고, 자동 시험과 HTTP 점검을 마친 뒤 종료합니다. 로그와 결과는 `build/verification-local/`에 저장합니다.

11월 9일 기준 로컬 검증에서 자동 테스트 109개와 HTTP 점검 24개가 통과했습니다. Railway 테스트 환경에서도 DB 상태, 게스트·이메일 로그인, 할 일, 공부 세션, 알림 예약·취소 흐름을 HTTPS로 검증했습니다. 실제 AI 대화 생성과 휴대전화 푸시 수신은 별도의 연동 환경에서 확인합니다.

## AWS 배포 시 설정

`docker build -t haeboyang-backend .`로 Java 21 컨테이너를 만들 수 있습니다. 이미지 기본 프로필은 `staging`, 기본 포트는 `8080`입니다. 이 프로필은 외부 MySQL을 사용하고 `/health`에서 DB 연결을 확인합니다. 팀의 AWS 실행 서비스와 데이터베이스가 정해지면 아래 값을 실행 환경에서 주입합니다.

| 환경변수 | 설정 내용 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `PORT` | 애플리케이션 및 상태 확인 포트. 기본값 `8080` |
| `GANZI_DB_URL` | AWS에서 접근 가능한 MySQL JDBC 주소 |
| `GANZI_DB_USERNAME`, `GANZI_DB_PASSWORD` | 애플리케이션 전용 DB 계정 |
| `GANZI_ALLOWED_ORIGINS` | 웹 프론트 Origin을 쉼표로 구분한 목록. 예시 `http://localhost:5173` |
| `GANZI_CHAT_ENABLED`, `GANZI_PUSH_ENABLED` | 초기 배포에서는 `false`. 각 제공자 설정을 검증한 뒤 활성화 |
| `GANZI_OPENAI_MODEL`, `GANZI_OPENAI_MAX_OUTPUT_TOKENS` | 기본 `gpt-6.1-sol`(Thinking `medium`), 출력 상한 `2048` 토큰 |
| `GANZI_OPENAI_REASONING_EFFORT` | 미지정 시 Sol은 `medium`, Luna·Terra는 `none`. 동일한 추론 조건으로 비교할 때는 `medium`을 명시 |
| `GANZI_CHAT_INTENT_ROUTING_ENABLED` | 기본 `true`. `false`로 설정하면 그룹 프롬프트로 대화한다. [캐릭터 대화 연동 가이드](docs/chat-intent-routing.md) |

DB 비밀번호와 외부 API 키는 AWS의 비밀값 관리 기능을 통해 실행 환경에 주입합니다. AWS 배포 후에는 `/health`와 Flyway 적용을 확인하고 프론트의 API 기본 주소를 새 HTTPS 주소로 연결합니다. AWS 실행 환경의 검증은 실제 배포 시 진행합니다.
