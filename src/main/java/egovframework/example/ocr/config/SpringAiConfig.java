package egovframework.example.ocr.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 설정.
 *
 * <p>spring-ai-ollama-spring-boot-starter 가 자동구성한
 * {@link ChatClient.Builder} 를 주입받아, 시스템 프롬프트(설계서 5장)를
 * 기본값으로 고정한 ChatClient 빈을 생성한다.</p>
 */
@Configuration
public class SpringAiConfig {

    /**
     * LLM에게 항상 전달되는 기본 시스템 프롬프트.
     *
     * <p>모델의 역할(문서 분석 전문가)과 응답 형식(JSON only)을 고정해,
     * {@link egovframework.example.ocr.dto.AnalysisResult} 로 안전하게
     * 역직렬화할 수 있도록 유도한다.</p>
     */
    private static final String SYSTEM_PROMPT =
            "당신은 문서 분석 전문가입니다. 다음 텍스트와 OCR 상태에 대해 분류하고, " +
            "숫자나 날짜 누락 여부를 검증하세요. 답변은 반드시 JSON 형식으로만 응답하세요.";

    /**
     * 문서 분류/검증 전용 {@link ChatClient} 빈을 생성한다.
     *
     * <p>{@code spring-ai-ollama-spring-boot-starter} 가 {@code application.yml}
     * 의 {@code spring.ai.ollama.*} 설정(base-url, model 등)을 바탕으로
     * 자동 구성해 준 {@link ChatClient.Builder} 를 주입받아, 매 요청마다
     * {@link #SYSTEM_PROMPT} 가 시스템 메시지로 포함되도록 기본값을 고정한다.</p>
     *
     * <p>이 빈은 {@link egovframework.example.ocr.service.DocumentProcessorService}
     * 에서 실제 분류/검증 요청(user 메시지 + 추출된 텍스트)을 만들 때 사용된다.</p>
     *
     * @param builder Ollama 자동구성이 제공하는 ChatClient 빌더
     * @return 시스템 프롬프트가 고정된 ChatClient
     */
    @Bean
    public ChatClient documentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
