package kr.co.pricelog.global.client.steam;

import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.BUNDLE;
import static kr.co.pricelog.global.client.steam.enums.SteamPurchaseOptionType.PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.global.client.steam.dto.SteamPrice;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOption;
import kr.co.pricelog.global.client.steam.dto.SteamPurchaseOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

class SteamStoreClientTest {

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final MockRestServiceServer mockRestServiceServer =
        MockRestServiceServer.bindTo(restClientBuilder).build();
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SteamStoreClient steamStoreClient = new SteamStoreClient(restClientBuilder,
        objectMapper);

    @Nested
    @DisplayName("한국 상점의 본편 구매 옵션 조회")
    class PurchaseOptions {

        @Test
        @DisplayName("414이면 묶음을 나누고 두 응답의 성공 앱을 원래 요청 순서대로 합친다")
        void splitsLongUri() {
            // given
            expectAppIds(List.of(7L, 4L, 1L)).andRespond(withStatus(HttpStatus.URI_TOO_LONG));
            expectAppIds(List.of(7L)).andRespond(withSuccess("""
                {"response":{"store_items":[{"id":7,"item_type":0,"success":1}]}}
                """, MediaType.APPLICATION_JSON));
            expectAppIds(List.of(4L, 1L)).andRespond(withSuccess("""
                {"response":{"store_items":[{"id":1,"item_type":0,"success":1},
                    {"id":4,"item_type":0,"success":15}]}}
                """, MediaType.APPLICATION_JSON));

            // when
            Map<Long, SteamPurchaseOptions> result = steamStoreClient.getPurchaseOptions(
                List.of(7L, 4L, 1L));

            // then
            assertThat(result.keySet()).containsExactly(7L, 1L);
            mockRestServiceServer.verify();
        }

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
            SteamPurchaseOptions result = steamStoreClient
                .getPurchaseOptions(List.of(101L)).get(101L);

