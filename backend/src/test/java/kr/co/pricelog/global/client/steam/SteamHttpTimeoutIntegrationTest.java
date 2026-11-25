package kr.co.pricelog.global.client.steam;

import static kr.co.pricelog.domain.game.enums.SteamAppType.GAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

@SpringBootTest(classes = {HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.http.client.read-timeout=500ms")
class SteamHttpTimeoutIntegrationTest {

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Test
    @Timeout(value = 8, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Steam 목록과 상점 모두 연결 후 응답이 없으면 읽기 시간 초과로 종료한다")
    void timesOutWaitingForSteamResponse() throws IOException {
        // given: TCP 연결만 받아 두고 HTTP 응답을 쓰지 않는다.
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            String url = "http://127.0.0.1:" + server.getLocalPort();
            restClientBuilder.uriBuilderFactory(new DefaultUriBuilderFactory(url));
            SteamAppListClient catalog = new SteamAppListClient(restClientBuilder, "test-key");
            SteamStoreClient store = new SteamStoreClient(restClientBuilder, new ObjectMapper());

            // when, then
            assertThatThrownBy(() -> catalog.getPage(GAME, 0, 1))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThatThrownBy(() -> store.getPurchaseOptions(List.of(10L)))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
        }
    }
}
