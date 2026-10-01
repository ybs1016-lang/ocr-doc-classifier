package egovframework.example.ocr.service.job;

import egovframework.example.ocr.dto.AnalysisResponse;
import egovframework.example.ocr.dto.JobStatus;
import egovframework.example.ocr.dto.JobStatusResponse;
import egovframework.example.ocr.service.DocumentProcessorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 문서 분석을 백그라운드 스레드에서 실행하고, 작업별 진행 상태를 관리한다.
 *
 * <p>HTTP 요청은 파일을 접수({@link #submit})하는 즉시 작업 ID 를 받고 끝난다.
 * 실제 분석은 {@code analysisExecutor} 스레드 풀에서 수행되며, 클라이언트는
 * {@link #find(String)} / {@link #toStatus(AnalysisJob)} 로 진행 상황을 조회한다.</p>
 *
 * <p><b>저장소는 메모리</b>이므로 서버를 재시작하면 작업 이력은 사라진다.
 * 종료된 작업은 {@code analysis.job.retention-seconds}(기본 30분) 뒤 제거된다.</p>
 */
@Slf4j
@Service
public class AnalysisJobService {

    private final DocumentProcessorService processor;
    private final ThreadPoolExecutor executor;
    private final long retentionMillis;

    private final ConcurrentMap<String, AnalysisJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AnalysisJobService(DocumentProcessorService processor,
                              @Qualifier("analysisExecutor") ThreadPoolExecutor executor,
                              @Value("${analysis.job.retention-seconds:1800}") long retentionSeconds) {
        this.processor = processor;
        this.executor = executor;
        this.retentionMillis = Math.max(0L, retentionSeconds) * 1000L;
    }

    /**
     * 분석 작업을 접수하고 백그라운드 실행을 예약한다.
     *
     * <p><b>임시 파일의 소유권이 이 메서드로 넘어온다.</b> 정상 접수되면 작업이 끝난 뒤,
     * 접수가 거절되면 이 메서드가 직접 파일을 삭제한다. 호출자는 이후 삭제하지 않는다.</p>
     *
     * @param tempFile         컨트롤러가 디스크에 저장해 둔 PDF 임시 파일
     * @param originalFileName 사용자가 업로드한 원본 파일명
     * @return 접수된 작업 (상태는 QUEUED)
     * @throws JobRejectedException 대기열이 가득 차 접수할 수 없는 경우
     */
    public AnalysisJob submit(File tempFile, String originalFileName) {
        purgeExpired();

        AnalysisJob job = new AnalysisJob(originalFileName, sequence.incrementAndGet());
        // 분석이 끝나면 임시 파일이 지워지므로, 화면의 원문 미리보기용으로 원본을 메모리에 보관한다.
        job.setOriginalPdf(readQuietly(tempFile));
        jobs.put(job.getId(), job);
        try {
            executor.execute(() -> run(job, tempFile));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.getId());
            deleteQuietly(tempFile);
            log.warn("분석 대기열이 가득 차 작업을 거절했습니다: {}", originalFileName);
            throw new JobRejectedException("현재 분석 요청이 많아 접수할 수 없습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        log.debug("분석 작업 접수 jobId={} file={}", job.getId(), originalFileName);
        return job;
    }

    /** 작업 ID 로 작업을 찾는다. 없거나 보관 기간이 지나 제거된 경우 empty. */
    public Optional<AnalysisJob> find(String jobId) {
        return jobId == null ? Optional.empty() : Optional.ofNullable(jobs.get(jobId));
    }

    /** 작업의 현재 상태를 API 응답 형태로 만든다. 대기 중이면 대기열 위치도 계산해 포함한다. */
    public JobStatusResponse toStatus(AnalysisJob job) {
        Integer queuePosition = null;
        if (job.getStatus() == JobStatus.QUEUED) {
            queuePosition = (int) jobs.values().stream()
                    .filter(other -> other.getStatus() == JobStatus.QUEUED
                            && other.getSequence() < job.getSequence())
                    .count();
        }
        return job.snapshot(queuePosition);
    }

    /** 작업 스레드에서 실행되는 본체. 어떤 경우에도 작업이 RUNNING 으로 남지 않도록 한다. */
    private void run(AnalysisJob job, File file) {
        long startedAt = System.nanoTime();
        try {
            job.markRunning();
            // job 자체가 진행 리스너이므로 단계 전환/OCR 페이지 진행이 그대로 job 에 반영된다.
            AnalysisResponse response = processor.analyzeFile(file, job.getFileName(), job);
            job.finish(response);
        } catch (Throwable t) {
            // DocumentProcessorService 는 대부분의 예외를 응답으로 변환하지만,
            // Error(OutOfMemoryError 등)까지 포함해 마지막 안전망을 둔다.
            log.error("분석 작업 처리 중 오류 jobId={}", job.getId(), t);
            job.fail("문서 처리 중 알 수 없는 오류가 발생했습니다.");
            if (t instanceof VirtualMachineError) {
                throw (VirtualMachineError) t;
            }
        } finally {
            deleteQuietly(file);
            log.debug("분석 작업 종료 jobId={} status={} {}ms", job.getId(), job.getStatus(),
                    (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    /** 보관 기간이 지난 종료 작업을 메모리에서 제거한다. 접수할 때마다 가볍게 수행한다. */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        jobs.values().removeIf(job -> job.isFinished() && now - job.getFinishedAtMillis() >= retentionMillis);
    }

    /** 파일 내용을 읽는다. 읽을 수 없거나 비어 있으면 null (미리보기만 생략된다). */
    private byte[] readQuietly(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return bytes.length == 0 ? null : bytes;
        } catch (IOException | RuntimeException e) {
            log.warn("원본 PDF 를 읽지 못해 미리보기를 생략합니다: {}", e.getMessage());
            return null;
        }
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("임시 파일 삭제 실패: {}", file.getAbsolutePath());
        }
    }
}
