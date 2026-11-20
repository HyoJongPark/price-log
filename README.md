# Price Log · 프라이스로그

한국 Steam 게임 가격 기록 서비스. `backend/`는 Java 21과 Spring Boot, `frontend/`는 React와 TypeScript로 구성한다.

## 로컬 실행

JDK 21, Node.js 24.15 이상(24.x) 또는 26 이상, Docker Compose를 사용할 수 있는 Docker 환경이 필요하다.

저장소 루트에서 백엔드와 개발용 PostgreSQL 17을 실행한다.

```sh
cd backend
docker compose up -d --wait
./gradlew bootRun
```

[백엔드 상태](http://localhost:8080/actuator/health)에서 `UP`을 확인한다. 개발용 DB는 `127.0.0.1:55436/pricelog`에 연결되며 접속 정보는 [개발용 Compose 설정](backend/compose.yaml)에 있다. 개발을 마치면 백엔드를 종료하고 `backend/`에서 `docker compose stop`으로 DB를 중지한다.

새 터미널을 열고 저장소 루트에서 프런트엔드를 실행한다.

```sh
cd frontend
npm ci
npm run dev
```

[프런트엔드](http://localhost:5173)에서 ‘프라이스로그’가 표시된다. 포트를 이미 사용 중이면 Vite가 터미널에 표시한 주소로 접속한다.

## 검증

- `backend/`에서 `./gradlew build`를 실행하면 통합 테스트와 JAR 빌드가 함께 수행된다. Docker 엔진만 실행 중이면 Testcontainers가 별도 PostgreSQL을 띄워 쓰기와 조회를 검증한다. 개발용 Compose DB는 꺼 두어도 된다.
- `frontend/`에서 `npm ci` 후 `npm run build`를 실행하면 타입 검사와 배포용 빌드가 수행된다.
