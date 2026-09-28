package egovframework.example.ocr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 문서 분석 작업을 백그라운드로 실행하는 스레드 풀 설정.
 *
 * <p>분석은 CPU(OCR)와 외부 LLM(Ollama)을 오래 점유하므로 동시 실행 수를
 * 작게 제한하고, 초과 요청은 대기열에 쌓는다. 대기열까지 가득 차면 새 요청은
 * 거절되어 HTTP 503 으로 응답한다. (무제한 대기는 메모리/응답 지연 문제를 만든다.)</p>
 *
 * <p>설정 키 (모두 선택, {@code application.yml} 에 없으면 기본값 사용):</p>
 * <ul>
 *   <li>{@code analysis.executor.pool-size} : 동시에 분석하는 작업 수 (기본 2)</li>
 *   <li>{@code analysis.executor.queue-capacity} : 대기열 크기 (기본 20)</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "analysisExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor analysisExecutor(
            @Value("${analysis.executor.pool-size:2}") int poolSize,
            @Value("${analysis.executor.queue-capacity:20}") int queueCapacity) {

        int size = Math.max(1, poolSize);
        int capacity = Math.max(1, queueCapacity);
        AtomicInteger counter = new AtomicInteger();

        // 데몬 스레드: 분석이 오래 걸리는 도중 서버를 종료(Ctrl+C)해도 JVM 이 작업 완료를 기다리며 멈추지 않는다.
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "analysis-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        return new ThreadPoolExecutor(
                size, size,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());   // 가득 차면 RejectedExecutionException
    }
}
