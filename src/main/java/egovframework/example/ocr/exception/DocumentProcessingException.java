package egovframework.example.ocr.exception;

/**
 * PDF 파싱, OCR 처리, LLM 호출 과정에서 발생하는 예외를 감싸는 공통 예외.
 * (설계서 6.3절의 PDSecurityException 등 암호화/서명 문서 접근 오류도 이 예외로 래핑한다.)
 */
public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
