# ocr-doc-classifier

eGovFrame Boot 실행환경(Spring Boot 기반) + Spring AI(Ollama) 를 이용한
OCR 문서 분류 및 검증 시스템입니다.

원본 설계서 「[기술 가이드] Spring AI 기반 OCR 문서 분류 및 검증 설계서」의
아키텍처(PDFBox 텍스트 추출 → 필요 시 OCR 보완 → Spring AI(Ollama, qwen2-vl)
분류/검증)를 그대로 구현했습니다. 자세한 다이어그램은 [`docs/architecture.md`](docs/architecture.md)
참고.

## 요구 사항

- JDK 17 이상
- Maven 3.8 이상
- [Ollama](https://ollama.com) 설치 및 실행
- (선택) Tesseract OCR 엔진 및 tessdata (`kor.traineddata`, `eng.traineddata`)

## 1. Ollama 및 모델 준비

```bash
# Ollama 서버 실행 (별도 터미널)
ollama serve

# 멀티모달 모델 다운로드 (최초 1회, 설계서 6.2절 권장 모델)
ollama run qwen2-vl:7b
```

## 2. 애플리케이션 설정

`src/main/resources/application.yml` 에서 다음 항목을 환경에 맞게 조정하세요.

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434   # Ollama 서버 주소
      chat:
        model: qwen2-vl:7b               # 사용할 모델명

ocr:
  tesseract:
    data-path: /usr/share/tesseract-ocr/5/tessdata   # OS 별 tessdata 경로
    language: kor+eng
```

## 3. 빌드 및 실행

```bash
mvn clean package
mvn spring-boot:run
# 또는
java -jar target/ocr-doc-classifier.jar
```

## 4. API 사용법

```bash
curl -X POST http://localhost:8080/api/v1/documents/analyze \
  -F "file=@/path/to/document.pdf"
```

### 응답 예시

```json
{
  "success": true,
  "fileName": "contract.pdf",
  "extractionMethod": "PDFBOX",
  "result": {
    "category": "계약서",
    "confidence": 0.95,
    "verificationStatus": "정상",
    "anomalies": ["금액 자리수 누락 감지됨"]
  },
  "errorMessage": null
}
```

## 프로젝트 구조

```
src/main/java/egovframework/example/ocr/
├── OcrDocClassifierApplication.java   # 부트 진입점
├── config/
│   └── SpringAiConfig.java            # ChatClient 빈 설정
├── controller/
│   └── DocumentAnalysisController.java
├── service/
│   ├── DocumentProcessorService.java  # 전체 흐름 오케스트레이션
│   ├── PdfExtractService.java         # PDFBox 텍스트/이미지 추출
│   ├── OcrService.java                # OCR 추상화 인터페이스
│   └── impl/Tess4jOcrService.java     # Tess4J 구현체
├── dto/
│   ├── AnalysisResult.java            # LLM 응답 매핑
│   └── AnalysisResponse.java          # API 응답 래퍼
└── exception/
    ├── DocumentProcessingException.java
    └── GlobalExceptionHandler.java
```

## 참고 사항 (설계서 6장)

- **하이브리드 아키텍처**: PDFBox 만으로 처리 불가능한 스캔 문서는
  `OcrService` 구현체를 교체해 PaddleOCR 등 별도 서버 호출 방식으로도
  확장할 수 있습니다.
- **보안**: 암호화되었거나 디지털 서명이 있는 PDF 는 `PDDocument.load()` 단계에서
  예외가 발생할 수 있으며, 이 경우 `DocumentProcessingException` 으로 래핑되어
  API 응답의 `errorMessage` 로 반환됩니다.
