package egovframework.example.ocr.service.job;

/**
 * 대기열이 가득 차서 새 분석 작업을 접수할 수 없을 때 던지는 예외.
 * 컨트롤러가 이를 HTTP 503(Service Unavailable) 으로 변환한다.
 */
public class JobRejectedException extends RuntimeException {

    public JobRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
