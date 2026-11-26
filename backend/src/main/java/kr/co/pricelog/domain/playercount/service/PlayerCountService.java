package kr.co.pricelog.domain.playercount.service;

import kr.co.pricelog.domain.playercount.repository.CcuHistoryRepository;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerChart;
import kr.co.pricelog.global.client.steam.dto.SteamPlayerCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원천 앱 번호와 관측 시각으로 동시 접속자 이력을 보존한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class PlayerCountService {

    private final CcuHistoryRepository ccuHistoryRepository;

    /** 같은 인덱스에 한 앱의 번호·순위·현재·최고 동접 수를 모아 일괄 적재한다. */
    public void saveChart(SteamPlayerChart chart) {
        int size = chart.entries().size();
        Long[] appIds = new Long[size];
        Integer[] ranks = new Integer[size];
        Integer[] players = new Integer[size];
        Integer[] peakPlayers = new Integer[size];
        for (int i = 0; i < size; i++) {
            SteamPlayerCount entry = chart.entries().get(i);
            appIds[i] = entry.steamAppId();
            ranks[i] = entry.sourceRank();
            players[i] = entry.playerCount();
            peakPlayers[i] = entry.peakPlayerCount();
        }

        ccuHistoryRepository.insertNewChart(chart.observedAt(), appIds, ranks, players,
            peakPlayers);
    }
}
