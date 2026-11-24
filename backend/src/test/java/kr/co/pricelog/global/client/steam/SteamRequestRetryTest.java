package kr.co.pricelog.global.client.steam;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class SteamRequestRetryTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SteamRequestRetry steamRequestRetry = new SteamRequestRetry();
    private final SteamStoreClient steamStoreClient =
        new SteamStoreClient(builder, new ObjectMapper(), steamRequestRetry);

    @Test
    @DisplayName("대기 헤더가 없는 연속 429에는 1초와 2초 이상 기다린 후 같은 요청을 재시도한다")
    void backsOffOnRepeatedRateLimits() {
        // given
        List<Instant> attempts = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(request -> attempts.add(Instant.now()))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }
        server.expect(request -> attempts.add(Instant.now())).andRespond(withSuccess(
            "{\"response\":{\"store_items\":[]}}", MediaType.APPLICATION_JSON));

        // when
        steamStoreClient.getPurchaseOptions(List.of(1L));

        // then
        assertThat(Duration.between(attempts.get(0), attempts.get(1))).isGreaterThanOrEqualTo(
            Duration.ofSeconds(1));
        assertThat(Duration.between(attempts.get(1), attempts.get(2))).isGreaterThanOrEqualTo(
            Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    @DisplayName("Retry-After가 HTTP 날짜이면 지정 시각 이후에 재요청한다")
    void waitsUntilHttpDate() {
        // given
        Instant deadline = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(2);
        String retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            deadline.atZone(ZoneOffset.UTC));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, retryAfter));
        server.expect(request -> assertThat(Instant.now()).isAfterOrEqualTo(deadline))
            .andRespond(withSuccess("{\"response\":{\"store_items\":[]}}",
                MediaType.APPLICATION_JSON));

        // when
        steamStoreClient.getPurchaseOptions(List.of(1L));

        // then
        server.verify();
    }

    @Test
    @DisplayName("상점이 마지막 429를 받은 뒤에도 같은 호스트의 목록 요청은 Retry-After까지 기다린다")
    void sharesCooldownWithCatalogRequests() {
        // given
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "0"));
        }
        List<Instant> attempts = new ArrayList<>();
        server.expect(request -> attempts.add(Instant.now()))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1"));
        server.expect(request -> attempts.add(Instant.now())).andRespond(withSuccess(
            "{\"response\":{\"apps\":[]}}", MediaType.APPLICATION_JSON));
        SteamAppListClient steamAppListClient = new SteamAppListClient(builder,
            steamRequestRetry, "test-key");

        // when
        Throwable failure = catchThrowable(() -> steamStoreClient.getPurchaseOptions(List.of(1L)));
        steamAppListClient.getPage(GAME, 0, 30);

        // then
        assertThat(failure).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertThat(Duration.between(attempts.get(0), attempts.get(1))).isGreaterThanOrEqualTo(
            Duration.ofSeconds(1));
        server.verify();
    }
}
