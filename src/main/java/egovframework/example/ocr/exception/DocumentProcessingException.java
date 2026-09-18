package egovframework.example.ocr.exception;

/**
 * PDF 파싱, OCR 처리, LLM 호출 과정에서 발생하는 예외를 감싸는 공통 예외.
 * (설계서 6.3절의 PDSecurityException 등 암호화/서명 문서 접근 오류도 이 예외로 래핑한다.)
 */
public class DocumentProcessingException extends RuntimeException {

    /**
     * 원인 예외 없이 메시지만으로 생성한다 (예: 입력값 자체가 유효하지 않은 경우).
     *
     * @param message 사용자에게 노출 가능한 오류 메시지
     */
    public DocumentProcessingException(String message) {
        super(message);
    }

    /**
     * 하위 계층에서 발생한 예외(IOException, PDSecurityException 등)를 감싸서 생성한다.
     *
     * @param message 사용자에게 노출 가능한 오류 메시지
     * @param cause   원본 예외 (스택트레이스 추적용)
     */
    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
