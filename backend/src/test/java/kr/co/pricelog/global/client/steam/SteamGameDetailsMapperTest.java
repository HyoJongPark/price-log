package kr.co.pricelog.global.client.steam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import kr.co.pricelog.global.client.steam.dto.SteamRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SteamGameDetailsMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("공백뿐인 출시 문구가 공개 일자를 가리지 않는다")
    void ignoresBlankReleaseMessage() throws Exception {
        // given
        JsonNode release = objectMapper.readTree("""
            {"is_coming_soon":true,"steam_release_date":1830520800,
             "coming_soon_display":"date_full",
             "custom_release_date_message":"\\u00a0"}
            """);

        // when
        SteamRelease result = SteamGameDetailsMapper.release(release);

        // then
        assertThat(result).isEqualTo(new SteamRelease(LocalDate.of(2028, 1, 3), null, true));
    }

    @Test
    @DisplayName("다른 언어를 식별하지 못해도 명시된 한국어 지원을 읽는다")
    void readsKoreanAmongIncompleteLanguages() throws Exception {
        // given
        JsonNode languages = objectMapper.readTree("""
            [{},{"elanguage":4,"supported":true,"subtitles":false,"full_audio":false}]
            """);

        // when
        String result = SteamGameDetailsMapper.koreanSupport(languages);

        // then
        assertThat(result).isEqualTo("인터페이스");
    }

    @ParameterizedTest
    @CsvSource({"date_month,2027년 12월", "date_quarter,2027년 4분기", "date_year,2027년",
        "unknown,"})
    @DisplayName("공개 단위가 일자보다 넓으면 달력 날짜를 만들지 않는다")
    void keepsPublicPrecision(String display, String expected) throws Exception {
        // given
        JsonNode release = objectMapper.readTree("""
            {"is_coming_soon":true,"steam_release_date":1830240000,"coming_soon_display":"%s"}
            """.formatted(display));

        // when
        SteamRelease result = SteamGameDetailsMapper.release(release);

        // then
        assertThat(result).isEqualTo(new SteamRelease(null, expected, true));
    }

    @ParameterizedTest
    @CsvSource({"true,false,false,인터페이스", "false,true,false,자막", "false,false,true,음성",
        "false,false,false,미지원"})
    @DisplayName("한국어 인터페이스·자막·음성은 각 지원 표시로 구분한다")
    void readsKoreanFeatures(boolean supported, boolean subtitles, boolean fullAudio,
                            String expected) throws Exception {
        // given
        JsonNode languages = objectMapper.readTree("""
            [{"elanguage":4,"supported":%b,"subtitles":%b,"full_audio":%b}]
            """.formatted(supported, subtitles, fullAudio));

        // when
        String result = SteamGameDetailsMapper.koreanSupport(languages);

        // then
        assertThat(result).isEqualTo(expected);
    }
}
