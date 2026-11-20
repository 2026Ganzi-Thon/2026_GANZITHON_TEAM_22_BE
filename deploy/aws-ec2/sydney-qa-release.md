# 시드니 팀 QA 배포 기록

2026-11-20에 시드니 단일 EC2의 API를 `e9bd282`에서 `develop`에 병합된 `234a97c3d86c4833eca795ebc97b3e681a9c5137`로 교체했다. QA에서 통과한 실행 JAR를 그대로 사용했고, MySQL과 Caddy는 기존 컨테이너를 유지했다. 이 기록의 대상은 사전 발급 계정으로 로그인하는 제출·팀 QA 환경이다.

## 검증된 JAR로 이미지 만들기

배포할 때는 앱 소스 커밋, 해당 커밋에서 시험한 JAR, 시험 시 기록한 SHA-256 세 가지를 함께 사용한다. 아래 `revision`과 `jar_sha`는 이번 배포의 실제 값이다. 백엔드에 포함된 Java 버전, 실행 사용자 또는 시작 명령이 달라지는 배포는 해당 버전의 루트 Dockerfile로 런타임부터 빌드한다.

운영 Compose 디렉터리에서 `JAR`에는 전달받은 실행 파일의 절대 경로를, `REPO_DIR`에는 이 Dockerfile이 있는 저장소의 절대 경로를 지정한다. 아래 예시는 이 서버에서 실제 사용하는 Compose 프로젝트와 파일을 선택한 상태를 전제로 한다.

```sh
(
  set -eu
  : "${JAR:?검증한 실행 JAR 경로를 지정하세요}"
  : "${REPO_DIR:?백엔드 저장소 경로를 지정하세요}"
  revision=234a97c3d86c4833eca795ebc97b3e681a9c5137
  jar_sha=7072f3a90d4d6a71143fb439914773226ca60908488e0604e6815f024746ad0f
  api_id=$(docker compose ps -q api)
  base_id=$(docker inspect --format '{{.Image}}' "$api_id")
  base_tag="haeboyang-backend:qa-runtime-${base_id#sha256:}"
  docker image tag "$base_id" "$base_tag"
  ctx=$(mktemp -d)
  trap 'rm -f "$ctx/app.jar"; rmdir "$ctx"' EXIT
  install -m 0644 "$JAR" "$ctx/app.jar"
  (cd "$ctx" && printf '%s  app.jar\n' "$jar_sha" | sha256sum -c -)
  image="haeboyang-backend:qa-$revision"
  docker build --pull=false --build-arg RUNTIME_IMAGE="$base_tag" \
    --build-arg APP_REVISION="$revision" -t "$image" \
    -f "$REPO_DIR/deploy/aws-ec2/Dockerfile.qa-jar" "$ctx"
  test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" = "$revision"
  digest=$(docker run --rm --network none --entrypoint sha256sum "$image" /app/app.jar)
  test "${digest%% *}" = "$jar_sha"
)
```

## API 교체와 확인

1. 현재 이미지 ID, Compose 파일, 권한이 제한된 환경 파일을 복구용으로 보관한다. DB를 일관된 시점으로 덤프하고 무결성을 확인한다. 이번 변경은 Flyway V1~V4를 유지하고 V5를 추가한다.
2. API 이미지 참조를 위에서 검증한 태그로 지정한다. `GANZI_CHAT_ENABLED=true`, `GANZI_OPENAI_MODEL=gpt-6.2-sol`, `GANZI_OPENAI_REASONING_EFFORT=medium`을 설정하고 `OPENAI_API_KEY`는 서버의 보호된 환경 파일로 전달한다. 같은 키가 `env_file`과 `environment` 양쪽에 있으면 `environment`가 우선한다. QA 예산은 `GANZI_CHAT_BUDGET_SCOPE=staging`, 일·월 한도 각 0.25달러, 사용자당 일 30회로 설정했다. 해당 환경변수는 `GANZI_CHAT_DAILY_USD`, `GANZI_CHAT_MONTHLY_USD`, `GANZI_CHAT_USER_DAILY_REQUESTS`다.
3. `docker compose config --quiet`로 구성을 확인하고 `docker compose up -d --no-deps api`로 API만 교체한다. EC2 내부 `/health`가 200이고 Flyway V5가 적용됐는지 확인한다. 외부 HTTPS에서 비로그인 `/api/v1/me`는 401, 팀 QA의 `/health`·신규 가입·게스트 발급 경로는 404여야 한다.
4. 제출 웹 Origin의 CORS 사전 요청을 확인한다. 합성 QA 계정에서 일반 대화와 새 스터디룸 대화를 각각 한 건 전송한다. 각 `clientRequestId`로 저장본을 다시 읽어 화자와 본문이 일치하는지 확인하고, 시험용 공부방을 종료하고 로그아웃한다. 이 검사는 모델 사용 비용이 발생한다.
5. 기동 또는 검증 실패 시 이전 이미지와 환경 설정으로 API부터 복구한다. 적용된 마이그레이션과 이전 앱의 호환성을 확인한다. DB 복원이 필요한 경우에는 전환 뒤 쌓인 데이터를 먼저 보존하고 쓰기 중단 시점을 정한다.

## 11월 20일 확인 결과

| 확인 항목 | 결과 |
|---|---|
| 같은 JAR의 로컬 검증 | JUnit 165건 통과, HTTP API 33종 호출, 배포 설정 HTTP 요청 18건 통과 |
| 시드니 배포 | 22:53:59 KST 새 API 기동, 내부 상태 확인 200, Flyway V5 성공 |
| 실제 일반 대화 | 답변 200(7.516초), 저장본 조회 200, 화자·본문 일치 |
| 실제 스터디룸 대화 | 답변 200(2.226초), 저장본 조회 200, 화자·본문 일치, 시험용 공부방 종료 200 |
| 로그인 | 웹·Android·심사위원 QA 계정 3개 모두 로그인 200, 내 계정 조회 200, 본문 없는 로그아웃 204 |

프론트는 웹과 Android 빌드의 `EXPO_PUBLIC_API_BASE_URL`을 같은 QA API 주소로 설정한다. CORS가 통과해도 이전 서버 주소를 포함한 웹이나 APK는 종전 서버를 호출하므로, 최종 제출본에서도 접속 대상을 확인한다.
