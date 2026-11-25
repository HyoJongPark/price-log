package kr.co.pricelog.global.client.steam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.global.client.steam.dto.SteamApp;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class SteamAppListClientTest {

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final MockRestServiceServer mockRestServiceServer =
        MockRestServiceServer.bindTo(restClientBuilder).build();
    private final SteamAppListClient steamAppListClient =
        new SteamAppListClient(restClientBuilder, "test-key");

    @Nested
    @DisplayName("종류별 목록과 페이지 경계")
    class Pages {

        @ParameterizedTest
        @CsvSource({"GAME,true,false", "DLC,false,true"})
        @DisplayName("해당 종류만 요청하고 직전 커서를 넘겨 마지막 페이지의 앱까지 읽는다")
        void readsPages(SteamAppType type, boolean games, boolean dlc) {
            // given
            expectPage(games, dlc, 0, 2, """
                {"response":{"apps":[{"appid":10,"name":"First"},{"appid":70,"name":"Second"}],
                 "have_more_results":true,"last_appid":70}}
                """);
            expectPage(games, dlc, 70, 2, """
                {"response":{"apps":[{"appid":200,"name":"Last","last_modified":123}],
                 "have_more_results":false,"last_appid":200}}
                """);

            // when
            SteamAppPage first = steamAppListClient.getPage(type, 0, 2);
            SteamAppPage last = steamAppListClient.getPage(type,
                first.nextCursor().orElseThrow(), 2);

            // then
            assertThat(first.apps()).containsExactly(new SteamApp(10, "First"),
                new SteamApp(70, "Second"));
            assertThat(last.apps()).containsExactly(new SteamApp(200, "Last"));
            assertThat(last.nextCursor()).isEmpty();
            mockRestServiceServer.verify();
        }

        @Test
        @DisplayName("변경 목록 요청은 조회 시작 시각의 Unix 초와 앱 커서를 함께 전달한다")
        void requestsChangedPage() {
            // given
            Instant since = Instant.ofEpochSecond(1_795_000_000);
            mockRestServiceServer.expect(request -> {
                String query = request.getURI().getQuery().substring("input_json=".length());
                JsonNode input = new ObjectMapper().readTree(query);
                assertThat(input.required("if_modified_since").longValue())
                    .isEqualTo(1_795_000_000);
                assertThat(input.required("last_appid").longValue()).isEqualTo(35);
            }).andExpect(header("x-webapi-key", "test-key"))
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

            // when
            SteamAppPage page = steamAppListClient.getChangedPage(SteamAppType.GAME, 35, 10, since);

            // then
            assertThat(page.apps()).isEmpty();
            mockRestServiceServer.verify();
        }

        @Test
        @DisplayName("최대 앱 ID를 다음 커서로 보내고 페이지 크기의 양 끝값을 허용한다")
        void continuesFromLargestAppId() {
            // given
            expectPage(true, false, 4_294_967_294L, 1, """
                {"response":{"apps":[{"appid":4294967295,"name":"Last app"}],
                 "have_more_results":true,"last_appid":4294967295}}
                """);
            expectPage(true, false, 4_294_967_295L, 50_000, "{\"response\":{}}");

            // when
            SteamAppPage first = steamAppListClient.getPage(SteamAppType.GAME, 4_294_967_294L, 1);
            SteamAppPage last = steamAppListClient.getPage(SteamAppType.GAME,
                first.nextCursor().orElseThrow(), 50_000);

            // then
            assertThat(first.apps()).containsExactly(new SteamApp(4_294_967_295L, "Last app"));
            assertThat(last.nextCursor()).isEmpty();
            mockRestServiceServer.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"response\":{}}", "{\"response\":{\"apps\":[]}}"})
        @DisplayName("남은 앱이 없으면 빈 페이지로 종료한다")
        void endsOnEmptyPage(String body) {
            // given
            mockRestServiceServer.expect(anything())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            // when
            SteamAppPage page = steamAppListClient.getPage(SteamAppType.GAME, 70, 2);

            // then
            assertThat(page.apps()).isEmpty();
            assertThat(page.nextCursor()).isEmpty();
        }
    }

    @Nested
    @DisplayName("실패와 정상 종료 구분")
    class Failures {

        @ParameterizedTest(name = "커서 {0}, 페이지 크기 {1}")
        @CsvSource({"-1,1", "4294967296,1", "0,0", "0,50001"})
        @DisplayName("허용 범위를 벗어나면 외부 요청 전에 거부한다")
        void rejectsInvalidRequest(long cursor, int pageSize) {
            // given
            SteamAppType type = SteamAppType.GAME;

            // when
            Throwable failure = catchThrowable(
                () -> steamAppListClient.getPage(type, cursor, pageSize));

            // then
            assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("범위");
        }

        @Test
        @DisplayName("종류 미확인 상태로는 Steam 목록을 요청할 수 없다")
        void rejectsUnknownType() {
            // given
            SteamAppType type = SteamAppType.UNKNOWN;

            // when
            Throwable failure = catchThrowable(() -> steamAppListClient.getPage(type, 0, 2));

            // then
            assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("미확인");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("Steam 키가 비어 있으면 클라이언트 생성 시 설정 오류를 알린다")
        void rejectsBlankApiKeyAtStartup(String blankKey) {
            // given
            RestClient.Builder builder = RestClient.builder();

            // when
            Throwable failure = catchThrowable(
                () -> new SteamAppListClient(builder, blankKey));

            // then
            assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STEAM_WEB_API_KEY");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "{}", "{\"response\":null}"})
        @DisplayName("성공 응답 본체가 없으면 수집 실패다")
        void rejectsMissingResponse(String body) {
            // given
            mockRestServiceServer.expect(anything())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            // when
            Throwable failure = catchThrowable(
                () -> steamAppListClient.getPage(SteamAppType.DLC, 0, 2));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class).hasMessageContaining("누락");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            """
                {"have_more_results":true,"apps":[{"appid":71,"name":"Next"}]}
                """,
            """
                {"have_more_results":true,"apps":[],"last_appid":90}
                """,
            """
                {"have_more_results":true,"apps":[{"appid":70,"name":"Old"}],"last_appid":70}
                """,
            """
                {"apps":[{"appid":69,"name":"Old"}]}
                """,
            """
                {"have_more_results":false,"apps":[{"appid":70,"name":"Repeated"}]}
                """,
            """
                {"have_more_results":true,"apps":[{"appid":71,"name":"Next"}],"last_appid":90}
                """
        })
        @DisplayName("응답의 앱이나 다음 커서가 요청 커서보다 앞서지 않으면 실패한다")
        void rejectsBrokenContinuation(String response) {
            // given
            String body = "{\"response\":" + response + "}";
            mockRestServiceServer.expect(anything())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            // when
            Throwable failure = catchThrowable(
                () -> steamAppListClient.getPage(SteamAppType.GAME, 70, 2));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class).hasMessageContaining("커서");
        }

        @ParameterizedTest
        @ValueSource(strings = {"null", "{\"name\":\"Missing ID\"}", "{\"appid\":10}",
            "{\"appid\":0,\"name\":\"Zero\"}", "{\"appid\":4294967296,\"name\":\"Overflow\"}"})
        @DisplayName("유효한 식별자와 이름이 없는 앱을 정상 페이지로 넘기지 않는다")
        void rejectsIncompleteApp(String app) {
            // given
            String body = "{\"response\":{\"apps\":[" + app + "]}}";
            mockRestServiceServer.expect(anything())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            // when
            Throwable failure = catchThrowable(
                () -> steamAppListClient.getPage(SteamAppType.GAME, 0, 2));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("Steam의 접근 거부를 빈 목록으로 처리하지 않는다")
        void propagatesAccessDenied() {
            // given
            mockRestServiceServer.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN));

            // when
            Throwable failure = catchThrowable(
                () -> steamAppListClient.getPage(SteamAppType.GAME, 0, 2));

            // then
            assertThat(failure).isInstanceOf(HttpClientErrorException.Forbidden.class);
        }
    }

    private void expectPage(boolean games, boolean dlc, long cursor, int pageSize, String body) {
        mockRestServiceServer.expect(request -> {
            assertThat(request.getURI().getScheme()).isEqualTo("https");
            assertThat(request.getURI().getHost()).isEqualTo("api.steampowered.com");
            assertThat(request.getURI().getPath()).isEqualTo("/IStoreService/GetAppList/v1/");
            String query = request.getURI().getQuery();
            assertThat(query).startsWith("input_json=").doesNotContain("test-key", "&");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode input = mapper.readTree(query.substring("input_json=".length()));
            assertThat(input.size()).isEqualTo(4);
            assertThat(input.required("include_games")).isEqualTo(mapper.valueToTree(games));
            assertThat(input.required("include_dlc")).isEqualTo(mapper.valueToTree(dlc));
            assertThat(input.required("last_appid").isIntegralNumber()).isTrue();
            assertThat(input.required("last_appid").longValue()).isEqualTo(cursor);
            assertThat(input.required("max_results")).isEqualTo(mapper.valueToTree(pageSize));
        }).andExpect(method(HttpMethod.GET)).andExpect(header("x-webapi-key", "test-key"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
