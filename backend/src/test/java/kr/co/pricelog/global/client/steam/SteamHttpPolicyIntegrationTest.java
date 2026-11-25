package kr.co.pricelog.global.client.steam;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@SpringBootTest(classes = {RetryAutoConfiguration.class, HttpClientAutoConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SteamHttpPolicyIntegrationTest {

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private ClientHttpRequestFactorySettings httpSettings;

    @Test
    @DisplayName("운영 HTTP 설정에서 연결은 5초, 응답 읽기는 30초로 제한한다")
    void appliesDefaultTimeouts() {
        assertThat(httpSettings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(httpSettings.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @ParameterizedTest
    @CsvSource({"steamRequest, 1, 15000", "steamRequest, 2, 30000",
        "steamRequest, 3, 45000", "steamCollection, 1, 300000"})
    @DisplayName("429 재시도 간격은 요청에서 15초·30초·45초, 전수·변경 회차에서 5분이다")
    void usesConfiguredDelay(String policy, int attempt, long expectedMillis) {
        // given
        HttpClientErrorException failure = HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, null, null);

        // when
        long delay = retryRegistry.retry(policy).getRetryConfig().getIntervalBiFunction()
            .apply(attempt, Either.left(failure));

        // then
        assertThat(delay).isEqualTo(expectedMillis);
    }
}
