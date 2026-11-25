package kr.co.pricelog.global.job;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

class SteamCollectionSchedulerTest {

    private final SteamCatalogCollector steamCatalogCollector = mock(SteamCatalogCollector.class);
    private final SteamStoreCollector steamStoreCollector = mock(SteamStoreCollector.class);
    private final SteamAppChangeCollector steamAppChangeCollector =
        mock(SteamAppChangeCollector.class);
    private final SteamCollectionScheduler scheduler = new SteamCollectionScheduler(
        steamCatalogCollector, steamStoreCollector, steamAppChangeCollector, 2);

    @ParameterizedTest
    @CsvSource({
        "2026-11-23T16:00:00Z,2026-11-23T16:00:00.000001Z",
        "2026-11-23T16:00:00.300Z,2026-11-23T16:00:00.300Z"
    })
    @DisplayName("기동 시 카탈로그와 가격을 초기화하고 다음 정각에는 변경분만 수집한다")
    void initializesOnceAndThenCollectsChanges(Instant startup, Instant fullPriceSlot) {
        // given
        Instant nextHour = Instant.parse("2026-11-23T17:00:00Z");

        // when
        scheduler.collectRecentChanges(startup);
        scheduler.initialize(startup);
        scheduler.collectRecentChanges(nextHour);

        // then
        InOrder order =
            inOrder(steamCatalogCollector, steamStoreCollector, steamAppChangeCollector);
        order.verify(steamCatalogCollector).initialize(startup);
        order.verify(steamStoreCollector).collectAll(fullPriceSlot, 2);
        order.verify(steamAppChangeCollector).collect(startup.truncatedTo(ChronoUnit.HOURS), 2);
        order.verify(steamAppChangeCollector).collect(nextHour, 2);
        verifyNoMoreInteractions(steamCatalogCollector, steamStoreCollector,
            steamAppChangeCollector);
    }
}
