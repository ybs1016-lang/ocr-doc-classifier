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

    /** PDFBox 기반 텍스트 스트림 추출 및 페이지 이미지 렌더링 담당. */
    private final PdfExtractService pdfExtractService;

    /** 이미지에서 텍스트를 인식하는 OCR 엔진 추상화 (기본 구현체: Tess4J). */
    private final OcrService ocrService;

    /** {@link egovframework.example.ocr.config.SpringAiConfig} 에서 시스템 프롬프트가 고정된 Ollama 클라이언트. */
    private final ChatClient documentChatClient;

    /** LLM이 반환한 JSON 문자열을 {@link AnalysisResult} 로 역직렬화하기 위한 매퍼. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * PDF 파일 하나를 받아 추출 → (필요 시) OCR 보완 → LLM 분류/검증까지의
     * 전체 파이프라인을 실행하고, 최종 API 응답 DTO를 만들어 반환한다.
     *
     * <p>이 메서드는 실패해도 예외를 밖으로 던지지 않고 {@code success=false}
     * 인 {@link AnalysisResponse} 로 변환해 반환한다. 즉, 컨트롤러 입장에서는
     * 항상 정상적으로 리턴값을 받아 HTTP 상태 코드만 분기하면 된다.</p>
     *
     * @param file             컨트롤러가 임시 디스크에 저장해 둔 PDF 파일
     * @param originalFileName 사용자가 업로드한 원본 파일명 (응답에 그대로 포함됨)
     * @return 분류/검증 결과 또는 실패 사유를 담은 응답
     */
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
                // 텍스트도, OCR 결과도 비어있다면 더 이상 분석을 진행할 수 없는 상태.
                throw new DocumentProcessingException("문서에서 텍스트를 추출하지 못했습니다: " + originalFileName);
            }

            // 추출된 텍스트를 LLM에 넘겨 문서 종류 분류 + 내용 검증(이상 탐지) 수행.
            AnalysisResult result = classifyAndVerify(rawText);

            return AnalysisResponse.builder()
                    .success(true)
                    .fileName(originalFileName)
                    .extractionMethod(extractionMethod)
                    .result(result)
                    .build();

        } catch (DocumentProcessingException e) {
            // 암호화된 PDF, 텍스트 추출 불가 등 "예상 가능한" 업무 오류.
            // 원인 메시지를 그대로 클라이언트에 노출해도 안전한 경우이므로 그대로 전달.
            log.warn("문서 처리 실패: {}", e.getMessage());
            return AnalysisResponse.builder()
                    .success(false)
                    .fileName(originalFileName)
                    .errorMessage(e.getMessage())
                    .build();
        } catch (Exception e) {
            // PDFBox, Tess4J, Ollama 호출 등에서 예상하지 못한 예외가 튀어나온 경우의 안전망.
            // 상세 원인은 서버 로그에만 남기고, 클라이언트에는 일반화된 메시지만 전달한다.
            log.error("문서 처리 중 알 수 없는 오류", e);
            return AnalysisResponse.builder()
                    .success(false)
                    .fileName(originalFileName)
                    .errorMessage("문서 처리 중 알 수 없는 오류가 발생했습니다.")
                    .build();
        }
    }

    /**
     * 이미지 기반(스캔) PDF에 대해, 페이지를 한 장씩 이미지로 렌더링한 뒤
     * OCR 엔진({@link OcrService})으로 텍스트를 인식하고 전체를 이어 붙인다.
     *
     * <p>페이지 순서를 유지하기 위해 인덱스 순서대로 처리하며, 각 페이지 텍스트
     * 사이에는 개행 문자를 넣어 구분한다.</p>
     *
     * @param file 텍스트 스트림이 없다고 판단된 PDF 파일
     * @return 모든 페이지의 OCR 결과를 합친 전체 텍스트 (앞뒤 공백 제거됨)
     */
    private String extractTextViaOcr(File file) {
        // 페이지별로 텍스트 추출 가능 여부를 먼저 조사해, 총 페이지 수만큼 순회한다.
        List<Boolean> pageAvailability = pdfExtractService.analyzeTextAvailability(file);
        StringBuilder combined = new StringBuilder();

        for (int i = 0; i < pageAvailability.size(); i++) {
            // 페이지를 비트맵 이미지(PNG 등)로 렌더링한 뒤 OCR 엔진에 전달.
            byte[] pageImage = pdfExtractService.renderPageAsImage(file, i);
            String pageText = ocrService.extractTextFromImage(pageImage);
            combined.append(pageText).append("\n");
        }
        return combined.toString().trim();
    }

    /**
     * 추출된 텍스트를 바탕으로 LLM(Ollama)에게 문서 분류 및 이상 검증을 요청한다.
     *
     * <p>설계서 4.2절의 {@code buildPrompt()} 를 그대로 구현: 사용자(user) 메시지에
     * 지시문 + 추출된 텍스트를 담아 전송하고, 시스템 프롬프트는
     * {@link egovframework.example.ocr.config.SpringAiConfig} 에서 이미 고정되어 있다.</p>
     *
     * @param extractedText PDFBox 또는 OCR로 얻은 원본 텍스트
     * @return LLM 응답을 파싱한 분류/검증 결과
     */
    private AnalysisResult classifyAndVerify(String extractedText) {
        String prompt = buildPrompt(extractedText);

        // ChatClient.prompt().user(...).call().content() : 동기 호출로 LLM 응답 텍스트만 받아온다.
        String content = documentChatClient
                .prompt()
                .user(prompt)
                .call()
                .content();

        return parseResult(content);
    }

    /**
     * LLM에게 보낼 사용자 프롬프트를 조립한다.
     *
     * <p>모델이 반드시 지정된 JSON 스키마({@code category}, {@code confidence},
     * {@code verification_status}, {@code anomalies})로만 응답하도록 형식을
     * 명시해, {@link #parseResult(String)} 에서 안정적으로 역직렬화할 수 있게 한다.</p>
     *
     * @param text 문서에서 추출된 원본 텍스트
     * @return LLM에 전달할 완성된 프롬프트 문자열
     */
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

    /**
     * LLM 응답 문자열을 {@link AnalysisResult} 로 파싱한다.
     *
     * <p>일부 모델은 JSON 응답을 ```json ... ``` 코드펜스로 감싸서 반환하는
     * 경우가 있어, 파싱 전에 해당 마크다운 구문을 제거한다.</p>
     *
     * <p>파싱에 실패하더라도(모델이 스키마를 어기고 응답한 경우 등) 예외를
     * 던지지 않고, "검증 실패" 상태를 나타내는 대체 결과를 반환해 API 전체가
     * 500 에러로 끊기지 않도록 한다.</p>
     *
     * @param content LLM이 반환한 원본 응답 텍스트
     * @return 파싱된 분류/검증 결과, 파싱 실패 시 UNKNOWN/검증 실패 결과
     */
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
