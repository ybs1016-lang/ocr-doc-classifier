package egovframework.example.ocr.controller;

import egovframework.example.ocr.dto.AnalysisResponse;
import egovframework.example.ocr.dto.JobStatus;
import egovframework.example.ocr.dto.JobStatusResponse;
import egovframework.example.ocr.exception.DocumentProcessingException;
import egovframework.example.ocr.service.DocumentProcessorService;
import egovframework.example.ocr.service.job.AnalysisJob;
import egovframework.example.ocr.service.job.AnalysisJobService;
import egovframework.example.ocr.service.job.JobRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * 설계서 3장 아키텍처의 진입점(A. PDF 파일)에 해당하는 REST API.
 *
 * <pre>
 * POST /api/v1/documents/analyze  (multipart/form-data, key = "file")
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentAnalysisController {

    /** 전체 흐름(추출 → OCR 보완 → LLM 분류/검증)을 오케스트레이션하는 서비스. */
    private final DocumentProcessorService documentProcessorService;

    /** 비동기 분석 작업의 접수/상태 조회를 담당하는 서비스. */
    private final AnalysisJobService analysisJobService;

    /**
     * PDF 파일을 업로드받아 분류/검증 결과를 반환하는 API.
     *
     * <p>처리 흐름:</p>
     * <ol>
     *   <li>업로드 파일 유효성 검사(비어있는지 여부)</li>
     *   <li>{@link #toTempFile(MultipartFile)} 로 임시 디스크 파일에 저장</li>
     *   <li>{@link DocumentProcessorService#analyzeFile} 호출 →
     *       PDFBox 텍스트 추출, 필요 시 OCR 보완, Ollama 분류/검증까지 수행</li>
     *   <li>성공 시 200 OK, 분석 자체는 됐지만 결과가 실패({@code success=false})인
     *       경우 422 Unprocessable Entity 로 응답</li>
     *   <li>{@code finally} 블록에서 임시 파일 정리</li>
     * </ol>
     *
     * <p>{@code file} 이 없거나 비어있으면 400 Bad Request 를 즉시 반환한다.
     * 그 외 처리 중 예외는 {@link GlobalExceptionHandler} 에서 공통 처리된다.</p>
     *
     * @param file multipart/form-data 로 전송된 PDF 파일 (form key: {@code file})
     * @return 분류/검증 결과를 담은 {@link AnalysisResponse}
     */
    @PostMapping(value = "/analyze", consumes = "multipart/form-data")
    public ResponseEntity<AnalysisResponse> analyze(@RequestParam("file") MultipartFile file) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(AnalysisResponse.builder()
                            .success(false)
                            .errorMessage("업로드된 파일이 없습니다.")
                            .build());
        }

        File tempFile = null;
        try {
            // MultipartFile은 요청이 끝나면 사라지므로, PDFBox/Tess4J 등에서
            // java.io.File 기반으로 다루기 위해 임시 파일로 옮겨둔다.
            tempFile = toTempFile(file);
            AnalysisResponse response = documentProcessorService.analyzeFile(tempFile, file.getOriginalFilename());
            return response.isSuccess()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        } finally {
            // 성공/실패와 무관하게 서버 디스크에 남지 않도록 반드시 삭제 시도.
            cleanup(tempFile);
        }
    }

    /**
     * 업로드된 {@link MultipartFile} 을 서버 임시 디렉터리에 {@code .pdf} 확장자로 저장한다.
     *
     * <p>파일명은 {@code ocr-doc-<UUID>.pdf} 형태로 무작위 생성해 동시 요청 간
     * 파일명 충돌을 방지한다.</p>
     *
     * @param file 클라이언트가 업로드한 원본 파일
     * @return 디스크에 저장된 임시 파일
     * @throws DocumentProcessingException 임시 파일 생성/쓰기 중 I/O 오류가 발생한 경우
     */
    /**
     * PDF 를 <b>비동기</b>로 분석하도록 접수한다. (권장 API)
     *
     * <p>파일을 임시 디스크에 저장한 뒤 곧바로 {@code 202 Accepted} 와 작업 ID 를 반환하고,
     * 실제 분석(PDF 추출 → OCR → AI 분류)은 백그라운드 스레드가 수행한다. 클라이언트는
     * {@link #getJob(String)} 으로 진행 상황을 주기적으로 조회해 결과를 받는다.
     * 오래 걸리는 분석이 HTTP 연결을 붙잡지 않으므로 타임아웃 위험이 없다.</p>
     *
     * <ul>
     *   <li>202 : 접수됨 (본문 = 초기 상태, {@code Location} = 상태 조회 URL)</li>
     *   <li>400 : 파일 없음</li>
     *   <li>503 : 대기열이 가득 참 ({@code Retry-After} 헤더 참고)</li>
     * </ul>
     *
     * @param file multipart form 의 {@code file} 파트
     */
    @PostMapping(value = "/jobs", consumes = "multipart/form-data")
    public ResponseEntity<JobStatusResponse> submitJob(@RequestParam("file") MultipartFile file) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorStatus("업로드된 파일이 없습니다."));
        }

        // 요청이 끝나면 MultipartFile 이 사라지므로, 백그라운드 작업이 읽을 수 있게 임시 파일로 옮긴다.
        // 저장 실패 시 DocumentProcessingException -> GlobalExceptionHandler(422) 가 처리한다.
        File tempFile = toTempFile(file);
        try {
            AnalysisJob job = analysisJobService.submit(tempFile, file.getOriginalFilename());
            tempFile = null;   // 접수 성공: 임시 파일의 소유권과 삭제 책임이 작업으로 넘어갔다.
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/documents/jobs/" + job.getId()))
                    .body(analysisJobService.toStatus(job));
        } catch (JobRejectedException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "10")
                    .body(errorStatus(e.getMessage()));
        } finally {
            // 접수 전에 예외가 난 경우에만 파일이 남아 있다. (정상 접수 시 tempFile == null)
            cleanup(tempFile);
        }
    }

    /**
     * 비동기 분석 작업의 현재 진행 상태를 조회한다. (클라이언트가 주기적으로 호출)
     *
     * <p>{@code status} 가 {@code COMPLETED}/{@code FAILED} 가 되면 {@code result} 에
     * 최종 분석 결과가 담기며, 이후에는 폴링을 멈추면 된다. 종료된 작업은 일정 시간
     * (기본 30분) 뒤 삭제되므로 그 뒤에는 404 가 반환된다.</p>
     *
     * @param jobId 접수 시 받은 작업 ID
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJob(@PathVariable("jobId") String jobId) {
        Optional<AnalysisJob> job = analysisJobService.find(jobId);
        if (job.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .body(errorStatus("분석 작업을 찾을 수 없습니다. 보관 기간이 지났거나 서버가 재시작되었을 수 있습니다."));
        }
        // 진행 상태는 계속 바뀌므로 브라우저/프록시가 캐시하지 않도록 한다.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(analysisJobService.toStatus(job.get()));
    }

    /**
     * 업로드한 원본 PDF 를 돌려준다. (결과 화면의 "원문 근거" 미리보기용)
     *
     * <p>작업과 같은 기간(기본 30분)만 보관하며, 그 뒤에는 404 가 반환된다.</p>
     */
    @GetMapping("/jobs/{jobId}/file")
    public ResponseEntity<byte[]> getJobFile(@PathVariable("jobId") String jobId) {
        Optional<AnalysisJob> job = analysisJobService.find(jobId);
        byte[] pdf = job.map(AnalysisJob::getOriginalPdf).orElse(null);
        if (pdf == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
        }
        String name = job.get().getFileName() != null ? job.get().getFileName() : "document.pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf);
    }

    /** 작업 API 의 오류 응답을 정상 응답과 같은 형태(JobStatusResponse)로 만든다. */
    private JobStatusResponse errorStatus(String message) {
        return JobStatusResponse.builder()
                .status(JobStatus.FAILED)
                .message(message)
                .build();
    }

    private File toTempFile(MultipartFile file) {
        try {
            Path tempPath = Files.createTempFile("ocr-doc-" + UUID.randomUUID(), ".pdf");
            file.transferTo(tempPath);
            return tempPath.toFile();
        } catch (IOException e) {
            throw new DocumentProcessingException("업로드 파일 처리 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * 분석에 사용한 임시 파일을 삭제한다.
     *
     * <p>삭제에 실패해도 요청 처리 자체를 실패시키지 않고, 경고 로그만 남긴다
     * (디스크 정리 실패가 사용자 응답에 영향을 주지 않도록 하기 위함).</p>
     *
     * @param tempFile {@link #toTempFile(MultipartFile)} 에서 생성된 임시 파일 (null 가능)
     */
    private void cleanup(File tempFile) {
        if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
            log.warn("임시 파일 삭제 실패: {}", tempFile.getAbsolutePath());
        }
    }
}
