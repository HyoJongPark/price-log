package kr.co.pricelog.global.job;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.OptionalLong;
import kr.co.pricelog.domain.collection.service.SteamCatalogCollectionService;
import kr.co.pricelog.global.client.steam.SteamAppListClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.RestClientException;

class SteamCatalogCollectorTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-11-20T00:00:00Z");

    private final SteamAppListClient steamAppListClient = mock(SteamAppListClient.class);
    private final SteamCatalogCollectionService steamCatalogCollectionService =
        mock(SteamCatalogCollectionService.class);
    private final SteamCatalogCollector steamCatalogCollector =
        new SteamCatalogCollector(steamAppListClient, mock(SteamDlcParentCollector.class),
            steamCatalogCollectionService);

    @Test
    @DisplayName("회차를 열지 못하면 실패 기록이나 Steam 조회 없이 원인을 전달한다")
    void propagatesOpeningFailure() {
        // given
        DataAccessResourceFailureException openingFailure =
            new DataAccessResourceFailureException("회차 열기 실패");
        when(steamCatalogCollectionService.resumeCursor(GAME, SCHEDULED_AT))
            .thenThrow(openingFailure);

        // when
        Throwable failure = catchThrowable(() -> steamCatalogCollector.collectAll(SCHEDULED_AT));

        // then
        assertThat(failure).isSameAs(openingFailure);
        verify(steamCatalogCollectionService).resumeCursor(GAME, SCHEDULED_AT);
        verifyNoMoreInteractions(steamCatalogCollectionService);
        verifyNoInteractions(steamAppListClient);
    }

    @Test
    @DisplayName("실패 상태를 저장할 수 없으면 저장 오류를 호출자에게 전달한다")
    void propagatesFailureToRecordStatus() {
        // given
        RestClientException readFailure = new RestClientException("Steam 조회 실패");
        DataAccessResourceFailureException recordingFailure =
            new DataAccessResourceFailureException("실패 상태 저장 실패");
        when(steamCatalogCollectionService.resumeCursor(GAME, SCHEDULED_AT))
            .thenReturn(OptionalLong.of(10));
        when(steamAppListClient.getPage(GAME, 10, 10_000)).thenThrow(readFailure);
        doThrow(recordingFailure).when(steamCatalogCollectionService)
            .markFailed(GAME, SCHEDULED_AT);

        // when
        Throwable failure = catchThrowable(() -> steamCatalogCollector.collectAll(SCHEDULED_AT));

        // then
        assertThat(failure).isSameAs(recordingFailure);
    }
}
