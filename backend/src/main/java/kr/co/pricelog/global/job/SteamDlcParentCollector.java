package kr.co.pricelog.global.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.co.pricelog.global.client.steam.SteamStoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 카탈로그의 DLC 페이지를 상점 요청 크기로 나누어 부모 앱 번호를 확인한다. */
@Component
public class SteamDlcParentCollector {

    private final SteamStoreClient steamStoreClient;
    private final int batchSize;

    /** 본편 가격 수집과 동일한 상점 요청 크기를 사용한다. */
    public SteamDlcParentCollector(SteamStoreClient steamStoreClient,
                                  @Value("${pricelog.collection.daily.batch-size}") int batchSize) {
        Assert.isTrue(batchSize > 0, "상점 수집 묶음 크기는 양수여야 합니다");
        this.steamStoreClient = steamStoreClient;
        this.batchSize = batchSize;
    }

    /** 주어진 DLC 앱 번호에 대해 확인한 부모만 반환한다. */
    public Map<Long, Long> findParents(List<Long> ids) {
        Map<Long, Long> parents = new HashMap<>();
        for (int start = 0; start < ids.size(); start += batchSize) {
            int end = Math.min(start + batchSize, ids.size());
            parents.putAll(steamStoreClient.getDlcParents(ids.subList(start, end)));
        }
        return parents;
    }
}
