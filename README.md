# Price Log · 프라이스로그

한국 Steam 게임 가격 기록 서비스. 서비스 도메인은 `price-log.co.kr`이다. `backend/`는 Java 21과 Spring Boot, `frontend/`는 React와 TypeScript로 구성한다.

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

Java 운영 코드와 테스트 코드는 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)를 따른다. 프로젝트 규칙으로 블록마다 공백 4칸을 들여쓰고, 줄 길이는 원칙적으로 100자로 제한한다. [Gradle 설정](backend/build.gradle)에서 Checkstyle에 포함된 `google_checks.xml`을 사용하며, 경고 한 건도 빌드 실패로 처리한다. 프로젝트 Checkstyle 규칙은 명시적 지역 변수 타입, 엔티티 필드 간격, 영속 시각 타입, 컨트롤러·서비스·저장소 이름의 접미사를 검사한다. 책임 배치와 문단 구분은 [백엔드 개발과 리뷰](backend/AGENTS.md)를 따른다.

프런트엔드는 [Biome 설정](frontend/biome.json)에 기본 권장 규칙과 [React 권장 규칙](https://biomejs.dev/linter/domains/#react)을 명시한다. 조건부 Hook 호출 같은 오류와 서식을 함께 검사하고 경고도 실패로 처리한다. 서식은 공백 2칸, JavaScript 작은따옴표, 필요한 경우에만 세미콜론을 사용한다.

백엔드 명령은 `backend/`에서 실행한다. 테스트는 Docker 엔진을 사용하며, Testcontainers가 별도 PostgreSQL 17을 띄운다. 개발용 Compose DB는 꺼 두어도 된다.

| 명령 | 확인하는 것 |
| --- | --- |
| `./gradlew checkstyleMain checkstyleTest checkstyleConventions` | 운영·테스트 Java 코드의 정적 규칙과 서식 |
| `./gradlew test` | JUnit 테스트와 격리 DB 쓰기·조회 |
| `./gradlew build` | 정적 검사·테스트를 포함한 전체 빌드와 JAR 생성 |

프런트엔드 명령은 `frontend/`에서 `npm ci` 후 실행한다.

| 명령 | 확인하는 것 |
| --- | --- |
| `npm run lint` | Biome 린트·서식 검사. 서식을 적용할 때는 `npm run format` |
| `npm run typecheck` | TypeScript 타입 |
| `npm run build` | 타입 검사와 배포용 번들 생성 |
| `npm run dev` | 브라우저에서 변경한 화면을 열고 입력·표시 결과를 확인할 개발 서버 |

변경에 적절한 검증 수준은 [테스트 판단 기준](docs/testing.md), 원본 화면과 기대 동작은 [제품 가이드](docs/product.md)에서 확인한다.
