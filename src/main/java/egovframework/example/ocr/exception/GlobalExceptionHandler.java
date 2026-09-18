package egovframework.example.ocr.exception;

import egovframework.example.ocr.dto.AnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 컨트롤러 전역에서 발생하는 예외를 일관된 {@link AnalysisResponse} 형식으로 변환한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * PDF 파싱/OCR/LLM 호출 과정에서 의도적으로 던진 {@link DocumentProcessingException} 처리.
     *
     * <p>예상 가능한 업무적 오류(암호화된 PDF, LLM 응답 파싱 실패 등)이므로
     * 스택트레이스 없이 경고(warn) 레벨로만 로깅하고, 422 Unprocessable Entity
     * 로 응답한다.</p>
     *
     * @param e 발생한 문서 처리 예외
     * @return success=false, errorMessage에 원인 메시지를 담은 응답
     */
    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<AnalysisResponse> handleDocumentProcessing(DocumentProcessingException e) {
        log.warn("문서 처리 예외: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(AnalysisResponse.builder().success(false).errorMessage(e.getMessage()).build());
    }

    /**
     * {@code application.yml} 의 {@code spring.servlet.multipart.max-file-size} (기본 20MB)
     * 를 초과하는 파일이 업로드됐을 때 처리.
     *
     * @param e 스프링이 던지는 업로드 용량 초과 예외
     * @return 413 Payload Too Large 와 함께 안내 메시지를 담은 응답
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<AnalysisResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(AnalysisResponse.builder().success(false).errorMessage("업로드 가능한 최대 파일 크기를 초과했습니다.").build());
    }

    /**
     * 위에서 명시적으로 처리하지 않은 나머지 모든 예외에 대한 최종 안전망(catch-all).
     *
     * <p>예상치 못한 오류이므로 원인 파악을 위해 error 레벨로 스택트레이스까지 로깅하되,
     * 클라이언트에게는 내부 구현이 노출되지 않도록 일반화된 메시지만 반환한다.</p>
     *
     * @param e 처리되지 않은 예외
     * @return 500 Internal Server Error 와 함께 일반화된 오류 메시지를 담은 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AnalysisResponse> handleUnknown(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AnalysisResponse.builder().success(false).errorMessage("서버 내부 오류가 발생했습니다.").build());
    }
}
