package egovframework.example.ocr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 컨트롤러가 클라이언트에게 반환하는 최종 응답 형식.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResponse {

    /** 처리 성공 여부 */
    private boolean success;

    /** 원본 파일명 */
    private String fileName;

    /** 텍스트 추출 방식: PDFBOX(텍스트 스트림) / OCR(이미지 기반) */
    private String extractionMethod;

    /** LLM 분류/검증 결과 */
    private AnalysisResult result;

    /** 오류 발생 시 메시지 (성공 시 null) */
    private String errorMessage;
}
