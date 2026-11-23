package kr.co.pricelog.global.client.steam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Steam이 요청받은 상점 상품별로 돌려준 결과 목록. */
public record SteamStoreItems(@JsonProperty("store_items") List<JsonNode> items) {}
