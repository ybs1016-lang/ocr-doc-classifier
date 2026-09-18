package egovframework.example.ocr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 컨트롤러({@link egovframework.example.ocr.controller.DocumentAnalysisController})가
 * 클라이언트에게 반환하는 최종 응답 형식.
 *
 * <p>{@link egovframework.example.ocr.service.DocumentProcessorService} 가
 * 파일 추출 → (필요 시) OCR 보완 → LLM 분류/검증 전 과정을 마친 뒤,
 * 그 결과를 이 DTO 하나로 감싸서 반환한다. 성공/실패 케이스 모두
 * 동일한 형식으로 응답하며, 실패 시에는 {@link #result} 는 null,
 * {@link #errorMessage} 에 원인이 채워진다.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResponse {

    /** 처리 성공 여부. false인 경우 {@link #errorMessage} 를 함께 확인한다. */
    private boolean success;

    /** 업로드된 원본 파일명 (클라이언트가 보낸 그대로, 서버 임시 파일명이 아님). */
    private String fileName;

    /**
     * 텍스트 추출 방식.
     * <ul>
     *   <li>{@code PDFBOX} - PDF 내 텍스트 스트림에서 바로 추출한 경우</li>
     *   <li>{@code OCR} - 텍스트 스트림이 없는 이미지 기반(스캔) 문서라
     *       Tess4J 등 OCR 엔진으로 보완 추출한 경우</li>
     * </ul>
     */
    private String extractionMethod;

    /** Ollama(LLM)가 반환한 문서 분류/검증 결과. 실패 시 null. */
    private AnalysisResult result;

    /** 오류 발생 시 사용자에게 보여줄 메시지 (성공 시 null). */
    private String errorMessage;
}
