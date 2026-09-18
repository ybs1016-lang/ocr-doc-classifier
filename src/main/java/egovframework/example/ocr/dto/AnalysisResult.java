package egovframework.example.ocr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM(Ollama)이 반환하는 문서 분류/검증 결과.
 *
 * <p>설계서 5장의 응답 포맷을 그대로 따른다.</p>
 * <pre>
 * {
 *   "category": "계약서",
 *   "confidence": 0.95,
 *   "verification_status": "정상",
 *   "anomalies": ["금액 자리수 누락 감지됨"]
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    /** 문서 분류 결과 (예: 계약서, 발주서, 세금계산서 등). LLM이 텍스트 내용을 보고 판단한 문서 종류. */
    @JsonProperty("category")
    private String category;

    /** 분류 신뢰도 (0.0 ~ 1.0). 1에 가까울수록 카테고리 판단에 대한 모델의 확신이 높음을 의미한다. */
    @JsonProperty("confidence")
    private Double confidence;

    /**
     * 텍스트/숫자 정확도 검증 상태.
     * 예: {@code "정상"} (이상 없음) / {@code "이상"} (금액·날짜 등 누락·오인식 의심).
     */
    @JsonProperty("verification_status")
    private String verificationStatus;

    /**
     * 검증 과정에서 LLM이 발견한 구체적인 이상 징후 설명 목록.
     * (예: "금액 자리수 누락 감지됨"). {@link #verificationStatus} 가 정상이면 보통 빈 리스트.
     */
    @JsonProperty("anomalies")
    private List<String> anomalies;
}
