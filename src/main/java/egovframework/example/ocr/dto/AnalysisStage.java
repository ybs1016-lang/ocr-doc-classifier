package egovframework.example.ocr.dto;

/**
 * 문서 분석 파이프라인의 처리 단계.
 *
 * <p>비동기 작업의 진행 상황을 화면에 표시하기 위한 값으로, 응답 JSON 의
 * {@code stage} 필드에 이름 그대로({@code "OCR"} 등) 직렬화된다.
 * 단계 순서는 {@code QUEUED → ANALYZING → (EXTRACTING | OCR) → AI_ANALYSIS → DONE} 이다.</p>
 */
public enum AnalysisStage {

    /** 접수되었으나 아직 작업 스레드를 배정받지 못한 상태. */
    QUEUED("queued", "분석 대기 중"),

    /** PDF 를 열어 페이지 수와 텍스트 스트림 존재 여부를 조사하는 단계. */
    ANALYZING("analyzing", "PDF 구조 분석 중"),

    /** 텍스트 스트림이 있는 PDF 에서 PDFBox 로 텍스트를 추출하는 단계. */
    EXTRACTING("extracting", "텍스트 추출 중"),

    /** 스캔(이미지) PDF 를 페이지별로 렌더링해 OCR 을 수행하는 단계. */
    OCR("ocr", "OCR 처리 중"),

    /** 추출된 텍스트를 LLM 에 보내 분류/검증 결과를 받는 단계 (보통 가장 오래 걸린다). */
    AI_ANALYSIS("aiAnalysis", "AI 분류·검증 중"),

    /** 모든 처리가 정상 종료된 상태. */
    DONE("done", "분석 완료");

    /** 단계별 소요 시간 맵({@code timingsMs}) 에서 사용하는 키. */
    private final String key;

    /** 사용자에게 보여줄 기본 상태 문구. */
    private final String label;

    AnalysisStage(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }
}
