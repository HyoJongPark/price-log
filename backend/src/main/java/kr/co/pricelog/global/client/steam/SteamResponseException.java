package kr.co.pricelog.global.client.steam;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 수집 코드가 Steam 응답을 검증하며 직접 작성한 실패 사유. */
public class SteamResponseException extends RestClientException {

    /** 응답 원문을 포함하지 않는 검증 문구를 받는다. */
    public SteamResponseException(String reason) {
        super(reason);
    }

    /** Jackson이 감싼 검증 사유도 보존한다. 외부 오류 메시지에는 원문이나 URL이 들어갈 수 있다. */
    public static String reasonForLog(Throwable failure) {
        return switch (NestedExceptionUtils.getMostSpecificCause(failure)) {
            case SteamResponseException validation -> validation.getMessage();
            case RestClientResponseException http -> http.getStatusCode().toString();
            default -> failure.getClass().getSimpleName();
        };
    }
}
