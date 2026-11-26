package kr.co.pricelog.global.client.steam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import kr.co.pricelog.global.client.steam.dto.SteamReviewCounts;
import kr.co.pricelog.global.client.steam.dto.SteamReviewWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class SteamReviewClientTest {

    private static final LocalDate REVIEW_DATE = LocalDate.of(2020, 11, 20);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SteamReviewClient steamReviewClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        steamReviewClient = new SteamReviewClient(builder);
    }

    @Test
    @DisplayName("한국 작성일 양 끝을 포함하고 첫 페이지의 Steam 구매 평가 전체 건수를 읽는다")
    void getsDailyCounts() {
        // given
        server.expect(request -> {
            assertThat(request.getURI().getPath())
                .isEqualTo("/IUserReviewsService/GetAppReviews/v1/");
            String query = URLDecoder.decode(request.getURI().getRawQuery(),
                StandardCharsets.UTF_8);
            assertThat(query).startsWith("input_json=");
            JsonNode input = objectMapper.readTree(query.substring("input_json=".length()));
            assertThat(input).isEqualTo(objectMapper.readTree("""
                {"appid":2909400,"languages":["all"],"filter":1,"purchase_type":0,"review_type":0,
                 "num_per_page":1,"filter_offtopic_activity":true,"date_range_start":1605798000,
                 "date_range_end":1605884399}
                """));
        }).andRespond(withSuccess("""
            {"response":{"query_summary":{"num_reviews":1,"total_positive":18,"total_negative":12,
              "total_reviews":30},"total_matching":30}}
            """, MediaType.APPLICATION_JSON));

        // when
        SteamReviewCounts counts = steamReviewClient.getDailyCounts(2909400, REVIEW_DATE);

        // then
        assertThat(counts).isEqualTo(new SteamReviewCounts(18, 30));
        server.verify();
    }

    @Test
    @DisplayName("명시적인 0건은 집계 성공이다")
    void readsConfirmedZero() {
        // given
        server.expect(request -> {}).andRespond(withSuccess("""
            {"response":{"query_summary":{"total_positive":0,"total_negative":0,"total_reviews":0},
             "total_matching":0}}
            """, MediaType.APPLICATION_JSON));

        // when
        SteamReviewCounts counts = steamReviewClient.getDailyCounts(2909400, REVIEW_DATE);

        // then
        assertThat(counts).isEqualTo(new SteamReviewCounts(0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"total_negative\":0,\"total_reviews\":0}",
        "{\"total_positive\":18,\"total_negative\":12,\"total_reviews\":0}"
    })
    @DisplayName("요약 필드가 빠지거나 합계가 다르면 0건으로 처리하지 않는다")
    void rejectsUnconfirmedCounts(String summary) {
        // given
        String response = "{\"response\":{\"query_summary\":" + summary + ",\"total_matching\":0}}";
        server.expect(request -> {}).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        // when & then
        assertThatThrownBy(() -> steamReviewClient.getDailyCounts(2909400, REVIEW_DATE))
            .isInstanceOf(RestClientException.class).hasMessageContaining("Steam 구매자 평가");
    }

    @Test
    @DisplayName("부분 페이지는 자정 이후 완결된 날짜만 집계하고 잘린 작성일은 다시 조회한다")
    void excludesTheBoundaryDayOfPartialPage() {
        // given
        server.expect(request -> {}).andRespond(withSuccess("""
            {"response":{"total_matching":3,"query_summary":{"num_reviews":2,"total_positive":2,
            "total_negative":1,"total_reviews":3},"reviews":[
            {"recommendationid":"2","timestamp_created":1605884400,"voted_up":true},
            {"recommendationid":"1","timestamp_created":1605884399,"voted_up":false}]}}
            """, MediaType.APPLICATION_JSON));

        // when
        SteamReviewWindow window = steamReviewClient.getReviewWindow(7, REVIEW_DATE,
            REVIEW_DATE.plusDays(1));

        // then
        assertThat(window.completeFrom()).isEqualTo(REVIEW_DATE.plusDays(1));
        assertThat(window.counts()).containsOnlyKeys(REVIEW_DATE.plusDays(1))
            .containsValue(new SteamReviewCounts(1, 1));
    }

    @Test
    @DisplayName("같은 평가가 중복된 페이지를 완결일로 저장하지 않는다")
    void rejectsDuplicateReviewIds() {
        // given
        server.expect(request -> {}).andRespond(withSuccess("""
            {"response":{"total_matching":2,"query_summary":{"num_reviews":2,"total_positive":2,
            "total_negative":0,"total_reviews":2},"reviews":[
            {"recommendationid":"1","timestamp_created":1605884399,"voted_up":true},
            {"recommendationid":"1","timestamp_created":1605884399,"voted_up":true}]}}
            """, MediaType.APPLICATION_JSON));

        // when & then
        assertThatThrownBy(() -> steamReviewClient.getReviewWindow(7, REVIEW_DATE, REVIEW_DATE))
            .isInstanceOf(RestClientException.class).hasMessageContaining("식별자");
    }

    @Test
    @DisplayName("완결되지 않은 날짜는 요청 전에 거절한다")
    void rejectsIncompleteDate() {
        // when & then
        assertThatThrownBy(() -> steamReviewClient.getDailyCounts(2909400, LocalDate.MAX))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("완결된");
    }
}
