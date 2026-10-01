package egovframework.example.ocr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 분석 작업의 처리 로그 한 줄.
 *
 * @param seq      1부터 증가하는 순번
 * @param offsetMs 작업 생성 시점부터의 경과 시간(ms)
 * @param status   {@code SUCCESS} | {@code STARTED} | {@code FAILED}
 * @param code     단계 코드 (예: UPLOAD_ACCEPTED)
 * @param message  사용자에게 보여줄 한글 설명
 * @param detail   부가 정보 (예: attempt=1). 없으면 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobLogEntry(int seq, long offsetMs, String status, String code, String message, String detail) {
}
