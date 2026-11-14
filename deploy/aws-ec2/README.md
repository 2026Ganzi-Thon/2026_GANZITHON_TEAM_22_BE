# 해보양 EC2 배포

EC2에서 Caddy와 Spring Boot 컨테이너를 실행합니다. 웹과 앱은 같은 HTTPS API를 사용하고, API는 같은 VPC의 비공개 RDS MySQL에 연결합니다. 시작 시 Flyway가 DB 마이그레이션을 적용합니다.

## 첫 배포 준비

1. 시드니 리전(`ap-southeast-2`)의 Ubuntu Server 26.04 LTS x86_64 EC2 `t3.small`에 Docker와 Compose 2.30 이상, AWS CLI v2, `curl`, Java 21 `keytool`을 준비합니다. Docker가 재부팅 후 자동으로 시작되도록 설정합니다. 도메인 연결 후 EC2의 고정 IP를 팀 API 도메인의 DNS 레코드에 연결하고, Caddy가 인증서를 발급받고 직접 요청을 받도록 DNS 전용으로 설정합니다. 공개 인바운드 TCP 80·443은 HTTPS 단계에서 엽니다. [Caddy 자동 HTTPS](https://caddyserver.com/docs/automatic-https)
2. 같은 VPC에 암호화와 자동 백업을 켠 비공개 RDS MySQL을 만듭니다. RDS의 TCP 3306은 EC2 보안 그룹에서만 허용합니다. `haeboyang` DB와 그 DB에서 마이그레이션과 애플리케이션 쿼리를 실행할 계정을 준비합니다. JDBC에는 RDS 엔드포인트를 넣어 TLS 호스트 이름 검증에 사용합니다. [EC2–RDS 연결](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/ec2-rds-connect.html) · [MySQL TLS 검증](https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html)
3. 별도 빌드 환경에서 검토한 백엔드 커밋을 체크아웃하고, 저장소 루트에서 `docker buildx build --platform linux/amd64 --tag <레지스트리>/haeboyang-backend:<커밋 SHA> --push .`를 실행합니다. `t3.small`은 x86_64이고 메모리 2GiB이므로 EC2 안에서 Gradle 이미지 빌드를 하지 않습니다. ECR을 사용한다면 빌드 환경에는 이미지 쓰기 권한, EC2 역할에는 이미지 읽기 권한을 부여합니다. [ECR 이미지 배포](https://docs.aws.amazon.com/AmazonECR/latest/userguide/docker-push-ecr-image.html)

## 설정 및 실행

이 디렉터리에서 `cp compose.env.example .env`를 실행합니다. `.env`에 API 도메인과 백엔드 이미지의 전체 주소를 넣습니다. API 도메인 값은 `api.example.com`처럼 호스트 이름만 사용합니다. 비공개 ECR을 사용하는 경우 EC2에서 레지스트리에 로그인한 뒤 실행합니다. [ECR 인증](https://docs.aws.amazon.com/AmazonECR/latest/userguide/registry_auth.html)

`api.env.example`에 나온 값을 한 줄에 하나씩 입력해 Parameter Store의 `/haeboyang/prod/api-env` SecureString으로 저장합니다. DB 접속 정보와 웹 CORS 주소를 입력하고, 로컬 웹 연동 테스트가 끝나면 `http://localhost:8081`을 목록에서 뺍니다. 값은 따옴표 없이 입력합니다. EC2 역할에는 이 파라미터에 대한 읽기 권한과, 고객 관리형 KMS 키를 사용하면 해당 키에 대한 복호화 권한을 부여합니다. 아래 명령은 파일을 소유자만 읽을 수 있게 만들고 읽기에 성공했을 때 교체합니다. [Parameter Store 접근 제어](https://docs.aws.amazon.com/systems-manager/latest/userguide/ps-restrict-parameter-access.html)

```sh
(
    set -eu
    umask 077
    temp_env=$(mktemp ./.env.api.XXXXXX)
    trap 'rm -f "$temp_env"' EXIT
    aws ssm get-parameter --region "${AWS_REGION:-ap-southeast-2}" --name /haeboyang/prod/api-env \
        --with-decryption --query Parameter.Value --output text > "$temp_env"
    test -s "$temp_env"
    mv -f "$temp_env" ./.env.api
)
```

시드니 리전 RDS의 공개 CA 인증서를 가져와 JDBC 전용 신뢰 저장소를 만듭니다. `prepare-rds-truststore.sh`는 `AWS_REGION`이 없으면 `ap-southeast-2`를 사용하며, 위 Parameter Store 조회도 같은 기본 리전을 사용합니다. DB 호스트 이름까지 검증하는 설정은 `api.env.example`에 들어 있습니다. [AWS RDS 인증서](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)

```sh
sh ./prepare-rds-truststore.sh
docker compose config --quiet
```

도메인이 아직 없으면 Caddy를 시작하지 않고 API만 띄워 EC2 내부에서 확인합니다. 이 주소는 Docker 내부 IP이므로 외부 접속 주소로 사용하지 않습니다. 포트 8080을 공개하지 않습니다.

```sh
docker compose up -d api
api_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose ps -q api)")
curl --fail --show-error "http://$api_ip:8080/health"
```

도메인 DNS가 준비되면 `.env`의 `HAEBOYANG_API_HOSTNAME`을 실제 호스트 이름으로 바꾸고 Caddy를 시작합니다.

```sh
docker compose up -d
docker compose logs --tail=50 api caddy
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
    "https://$(sed -n 's/^HAEBOYANG_API_HOSTNAME=//p' .env)/api/v1/me"
```

Caddy만 호스트에 포트를 엽니다. Caddy는 `/api/v1/*`만 API에 전달하고 요청 본문을 64KiB로 제한합니다. DB를 검사하는 `/health`는 EC2 내부에서 조회합니다. Tomcat은 Compose 네트워크에서 Caddy가 전달한 IP로 요청 제한을 적용합니다. API 포트를 호스트에 게시하거나 같은 네트워크에 다른 서비스 또는 앞단 프록시를 추가할 때는 신뢰 범위를 다시 확인합니다. [Caddy 본문 크기 제한](https://caddyserver.com/docs/caddyfile/directives/request_body) · [Caddy 전달 헤더](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy) · [Spring Boot 프록시 설정](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html)

## 배포 직후 확인

1. EC2 내부 `/health`가 200을 반환하고 Flyway 적용 로그에 오류가 없는지 확인합니다. 외부 HTTPS에서 인증 없이 `GET /api/v1/me`를 호출하면 401, `/health`를 호출하면 404가 와야 합니다. Cloudflare 웹에서 가입·로그인, 할 일 생성, 공부 시작·종료, 재로그인 후 할 일 조회를 확인합니다. 완료한 공부 기록은 `GET /api/v1/study-sessions/{sessionId}`로 확인합니다. 프론트 웹과 APK의 `EXPO_PUBLIC_API_BASE_URL`은 같은 API 주소를 사용합니다.
2. 서로 다른 네트워크의 클라이언트에서 로그인 요청 제한이 독립적으로 적용되는지 확인합니다. 같은 IP에서 임의의 `X-Forwarded-For` 값을 보내도 기존 요청 제한이 적용되어야 합니다.
3. 검증에 사용할 키와 예산을 설정한 뒤 `GANZI_CHAT_ENABLED`를 켜고 실제 답변을 확인합니다. 푸시는 `GANZI_PUSH_ENABLED`와 Expo 설정을 적용한 뒤 안드로이드 실기기에서 확인합니다. `.env.api`를 갱신한 후에는 `docker compose up -d --force-recreate api`로 새 환경변수를 적용합니다.

서버 버전을 바꿀 때는 이전 이미지 태그를 기록하고 `.env`의 이미지 태그를 갱신한 뒤 `docker compose up -d api`를 실행합니다. DB 마이그레이션이 포함된 변경이라면 먼저 RDS 스냅샷을 만들고, 이전 앱이 새 스키마에서 실행 가능한지 확인합니다.

## 임시 대안: EC2 한 대에 MySQL 함께 실행

RDS를 아직 만들 수 없다면 `compose.single-ec2.yaml`을 사용합니다. API·Caddy는 위와 같은 이미지를 쓰고, MySQL 8.4는 `mysql_data` Docker 볼륨에 저장합니다. MySQL 3306과 API 8080은 호스트에 게시하지 않습니다. 이 구성의 JDBC는 내부 `mysql` 컨테이너에 TLS를 요구하는 `sslMode=REQUIRED`를 사용하며, RDS용 인증서 파일은 필요하지 않습니다.

```sh
cp compose.single-ec2.env.example .env
cp api.env.example .env.api
chmod 600 .env .env.api
```

`.env`의 이미지 주소와 서로 다른 `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`를 실제 값으로 채웁니다. `.env.api`에는 실제 웹 Origin과 AI·푸시 설정을 넣습니다. 이 모드의 DB URL·사용자·비밀번호는 Compose의 `environment`가 `.env.api`보다 우선하므로 `api.env.example`에 적힌 RDS DB 값은 사용되지 않습니다. 두 비밀번호는 Git에 올리지 않습니다. 도메인 준비 전에는 다음처럼 MySQL과 API만 시작합니다.

```sh
docker compose -f compose.single-ec2.yaml config --quiet
docker compose -f compose.single-ec2.yaml up -d mysql api
api_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose -f compose.single-ec2.yaml ps -q api)")
curl --fail --show-error "http://$api_ip:8080/health"
```

도메인·DNS가 준비되면 `.env`의 호스트 이름을 실제 값으로 바꾸고 `docker compose -f compose.single-ec2.yaml up -d`로 Caddy를 시작합니다. 이후 모든 Compose 명령에는 `-f compose.single-ec2.yaml`을 붙입니다. 일반 `compose.yaml`은 RDS용 구성이므로 두 파일을 섞어 실행하지 않습니다.

이 구성은 EC2·루트 디스크·MySQL이 함께 장애를 겪을 수 있고 자동 백업이 없습니다. `t3.small`의 2GiB 메모리에서 API 900MiB, MySQL 640MiB, Caddy 128MiB의 상한을 두므로 실제 부하에서 메모리 사용을 확인해야 합니다. 데이터를 보존하려면 정기적으로 `mysqldump --single-transaction`으로 덤프한 뒤 암호화해 EC2 밖에 복사하고 복원도 시험합니다. `docker compose down -v`는 `mysql_data`를 삭제하므로 사용하지 않습니다. RDS로 옮길 때는 쓰기를 멈추고 덤프·복원·검증한 뒤 DB 주소를 바꿉니다.

## 팀 QA에서 사용하는 접근 제한

`Caddyfile.qa`는 `/api/v1/auth/register`와 `/api/v1/guest-sessions`를 차단합니다. QA 계정은 운영자가 내부 API로 미리 발급합니다. 이 규칙은 가입 경로를 차단하므로, 기존에 발급된 계정 중 로그인할 수 있는 계정의 범위는 데이터베이스의 계정 상태로 결정됩니다. QA를 시작하기 전에 이전 시험 계정도 확인합니다.

현 QA는 한 EC2에서 MySQL을 함께 실행합니다. 현 서버에 적용한 IP 주소용 공인 인증서와 기본 SNI 설정은 도메인용 `Caddyfile.qa` 템플릿과 차이가 있습니다. 팀 도메인을 연결할 때는 도메인용 템플릿으로 전환하고, HTTPS에서 가입 차단과 사전 발급 계정의 로그인을 다시 확인합니다.
