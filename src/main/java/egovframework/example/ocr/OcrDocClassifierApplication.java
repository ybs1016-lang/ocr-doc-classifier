package egovframework.example.ocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * eGovFrame Boot + Spring AI(Ollama) 기반 OCR 문서 분류 및 검증 시스템.
 *
 * <p>설계 문서(「[기술 가이드] Spring AI 기반 OCR 문서 분류 및 검증 설계서」)의
 * 아키텍처를 기반으로 한다.</p>
 *
 * <pre>
 * PDF 파일 -> 문서 분석기
 *              ├─ 텍스트 스트림 있음  -> PDFBox 텍스트 추출
 *              └─ 이미지 기반 문서    -> OCR 처리 (Tess4J/PaddleOCR)
 *                          └─ Spring AI ChatRequest -> Ollama(qwen2-vl 모델)
 *                                      ├─ 문서 분류 결과 JSON
 *                                      └─ 텍스트 정확도 검증 결과
 * </pre>
 */
@SpringBootApplication
public class OcrDocClassifierApplication {

    /**
     * 애플리케이션 부트스트랩 진입점.
     *
     * <p>Spring Boot 내장 톰캣을 기동하고, {@code @SpringBootApplication}이 붙은
     * 이 클래스의 패키지({@code egovframework.example.ocr}) 하위에 있는
     * 컴포넌트(컨트롤러, 서비스, 설정 클래스 등)를 스캔하여 빈으로 등록한다.</p>
     *
     * @param args 커맨드라인 인자 (예: {@code --server.port=8081} 등 프로퍼티 오버라이드)
     */
    public static void main(String[] args) {
        SpringApplication.run(OcrDocClassifierApplication.class, args);
    }
}