            // then
            assertThat(result.freeToPlay()).isEqualTo(expectedFlag);
            assertThat(result.purchaseOptions()).containsExactly(
                new SteamPurchaseOption(PACKAGE, 201, "추가 상품", null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"header\":\"header.jpg\"}",
            "{\"asset_url_format\":\"steam/apps/101/${FILENAME}\"}",
            "{\"asset_url_format\":\"steam/apps/101/\",\"header\":\"header.jpg\"}"})
        @DisplayName("헤더 파일명이나 경로 형식이 불완전해도 구매 옵션을 읽는다")
        void readsOptionsWithoutUsableHeader(String assets) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"assets":%s,
                  "purchase_options":[{"packageid":42}]}]
                """.formatted(assets));

            // when
            SteamPurchaseOptions options = steamStoreClient.getPurchaseOptions(List.of(101L))
                .get(101L);

            // then
            assertThat(options.imageUrl()).isNull();
            assertThat(options.purchaseOptions()).extracting(SteamPurchaseOption::steamId)
                .containsExactly(42L);
        }

        @Test
        @DisplayName("동일한 숫자 ID의 패키지와 번들 중 Steam이 지정한 번들을 목록에서 찾는다")
        void readsKoreanPurchaseOptions() {
            // given
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
                         "language":"koreana"},"data_request":{"include_all_purchase_options":true,
                         "include_assets":true,"include_basic_info":true,
                         "include_release":true,"include_supported_languages":true,
                         "include_tag_count":20}}
                        """));
            }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

            // when
            SteamPurchaseOptions result = steamStoreClient
                .getPurchaseOptions(List.of(101L)).get(101L);

            // then
            assertThat(result.purchaseOptions()).containsExactlyInAnyOrder(
                new SteamPurchaseOption(PACKAGE, 201, "기본판", null),
                new SteamPurchaseOption(PACKAGE, 202, "디럭스판", null),
                new SteamPurchaseOption(BUNDLE, 201, "합본", null));
            assertThat(result.bestPurchaseOption())
                .isEqualTo(new SteamPurchaseOption(BUNDLE, 201, "합본", null));
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
            SteamPurchaseOptions result = steamStoreClient
                .getPurchaseOptions(List.of(101L)).get(101L);

            // then
            assertThat(result.purchaseOptions()).containsExactlyInAnyOrder(
                new SteamPurchaseOption(PACKAGE, 42, null, null),
                new SteamPurchaseOption(BUNDLE, 43, "합본", null));
            assertThat(result.bestPurchaseOption())
                .isEqualTo(new SteamPurchaseOption(PACKAGE, 42, null, null));
        }

        @Test
        @DisplayName("구매 옵션이 하나여도 Steam의 기본 지정이 없으면 선택하지 않는다")
        void keepsSingleOptionWithoutBestSelection() {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"purchase_options":[{"packageid":42}]}]
                """);

            // when
            SteamPurchaseOptions result = steamStoreClient
                .getPurchaseOptions(List.of(101L)).get(101L);

            // then
            assertThat(result.purchaseOptions())
                .containsExactly(new SteamPurchaseOption(PACKAGE, 42, null, null));
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
            SteamPurchaseOptions result = steamStoreClient
                .getPurchaseOptions(List.of(570L)).get(570L);

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
        @DisplayName("지정한 종류와 ID에 해당하는 구매 옵션이 없거나 여러 건이면 해당 앱을 제외한다")
        void rejectsUnmatchedBestOption(String options) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":101,"success":1,"best_purchase_option":{"packageid":42},
                  "purchase_options":%s}]
                """.formatted(options));

            // when
            Map<Long, SteamPurchaseOptions> result = steamStoreClient.getPurchaseOptions(
                List.of(101L));

            // then
            assertThat(result).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "[]",
            "[{\"item_type\":0,\"id\":570,\"success\":15}]",
            "[{\"item_type\":0,\"id\":570,\"success\":2,\"visible\":false}]",
            "[{\"item_type\":1,\"id\":570,\"success\":15,\"visible\":false}]",
            "[{\"item_type\":0,\"id\":10,\"success\":1}]",
            "[{\"item_type\":1,\"id\":570,\"success\":1}]",
            "[{\"item_type\":0,\"id\":570,\"success\":2}]"})
        @DisplayName("결과 누락·앱 ID 불일치·다른 상품 종류·조회 실패를 빈 옵션과 구분한다")
        void rejectsUnconfirmedApp(String items) {
            // given
            respondWithItems(items);

            // when
            Map<Long, SteamPurchaseOptions> result = steamStoreClient.getPurchaseOptions(
                List.of(570L));

            // then
            assertThat(result).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{}",
            "{\"packageid\":42,\"bundleid\":42}",
            "{\"packageid\":0}"})
        @DisplayName("상품 ID가 누락·중복·무효이면 해당 앱을 제외한다")
        void rejectsIncompleteOption(String option) {
            // given
            respondWithItems("""
                [{"item_type":0,"id":570,"success":1,"purchase_options":[%s]}]
                """.formatted(option));

            // when
            Map<Long, SteamPurchaseOptions> result = steamStoreClient.getPurchaseOptions(
                List.of(570L));

            // then
            assertThat(result).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"final_price_in_cents\":0}",
        "{\"final_price_in_cents\":1200,\"formatted_final_price\":\"$12\"}",
        "{\"formatted_final_price\":\"₩ -1\"}",
        "{\"formatted_final_price\":\"₩ 9223372036854775808\"}",
        "{\"final_price_in_cents\":0,\"formatted_final_price\":\"₩ 0\",\"is_free_license\":true}",
        "{\"final_price_in_cents\":0,\"formatted_final_price\":\"₩ 0\",\"recurrence_info\":{}}"})
    @DisplayName("미확인 금액·무료 라이선스·정기 결제는 옵션을 유지하고 해당 가격만 비운다")
    void keepsOptionWithoutEligiblePrice(String fields) throws Exception {
        // given
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode option = (ObjectNode) objectMapper.readTree(fields);
        option.put("bundleid", 31);
        respondWithItems("""
            [{"id":101,"item_type":0,"success":1,"purchase_options":[%s,{"packageid":31,
              "formatted_final_price":"₩ 9,420",
              "original_price_in_cents":"1","formatted_original_price":"₩ 62,800",
              "active_discounts":[{"discount_end_date":1796234400}]}]}]
            """.formatted(option));

        // when
        SteamPurchaseOptions result = steamStoreClient
            .getPurchaseOptions(List.of(101L)).get(101L);

        // then
        assertThat(result.purchaseOptions()).containsExactly(new SteamPurchaseOption(
            BUNDLE, 31, null, null), new SteamPurchaseOption(PACKAGE, 31, null,
                new SteamPrice(9420, 62800L, Instant.ofEpochSecond(1796234400))));
    }

    @Test
    @DisplayName("한 요청에서 확인된 앱만 요청 순서대로 반환한다")
    void separatesUnconfirmedAppsInBatch() {
        // given
        mockRestServiceServer.expect(request -> {
            String query = request.getURI().getQuery().substring("input_json=".length());
            assertThat(objectMapper.readTree(query).get("ids")).isEqualTo(objectMapper.readTree("""
                [{"appid":1},{"appid":2},{"appid":3},{"appid":4},{"appid":5},{"appid":6}]
                """));
        }).andRespond(withSuccess("""
            {"response":{"store_items":[
                {"item_type":0,"id":6,"success":15},
                {"item_type":0,"id":5,"success":1,"is_free":true},
                {"item_type":0,"id":4,"success":1,"purchase_options":[{"packageid":0}]},
                {"item_type":0,"id":3,"success":1},{"item_type":0,"id":3,"success":1},
                {"item_type":0,"id":1,"success":1},{"item_type":0,"id":99,"success":1}
            ]}}
            """, MediaType.APPLICATION_JSON));

        // when
        Map<Long, SteamPurchaseOptions> result = steamStoreClient.getPurchaseOptions(
            List.of(1L, 2L, 3L, 4L, 5L, 6L));

        // then
        assertThat(result.keySet()).containsExactly(1L, 5L);
        assertThat(result.get(5L).freeToPlay()).isTrue();
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("DLC 조회는 관계 필드만 요청하고 앱 ID가 일치하는 정상 응답에서 부모를 읽는다")
    void readsDlcParentIds() {
        // given
        List<Long> ids = List.of(660L, 1256L, 1257L, 1258L);
        mockRestServiceServer.expect(request -> {
            String query = request.getURI().getQuery().substring("input_json=".length());
            assertThat(objectMapper.readTree(query).get("data_request"))
                .isEqualTo(objectMapper.readTree("{\"include_related_items\":true}"));
        }).andRespond(withSuccess("""
            {"response":{"store_items":[
             {"id":1257,"item_type":0,"success":1,"related_items":{"parent_appid":1250}},
             {"id":660,"item_type":0,"success":15,"visible":false},
             {"id":1258,"item_type":0,"success":1},
             {"id":1256,"item_type":0,"success":1,"related_items":{"parent_appid":1250}},
             {"id":99,"item_type":0,"success":1,"related_items":{"parent_appid":100}}]}}
            """, MediaType.APPLICATION_JSON));

        // when
        Map<Long, Long> parents = steamStoreClient.getDlcParents(ids);

        // then
        assertThat(parents).isEqualTo(Map.of(1256L, 1250L, 1257L, 1250L));
        mockRestServiceServer.verify();
    }

    private ResponseActions expectAppIds(List<Long> ids) {
        return mockRestServiceServer.expect(request -> {
            String query = request.getURI().getQuery().substring("input_json=".length());
            assertThat(objectMapper.readTree(query).get("ids").findValues("appid"))
                .extracting(id -> id.longValue()).containsExactlyElementsOf(ids);
        });
    }

    private void respondWithItems(String items) {
        String body = "{\"response\":{\"store_items\":" + items + "}}";
        mockRestServiceServer.expect(anything())
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
