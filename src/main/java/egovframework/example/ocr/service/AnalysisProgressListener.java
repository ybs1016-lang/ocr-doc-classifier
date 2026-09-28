package egovframework.example.ocr.service;

import egovframework.example.ocr.dto.AnalysisStage;

/**
 * 문서 분석 파이프라인({@link DocumentProcessorService})의 진행 상황을 전달받는 리스너.
 *
 * <p>동기 API 처럼 진행 상황이 필요 없는 호출자는 {@link #NOOP} 을 사용한다.
 * 모든 메서드는 분석을 수행하는 스레드에서 호출되며, 구현체는 빠르게 반환해야 한다.
 * (호출이 늦어지면 그만큼 분석이 지연된다.)</p>
 */
public interface AnalysisProgressListener {

    /** 아무 동작도 하지 않는 리스너. 진행 상황을 추적하지 않는 호출에 사용한다. */
    AnalysisProgressListener NOOP = new AnalysisProgressListener() { };

    /** 새 처리 단계가 시작될 때 호출된다. */
    default void stageStarted(AnalysisStage stage) {
    }

    /**
     * PDF 분석이 끝나 텍스트 추출 방식이 정해졌을 때 호출된다.
     *
     * @param method     {@code "PDFBOX"} 또는 {@code "OCR"}
     * @param totalPages PDF 전체 페이지 수
     */
    default void extractionMethodDetermined(String method, int totalPages) {
    }

    /**
     * OCR 로 한 페이지 처리를 마칠 때마다 호출된다.
     *
     * @param donePages  처리를 마친 페이지 수 (1부터 시작)
     * @param totalPages 전체 페이지 수
     */
    default void ocrPageCompleted(int donePages, int totalPages) {
    }
}
