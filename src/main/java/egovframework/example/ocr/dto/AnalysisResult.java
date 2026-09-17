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

    /** 문서 분류 결과 (예: 계약서, 발주서 등) */
    @JsonProperty("category")
    private String category;

    /** 분류 신뢰도 (0.0 ~ 1.0) */
    @JsonProperty("confidence")
    private Double confidence;

    /** 텍스트/숫자 정확도 검증 상태 (예: 정상, 이상) */
    @JsonProperty("verification_status")
    private String verificationStatus;

    /** 검증 과정에서 발견된 이상 징후 목록 */
    @JsonProperty("anomalies")
    private List<String> anomalies;
}
