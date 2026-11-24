package kr.co.pricelog.global.job;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class SteamDailyCollectionSchedulerTest {

    private final SteamCatalogCollector steamCatalogCollector = mock(SteamCatalogCollector.class);
    private final SteamStoreCollector steamStoreCollector = mock(SteamStoreCollector.class);
    private final SteamDailyCollectionScheduler scheduler = new SteamDailyCollectionScheduler(
        steamCatalogCollector, steamStoreCollector, 2);

    @Test
    @DisplayName("03:10 직전에 서버가 기동하면 카탈로그와 가격을 조회하지 않는다")
    void waitsUntilScheduledTime() {
        // given
        Instant beforeSlot = Instant.parse("2026-11-23T18:09:59Z");

        // when
        scheduler.collectToday(beforeSlot);

        // then
        verifyNoInteractions(steamCatalogCollector, steamStoreCollector);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-23T18:10:00Z", "2026-11-24T00:00:00Z"})
    @DisplayName("기동과 정기 호출이 연달아 와도 오전과 오후 가격을 회차마다 한 번씩 수집한다")
    void collectsEachPriceSlotOnce(Instant firstCall) {
        // given
        final Instant today = Instant.parse("2026-11-23T18:10:00Z");
        final Instant afternoon = Instant.parse("2026-11-24T06:10:00Z");
        Instant tomorrow = Instant.parse("2026-11-24T18:10:00Z");

        // when
        scheduler.collectToday(firstCall);
        scheduler.collectToday(firstCall.plusSeconds(1));
        scheduler.collectToday(afternoon.minusSeconds(1));
        scheduler.collectToday(afternoon);
        scheduler.collectToday(afternoon.plusSeconds(1));
        scheduler.collectToday(tomorrow);

        // then
        InOrder order = inOrder(steamCatalogCollector, steamStoreCollector);
        order.verify(steamCatalogCollector).collectAll(today);
        order.verify(steamStoreCollector).collectAll(today, 2);
        order.verify(steamCatalogCollector).collectAll(today);
        order.verify(steamStoreCollector).collectAll(afternoon, 2);
        order.verify(steamCatalogCollector).collectAll(tomorrow);
        order.verify(steamStoreCollector).collectAll(tomorrow, 2);
        verifyNoMoreInteractions(steamCatalogCollector, steamStoreCollector);
    }

    @Test
    @DisplayName("오후에 처음 기동하면 오늘 카탈로그를 확보하고 오후 가격만 관측한다")
    void startsInAfternoon() {
        // given
        Instant startupTime = Instant.parse("2026-11-24T07:00:00Z");

        // when
        scheduler.collectToday(startupTime);

        // then
        InOrder order = inOrder(steamCatalogCollector, steamStoreCollector);
        order.verify(steamCatalogCollector).collectAll(Instant.parse("2026-11-23T18:10:00Z"));
        order.verify(steamStoreCollector).collectAll(Instant.parse("2026-11-24T06:10:00Z"), 2);
        verifyNoMoreInteractions(steamCatalogCollector, steamStoreCollector);
    }
}
