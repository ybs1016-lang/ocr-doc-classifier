# ocr-doc-classifier

eGovFrame Boot 실행환경(Spring Boot 기반) + Spring AI(Ollama) 를 이용한
OCR 문서 분류 및 검증 시스템입니다.

원본 설계서 「[기술 가이드] Spring AI 기반 OCR 문서 분류 및 검증 설계서」의
아키텍처(PDFBox 텍스트 추출 → 필요 시 OCR 보완 → Spring AI(Ollama, qwen3-vl)
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
ollama run qwen3-vl
```

## 2. 애플리케이션 설정

`src/main/resources/application.yml` 에서 다음 항목을 환경에 맞게 조정하세요.

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434   # Ollama 서버 주소
      chat:
        model: qwen3-vl               # 사용할 모델명

ocr:
  tesseract:
    data-path: /usr/share/tesseract-ocr/5/tessdata   # OS 별 tessdata 경로
    language: kor+eng
```

## 3. 빌드 및 실행

VS Code에서는 실행 및 디버그 목록의 `OCR 문서 분류기 (UTF-8 로그)`를 선택해 실행합니다. Java 실행 로그는 디버그 콘솔에 표시되며, 이 실행 방식은 터미널 코드 페이지 설정에 영향을 받지 않습니다. 기존 실행 중인 앱은 종료하고 다시 시작해야 새 설정이 적용됩니다.

Windows PowerShell에서 직접 실행할 때는 같은 터미널에서 먼저 `chcp 65001`을 실행해 코드 페이지를 UTF-8로 맞춥니다. 이미 실행 중인 앱은 종료한 뒤 다시 시작해야 적용됩니다.

```powershell
chcp 65001
mvn clean package
mvn spring-boot:run
```

그 외 환경에서는 다음 명령으로 실행합니다.

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


## 결과 화면 (대시보드)

분석을 시작하면 업로드 화면이 결과 대시보드로 바뀌고, 작업이 진행되는 동안 실시간으로 갱신됩니다.

| 영역 | 내용 | 데이터 출처 |
|---|---|---|
| 상단 바 | 파일명·페이지 수, 작업 ID/문서 ID(복사), 처리 상태 | `jobId`, `documentId`, `status` |
| 01 원문 근거 | 업로드한 PDF 미리보기(확대/축소, 새 탭 열기) | `GET /api/v1/documents/jobs/{id}/file` |
| 02 분석 결과 | 전체 처리 상태(9단계 막대), 페이지·추출 토큰·인식 상태·스키마 버전, 문서유형, 추출 필드(전체/검수 필요) | `report` |
| 03 원본 JSON | `report` 원문, 필드명 검색, 전체 복사, JSON 다운로드 | `report` |
| 04 전체 분석 로그 | 단계별 처리 로그(`[0001] +0.014초` 형식) | `logs` |

- 추출 필드는 `StructuredReportBuilder` 가 텍스트에서 규칙 기반으로 뽑습니다. 인식하는 필드 이름은 `LABELS` 사전과 `이름: 값` 형태이며, 새 서식은 사전에 라벨을 추가하면 됩니다.
- 값이 비어 있는 필드는 `EMPTY` + **검수 필요** 로 표시됩니다.
- 원문 미리보기를 위해 업로드한 PDF 를 작업과 같은 기간(`analysis.job.retention-seconds`, 기본 30분) 동안 **메모리에 보관**합니다.
- 작업 ID 는 `job-<UUID>`, 문서 ID 는 `doc-<UUID>` 형식입니다.
