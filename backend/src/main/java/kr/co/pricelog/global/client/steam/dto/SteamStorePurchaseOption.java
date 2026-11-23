package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Steam 앱 응답의 구매 옵션 원본 필드. */
record SteamStorePurchaseOption(
    @JsonProperty("packageid") Long packageId,
    @JsonProperty("bundleid") Long bundleId,
    @JsonProperty("purchase_option_name") String name,

    @JsonProperty("formatted_final_price") String finalDisplay,
    @JsonProperty("formatted_original_price") String originalDisplay,

    @JsonProperty("is_free_license") boolean freeLicense,
    @JsonProperty("recurrence_info") JsonNode recurrenceInfo,
    @JsonProperty("active_discounts") List<SteamDiscount> discounts) {

    private static final Pattern WON = Pattern.compile("₩\\s*([0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)");

    /** 일회 구매의 원화 가격을 반환한다. null이면 상품 연결은 유지하고 이번 가격 관측을 건너뛴다. */
    SteamPrice price() {
        // 무료 라이선스와 정기 결제는 일회 구매 가격 이력에서 제외한다.
        if (freeLicense || (recurrenceInfo != null && !recurrenceInfo.isNull())) {
            return null;
        }

        Long currentPrice = parseWon(finalDisplay);
        if (currentPrice == null) {
            return null;
        }

        return new SteamPrice(currentPrice, parseWon(originalDisplay), discountEndsAt());
    }

    /** 활성 할인이 여러 개면 모두 같은 시각에 끝날 때만 상품의 할인 종료 시각을 기록한다. */
    private Instant discountEndsAt() {
        if (discounts == null || discounts.isEmpty() || discounts.getFirst() == null) {
            return null;
        }

        Long end = discounts.getFirst().endDate();
        if (end == null || end <= 0
            || discounts.stream().anyMatch(discount -> discount == null
                || !end.equals(discount.endDate()))) {
            return null;
        }

        return Instant.ofEpochSecond(end);
    }

    /** 한국 상점 표시가에서 원 단위 금액을 읽는다. 예를 들어 "₩ 9,420"은 9420원이다. */
    private static Long parseWon(String display) {
        Matcher matcher = WON.matcher(display == null ? "" : display);
        if (!matcher.matches()) {
            return null;
        }

        try {
            return Long.parseLong(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
