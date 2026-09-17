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

    private static final String SYSTEM_PROMPT =
            "당신은 문서 분석 전문가입니다. 다음 텍스트와 OCR 상태에 대해 분류하고, " +
            "숫자나 날짜 누락 여부를 검증하세요. 답변은 반드시 JSON 형식으로만 응답하세요.";

    @Bean
    public ChatClient documentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
