package egovframework.example.ocr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 문서에서 추출한 필드 하나.
 *
 * @param name            필드 이름 (예: {@code documentTitle}, {@code dynamic.상호})
 * @param section         필드가 속한 구역 제목 (예: 송하인). 알 수 없으면 null
 * @param extractedValue  문서에 적힌 그대로 읽은 값
 * @param normalizedValue 공백 등을 제거해 비교/검색하기 쉽게 정규화한 값
 * @param valueStatus     {@code PRESENT}(값 있음) | {@code EMPTY}(값 비어 있음)
 * @param verification    {@code NORMAL}(정상) | {@code REVIEW_REQUIRED}(검수 필요)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"name", "section", "extractedValue", "normalizedValue", "valueStatus", "verification"})
public record ExtractedField(
        String name,
        String section,
        String extractedValue,
        String normalizedValue,
        String valueStatus,
        String verification) {
}
