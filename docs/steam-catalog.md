# Steam 본편·DLC 카탈로그 수집

`SteamCatalogCollector.collectAll(scheduledAt)`는 본편 목록을 끝까지 읽은 뒤 DLC 목록을 읽는다. 두 목록은 예정 시각이 같아도 각각의 진행 위치를 가진다. 현재는 통합 테스트에서 수집기를 호출하며, 일별 스케줄 연결은 후속 작업이다.

1. 목록 종류와 예정 시각으로 `pipeline_execution`에서 회차를 연다. 새 회차는 커서 0에서 시작하고, 완료한 목록은 건너뛴다.
2. Steam에 최대 10,000개 앱을 요청한다. 응답을 받은 뒤 앱 목록과 마지막 앱 ID를 한 트랜잭션에 저장한다.
3. 다음 페이지가 있으면 저장한 앱 ID 다음부터 읽는다. 마지막 페이지까지 저장하면 해당 목록을 `COMPLETED`로 기록한다.

| 실패한 시점 | 저장 결과 | 같은 회차를 다시 호출하면 |
| --- | --- | --- |
| 본편 첫 페이지 조회 | 본편 `FAILED`, 커서 없음. DLC 회차는 아직 없음 | 본편을 커서 0부터 요청 |
| 본편 페이지 저장 | 해당 페이지 롤백, 본편 `FAILED` | 직전 커밋 다음부터 본편을 읽고 DLC로 진행 |
| 본편 완료 후 DLC 조회 | 본편 `COMPLETED`, DLC `FAILED` | 본편을 건너뛰고 DLC의 직전 커밋 다음부터 요청 |

실패한 목록을 재개하면 `RUNNING`으로 전환하고 `finished_at`을 비운다. DB 연결이 끊기거나 프로세스가 강제 종료되면 실패 상태를 쓰기 전에 멈출 수 있다. 이때도 마지막으로 커밋한 커서에서 이어진다.

## 한국 상점 구매 옵션 조회

카탈로그에서 얻은 앱 ID를 `SteamStoreClient.getPurchaseOptions(steamAppId)`에 전달하면 한국 상점의 구매 옵션 목록(`purchaseOptions`)과 Steam이 지정한 기본 옵션(`bestPurchaseOption`)을 받는다. 패키지와 번들은 같은 숫자 ID를 쓸 수 있어 종류와 ID를 함께 비교한다. 이름이 없는 상품도 ID로 식별한다.

기본 지정이 생략되면 전체 목록을 반환하고 `bestPurchaseOption`은 `null`이다. 옵션이 하나인 경우에도 같다. Steam이 기본값을 지정했는데 같은 종류·ID의 항목이 없거나 둘 이상이면 조회가 실패한다. 앱의 무료 표기와 각 상품의 종류·ID·이름을 읽는다.

## 게임별 구매 옵션 저장

`SteamStoreCollector.collectPurchaseOptions(steamAppId)`가 성공하면 같은 트랜잭션에서 무료 표기와 구매 옵션을 저장한다. `purchase_option`은 종류(PACKAGE·BUNDLE)와 Steam ID로 활성 판매 상품을 식별한다. 여러 게임이 같은 상품을 팔면 상품 한 행을 공유하고, `game_purchase_option`은 `game_id`와 `purchase_option_id`로 게임과 구매 옵션을 다대다로 연결한다.

`game_purchase_option.is_best_option`은 Steam이 해당 게임에서 지정한 옵션 한 건에만 true다. 정상 응답에서 기본 지정이 생략되면 대표 선택을 해제한다. 목록에서 빠진 상품은 해당 게임의 연결만 닫는다. 조회가 실패하거나 지정된 옵션을 목록에서 식별할 수 없으면 마지막 성공 상태를 유지한다.

게임·구매 옵션·게임별 연결은 일반 JPA 조회에서 활성 행만 반환한다. 수집기가 연결을 닫으면 그 행에 종료 시각이 남는다. 종료된 구간의 이력을 직접 SQL로 읽을 때는 조회 목적에 맞게 `deleted_at` 조건을 지정한다.

## 무료 플레이와 상품 가격의 기준

`SteamStoreCollector.collectPurchaseOptions(steamAppId)`는 카탈로그에 있는 앱의 한국 상점 응답을 한 번 읽고, `IStoreBrowseService/GetItems`의 `response.store_items[].is_free`를 `game.is_free`에 저장한다. `true`는 무료 플레이, 명시된 `false`는 무료 플레이 아님, `NULL`은 미확인이다. 상점을 조회하기 전이거나 성공 응답에 필드가 생략된 경우에는 무료 여부를 추정하지 않는다. 필드가 생략된 새 성공 응답은 이전 표기를 미확인으로 바꾸고, 조회 실패는 마지막 값을 보존한다. 구매 옵션 개수와 0원 라이선스는 이 판단에 사용하지 않는다. 단건 수집기를 일별 실행에 연결하는 작업은 후속 단계다.

출시 예정 여부(`game.coming_soon`)와 무료 플레이(`game.is_free`)는 동시에 성립할 수 있고, 한 게임의 일반판과 디럭스판은 서로 다른 가격에 팔릴 수 있다. 한국 원화 현재가·정가·할인율·할인 종료 시각의 원천은 구매 옵션별 `final_price_in_cents`, `original_price_in_cents`, `discount_pct`, `active_discounts[].discount_end_date`다. 가격 저장 단계에서는 해당 옵션의 종류·Steam 상품 ID와 관측 시각에 연결한다.
