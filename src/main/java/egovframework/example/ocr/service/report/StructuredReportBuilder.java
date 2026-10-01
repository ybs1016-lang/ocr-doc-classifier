package egovframework.example.ocr.service.report;

import egovframework.example.ocr.dto.AnalysisReport;
import egovframework.example.ocr.dto.AnalysisResult;
import egovframework.example.ocr.dto.DocumentClassification;
import egovframework.example.ocr.dto.ExtractedField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 추출된 텍스트에서 "구조 우선(structure-first)" 방식으로 필드를 뽑아 {@link AnalysisReport} 를 만든다.
 *
 * <p>LLM 이 없어도 동작하는 규칙 기반 추출이다. 한 줄에서 <b>필드 이름(라벨)</b>이 나오면 다음 라벨이
 * 나오기 전까지의 글자를 그 필드의 값으로 본다. (표에서 읽힌 "상호 (주)새벽정밀 담당자 홍길동" 같은 줄 처리용)</p>
 * <ul>
 *   <li>라벨은 {@link #LABELS} 사전과 {@code "이름: 값"} 형태에서 찾는다.</li>
 *   <li>줄이 라벨로 시작하지 않으면 필드 줄이 아니다. 짧은 줄은 구역 제목(송하인 등)으로 기억해 둔다.</li>
 *   <li>값이 비어 있으면 {@code EMPTY} + 검수 필요로 표시한다.</li>
 * </ul>
 * 사전에 없는 라벨은 {@link #LABELS} 에 추가하면 된다.
 */
public final class StructuredReportBuilder {

    public static final String SCHEMA_VERSION = "1.1";
    public static final String POLICY_VERSION = "generic-structure-first-v1";
    public static final String PROCESSING_MODE = "GENERIC";

    /** 라벨 사전. 공백 유무와 상관없이 비교하며, 필드 이름에는 여기 적힌 표기를 쓴다. */
    private static final List<String> LABELS = List.of(
            "상호", "상호명", "회사명", "주소", "담당자", "연락처", "전화번호", "전화", "팩스", "이메일",
            "대표자", "사업자등록번호", "등록번호",
            "품명", "품목", "규격(가로×세로×높이)", "규격", "수량", "단가", "금액", "공급가액", "세액", "합계",
            "중량", "포장", "파손 주의", "운임", "배송 요청", "인수자 성명", "인수 일시",
            "운송장번호", "접수일", "발행일", "작성일", "일자", "날짜", "계약일", "계약기간", "납기", "납품일",
            "성명", "이름", "비고");

    private static final int MAX_LABEL_TOKENS = 4;
    private static final int MAX_TITLE_LENGTH = 40;
    private static final int MAX_SECTION_LENGTH = 8;

    /** 공백을 뺀 라벨 → 표기 */
    private static final Map<String, String> LABEL_BY_KEY = new LinkedHashMap<>();
    static {
        for (String label : LABELS) {
            LABEL_BY_KEY.put(strip(label), label);
        }
    }

    /** "라벨: 값" 형태. 라벨은 글자로 시작해야 해서 "10:30" 같은 시각은 걸리지 않는다. */
    private static final Pattern COLON_LINE =
            Pattern.compile("^([\\p{L}][\\p{L}\\p{N} ()/×·_\\-]{0,19}?)\\s*[:：]\\s+(.*)$");

    private StructuredReportBuilder() {
    }

    /**
     * 분석 보고서를 만든다. 작업/문서 ID 와 처리 상태는 작업 스냅샷을 만들 때 채워진다.
     *
     * @param rawText          PDFBox 또는 OCR 로 얻은 전체 텍스트
     * @param pageCount        전체 페이지 수
     * @param extractionMethod {@code PDFBOX} | {@code OCR}
     * @param ai               LLM 분류/검증 결과 (null 가능)
     */
    public static AnalysisReport build(String rawText, int pageCount, String extractionMethod, AnalysisResult ai) {
        String text = rawText == null ? "" : rawText;
        List<ExtractedField> fields = extractFields(text);
        boolean ocr = "OCR".equalsIgnoreCase(extractionMethod);
        return new AnalysisReport(SCHEMA_VERSION, null, null, PROCESSING_MODE, "COMPLETED",
                classify(ai), fields, pageCount, countTokens(text),
                ocr ? "OCR_USED" : "TEXT_LAYER_OK", ai);
    }

    /** 공백 기준으로 나눈 단어(토큰) 수. */
    static int countTokens(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    // ── 문서유형 ─────────────────────────────────────────────────

    private static DocumentClassification classify(AnalysisResult ai) {
        String category = ai == null ? null : ai.getCategory();
        String type = mapDocumentType(category);

        List<String> reasons = new ArrayList<>();
        boolean unknown = category == null || category.isBlank() || "UNKNOWN".equalsIgnoreCase(category);
        if (unknown) {
            reasons.add("AI_CLASSIFICATION_UNAVAILABLE");
        }
        if (ai != null && ai.getVerificationStatus() != null && !"정상".equals(ai.getVerificationStatus())
                && !unknown) {
            reasons.add("AI_VERIFICATION_ANOMALY");
        }
        return new DocumentClassification(type, unknown ? "UNCLASSIFIED" : "CLASSIFIED",
                POLICY_VERSION, List.of(), List.copyOf(reasons));
    }

    /** AI 가 돌려준 한글 분류명을 문서유형 코드로 바꾼다. 해당 없으면 OTHER. */
    static String mapDocumentType(String category) {
        if (category == null) {
            return "OTHER";
        }
        String c = category.toLowerCase(Locale.ROOT);
        if (c.contains("세금계산서")) return "TAX_INVOICE";
        if (c.contains("계약")) return "CONTRACT";
        if (c.contains("발주")) return "PURCHASE_ORDER";
        if (c.contains("견적")) return "QUOTATION";
        if (c.contains("거래명세")) return "TRANSACTION_STATEMENT";
        if (c.contains("영수")) return "RECEIPT";
        if (c.contains("청구") || c.contains("invoice") || c.contains("인보이스")) return "INVOICE";
        return "OTHER";
    }

    // ── 필드 추출 ────────────────────────────────────────────────

    static List<ExtractedField> extractFields(String text) {
        List<ExtractedField> fields = new ArrayList<>();
        String section = null;
        boolean first = true;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) {
                continue;
            }
            List<Segment> segments = splitByLabels(tokens, line);

            // 첫 줄이 라벨 줄이 아니면 문서 제목으로 본다.
            if (first) {
                first = false;
                if (segments.isEmpty() && line.length() <= MAX_TITLE_LENGTH) {
                    boolean singles = tokens.stream().allMatch(t -> t.codePointCount(0, t.length()) == 1);
                    String title = String.join(singles ? "" : " ", tokens);
                    fields.add(field("documentTitle", null, title));
                    continue;
                }
            }

            if (segments.isEmpty()) {
                if (isSectionHeading(tokens)) {
                    section = String.join(" ", tokens);
                }
                continue;
            }
            for (Segment seg : segments) {
                fields.add(field("dynamic." + seg.label, section, String.join(" ", seg.valueTokens)));
            }
        }
        return fields;
    }

    private static ExtractedField field(String name, String section, String value) {
        String normalized = strip(value);
        boolean empty = normalized.isEmpty();
        return new ExtractedField(name, section, value, normalized,
                empty ? "EMPTY" : "PRESENT", empty ? "REVIEW_REQUIRED" : "NORMAL");
    }

    private static boolean isSectionHeading(List<String> tokens) {
        if (tokens.size() > 2) {
            return false;
        }
        String joined = String.join("", tokens);
        return joined.length() <= MAX_SECTION_LENGTH && joined.chars().noneMatch(Character::isDigit);
    }

    /** 공백으로 나누되, 표 구분선 같은 장식 문자 토큰은 버린다. */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        for (String t : line.split("\\s+")) {
            if (!t.isEmpty() && !t.matches("[|│┃·•]+")) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private record Segment(String label, List<String> valueTokens) {
    }

    /** 줄이 라벨로 시작하지 않으면 빈 목록을 돌려준다. */
    private static List<Segment> splitByLabels(List<String> tokens, String line) {
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int matched = matchLabelAt(tokens, i);
            if (matched > 0) {
                String label = LABEL_BY_KEY.get(strip(String.join("", tokens.subList(i, i + matched))));
                segments.add(new Segment(label, new ArrayList<>()));
                i += matched;
            } else if (segments.isEmpty()) {
                return colonSegments(line);
            } else {
                segments.get(segments.size() - 1).valueTokens().add(tokens.get(i));
                i++;
            }
        }
        return segments;
    }

    /** {@code i} 위치에서 시작하는 라벨이 몇 토큰짜리인지 (긴 것 우선). 없으면 0. */
    private static int matchLabelAt(List<String> tokens, int i) {
        int max = Math.min(MAX_LABEL_TOKENS, tokens.size() - i);
        for (int k = max; k >= 1; k--) {
            if (LABEL_BY_KEY.containsKey(strip(String.join("", tokens.subList(i, i + k))))) {
                return k;
            }
        }
        return 0;
    }

    private static List<Segment> colonSegments(String line) {
        Matcher m = COLON_LINE.matcher(line);
        if (!m.matches()) {
            return List.of();
        }
        List<String> value = new ArrayList<>();
        for (String t : m.group(2).trim().split("\\s+")) {
            if (!t.isEmpty()) {
                value.add(t);
            }
        }
        return List.of(new Segment(m.group(1).trim(), value));
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
