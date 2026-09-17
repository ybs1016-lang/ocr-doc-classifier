package egovframework.example.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트 로드 여부만 검증하는 스모크 테스트.
 * (실제 Ollama 서버 연결이 필요한 통합 테스트는 별도로 작성 권장)
 */
@SpringBootTest
class OcrDocClassifierApplicationTests {

    @Test
    void contextLoads() {
    }
}
