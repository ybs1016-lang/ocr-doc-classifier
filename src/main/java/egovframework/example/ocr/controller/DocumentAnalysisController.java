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

    private final DocumentProcessorService documentProcessorService;

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
            tempFile = toTempFile(file);
            AnalysisResponse response = documentProcessorService.analyzeFile(tempFile, file.getOriginalFilename());
            return response.isSuccess()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        } finally {
            cleanup(tempFile);
        }
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

    private void cleanup(File tempFile) {
        if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
            log.warn("임시 파일 삭제 실패: {}", tempFile.getAbsolutePath());
        }
    }
}
