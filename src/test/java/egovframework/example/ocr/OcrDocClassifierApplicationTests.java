package egovframework.example.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트 로드 여부만 검증하는 스모크 테스트.
 * (실제 Ollama 서버 연결이 필요한 통합 테스트는 별도로 작성 권장)
 */
@SpringBootTest
class OcrDocClassifierApplicationTests {

    /**
     * Spring 컨텍스트(모든 빈: 컨트롤러, 서비스, 설정 등)가 예외 없이
     * 정상적으로 기동되는지만 확인하는 최소 단위의 헬스 체크 테스트.
     * 별도 assertion이 없어도, 컨텍스트 로드 중 예외가 발생하면 테스트가 실패한다.
     */
    @Test
    void contextLoads() {
    }
}
