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

    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<AnalysisResponse> handleDocumentProcessing(DocumentProcessingException e) {
        log.warn("문서 처리 예외: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(AnalysisResponse.builder().success(false).errorMessage(e.getMessage()).build());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<AnalysisResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(AnalysisResponse.builder().success(false).errorMessage("업로드 가능한 최대 파일 크기를 초과했습니다.").build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AnalysisResponse> handleUnknown(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AnalysisResponse.builder().success(false).errorMessage("서버 내부 오류가 발생했습니다.").build());
    }
}
