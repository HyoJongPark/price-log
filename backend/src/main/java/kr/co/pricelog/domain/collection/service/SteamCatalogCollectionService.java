package kr.co.pricelog.domain.collection.service;

import java.time.Instant;
import java.util.OptionalLong;
import kr.co.pricelog.domain.collection.entity.PipelineExecution;
import kr.co.pricelog.domain.collection.enums.PipelineType;
import kr.co.pricelog.domain.collection.repository.PipelineExecutionRepository;
import kr.co.pricelog.domain.game.enums.SteamAppType;
import kr.co.pricelog.domain.game.service.GameCatalogService;
import kr.co.pricelog.global.client.steam.dto.SteamAppPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Steam 목록 수집을 재개하고 페이지의 게임과 처리 위치를 함께 커밋한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class SteamCatalogCollectionService {

    private final PipelineExecutionRepository pipelineExecutionRepository;
    private final GameCatalogService gameCatalogService;

    /** 처음이면 0, 재실행이면 DB에 커밋된 커서, 완료했으면 빈 값을 반환한다. */
    public OptionalLong resumeCursor(SteamAppType type, Instant scheduledAt) {
        PipelineType pipelineType = PipelineType.forSteamList(type);
        pipelineExecutionRepository.openIfAbsent(pipelineType, scheduledAt);
        PipelineExecution execution = pipelineExecutionRepository.findByPipelineTypeAndScheduledAt(
            pipelineType, scheduledAt);
        execution.resume();
        return execution.nextCursor();
    }

    /** 페이지 트랜잭션이 끝난 뒤 실패한 목록에만 결과를 기록한다. */
    public void markFailed(SteamAppType type, Instant scheduledAt) {
        PipelineExecution execution = pipelineExecutionRepository.findByPipelineTypeAndScheduledAt(
            PipelineType.forSteamList(type), scheduledAt);
        execution.fail(Instant.now());
    }

    /** 게임과 마지막 처리 위치를 함께 저장한다. */
    public OptionalLong savePageAndAdvanceCursor(SteamAppType type, Instant scheduledAt,
                                                 SteamAppPage page,
                                                 Instant observedAt) {
        PipelineExecution execution = pipelineExecutionRepository.findByPipelineTypeAndScheduledAt(
            PipelineType.forSteamList(type), scheduledAt);
        OptionalLong lastAppId = OptionalLong.empty();
        if (!page.apps().isEmpty()) {
            gameCatalogService.applyPage(type, page.apps(), observedAt);
            lastAppId = OptionalLong.of(page.apps().getLast().appId());
        }

        execution.recordPage(lastAppId, page.nextCursor().isEmpty(), observedAt);
        return execution.nextCursor();
    }
}
