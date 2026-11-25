package kr.co.pricelog.global.client.steam;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import kr.co.pricelog.global.client.steam.dto.SteamRelease;

/** 한국 상점 응답을 달력의 공개 날짜와 게임 상세의 언어 지원 문구로 변환한다. */
final class SteamGameDetailsMapper {

    static final int TAG_COUNT = 20;

    private static final int KOREAN_LANGUAGE = 4;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private SteamGameDetailsMapper() {}

    static String textOrNull(JsonNode value) {
        String text = value.textValue();
        if (text == null || text.codePoints()
            .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            return null;
        }
        return text;
    }

    /** Steam 응답 순서대로 검색용 상위 20개를 보존한다. 불완전한 배열로 기존 연결을 지우지 않는다. */
    static List<Long> tagIds(JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (!tag.isIntegralNumber() || !tag.canConvertToLong() || tag.longValue() <= 0
                || ids.contains(tag.longValue())) {
                return null;
            }
            ids.add(tag.longValue());
            if (ids.size() == TAG_COUNT) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    static SteamRelease release(JsonNode release) {
        if (release == null || !release.isObject()) {
            return null;
        }

        JsonNode timestamp = release.path("steam_release_date");
        LocalDate date = timestamp.isIntegralNumber() && timestamp.canConvertToLong()
            && timestamp.asLong() > 0
            && timestamp.asLong() <= 0xffff_ffffL
            ? Instant.ofEpochSecond(timestamp.asLong()).atZone(SEOUL).toLocalDate() : null;
        if (!release.path("is_coming_soon").asBoolean()) {
            if (date != null || release.path("is_coming_soon").isBoolean()) {
                return new SteamRelease(date, null, false);
            }
            return null;
        }

        String message = textOrNull(release.path("custom_release_date_message"));
        if (message != null) {
            return new SteamRelease(null, message, true);
        }
        if (date == null) {
            return new SteamRelease(null, null, true);
        }

        // 분기·월의 마지막 날이 타임스탬프로 와도 실제 출시일로 공개된 것은 아니다.
        return switch (release.path("coming_soon_display").asText("")) {
            case "date_full" -> new SteamRelease(date, null, true);
            case "date_month" -> new SteamRelease(null,
                date.getYear() + "년 " + date.getMonthValue() + "월", true);
            case "date_quarter" -> new SteamRelease(null,
                date.getYear() + "년 " + date.get(IsoFields.QUARTER_OF_YEAR) + "분기", true);
            case "date_year" -> new SteamRelease(null, date.getYear() + "년", true);
            default -> new SteamRelease(null, null, true);
        };
    }

    static String koreanSupport(JsonNode languages) {
        if (languages == null || !languages.isArray() || languages.isEmpty()) {
            return null;
        }

        boolean complete = true;
        for (JsonNode language : languages) {
            JsonNode code = language.path("elanguage");
            if (!code.isIntegralNumber() || !code.canConvertToInt()) {
                complete = false;
            } else if (code.intValue() == KOREAN_LANGUAGE) {
                return supportedFeatures(language);
            }
        }
        return complete ? "미지원" : null;
    }

    private static String supportedFeatures(JsonNode language) {
        List<String> features = new ArrayList<>();
        if (!language.path("supported").isBoolean() || !language.path("subtitles").isBoolean()
            || !language.path("full_audio").isBoolean()) {
            return null;
        }
        if (language.get("supported").booleanValue()) {
            features.add("인터페이스");
        }
        if (language.get("subtitles").booleanValue()) {
            features.add("자막");
        }
        if (language.get("full_audio").booleanValue()) {
            features.add("음성");
        }
        return features.isEmpty() ? "미지원" : String.join(", ", features);
    }
}
