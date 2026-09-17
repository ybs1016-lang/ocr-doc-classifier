package egovframework.example.ocr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.example.ocr.dto.AnalysisResponse;
import egovframework.example.ocr.dto.AnalysisResult;
import egovframework.example.ocr.exception.DocumentProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/**
 * 설계서 4.2절 DocumentProcessorService 를 확장한 실제 구현체.
 *
 * <p>처리 흐름 (설계서 3장 아키텍처):</p>
 * <ol>
 *   <li>PDFBox 로 텍스트 스트림 존재 여부 판단</li>
 *   <li>텍스트 스트림이 없는 페이지는 이미지로 렌더링 후 OCR(Tess4J) 처리</li>
 *   <li>추출된 텍스트를 Spring AI(Ollama, qwen2-vl) 에 전달하여 분류/검증</li>
 *   <li>LLM 응답(JSON)을 {@link AnalysisResult} 로 파싱</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessorService {

    private final PdfExtractService pdfExtractService;
    private final OcrService ocrService;
    private final ChatClient documentChatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisResponse analyzeFile(File file, String originalFileName) {
        try {
            boolean hasText = pdfExtractService.hasExtractableText(file);
            String extractionMethod;
            String rawText;

            if (hasText) {
                // 1. 텍스트 스트림이 있는 일반 PDF -> PDFBox 로 바로 추출
                rawText = pdfExtractService.extractText(file);
                extractionMethod = "PDFBOX";
            } else {
                // 2. 이미지 기반 스캔 문서 -> 페이지 렌더링 후 OCR 처리
                rawText = extractTextViaOcr(file);
                extractionMethod = "OCR";
            }

            if (rawText == null || rawText.isBlank()) {
                throw new DocumentProcessingException("문서에서 텍스트를 추출하지 못했습니다: " + originalFileName);
            }

            AnalysisResult result = classifyAndVerify(rawText);

            return AnalysisResponse.builder()
                    .success(true)
                    .fileName(originalFileName)
                    .extractionMethod(extractionMethod)
                    .result(result)
                    .build();

        } catch (DocumentProcessingException e) {
            log.warn("문서 처리 실패: {}", e.getMessage());
            return AnalysisResponse.builder()
                    .success(false)
                    .fileName(originalFileName)
                    .errorMessage(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("문서 처리 중 알 수 없는 오류", e);
            return AnalysisResponse.builder()
                    .success(false)
                    .fileName(originalFileName)
                    .errorMessage("문서 처리 중 알 수 없는 오류가 발생했습니다.")
                    .build();
        }
    }

    /** 페이지별 이미지 렌더링 후 OCR 을 수행해 전체 텍스트를 합친다. */
    private String extractTextViaOcr(File file) {
        List<Boolean> pageAvailability = pdfExtractService.analyzeTextAvailability(file);
        StringBuilder combined = new StringBuilder();

        for (int i = 0; i < pageAvailability.size(); i++) {
            byte[] pageImage = pdfExtractService.renderPageAsImage(file, i);
            String pageText = ocrService.extractTextFromImage(pageImage);
            combined.append(pageText).append("\n");
        }
        return combined.toString().trim();
    }

    /** 설계서 4.2절 buildPrompt() 를 기반으로 LLM 에 분류/검증을 요청한다. */
    private AnalysisResult classifyAndVerify(String extractedText) {
        String prompt = buildPrompt(extractedText);

        String content = documentChatClient
                .prompt()
                .user(prompt)
                .call()
                .content();

        return parseResult(content);
    }

    private String buildPrompt(String text) {
        return """
                다음 PDF 문서를 분석해주세요:
                1. [분류]: 이 문서는 무엇인가? (계약서, 발주서 등)
                2. [검증]: OCR 오류로 숫자가 왜곡되지 않았는지 확인하세요.

                추출된 텍스트: %s

                답변은 아래 JSON 형식으로만 응답하세요:
                {
                  "category": "문서 종류",
                  "confidence": 0.0,
                  "verification_status": "정상 | 이상",
                  "anomalies": ["발견된 이상 징후"]
                }
                """.formatted(text);
    }

    /** LLM 응답에서 코드펜스(```json ... ```)를 제거하고 JSON 을 파싱한다. */
    private AnalysisResult parseResult(String content) {
        try {
            String cleaned = content == null ? "" : content
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();
            return objectMapper.readValue(cleaned, AnalysisResult.class);
        } catch (Exception e) {
            log.warn("LLM 응답 JSON 파싱 실패, 원문을 그대로 보존합니다: {}", content);
            return AnalysisResult.builder()
                    .category("UNKNOWN")
                    .confidence(0.0)
                    .verificationStatus("검증 실패")
                    .anomalies(List.of("LLM 응답을 JSON 으로 해석하지 못했습니다."))
                    .build();
        }
    }
}
