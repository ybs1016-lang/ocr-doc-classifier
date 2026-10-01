package egovframework.example.ocr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 결과 화면의 "원본 JSON" 으로 그대로 보여주는 분석 보고서.
 *
 * <p>진행 중인 작업은 {@link #pending} 으로 만든 뼈대(분류·필드 없음)로 내려가고,
 * 분석이 끝나면 {@code StructuredReportBuilder} 가 만든 완성본으로 바뀐다.</p>
 *
 * @param schemaVersion     보고서 스키마 버전
 * @param jobId             작업 ID
 * @param documentId        문서 ID
 * @param processingMode    처리 방식 (GENERIC)
 * @param processingState   QUEUED | RUNNING | COMPLETED | FAILED
 * @param classification    문서유형 판별 결과
 * @param fields            추출 필드 목록
 * @param pageCount         페이지 수
 * @param tokenCount        추출된 토큰(공백 기준 단어) 수
 * @param recognitionStatus {@code TEXT_LAYER_OK}(텍스트 레이어 인식) | {@code OCR_USED}(OCR 보완)
 * @param ai                AI 분류/검증 원본 결과 (카테고리·신뢰도·이상 징후)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"schemaVersion", "jobId", "documentId", "processingMode", "processingState",
        "classification", "fields", "pageCount", "tokenCount", "recognitionStatus", "ai"})
public record AnalysisReport(
        String schemaVersion,
        String jobId,
        String documentId,
        String processingMode,
        String processingState,
        DocumentClassification classification,
        List<ExtractedField> fields,
        Integer pageCount,
        Integer tokenCount,
        String recognitionStatus,
        AnalysisResult ai) {

    /** 작업 ID/문서 ID/처리 상태를 채운 사본을 만든다. */
    public AnalysisReport withIds(String newJobId, String newDocumentId, String newState) {
        return new AnalysisReport(schemaVersion, newJobId, newDocumentId, processingMode, newState,
                classification, fields, pageCount, tokenCount, recognitionStatus, ai);
    }

    /** 아직 분석 결과가 없는 작업(대기/진행 중/실패)용 뼈대. */
    public static AnalysisReport pending(String schemaVersion, String jobId, String documentId, String state) {
        return new AnalysisReport(schemaVersion, jobId, documentId, "GENERIC", state,
                null, List.of(), null, null, null, null);
    }
}
