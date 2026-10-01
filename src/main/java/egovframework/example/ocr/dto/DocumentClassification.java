package egovframework.example.ocr.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 문서유형 판별 결과.
 *
 * @param documentType  문서유형 코드 (CONTRACT, PURCHASE_ORDER, TAX_INVOICE, QUOTATION, INVOICE, RECEIPT, OTHER ...)
 * @param status        {@code CLASSIFIED} | {@code UNCLASSIFIED}
 * @param policyVersion 판별에 적용한 기준 버전
 * @param evidenceIds   판별 근거 식별자 목록
 * @param reasonCodes   검수가 필요한 이유 코드 목록 (이상 없으면 빈 목록)
 */
@JsonPropertyOrder({"documentType", "status", "policyVersion", "evidenceIds", "reasonCodes"})
public record DocumentClassification(
        String documentType,
        String status,
        String policyVersion,
        List<String> evidenceIds,
        List<String> reasonCodes) {
}
