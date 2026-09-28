package egovframework.example.ocr.dto;

/**
 * 비동기 분석 작업의 전체 상태.
 *
 * <ul>
 *   <li>{@link #QUEUED}    : 접수됨, 실행 대기 중</li>
 *   <li>{@link #RUNNING}   : 작업 스레드에서 처리 중</li>
 *   <li>{@link #COMPLETED} : 분석 성공 ({@code result.success == true})</li>
 *   <li>{@link #FAILED}    : 분석 실패 ({@code result.errorMessage} 에 사유)</li>
 * </ul>
 */
public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED;

    /** 더 이상 상태가 바뀌지 않는 종료 상태인지 여부. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
