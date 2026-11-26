# 수집 서버 배포

`main`에 push하면 GitHub Actions가 백엔드 빌드·테스트와 프런트엔드 lint·build를 실행한다. 백엔드 실행 코드·빌드 설정, 앱용 Compose·배포 스크립트 또는 워크플로우가 바뀌면 검증한 JAR로 GHCR 이미지를 발행한다. 서버에 해당 커밋의 Compose와 스크립트를 전달하고 이미지 digest로 앱 한 개를 교체한다. DB는 별도 Compose 프로젝트에서 운영한다.

## 서버 최초 준비

운영 서버에서는 `price-log-deploy` 계정의 rootless Docker와 Docker Compose를 사용한다. SSH로 해당 계정에 접속한 뒤 `docker context show`가 `rootless`인지 확인한다. 운영 디렉터리는 `/home/price-log-deploy/price-log`다. 처음 준비할 때 [운영 환경 예시](../deploy/.env.example)와 [DB Compose](../deploy/db.compose.yaml)를 아래 위치에 복사한다. 이미 DB가 실행 중이면 기존 `.env`를 유지한다.

```text
~/price-log/.env
~/price-log/db.compose.yaml
~/price-log/logs/
~/price-log/releases/     # Actions가 생성
```

DB 최초 기동 전에 `.env`에 DB 암호를 설정한다. 앱을 처음 배포할 때는 같은 파일에 `STEAM_WEB_API_KEY`를 설정하고 `PRICELOG_COLLECTION_DAILY_ENABLED=false`로 둔다. Compose에서 `$`를 포함하는 암호는 작은따옴표로 감싼다.

```sh
cd ~/price-log
chmod 600 .env
docker network inspect price-log >/dev/null 2>&1 || docker network create price-log
docker compose --env-file .env -f db.compose.yaml up -d --wait db
```

앱은 컨테이너 안에서 `10001:10001`로 실행한다. rootless Docker는 이 UID를 다른 호스트 UID에 매핑하므로 로그 폴더의 소유권도 컨테이너 안에서 설정한다. 아래 명령은 DB 기동 때 받은 이미지를 사용한다. 폴더에 setgid를 설정해 새 로그가 배포 계정에 대응하는 그룹을 상속하도록 한다.

```sh
cd ~/price-log
mkdir -p -m 0750 logs
docker run --rm --pull=never --network none --read-only \
  --mount "type=bind,src=$PWD/logs,dst=/logs" \
  --entrypoint sh postgres:17.6-alpine \
  -ec 'chown 10001:0 /logs && chmod 2750 /logs'
```

PostgreSQL은 호스트에 포트를 게시하지 않으며 같은 Docker 네트워크에서 `db:5432`로 접근한다. DB 데이터는 `price-log-db_data` 볼륨에 쌓인다. 기존 DB 볼륨을 사용할 때는 현재 사용자·암호와 백업을 확인한다. 초기화용 환경 변수만 변경해도 기존 DB의 암호는 바뀌지 않는다.

## GitHub와 SSH 설정

저장소의 `production` Environment에 다음 값을 등록한다. 운영 DB 암호와 Steam 키는 서버 `.env`에서만 관리한다.

| 종류 | 이름 | 값 |
| --- | --- | --- |
| Variables | `DEPLOY_HOST`, `DEPLOY_USER` | SSH 호스트와 배포 계정 |
| Variables | `DEPLOY_PORT` | SSH 포트. 생략하면 22 |
| Secrets | `DEPLOY_SSH_KEY` | 배포 계정에 허용한 전용 개인 키 |
| Secrets | `DEPLOY_KNOWN_HOSTS` | 서버 콘솔에서 지문을 대조한 OpenSSH known_hosts 행. 기본 포트 외에는 `[호스트]:포트` 표기를 사용한다. |

SSH는 GitHub 호스팅 러너에서 접속할 수 있어야 한다. Actions는 `GITHUB_TOKEN`으로 GHCR에 이미지를 발행한다. 비공개 패키지는 서버 배포 계정에도 패키지 읽기 권한을 부여하고 `docker login ghcr.io`를 한 번 수행한다. CI와 앱 실행에는 운영 DB 외부 공개가 필요하지 않다.

## 첫 기동과 수집 시작

1. 첫 릴리스가 성공하면 서버에서 `curl -fsS http://127.0.0.1:8080/actuator/health`와 `~/price-log/logs/pricelog.log`를 확인한다. 앱이 운영 DB에 Flyway를 적용했는지 `flyway_schema_history`와 대조한다.
2. `.env`에서 `PRICELOG_COLLECTION_DAILY_ENABLED=true`로 변경한다. 배포 잡이 끝난 상태에서 아래 명령으로 같은 릴리스를 적용한다. Compose가 변경된 환경 변수로 앱을 다시 생성한다.
3. `pipeline_execution`의 카탈로그 커서와 가격 처리 위치, 최초 적재 후 매시 변경분을 확인한다. 리뷰는 한국 시각 00:15에 담당 앱을 확인한다. 헬스 체크 성공은 애플리케이션 기동을 뜻하며 수집 완료는 회차와 실제 적재로 확인한다.

```sh
cd ~/price-log
bash current/deploy.sh "$(cat current/image)"
```

## 실패와 수동 복귀

이미지 pull이 실패하면 기존 앱은 계속 동작한다. 교체 후 120초 안에 컨테이너가 healthy가 되지 않으면 Actions가 실패하고 `current`는 **마지막 정상 릴리스**를 계속 가리킨다. 실패한 앱이 실행 중일 수 있으므로 `docker ps -a --filter label=com.docker.compose.project=price-log-prod`와 운영 로그를 확인한다. 최초 배포 실패 시에는 `current`가 아직 없다.

실패한 교체를 되돌릴 때는 `current`, 정상 배포 직후 한 버전 되돌릴 때는 `previous`를 선택한다. 해당 이미지가 적용된 DB 스키마와 호환되는지 먼저 확인한다. 배포 잡이 끝난 뒤 다음처럼 실행한다.

```sh
cd ~/price-log
release=current  # 이미 성공한 배포를 한 버전 되돌릴 때는 previous
bash "$release/deploy.sh" "$(cat "$release/image")"
```

첫 배포 실패나 스키마 비호환 시에는 실패한 앱 컨테이너만 중지한 뒤 조사한다. DB 백업·복원과 데이터 볼륨 수명은 별도로 관리한다. [수집 로그 보관](collection-logging.md) · [수집 동작과 재개](steam-catalog.md)
