# 아키텍처

원본 설계서(「[기술 가이드] Spring AI 기반 OCR 문서 분류 및 검증 설계서」) 3장의 다이어그램을 그대로 따른다.

```mermaid
graph TD
    A[PDF 파일] --> B{문서 분석기}
    B -->|텍스트 스트림 있음| C[PDFBox 텍스트 추출]
    B -->|이미지 기반 문서| D[OCR 처리 Tess4J/PaddleOCR]
    C & D --> E[Spring AI ChatRequest]
    E --> F[Ollama qwen2-vl 모델]
    F --> G[문서 분류 결과 JSON]
    F --> H[텍스트 정확도 검증 결과]
```

## 코드와의 매핑

| 다이어그램 노드 | 구현 클래스 |
|---|---|
| B. 문서 분석기 | `PdfExtractService#analyzeTextAvailability` |
| C. PDFBox 텍스트 추출 | `PdfExtractService#extractText` |
| D. OCR 처리 | `OcrService` / `Tess4jOcrService` |
| E~F. Spring AI ChatRequest → Ollama | `DocumentProcessorService#classifyAndVerify`, `SpringAiConfig#documentChatClient` |
| G/H. 분류·검증 결과 | `dto/AnalysisResult`, `dto/AnalysisResponse` |
