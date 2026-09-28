package egovframework.example.ocr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 비동기 분석 작업의 진행 상태 응답 DTO.
 *
 * <p>{@code POST /api/v1/documents/jobs}(접수) 와
 * {@code GET /api/v1/documents/jobs/{jobId}}(상태 조회) 가 같은 형태를 반환한다.
 * 값이 없는 필드는 JSON 에서 생략된다.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatusResponse {

    /** 작업 식별자 (UUID). 상태 조회 URL 에 사용한다. */
    private String jobId;

    /** 사용자가 업로드한 원본 파일명. */
    private String fileName;

    /** 작업 전체 상태. */
    private JobStatus status;

    /** 현재(또는 마지막으로 진행한) 처리 단계. */
    private AnalysisStage stage;

    /** 진행률 0~100. AI 분석 단계는 소요 시간을 예측할 수 없어 75 에 머문다. */
    private int progress;

    /** 사용자에게 그대로 보여줄 수 있는 상태 문구. 실패 시에는 실패 사유. */
    private String message;

    /** 대기 중일 때 내 앞에 대기 중인 작업 수 (0 이면 다음 순서). 대기 상태가 아니면 생략. */
    private Integer queuePosition;

    /** OCR 단계에서 지금까지 처리를 마친 페이지 수. */
    private Integer currentPage;

    /** 전체 페이지 수. 파일 분석이 끝난 뒤부터 채워진다. */
    private Integer totalPages;

    /** 텍스트 추출 방식({@code PDFBOX} | {@code OCR}). 판별되기 전에는 생략. */
    private String extractionMethod;

    /** 접수 시점부터 현재(또는 종료 시점)까지 경과한 시간(ms). */
    private long elapsedMs;

    /** 단계별 누적 소요 시간(ms). 키: queued, analyzing, extracting, ocr, aiAnalysis. */
    private Map<String, Long> timingsMs;

    /** 작업이 끝난 뒤(COMPLETED/FAILED)에만 채워지는 최종 분석 결과. */
    private AnalysisResponse result;
}
