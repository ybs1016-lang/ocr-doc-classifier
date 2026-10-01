package egovframework.example.ocr.service.report;

import egovframework.example.ocr.dto.AnalysisReport;
import egovframework.example.ocr.dto.AnalysisResult;
import egovframework.example.ocr.dto.ExtractedField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StructuredReportBuilder} 단위 테스트. 외부 서버 없이 실행된다. */
class StructuredReportBuilderTest {

    private static final String WAYBILL = String.join("\n",
            "운 송 장",
            "운송장번호 6041-2345-7788 | 접수일 2026-09-18",
            "송하인",
            "상호 (주)새벽정밀 담당자 홍길동",
            "주소 경기도 행복시 산업로 123 연락처 031-000-1111",
            "수하인",
            "상호 (주)미래테크 입고팀 담당자 이수진",
            "물품 정보",
            "규격(가로×세로× 높이) 40×30×25cm",
            "파손 주의 예",
            "인수자 성명 인수 일시",
            "※ 인수 시 물품 상태를 확인하시고 파손 시 즉시 연락하시기 바랍니다.");

    private static AnalysisResult ai(String category, String verification) {
        return AnalysisResult.builder().category(category).confidence(0.9)
                .verificationStatus(verification).anomalies(List.of()).build();
    }

    private static ExtractedField find(AnalysisReport r, String name, String section) {
        return r.fields().stream()
                .filter(f -> f.name().equals(name) && java.util.Objects.equals(f.section(), section))
                .findFirst().orElseThrow(() -> new AssertionError("필드 없음: " + name + "/" + section));
    }

    @Test
    void 첫_줄은_제목이고_글자_사이_공백은_합쳐진다() {
        AnalysisReport r = StructuredReportBuilder.build(WAYBILL, 1, "PDFBOX", ai("운송장", "정상"));
        ExtractedField title = r.fields().get(0);
        assertEquals("documentTitle", title.name());
        assertEquals("운송장", title.extractedValue());
    }

    @Test
    void 한_줄에_라벨이_여럿이면_각각_값을_나눠_담는다() {
        AnalysisReport r = StructuredReportBuilder.build(WAYBILL, 1, "PDFBOX", ai("운송장", "정상"));
        assertEquals("6041-2345-7788", find(r, "dynamic.운송장번호", null).extractedValue());
        assertEquals("2026-09-18", find(r, "dynamic.접수일", null).extractedValue());
        assertEquals("(주)새벽정밀", find(r, "dynamic.상호", "송하인").extractedValue());
        assertEquals("홍길동", find(r, "dynamic.담당자", "송하인").extractedValue());
        assertEquals("(주)미래테크 입고팀", find(r, "dynamic.상호", "수하인").extractedValue());
    }

    @Test
    void 구역_제목이_같은_라벨을_구분해_준다() {
        AnalysisReport r = StructuredReportBuilder.build(WAYBILL, 1, "PDFBOX", ai("운송장", "정상"));
        ExtractedField a = find(r, "dynamic.주소", "송하인");
        assertEquals("경기도 행복시 산업로 123", a.extractedValue());
        assertEquals("경기도행복시산업로123", a.normalizedValue());
    }

    @Test
    void 여러_토큰으로_나뉜_라벨도_찾는다() {
        AnalysisReport r = StructuredReportBuilder.build(WAYBILL, 1, "PDFBOX", ai("운송장", "정상"));
        assertEquals("40×30×25cm", find(r, "dynamic.규격(가로×세로×높이)", "물품 정보").extractedValue());
        assertEquals("예", find(r, "dynamic.파손 주의", "물품 정보").extractedValue());
    }

    @Test
    void 값이_비어_있으면_EMPTY이고_검수_필요가_된다() {
        AnalysisReport r = StructuredReportBuilder.build(WAYBILL, 1, "PDFBOX", ai("운송장", "정상"));
        ExtractedField empty = find(r, "dynamic.인수자 성명", "물품 정보");
        assertEquals("EMPTY", empty.valueStatus());
        assertEquals("REVIEW_REQUIRED", empty.verification());
        assertEquals("PRESENT", find(r, "dynamic.담당자", "송하인").valueStatus());
    }

    @Test
    void 라벨로_시작하지_않는_안내문은_필드가_아니다() {
        AnalysisReport r = StructuredReportBuilder.build(WAYBILL, 1, "PDFBOX", ai("운송장", "정상"));
        String names = r.fields().stream().map(ExtractedField::name).collect(Collectors.joining(","));
        assertFalse(names.contains("인수 시"), names);
    }

    @Test
    void 콜론_형식_라벨도_추출한다() {
        AnalysisReport r = StructuredReportBuilder.build("견적서\nProject Code: AB-12 rev 3", 1, "PDFBOX", null);
        ExtractedField f = r.fields().get(1);
        assertEquals("dynamic.Project Code", f.name());
        assertEquals("AB-12 rev 3", f.extractedValue());
    }

    @Test
    void 토큰_수와_인식_상태와_문서유형을_채운다() {
        AnalysisReport pdf = StructuredReportBuilder.build("a b  c\nd", 2, "PDFBOX", ai("발주서", "정상"));
        assertEquals(4, pdf.tokenCount());
        assertEquals(2, pdf.pageCount());
        assertEquals("TEXT_LAYER_OK", pdf.recognitionStatus());
        assertEquals("PURCHASE_ORDER", pdf.classification().documentType());
        assertEquals("CLASSIFIED", pdf.classification().status());
        assertEquals(StructuredReportBuilder.POLICY_VERSION, pdf.classification().policyVersion());

        AnalysisReport scan = StructuredReportBuilder.build("x", 1, "OCR", ai("운송장", "정상"));
        assertEquals("OCR_USED", scan.recognitionStatus());
        assertEquals("OTHER", scan.classification().documentType());
    }

    @Test
    void AI_분류가_없거나_이상이면_사유_코드가_붙는다() {
        AnalysisReport unknown = StructuredReportBuilder.build("x", 1, "PDFBOX", ai("UNKNOWN", "검증 실패"));
        assertEquals("UNCLASSIFIED", unknown.classification().status());
        assertTrue(unknown.classification().reasonCodes().contains("AI_CLASSIFICATION_UNAVAILABLE"));

        AnalysisReport anomaly = StructuredReportBuilder.build("x", 1, "PDFBOX", ai("계약서", "이상"));
        assertEquals("CONTRACT", anomaly.classification().documentType());
        assertTrue(anomaly.classification().reasonCodes().contains("AI_VERIFICATION_ANOMALY"));
    }
}
