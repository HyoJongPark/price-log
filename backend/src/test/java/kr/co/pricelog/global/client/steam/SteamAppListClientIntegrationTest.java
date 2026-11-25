package kr.co.pricelog.global.client.steam;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.client.MockRestServiceServer;

@ContextConfiguration(classes = SteamAppListClient.class)
@RestClientTest(properties = {"STEAM_WEB_API_KEY=test-key",
    "resilience4j.retry.instances.steamRequest.wait-duration=1ms"})
@ImportAutoConfiguration({AopAutoConfiguration.class, RetryAutoConfiguration.class})
class SteamAppListClientIntegrationTest {

    @Autowired
    private SteamAppListClient steamAppListClient;

    @Autowired
    private MockRestServiceServer server;

    @NullSource
    @ParameterizedTest
    @ValueSource(longs = 1234)
    @DisplayName("전체 목록과 변경 목록 모두 429 이후 같은 페이지를 다시 읽는다")
    void retriesSamePage(Long modifiedSince) {
        // given
        List<URI> requests = new ArrayList<>();
        server.expect(request -> requests.add(request.getURI()))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(request -> requests.add(request.getURI())).andRespond(withSuccess(
            "{\"response\":{\"apps\":[{\"appid\":11,\"name\":\"게임\"}]}}",
            MediaType.APPLICATION_JSON));

        // when
        SteamAppPage page = modifiedSince == null ? steamAppListClient.getPage(GAME, 10, 1)
            : steamAppListClient.getChangedPage(GAME, 10, 1, Instant.ofEpochSecond(modifiedSince));

        // then
        assertThat(page.apps()).containsExactly(new SteamApp(11, "게임"));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).isEqualTo(requests.getFirst());
        server.verify();
    }
}
