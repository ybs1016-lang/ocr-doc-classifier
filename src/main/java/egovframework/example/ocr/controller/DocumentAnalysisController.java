package egovframework.example.ocr.controller;

import egovframework.example.ocr.dto.AnalysisResponse;
import egovframework.example.ocr.exception.DocumentProcessingException;
import egovframework.example.ocr.service.DocumentProcessorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
