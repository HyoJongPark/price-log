# 백엔드 개발과 리뷰

## 코드의 위치

Spring Boot 진입점 아래에서 업무 모델은 `domain`, 외부 시스템 연결과 애플리케이션 실행은 `global`에 둔다.

| 기능 구현 시 위치 | 맡는 일 |
| --- | --- |
| `domain/collection` | 수집 회차의 재개·완료, 페이지 결과와 커서를 함께 저장하는 트랜잭션 |
| `domain/game` | 게임·판매 패키지, 추적 대상과 카탈로그 갱신 |
| `domain/price` | 한국 원화 가격과 패키지별 가격 이력 |
| `domain/playercount` | 게임별 동시 접속자 이력 |
| `global/job` | 예약 실행, 관측 구간, 대상별 호출 순서와 실패 격리 |
| `global/client/<공급자>` | HTTP 연결, 인증, 응답 검증. 연동 결과 타입은 해당 공급자의 `dto`에 둔다. |
| `global/config`, `global/entity` | 애플리케이션 설정과 여러 업무에서 공유하는 영속 타입. 가격·동접의 공통 관측 키가 한 예다. |

도메인 안에서는 JPA 엔티티와 내장 값은 `entity`, 조회·잠금은 `repository`, 업무 규칙을 적용하는 트랜잭션은 `service`에 둔다. HTTP 진입점과 요청·응답 타입이 생기면 해당 도메인의 `controller`, `dto`에 둔다. 열거형은 그 값의 의미를 결정하는 패키지의 `enums`에 둔다. 예를 들어 관측 주기는 `global/job/enums`에 속한다. 패키지는 그곳에 둘 코드가 생길 때 만든다. 테스트 경로는 대상 운영 코드의 패키지와 맞춘다.

컨트롤러·서비스·저장소 타입은 업무 이름 뒤에 각각 `Controller`, `Service`, `Repository`를 붙인다. 저장소 인터페이스에도 같은 접미사를 적용한다. 영속화는 Spring Data JPA 저장소를 통해 수행한다. 직접 작성한 쿼리도 해당 업무의 `repository`에 둔다.

## 계층 사이에 전달하는 값

- Controller ↔ Service, Service ↔ Client 간 데이터 전달에는 DTO를 사용한다. 단순 값·ID·enum은 그대로 전달하며, 엔티티는 Service와 Repository에서 사용한다.

DB에 쓰는 업무 단위는 서비스 클래스에 `@Transactional`을 붙이고, 그 서비스의 조회 전용 메서드는 `@Transactional(readOnly = true)`로 명시한다. 수집기는 원격 응답을 받은 뒤 서비스 빈을 호출한다. 저장소의 변경 쿼리는 서비스의 트랜잭션에 참여한다. 같은 기록을 동시에 저장할 수 있으면 중복 판정과 저장을 한 트랜잭션에서 보장한다. 스키마 변경은 Flyway와 엔티티 매핑을 함께 확인한다. [Spring Data JPA 트랜잭션 경계](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)

JPA 엔티티는 `global/entity/BaseEntity`를 상속한다. Spring Data JPA Auditing으로 `createdAt`은 최초 저장 시 한 번, `updatedAt`은 생성과 실제 수정 시 기록한다. 관측 시각과 카탈로그 확인 시각은 도메인이 별도로 관리한다.

## 외부 API 클라이언트

- 공급자별 패키지 안에서 호출자가 요청하는 기능을 기준으로 메서드를 묶는다. 같은 상품의 구매 옵션과 가격을 조회하는 경우처럼 한 기능으로 설명되면 경로나 응답 DTO가 달라도 기존 클라이언트에 메서드를 추가한다.
- 새 기능이 별도로 사용되고, 인증 권한·페이지 순회·응답 검증처럼 독립적으로 변경하고 테스트할 규칙이 생기면 별도 클라이언트로 나눈다. 단위별 재호출은 메서드로도 가능하므로 수집 주기나 재시도 일정은 작업 실행 코드에서 정한다.
- 같은 호스트의 연결·타임아웃 설정은 공유할 수 있다. 인증 헤더는 해당 자격 증명이 필요한 요청에만 붙인다.

## 시각과 가독성

- 관측·갱신·구간 시작처럼 타임라인의 한 시점을 표현하는 필드, 파라미터, 반환값은 `Instant`로 통일하고 PostgreSQL에는 `timestamptz`로 저장한다. 수집기는 페이지를 받은 뒤 `Instant.now()`로 관측 시각을 한 번 읽어 같은 페이지의 모든 행에 전달한다. 한국 날짜·구간 계산과 화면 표시 경계에서만 `Asia/Seoul`을 적용한다. 시각 자체가 없는 출시일은 `LocalDate`, 경과 시간은 `Duration`으로 의미를 보존한다.
- Java 운영·테스트 코드의 지역 변수는 타입을 명시한다. 엔티티와 복합 키의 각 필드 사이에는 빈 줄을 둔다. 주입·매핑 애너테이션은 필드 선언 바로 윗줄에 쓴다.
- 운영 코드와 테스트에서 의존성 필드와 생성자 인자는 `gameCatalogService`, `steamAppListClient`, `gameRepository`처럼 타입 이름을 lowerCamelCase로 쓴다. 같은 타입을 여럿 쓰면 이름에 각각의 역할을 드러낸다.
- 서비스·저장소에서는 같은 역할의 의존성을 붙여 쓰고, 상수·로거와 주입받는 빈처럼 성격이 바뀌면 한 줄을 비운다.
- 서비스 메서드는 작업의 목적이 바뀌는 곳에 빈 줄 하나를 둔다. 예를 들어 한 페이지의 앱 ID와 이름을 저장 인자로 만드는 두 줄은 붙이고, 실제 저장 호출 앞에서 문단을 나눈다. [Kent Beck의 Chunk Statements](https://www.oreilly.com/library/view/tidy-first/9781098151232/ch11.html) · [Google Java Style §4.6.1](https://google.github.io/styleguide/javaguide.html#s4.6.1-vertical-whitespace)
- 로컬 리뷰에서 바뀐 타입의 소유자, 외부 호출과 트랜잭션의 경계, 문단별 의도를 확인한다. 새 린트 규칙은 위반 예제로 실패를 확인한 뒤 예제를 제거하고 빌드를 통과시킨다. Java 테스트 작성과 리뷰는 [테스트 판단 기준](../docs/testing.md)을 따른다.
