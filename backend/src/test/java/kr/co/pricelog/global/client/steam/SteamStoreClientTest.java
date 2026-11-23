package kr.co.pricelog.global.client.steam;

import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.BUNDLE;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class SteamStoreClientTest {

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final MockRestServiceServer mockRestServiceServer =
        MockRestServiceServer.bindTo(restClientBuilder).build();
    private final SteamStoreClient steamStoreClient = new SteamStoreClient(restClientBuilder);

    @Nested
    @DisplayName("한국 상점의 본편 구매 옵션 조회")
    class PurchaseOptions {

        @ParameterizedTest
        @CsvSource({"true,true", "false,false"})
        @DisplayName("유료 옵션이 있어도 게임의 무료 여부는 앱의 is_free 표기를 따른다")
        void readsAppFreeFlag(boolean sourceFlag, boolean expectedFlag) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"is_free":%s,"purchase_options":[
                  {"packageid":201,"purchase_option_name":"추가 상품",
                   "final_price_in_cents":"990000"}]}]
                """.formatted(sourceFlag));

            // when
            SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(101);

            // then
            assertThat(result.freeToPlay()).isEqualTo(expectedFlag);
            assertThat(result.purchaseOptions()).containsExactly(
                new SteamPurchaseOption(PACKAGE, 201, "추가 상품"));
        }

        @Test
        @DisplayName("동일한 숫자 ID의 패키지와 번들 중 Steam이 지정한 번들을 목록에서 찾는다")
        void readsKoreanPurchaseOptions() {
            // given
            ObjectMapper objectMapper = new ObjectMapper();
            String response = """
                {"response":{"store_items":[{"item_type":0,"id":101,"success":1,
                 "best_purchase_option":{"bundleid":201},
                 "purchase_options":[
                   {"packageid":201,"purchase_option_name":"기본판"},
                   {"packageid":202,"purchase_option_name":"디럭스판"},
                   {"bundleid":201,"purchase_option_name":"합본"}]}]}}
                """;
            mockRestServiceServer.expect(request -> {
                assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
                assertThat(request.getURI().getHost()).isEqualTo("api.steampowered.com");
                assertThat(request.getURI().getPath())
                    .isEqualTo("/IStoreBrowseService/GetItems/v1/");
                assertThat(request.getHeaders()).doesNotContainKey("x-webapi-key");
                String query = request.getURI().getQuery();
                assertThat(query).startsWith("input_json=");
                assertThat(objectMapper.readTree(query.substring("input_json=".length())))
                    .isEqualTo(objectMapper.readTree("""
                        {"ids":[{"appid":101}],"context":{"country_code":"KR",
                         "language":"koreana"},"data_request":{"include_all_purchase_options":true}}
                        """));
            }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

            // when
            SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(101);

            // then
            assertThat(result.purchaseOptions()).containsExactlyInAnyOrder(
                new SteamPurchaseOption(PACKAGE, 201, "기본판"),
                new SteamPurchaseOption(PACKAGE, 202, "디럭스판"),
                new SteamPurchaseOption(BUNDLE, 201, "합본"));
            assertThat(result.bestPurchaseOption())
                .isEqualTo(new SteamPurchaseOption(BUNDLE, 201, "합본"));
            mockRestServiceServer.verify();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{\"packageid\":42}",
            "{\"packageid\":42,\"purchase_option_name\":null}"})
        @DisplayName("이름이 없는 패키지도 ID를 보존하고 같은 응답의 다른 옵션을 함께 반환한다")
        void keepsOptionWithoutName(String unnamedOption) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"best_purchase_option":{"packageid":42},
                  "purchase_options":[%s,{"bundleid":43,"purchase_option_name":"합본"}]}]
                """.formatted(unnamedOption));

            // when
            SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(101);

            // then
            assertThat(result.purchaseOptions()).containsExactlyInAnyOrder(
                new SteamPurchaseOption(PACKAGE, 42, null),
                new SteamPurchaseOption(BUNDLE, 43, "합본"));
            assertThat(result.bestPurchaseOption())
                .isEqualTo(new SteamPurchaseOption(PACKAGE, 42, null));
        }

        @Test
        @DisplayName("구매 옵션이 하나여도 Steam의 기본 지정이 없으면 선택하지 않는다")
        void keepsSingleOptionWithoutBestSelection() {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"purchase_options":[{"packageid":42}]}]
                """);

            // when
            SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(101);

            // then
            assertThat(result.purchaseOptions())
                .containsExactly(new SteamPurchaseOption(PACKAGE, 42, null));
            assertThat(result.bestPurchaseOption()).isNull();
        }

        @Test
        @DisplayName("요청한 앱을 찾았지만 구매 옵션이 없으면 빈 목록을 반환한다")
        void readsAppWithoutOptions() {
            // given
            respondWithItems("""
                [{"item_type":0,"id":570,"success":1}]
                """);

            // when
            SteamPurchaseOptions result = steamStoreClient.getPurchaseOptions(570);

            // then
            assertThat(result.purchaseOptions()).isEmpty();
            assertThat(result.freeToPlay()).isNull();
            assertThat(result.bestPurchaseOption()).isNull();
        }
    }

    @Nested
    @DisplayName("다른 상품이나 불완전한 결과를 구매 옵션으로 사용하지 않는다")
    class Failures {

        @ParameterizedTest
        @ValueSource(strings = {
            "[{\"packageid\":43}]",
            "[{\"bundleid\":42}]",
            "[{\"packageid\":42},{\"packageid\":42}]"})
        @DisplayName("지정한 종류와 ID에 해당하는 구매 옵션이 없거나 여러 건이면 실패한다")
        void rejectsUnmatchedBestOption(String options) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"best_purchase_option":{"packageid":42},
                  "purchase_options":%s}]
                """.formatted(options));

            // when
            Throwable failure = catchThrowable(() -> steamStoreClient.getPurchaseOptions(101));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class)
                .hasMessage("Steam 기본 구매 옵션을 구매 목록의 한 상품에 대응할 수 없습니다");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
            [] | Steam 상점의 단건 조회 결과가 누락되거나 중복되었습니다
            [{"item_type":0,"id":10,"success":1}]  | Steam 상점이 요청한 앱의 구매 옵션을 확인하지 못했습니다
            [{"item_type":1,"id":570,"success":1}] | Steam 상점이 요청한 앱의 구매 옵션을 확인하지 못했습니다
            [{"item_type":0,"id":570,"success":2}] | Steam 상점이 요청한 앱의 구매 옵션을 확인하지 못했습니다
            """)
        @DisplayName("결과 누락·앱 ID 불일치·다른 상품 종류·조회 실패를 빈 옵션과 구분한다")
        void rejectsUnconfirmedApp(String items, String expectedReason) {
            // given
            respondWithItems(items);

            // when
            Throwable failure = catchThrowable(() -> steamStoreClient.getPurchaseOptions(570));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class)
                .hasMessage(expectedReason);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
            {}                             | Steam 구매 옵션에 패키지 또는 번들 ID 하나가 필요합니다
            {"packageid":42,"bundleid":42} | Steam 구매 옵션에 패키지 또는 번들 ID 하나가 필요합니다
            {"packageid":0}                | Steam 구매 옵션의 종류 또는 ID가 유효하지 않습니다
            """)
        @DisplayName("상품 ID가 누락·중복·무효이면 조회에 실패한다")
        void rejectsIncompleteOption(String option, String expectedReason) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":570,"success":1,"purchase_options":[%s]}]
                """.formatted(option));

            // when
            Throwable failure = catchThrowable(() -> steamStoreClient.getPurchaseOptions(570));

            // then
            assertThat(failure).isInstanceOf(RestClientException.class)
                .hasRootCauseMessage(expectedReason);
        }
    }

    private void respondWithItems(String items) {
        String body = "{\"response\":{\"store_items\":" + items + "}}";
        mockRestServiceServer.expect(anything())
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
