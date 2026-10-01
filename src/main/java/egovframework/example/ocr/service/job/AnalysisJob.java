package egovframework.example.ocr.service.job;

import egovframework.example.ocr.dto.AnalysisReport;
import egovframework.example.ocr.dto.AnalysisResponse;
import egovframework.example.ocr.dto.AnalysisStage;
import egovframework.example.ocr.dto.JobStatus;
import egovframework.example.ocr.dto.JobLogEntry;
import egovframework.example.ocr.dto.JobStatusResponse;
import egovframework.example.ocr.service.AnalysisProgressListener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 비동기 분석 작업 하나의 상태를 보관하는 객체.
 *
 * <p>작업 스레드(진행 상황을 갱신)와 HTTP 요청 스레드(상태를 조회)가 동시에
 * 접근하므로, 가변 필드는 모두 {@code this} 모니터로 보호한다. 조회는 항상
 * {@link #snapshot(Integer)} 로 <b>일관된 사본(JobStatusResponse)</b>을 얻어 사용한다.</p>
 *
 * <p>스스로 {@link AnalysisProgressListener} 를 구현하므로, 파이프라인에 그대로 넘기면
 * 단계 전환·OCR 페이지 진행이 이 객체에 반영된다.</p>
 */
public final class AnalysisJob implements AnalysisProgressListener {

    // ── 진행률(%) 매핑 ────────────────────────────────────────────
    // AI 분석은 소요 시간을 예측할 수 없으므로 진행률을 75 에 고정하고,
    // 화면은 경과 시간과 애니메이션으로 "진행 중"임을 보여준다.
    private static final int PROGRESS_ANALYZING = 5;
    private static final int PROGRESS_OCR_BASE = 10;
    private static final int PROGRESS_OCR_SPAN = 60;   // OCR 완료 시 10 + 60 = 70
    private static final int PROGRESS_EXTRACTING = 20;
    private static final int PROGRESS_AI = 75;

    // ── 불변 필드 ────────────────────────────────────────────────
    private static final String SCHEMA_VERSION = "1.1";

    private final String uuid = UUID.randomUUID().toString();
    /** 작업 ID. 화면에 그대로 보이므로 {@code job-} 접두사를 붙인다. */
    private final String id = "job-" + uuid;
    /** 문서 ID. 작업과 같은 UUID 를 쓴다. */
    private final String documentId = "doc-" + uuid;
    private final String fileName;
    /** 접수 순번. 대기열 내 위치 계산에 사용한다. */
    private final long sequence;
    private final long createdAtNanos = System.nanoTime();

    // ── 가변 필드 (this 로 보호) ──────────────────────────────────
    private JobStatus status = JobStatus.QUEUED;
    private AnalysisStage stage = AnalysisStage.QUEUED;
    private String extractionMethod;
    private int currentPage;
    private int totalPages;
    private AnalysisResponse result;
    private long stageStartedNanos = createdAtNanos;
    private long finishedAtNanos;
    /** 종료 시각(epoch ms). 0 이면 아직 종료되지 않음. 보관 기간 만료 판단에 사용한다. */
    private long finishedAtMillis;
    private final EnumMap<AnalysisStage, Long> timingsNanos = new EnumMap<>(AnalysisStage.class);
    /** 단계별 처리 로그. */
    private final List<JobLogEntry> logs = new ArrayList<>();
    /** 화면의 원문 미리보기용으로 보관하는 업로드 원본(PDF). 없으면 null. */
    private byte[] originalPdf;
    private boolean aiLogged;

    public AnalysisJob(String fileName, long sequence) {
        this.fileName = fileName;
        this.sequence = sequence;
        synchronized (this) {
            addLog("SUCCESS", "UPLOAD_ACCEPTED", "문서 확인 완료", null);
            addLog("SUCCESS", "JOB_CREATED", "작업 생성 완료", null);
            addLog("SUCCESS", "JOB_QUEUED", "작업 등록 완료", null);
        }
    }

    // ── 불변 값 접근자 ───────────────────────────────────────────
    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDocumentId() {
        return documentId;
    }

    /** 원문 미리보기용 원본 PDF 를 보관한다. */
    public synchronized void setOriginalPdf(byte[] bytes) {
        this.originalPdf = bytes;
    }

    public synchronized byte[] getOriginalPdf() {
        return originalPdf;
    }

    public long getSequence() {
        return sequence;
    }

    // ── 상태 조회 ────────────────────────────────────────────────
    public synchronized JobStatus getStatus() {
        return status;
    }

    public synchronized boolean isFinished() {
        return status.isTerminal();
    }

    public synchronized long getFinishedAtMillis() {
        return finishedAtMillis;
    }

    // ── 작업 스레드에서의 상태 전이 ───────────────────────────────

    /** 작업 스레드가 실행을 시작했음을 기록한다. 대기 시간(queued)이 여기서 확정된다. */
    public synchronized void markRunning() {
        if (status != JobStatus.QUEUED) {
            return;
        }
        long now = System.nanoTime();
        closeStageTiming(now);
        status = JobStatus.RUNNING;
        stageStartedNanos = now;
    }

    @Override
    public synchronized void stageStarted(AnalysisStage newStage) {
        if (status.isTerminal()) {
            return;
        }
        long now = System.nanoTime();
        closeStageTiming(now);
        stage = newStage;
        stageStartedNanos = now;
        if (newStage == AnalysisStage.OCR) {
            currentPage = 0;
        }
        if (newStage == AnalysisStage.AI_ANALYSIS && !aiLogged) {
            // 텍스트 추출(또는 OCR)이 끝나 분석이 시작되는 시점.
            aiLogged = true;
            addLog("SUCCESS", "TEXT_EXTRACTION_COMPLETED", "문자·위치정보 추출 완료", null);
            addLog("STARTED", "ANALYSIS_STARTED", "분석 시작", "attempt=1");
        }
    }

    @Override
    public synchronized void extractionMethodDetermined(String method, int pages) {
        this.extractionMethod = method;
        this.totalPages = pages;
    }

    @Override
    public synchronized void ocrPageCompleted(int donePages, int pages) {
        this.currentPage = donePages;
        this.totalPages = pages;
    }

    /**
     * 파이프라인이 반환한 최종 응답으로 작업을 종료한다.
     * {@code response.success} 가 true 면 COMPLETED, 아니면 FAILED 이다.
     * 이미 종료된 작업에는 아무 영향이 없다.
     */
    public synchronized void finish(AnalysisResponse response) {
        if (status.isTerminal()) {
            return;
        }
        long now = System.nanoTime();
        closeStageTiming(now);
        this.result = response;
        if (response != null && response.isSuccess()) {
            status = JobStatus.COMPLETED;
            stage = AnalysisStage.DONE;
            logCompletion(response.getReport());
        } else {
            status = JobStatus.FAILED;   // stage 는 실패한 시점의 단계로 남겨 화면에서 위치를 표시
            addLog("FAILED", "ANALYSIS_FAILED", "분석 실패",
                    response != null ? response.getErrorMessage() : null);
        }
        finishedAtNanos = now;
        finishedAtMillis = System.currentTimeMillis();
    }

    /** 예상하지 못한 예외 등으로 작업을 실패 처리한다. */
    public void fail(String errorMessage) {
        finish(AnalysisResponse.builder()
                .success(false)
                .fileName(fileName)
                .errorMessage(errorMessage)
                .build());
    }

    // ── 조회용 스냅샷 ────────────────────────────────────────────

    /**
     * 현재 상태의 일관된 사본을 만든다.
     *
     * @param queuePosition 대기 중일 때 앞선 대기 작업 수. 대기 상태가 아니면 null
     */
    public synchronized JobStatusResponse snapshot(Integer queuePosition) {
        long now = System.nanoTime();
        boolean finished = status.isTerminal();

        EnumMap<AnalysisStage, Long> timings = new EnumMap<>(timingsNanos);
        if (!finished) {
            // 진행 중인 단계의 경과 시간도 포함해 화면에서 실시간으로 보이게 한다.
            timings.merge(stage, now - stageStartedNanos, Long::sum);
        }
        Map<String, Long> timingsMs = new LinkedHashMap<>();
        for (AnalysisStage s : AnalysisStage.values()) {
            Long nanos = timings.get(s);
            if (nanos != null && s != AnalysisStage.DONE) {
                timingsMs.put(s.getKey(), nanos / 1_000_000L);
            }
        }

        long elapsedNanos = (finished ? finishedAtNanos : now) - createdAtNanos;

        return JobStatusResponse.builder()
                .jobId(id)
                .documentId(documentId)
                .fileName(fileName)
                .fileUrl(originalPdf != null ? "/api/v1/documents/jobs/" + id + "/file" : null)
                .status(status)
                .stage(stage)
                .progress(progressLocked())
                .message(messageLocked(queuePosition))
                .queuePosition(status == JobStatus.QUEUED ? queuePosition : null)
                .currentPage(extractionMethod != null && "OCR".equals(extractionMethod) ? currentPage : null)
                .totalPages(extractionMethod != null ? totalPages : null)
                .extractionMethod(extractionMethod)
                .elapsedMs(elapsedNanos / 1_000_000L)
                .timingsMs(timingsMs)
                .result(finished ? result : null)
                .report(reportLocked())
                .logs(List.copyOf(logs))
                .build();
    }

    // ── 내부 계산 (호출자가 this 락을 보유) ───────────────────────

    /** 로그 한 줄을 추가한다. 시각은 작업 생성 시점 기준 경과 시간이다. */
    private void addLog(String logStatus, String code, String message, String detail) {
        long offsetMs = (System.nanoTime() - createdAtNanos) / 1_000_000L;
        logs.add(new JobLogEntry(logs.size() + 1, offsetMs, logStatus, code, message, detail));
    }

    /** 분석 성공 시 남기는 마무리 로그들. */
    private void logCompletion(AnalysisReport report) {
        String type = null;
        String fieldDetail = null;
        String verifyDetail = null;
        if (report != null) {
            if (report.classification() != null) {
                type = report.classification().documentType();
            }
            int total = report.fields() == null ? 0 : report.fields().size();
            long review = report.fields() == null ? 0 : report.fields().stream()
                    .filter(f -> "REVIEW_REQUIRED".equals(f.verification())).count();
            fieldDetail = total + "개 필드";
            verifyDetail = "검수 필요 " + review + "건";
        }
        addLog("SUCCESS", "CLASSIFICATION_COMPLETED", "문서유형 판별 완료", type);
        addLog("SUCCESS", "FIELD_EXTRACTION_COMPLETED", "필드 추출 완료", fieldDetail);
        addLog("SUCCESS", "VERIFICATION_COMPLETED", "값 검증 완료", verifyDetail);
        addLog("SUCCESS", "ANALYSIS_COMPLETED", "분석 완료", null);
    }

    /** 화면용 보고서. 결과가 있으면 완성본에, 없으면 뼈대에 ID 와 상태를 채운다. */
    private AnalysisReport reportLocked() {
        AnalysisReport base = result != null ? result.getReport() : null;
        if (base == null) {
            return AnalysisReport.pending(SCHEMA_VERSION, id, documentId, status.name());
        }
        return base.withIds(id, documentId, status.name());
    }

    /** 현재 단계에서 지금까지 흐른 시간을 해당 단계의 누적 시간에 더한다. */
    private void closeStageTiming(long nowNanos) {
        timingsNanos.merge(stage, nowNanos - stageStartedNanos, Long::sum);
    }

    private int progressLocked() {
        if (status == JobStatus.COMPLETED) {
            return 100;
        }
        switch (stage) {
            case ANALYZING:
                return PROGRESS_ANALYZING;
            case EXTRACTING:
                return PROGRESS_EXTRACTING;
            case OCR:
                return totalPages > 0
                        ? PROGRESS_OCR_BASE + (int) Math.round((double) PROGRESS_OCR_SPAN * currentPage / totalPages)
                        : PROGRESS_OCR_BASE;
            case AI_ANALYSIS:
                return PROGRESS_AI;
            case DONE:
                return 100;
            case QUEUED:
            default:
                return 0;
        }
    }

    private String messageLocked(Integer queuePosition) {
        if (status == JobStatus.COMPLETED) {
            return "분석이 완료되었습니다.";
        }
        if (status == JobStatus.FAILED) {
            return (result != null && result.getErrorMessage() != null)
                    ? result.getErrorMessage()
                    : "문서 분석에 실패했습니다.";
        }
        if (status == JobStatus.QUEUED && queuePosition != null && queuePosition > 0) {
            return "분석 대기 중 (앞에 " + queuePosition + "건)";
        }
        if (stage == AnalysisStage.OCR && totalPages > 0) {
            return "OCR 처리 중 (" + currentPage + "/" + totalPages + " 페이지)";
        }
        return stage.getLabel();
    }
}
