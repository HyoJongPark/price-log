package kr.co.pricelog.global.client.steam;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamReviewCounts;
import kr.co.pricelog.global.client.steam.dto.SteamReviewWindow;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 한국 작성일 하루의 Steam 구매자 평가를 모든 언어에서 집계한다. */
@Component
public class SteamReviewClient {

    public static final int REVIEWS_PER_PAGE = 100;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String REVIEW_QUERY = """
        {"appid":%d,"languages":["all"],"filter":1,"purchase_type":0,"review_type":0,
         "filter_offtopic_activity":true,"num_per_page":%d,
         "date_range_start":%d,"date_range_end":%d}
        """;

    private final RestClient restClient;

    /** 공개 리뷰 API에는 인증 헤더를 보내지 않는다. */
    public SteamReviewClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(SteamApiConstants.BASE_URL).build();
    }

    /** 첫 페이지의 합계만 사용한다. Steam의 조회 구간은 양 끝 초를 모두 포함한다. */
    @Retry(name = "steamRequest")
    public SteamReviewCounts getDailyCounts(long steamAppId, LocalDate reviewDate) {
        return summary(request(steamAppId, reviewDate, reviewDate, 1));
    }

    /** 부분 페이지의 가장 오래된 작성일에는 다음 페이지의 평가도 있을 수 있다. */
    @Retry(name = "steamRequest")
    public SteamReviewWindow getReviewWindow(long steamAppId, LocalDate start, LocalDate end) {
        JsonNode response = request(steamAppId, start, end, REVIEWS_PER_PAGE);
        SteamReviewCounts summary = summary(response);
        JsonNode reviews = response.path("reviews");
        if (summary.totalCount() == 0) {
            if (reviews.isArray() && !reviews.isEmpty()) {
                throw new RestClientException("0건 집계에 Steam 평가가 포함되었습니다");
            }
            return new SteamReviewWindow(0, start, Map.of());
        }
        if (!reviews.isArray() || reviews.isEmpty() || reviews.size() > summary.totalCount()
            || reviews.size() != count(response.path("query_summary"), "num_reviews")) {
            throw new RestClientException("Steam 구매자 평가 목록과 건수가 일치하지 않습니다");
        }

        Map<LocalDate, SteamReviewCounts> counts = new HashMap<>();
        Set<String> ids = new HashSet<>();
        LocalDate oldest = end;
        int positive = 0;
        for (JsonNode review : reviews) {
            JsonNode timestamp = review.path("timestamp_created");
            String id = review.path("recommendationid").asText("");
            if (id.isBlank() || !ids.add(id) || !timestamp.isIntegralNumber()
                || !timestamp.canConvertToLong() || timestamp.longValue() < 0
                || timestamp.longValue() > 0xffff_ffffL || !review.path("voted_up").isBoolean()) {
                throw new RestClientException("Steam 평가 식별자 또는 작성 정보가 유효하지 않습니다");
            }
            LocalDate day = Instant.ofEpochSecond(timestamp.longValue()).atZone(SEOUL)
                .toLocalDate();
            if (day.isBefore(start) || day.isAfter(oldest)) {
                throw new RestClientException("Steam 평가의 작성일 범위 또는 순서가 다릅니다");
            }
            oldest = day;
            int vote = review.get("voted_up").booleanValue() ? 1 : 0;
            positive += vote;
            counts.merge(day, new SteamReviewCounts(vote, 1), (a, b) ->
                new SteamReviewCounts(a.positiveCount() + b.positiveCount(),
                    a.totalCount() + b.totalCount()));
        }

        if (positive > summary.positiveCount()
            || reviews.size() - positive > summary.totalCount() - summary.positiveCount()) {
            throw new RestClientException("Steam 평가 목록과 긍정·부정 건수가 일치하지 않습니다");
        }
        if (reviews.size() == summary.totalCount()) {
            return new SteamReviewWindow(summary.totalCount(), start, counts);
        }
        counts.remove(oldest);
        return new SteamReviewWindow(summary.totalCount(), oldest.plusDays(1), counts);
    }

    private JsonNode request(long steamAppId, LocalDate from, LocalDate to, int pageSize) {
        if (from.isAfter(to) || !to.isBefore(LocalDate.now(SEOUL))) {
            throw new IllegalArgumentException("완결된 한국 작성일만 집계할 수 있습니다");
        }
        long start = from.atStartOfDay(SEOUL).toEpochSecond();
        long end = to.plusDays(1).atStartOfDay(SEOUL).toEpochSecond() - 1;
        String query = String.format(Locale.ROOT, REVIEW_QUERY, steamAppId, pageSize, start, end);

        JsonNode body = restClient.get()
            .uri("/IUserReviewsService/GetAppReviews/v1/?input_json={q}", query)
            .retrieve().body(JsonNode.class);
        if (body == null) {
            throw new RestClientException("Steam 구매자 평가 응답이 비어 있습니다");
        }
        return body.path("response");
    }

    private static SteamReviewCounts summary(JsonNode response) {
        JsonNode summary = response.path("query_summary");
        int positive = count(summary, "total_positive");
        int negative = count(summary, "total_negative");
        int total = count(summary, "total_reviews");
        if ((long) positive + negative != total || total != count(response, "total_matching")) {
            throw new RestClientException("Steam 구매자 평가 합계가 일치하지 않습니다");
        }
        return new SteamReviewCounts(positive, total);
    }

    private static int count(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isInt() || value.intValue() < 0) {
            throw new RestClientException("Steam 구매자 평가 건수를 확인할 수 없습니다: " + field);
        }
        return value.intValue();
    }
}
