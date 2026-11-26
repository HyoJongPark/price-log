package kr.co.pricelog.global.client.steam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kr.co.pricelog.global.client.steam.constants.SteamApiConstants;
import kr.co.pricelog.global.client.steam.dto.SteamTagDictionary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** 한국어 태그 사전의 버전을 확인하고 변경된 사전 전체를 읽는다. */
@Component
public class SteamTagDictionaryClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** 공개 Web API에 인증 헤더 없이 연결한다. */
    public SteamTagDictionaryClient(RestClient.Builder restClientBuilder,
                                     ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(SteamApiConstants.BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    /** 이전 버전과 같으면 Steam은 빈 response 객체를 돌려준다. */
    @Retry(name = "steamRequest")
    public Optional<SteamTagDictionary> getTagDictionary(String versionHash) {
        ObjectNode query = objectMapper.createObjectNode().put("language", "koreana");
        if (versionHash != null) {
            query.put("have_version_hash", versionHash);
        }
        JsonNode body = restClient.get().uri("/IStoreService/GetTagList/v1/?input_json={query}",
            query.toString()).retrieve().body(JsonNode.class);
        JsonNode response = body == null ? null : body.get("response");
        if (response == null || !response.isObject()) {
            throw new SteamResponseException("Steam 태그 사전 응답이 누락되었습니다");
        }
        if (versionHash != null && response.isEmpty()) {
            return Optional.empty();
        }

        String version = response.path("version_hash").textValue();
        JsonNode tags = response.path("tags");
        if (!StringUtils.hasText(version) || !tags.isArray() || tags.isEmpty()) {
            throw new SteamResponseException("Steam 태그 사전 버전 또는 목록이 누락되었습니다");
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (JsonNode tag : tags) {
            JsonNode id = tag.path("tagid");
            String name = tag.path("name").textValue();
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0
                || !StringUtils.hasText(name) || names.putIfAbsent(id.longValue(), name) != null) {
                throw new SteamResponseException("Steam 태그 사전에 유효하지 않은 항목이 있습니다");
            }
        }
        return Optional.of(new SteamTagDictionary(version, Map.copyOf(names)));
    }
}
