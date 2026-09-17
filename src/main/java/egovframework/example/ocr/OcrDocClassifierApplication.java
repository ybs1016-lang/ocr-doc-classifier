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

    public static void main(String[] args) {
        SpringApplication.run(OcrDocClassifierApplication.class, args);
    }
}
