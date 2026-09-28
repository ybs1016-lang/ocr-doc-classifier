package egovframework.example.ocr.service;

import egovframework.example.ocr.dto.AnalysisResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import egovframework.example.ocr.dto.AnalysisStage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DocumentProcessorService} 의 진행 단계 알림을 검증한다.
 *
 * <p>PDFBox 는 <b>실제로</b> 사용해 텍스트 PDF / 스캔(이미지) PDF 를 만들어 분기를 검증하고,
 * 외부 서버인 LLM 호출({@code callLlm})과 OCR 엔진만 가짜로 대체한다.</p>
 */
class DocumentProcessorServiceProgressTest {

    private static final String LLM_JSON =
            "{\"category\":\"견적서\",\"confidence\":0.93,\"verification_status\":\"정상\",\"anomalies\":[]}";

    @TempDir
    File dir;

    // ── 테스트 도구 ──────────────────────────────────────────────

    /** 호출된 진행 이벤트를 순서대로 기록하는 리스너. */
    private static final class Recorder implements AnalysisProgressListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void stageStarted(AnalysisStage stage) {
            events.add("stage:" + stage);
        }

        @Override
        public void extractionMethodDetermined(String method, int totalPages) {
            events.add("method:" + method + "/" + totalPages);
        }

        @Override
        public void ocrPageCompleted(int done, int total) {
            events.add("ocr:" + done + "/" + total);
        }
    }

    /** 이미지가 넘어온 횟수를 세고 미리 정한 텍스트를 돌려주는 가짜 OCR. */
    private static final class FakeOcr implements OcrService {
        final AtomicInteger calls = new AtomicInteger();
        final String text;

        FakeOcr(String text) {
            this.text = text;
        }

        @Override
        public String extractTextFromImage(byte[] imageBytes) {
            // PNG 시그니처(0x89 'P' 'N' 'G')로 실제 렌더링된 이미지가 넘어왔는지 확인한다.
            assertTrue(imageBytes.length > 8 && imageBytes[0] == (byte) 0x89 && imageBytes[1] == 'P',
                    "PNG 로 렌더링된 페이지 이미지여야 한다");
            calls.incrementAndGet();
            return text.replace("{n}", String.valueOf(calls.get()));
        }
    }

    private static DocumentProcessorService processor(OcrService ocr, List<String> llmPrompts) {
        return new DocumentProcessorService(new PdfExtractService(), ocr, null) {
            @Override
            protected String callLlm(String prompt) {
                llmPrompts.add(prompt);
                return LLM_JSON;
            }
        };
    }

    /** 페이지마다 텍스트 스트림이 있는 PDF 를 만든다. */
    private File textPdf(int pages) throws IOException {
        File f = new File(dir, "text.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Quotation total 2,035,000 KRW page " + i);
                    cs.endText();
                }
            }
            doc.save(f);
        }
        return f;
    }

    /** 텍스트 스트림 없이 이미지만 든(스캔본과 같은 구조의) PDF 를 만든다. */
    private File imageOnlyPdf(int pages) throws IOException {
        File f = new File(dir, "scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                BufferedImage img = new BufferedImage(300, 120, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 300, 120);
                g.setColor(Color.BLACK);
                g.drawString("scanned page " + i, 20, 60);
                g.dispose();
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdImage, 50, 500, 300, 120);
                }
            }
            doc.save(f);
        }
        return f;
    }

    // ── 테스트 ───────────────────────────────────────────────────

    @Test
    void 텍스트_PDF는_PDFBox_경로로_단계가_알려진다() throws Exception {
        FakeOcr ocr = new FakeOcr("사용되면 안 됨");
        List<String> prompts = new ArrayList<>();
        Recorder rec = new Recorder();

        AnalysisResponse res = processor(ocr, prompts).analyzeFile(textPdf(2), "q.pdf", rec);

        assertTrue(res.isSuccess());
        assertEquals("PDFBOX", res.getExtractionMethod());
        assertEquals("견적서", res.getResult().getCategory());
        assertEquals(List.of("stage:ANALYZING", "method:PDFBOX/2", "stage:EXTRACTING", "stage:AI_ANALYSIS"),
                rec.events);
        assertEquals(0, ocr.calls.get(), "텍스트 PDF 에서는 OCR 을 호출하면 안 된다");
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("2,035,000 KRW"), "PDFBox 가 추출한 텍스트가 프롬프트에 들어가야 한다");
    }

    @Test
    void 스캔_PDF는_OCR_경로로_페이지마다_진행이_알려진다() throws Exception {
        FakeOcr ocr = new FakeOcr("OCR-PAGE-{n} 합계 1,850,000원");
        List<String> prompts = new ArrayList<>();
        Recorder rec = new Recorder();

        AnalysisResponse res = processor(ocr, prompts).analyzeFile(imageOnlyPdf(3), "s.pdf", rec);

        assertTrue(res.isSuccess());
        assertEquals("OCR", res.getExtractionMethod());
        assertEquals(List.of("stage:ANALYZING", "method:OCR/3", "stage:OCR",
                        "ocr:1/3", "ocr:2/3", "ocr:3/3", "stage:AI_ANALYSIS"),
                rec.events);
        assertEquals(3, ocr.calls.get());
        assertTrue(prompts.get(0).contains("OCR-PAGE-1") && prompts.get(0).contains("OCR-PAGE-3"),
                "모든 페이지의 OCR 결과가 프롬프트에 순서대로 들어가야 한다");
    }

    @Test
    void 진행_리스너_없는_기존_동기_호출도_동일하게_동작한다() throws Exception {
        List<String> prompts = new ArrayList<>();
        DocumentProcessorService svc = processor(new FakeOcr("x"), prompts);

        AnalysisResponse viaTwoArgs = svc.analyzeFile(textPdf(1), "q.pdf");
        AnalysisResponse viaNull = svc.analyzeFile(textPdf(1), "q.pdf", null);

        assertTrue(viaTwoArgs.isSuccess());
        assertTrue(viaNull.isSuccess());
        assertEquals(viaTwoArgs.getExtractionMethod(), viaNull.getExtractionMethod());
    }

    @Test
    void PDF가_아닌_파일은_분석_단계에서_실패하고_AI_단계에는_가지_않는다() throws Exception {
        File notPdf = new File(dir, "fake.pdf");
        Files.write(notPdf.toPath(), "이건 PDF 가 아닙니다".getBytes(StandardCharsets.UTF_8));
        List<String> prompts = new ArrayList<>();
        Recorder rec = new Recorder();

        AnalysisResponse res = processor(new FakeOcr("x"), prompts).analyzeFile(notPdf, "fake.pdf", rec);

        assertFalse(res.isSuccess());
        assertNotNull(res.getErrorMessage());
        assertEquals(List.of("stage:ANALYZING"), rec.events);
        assertTrue(prompts.isEmpty(), "LLM 을 호출하면 안 된다");
    }

    @Test
    void OCR_결과가_비어있으면_AI_단계로_넘어가지_않고_실패한다() throws Exception {
        List<String> prompts = new ArrayList<>();
        Recorder rec = new Recorder();

        AnalysisResponse res = processor(new FakeOcr("   "), prompts).analyzeFile(imageOnlyPdf(2), "blank.pdf", rec);

        assertFalse(res.isSuccess());
        assertTrue(res.getErrorMessage().contains("텍스트를 추출하지 못했습니다"));
        assertFalse(rec.events.contains("stage:AI_ANALYSIS"));
        assertTrue(rec.events.contains("ocr:2/2"), "OCR 은 끝까지 진행된 뒤에 빈 결과로 판정된다");
        assertTrue(prompts.isEmpty());
    }
}
