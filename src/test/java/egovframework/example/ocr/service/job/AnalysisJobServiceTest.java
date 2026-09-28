package egovframework.example.ocr.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.example.ocr.dto.AnalysisResponse;
import egovframework.example.ocr.dto.AnalysisResult;
import egovframework.example.ocr.dto.AnalysisStage;
import egovframework.example.ocr.dto.JobStatus;
import egovframework.example.ocr.dto.JobStatusResponse;
import egovframework.example.ocr.service.AnalysisProgressListener;
import egovframework.example.ocr.service.DocumentProcessorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnalysisJobService} / {@link AnalysisJob} 단위 테스트.
 *
 * <p>Spring 컨텍스트, Ollama, Tesseract 없이 실행된다. 실제 파이프라인 대신 테스트가
 * 진행 시점을 제어할 수 있는 가짜 프로세서를 사용한다.</p>
 */
class AnalysisJobServiceTest {

    private static final long TIMEOUT_MS = 5_000;

    private final List<ThreadPoolExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ThreadPoolExecutor::shutdownNow);
    }

    // ── 테스트 도구 ──────────────────────────────────────────────

    private ThreadPoolExecutor newExecutor(int pool, int queue) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(pool, pool, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), new ThreadPoolExecutor.AbortPolicy());
        executors.add(ex);
        return ex;
    }

    private static File tempPdf() throws IOException {
        File f = Files.createTempFile("job-test-", ".pdf").toFile();
        f.deleteOnExit();
        return f;
    }

    private static AnalysisResponse success(String name) {
        return AnalysisResponse.builder()
                .success(true).fileName(name).extractionMethod("PDFBOX")
                .result(AnalysisResult.builder().category("견적서").confidence(0.9)
                        .verificationStatus("정상").anomalies(List.of()).build())
                .build();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("시간 초과: " + what);
            }
            Thread.sleep(10);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "래치 대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 분석 동작을 람다로 지정할 수 있는 가짜 프로세서. */
    private interface Behavior {
        AnalysisResponse run(File file, String name, AnalysisProgressListener progress) throws Exception;
    }

    private static DocumentProcessorService processor(Behavior behavior) {
        return new DocumentProcessorService(null, null, null) {
            @Override
            public AnalysisResponse analyzeFile(File file, String name, AnalysisProgressListener progress) {
                try {
                    return behavior.run(file, name, progress);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    // ── 테스트 ───────────────────────────────────────────────────

    @Test
    void 진행_상황이_실행_중에_조회되고_완료_시_결과가_담긴다() throws Exception {
        CountDownLatch afterFirstPage = new CountDownLatch(1);
        CountDownLatch releaseOcr = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);

        AnalysisJobService service = new AnalysisJobService(processor((file, name, p) -> {
            p.stageStarted(AnalysisStage.ANALYZING);
            p.extractionMethodDetermined("OCR", 4);
            p.stageStarted(AnalysisStage.OCR);
            p.ocrPageCompleted(1, 4);
            afterFirstPage.countDown();
            awaitLatch(releaseOcr);
            p.ocrPageCompleted(4, 4);
            p.stageStarted(AnalysisStage.AI_ANALYSIS);
            awaitLatch(releaseAi);
            return success(name);
        }), newExecutor(1, 5), 1800);

        File pdf = tempPdf();
        AnalysisJob job = service.submit(pdf, "scan.pdf");
        assertNotNull(job.getId());

        // OCR 1/4 페이지 처리 중
        awaitLatch(afterFirstPage);
        JobStatusResponse s = service.toStatus(job);
        assertEquals(JobStatus.RUNNING, s.getStatus());
        assertEquals(AnalysisStage.OCR, s.getStage());
        assertEquals(25, s.getProgress(), "10 + 60 * 1/4");
        assertEquals("OCR 처리 중 (1/4 페이지)", s.getMessage());
        assertEquals("OCR", s.getExtractionMethod());
        assertEquals(1, s.getCurrentPage());
        assertEquals(4, s.getTotalPages());
        assertNull(s.getResult(), "진행 중에는 결과가 없어야 한다");
        assertNull(s.getQueuePosition());

        // AI 분석 단계: 진행률은 75 에서 머문다
        releaseOcr.countDown();
        awaitTrue(() -> service.toStatus(job).getStage() == AnalysisStage.AI_ANALYSIS, "AI 단계 진입");
        s = service.toStatus(job);
        assertEquals(JobStatus.RUNNING, s.getStatus());
        assertEquals(75, s.getProgress());
        assertTrue(s.getTimingsMs().containsKey("ocr"));
        assertTrue(s.getTimingsMs().containsKey("aiAnalysis"), "진행 중 단계의 시간도 포함되어야 한다");

        // 완료
        releaseAi.countDown();
        awaitTrue(job::isFinished, "작업 종료");
        s = service.toStatus(job);
        assertEquals(JobStatus.COMPLETED, s.getStatus());
        assertEquals(AnalysisStage.DONE, s.getStage());
        assertEquals(100, s.getProgress());
        assertNotNull(s.getResult());
        assertTrue(s.getResult().isSuccess());
        assertEquals("견적서", s.getResult().getResult().getCategory());
        assertFalse(s.getTimingsMs().containsKey("done"));
        awaitTrue(() -> !pdf.exists(), "임시 파일 삭제");
    }

    @Test
    void 대기_중인_작업은_대기_순번을_알려주고_대기열이_가득_차면_거절한다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        // 동시 1건 + 대기열 2건 = 최대 3건 접수 가능
        AnalysisJobService service = new AnalysisJobService(processor((file, name, p) -> {
            started.countDown();
            p.stageStarted(AnalysisStage.ANALYZING);
            awaitLatch(release);
            return success(name);
        }), newExecutor(1, 2), 1800);

        File pdfA = tempPdf(), pdfB = tempPdf(), pdfC = tempPdf(), pdfD = tempPdf();
        AnalysisJob a = service.submit(pdfA, "a.pdf");
        awaitLatch(started);                                   // A 가 실행 중임을 확인
        awaitTrue(() -> a.getStatus() == JobStatus.RUNNING, "A 실행");
        AnalysisJob b = service.submit(pdfB, "b.pdf");
        AnalysisJob c = service.submit(pdfC, "c.pdf");

        JobStatusResponse sb = service.toStatus(b);
        assertEquals(JobStatus.QUEUED, sb.getStatus());
        assertEquals(0, sb.getQueuePosition(), "B 는 다음 순서");
        assertEquals("분석 대기 중", sb.getMessage());
        assertEquals(0, sb.getProgress());

        JobStatusResponse sc = service.toStatus(c);
        assertEquals(1, sc.getQueuePosition(), "C 앞에는 B 1건");
        assertEquals("분석 대기 중 (앞에 1건)", sc.getMessage());

        // 대기열이 가득 참 -> 거절되고, 거절된 작업의 임시 파일은 서비스가 지운다
        assertThrows(JobRejectedException.class, () -> service.submit(pdfD, "d.pdf"));
        assertFalse(pdfD.exists(), "거절된 작업의 임시 파일은 삭제되어야 한다");
        assertTrue(pdfB.exists() && pdfC.exists(), "접수된 작업의 파일은 아직 남아 있어야 한다");

        // 모두 처리되면 순서대로 완료된다
        release.countDown();
        awaitTrue(() -> a.isFinished() && b.isFinished() && c.isFinished(), "모든 작업 종료");
        assertEquals(JobStatus.COMPLETED, service.toStatus(c).getStatus());
        assertNull(service.toStatus(c).getQueuePosition());
        awaitTrue(() -> !pdfA.exists() && !pdfB.exists() && !pdfC.exists(), "임시 파일 정리");
    }

    @Test
    void 예상하지_못한_예외는_FAILED로_기록되고_임시_파일이_삭제된다() throws Exception {
        AnalysisJobService service = new AnalysisJobService(processor((file, name, p) -> {
            p.stageStarted(AnalysisStage.OCR);
            throw new IllegalStateException("내부 상세 원인: /secret/path");
        }), newExecutor(1, 1), 1800);

        File pdf = tempPdf();
        AnalysisJob job = service.submit(pdf, "boom.pdf");
        awaitTrue(job::isFinished, "작업 종료");

        JobStatusResponse s = service.toStatus(job);
        assertEquals(JobStatus.FAILED, s.getStatus());
        assertEquals(AnalysisStage.OCR, s.getStage(), "실패한 시점의 단계가 남아야 한다");
        assertEquals("문서 처리 중 알 수 없는 오류가 발생했습니다.", s.getMessage());
        assertFalse(s.getMessage().contains("secret"), "내부 원인은 클라이언트에 노출하지 않는다");
        assertFalse(s.getResult().isSuccess());
        awaitTrue(() -> !pdf.exists(), "임시 파일 삭제");
    }

    @Test
    void 파이프라인이_실패_응답을_반환하면_FAILED와_사유가_전달된다() throws Exception {
        AnalysisJobService service = new AnalysisJobService(processor((file, name, p) -> {
            p.stageStarted(AnalysisStage.ANALYZING);
            return AnalysisResponse.builder().success(false).fileName(name)
                    .errorMessage("문서에서 텍스트를 추출하지 못했습니다: " + name).build();
        }), newExecutor(1, 1), 1800);

        AnalysisJob job = service.submit(tempPdf(), "empty.pdf");
        awaitTrue(job::isFinished, "작업 종료");

        JobStatusResponse s = service.toStatus(job);
        assertEquals(JobStatus.FAILED, s.getStatus());
        assertEquals("문서에서 텍스트를 추출하지 못했습니다: empty.pdf", s.getMessage());
        assertEquals("문서에서 텍스트를 추출하지 못했습니다: empty.pdf", s.getResult().getErrorMessage());
    }

    @Test
    void 보관_기간이_지난_종료_작업은_다음_접수_때_제거된다() throws Exception {
        // retention 0초: 종료되는 즉시 만료 대상
        AnalysisJobService service = new AnalysisJobService(
                processor((f, n, p) -> success(n)), newExecutor(1, 5), 0);

        AnalysisJob first = service.submit(tempPdf(), "1.pdf");
        awaitTrue(first::isFinished, "첫 작업 종료");
        assertTrue(service.find(first.getId()).isPresent(), "제거는 다음 접수 시점에 일어난다");

        AnalysisJob second = service.submit(tempPdf(), "2.pdf");
        assertTrue(service.find(first.getId()).isEmpty(), "만료된 작업은 제거되어야 한다");
        assertTrue(service.find(second.getId()).isPresent());
    }

    @Test
    void 보관_기간_안의_종료_작업은_유지된다() throws Exception {
        AnalysisJobService service = new AnalysisJobService(
                processor((f, n, p) -> success(n)), newExecutor(1, 5), 1800);

        AnalysisJob first = service.submit(tempPdf(), "1.pdf");
        awaitTrue(first::isFinished, "첫 작업 종료");
        service.submit(tempPdf(), "2.pdf");
        assertTrue(service.find(first.getId()).isPresent());
        assertTrue(service.find("no-such-id").isEmpty());
        assertTrue(service.find(null).isEmpty());
    }

    @Test
    void 상태_JSON은_프론트가_기대하는_형태다() throws Exception {
        CountDownLatch inOcr = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AnalysisJobService service = new AnalysisJobService(processor((file, name, p) -> {
            p.stageStarted(AnalysisStage.ANALYZING);
            p.extractionMethodDetermined("OCR", 3);
            p.stageStarted(AnalysisStage.OCR);
            p.ocrPageCompleted(2, 3);
            inOcr.countDown();
            awaitLatch(release);
            return success(name);
        }), newExecutor(1, 1), 1800);

        AnalysisJob job = service.submit(tempPdf(), "scan.pdf");
        awaitLatch(inOcr);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode running = mapper.readTree(mapper.writeValueAsString(service.toStatus(job)));
        assertEquals(job.getId(), running.get("jobId").asText());
        assertEquals("RUNNING", running.get("status").asText());
        assertEquals("OCR", running.get("stage").asText());
        assertEquals(50, running.get("progress").asInt());          // 10 + 60 * 2/3
        assertEquals(2, running.get("currentPage").asInt());
        assertEquals(3, running.get("totalPages").asInt());
        assertEquals("OCR", running.get("extractionMethod").asText());
        assertTrue(running.get("elapsedMs").isNumber());
        assertTrue(running.get("timingsMs").isObject());
        assertFalse(running.has("result"), "진행 중에는 result 필드가 없어야 한다 (NON_NULL)");
        assertFalse(running.has("queuePosition"));

        release.countDown();
        awaitTrue(job::isFinished, "작업 종료");
        JsonNode done = mapper.readTree(mapper.writeValueAsString(service.toStatus(job)));
        assertEquals("COMPLETED", done.get("status").asText());
        assertEquals("DONE", done.get("stage").asText());
        assertTrue(done.get("result").get("success").asBoolean());
        assertEquals("견적서", done.get("result").get("result").get("category").asText());
        // AnalysisResult 는 verification_status 로 직렬화된다 (프론트가 두 이름을 모두 허용해야 하는 이유)
        assertTrue(done.get("result").get("result").has("verification_status"));
    }
}
