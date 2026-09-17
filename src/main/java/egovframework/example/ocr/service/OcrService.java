package egovframework.example.ocr.service;

/**
 * 이미지 기반 스캔 문서의 OCR 처리를 담당하는 인터페이스.
 *
 * <p>설계서 6.1절 하이브리드 아키텍처 권장에 따라, 구현체를 Tess4J(Java 인프로세스)
 * 또는 PaddleOCR(별도 Python 서버 호출)로 자유롭게 교체할 수 있도록 인터페이스로 분리한다.</p>
 */
public interface OcrService {

    /**
     * 이미지 바이트 배열에서 텍스트를 추출한다.
     *
     * @param imageBytes PNG/JPEG 등 이미지 바이너리
     * @return 인식된 텍스트
     */
    String extractTextFromImage(byte[] imageBytes);
}
