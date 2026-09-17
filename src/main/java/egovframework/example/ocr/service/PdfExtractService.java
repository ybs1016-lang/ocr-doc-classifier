package egovframework.example.ocr.service;

import egovframework.example.ocr.exception.DocumentProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache PDFBox 를 이용한 문서 구조 파싱 및 텍스트 추출.
 *
 * <p>설계서 2.1절 한계: PDFBox 는 이미지로만 구성된(텍스트 스트림이 없는) 페이지는
 * 읽을 수 없다. 이 서비스는 페이지별 텍스트 스트림 존재 여부를 판단하여,
 * 텍스트가 없는 페이지는 {@link #renderPageAsImage(File, int)} 로 이미지를 렌더링해
 * OCR 서비스로 넘길 수 있도록 한다.</p>
 */
@Slf4j
@Service
public class PdfExtractService {

    private static final int MIN_TEXT_LENGTH_PER_PAGE = 5;

    /**
     * PDF 전체 텍스트를 추출한다. 텍스트 스트림이 없는(이미지 기반) 페이지는
     * 결과에서 빈 문자열로 표시되며, 호출 측에서 {@link #hasExtractableText}로
     * 페이지 단위 판단 후 OCR 로 보완해야 한다.
     */
    public String extractText(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text.trim();
        } catch (IOException e) {
            throw new DocumentProcessingException("PDF 텍스트 추출 중 오류가 발생했습니다: " + pdfFile.getName(), e);
        }
    }

    /**
     * 각 페이지에 텍스트 스트림이 존재하는지 페이지 단위로 판단한다.
     * false 인 페이지는 이미지 기반(스캔) 페이지로 간주하고 OCR 처리 대상이 된다.
     */
    public List<Boolean> analyzeTextAvailability(File pdfFile) {
        List<Boolean> pageHasText = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                boolean hasText = pageText != null && pageText.trim().length() >= MIN_TEXT_LENGTH_PER_PAGE;
                pageHasText.add(hasText);
            }
        } catch (IOException e) {
            throw new DocumentProcessingException("PDF 페이지 분석 중 오류가 발생했습니다: " + pdfFile.getName(), e);
        }
        return pageHasText;
    }

    public boolean hasExtractableText(File pdfFile) {
        return analyzeTextAvailability(pdfFile).stream().anyMatch(Boolean::booleanValue);
    }

    /**
     * 특정 페이지를 이미지(PNG)로 렌더링한다.
     * - Tess4J/PaddleOCR 로 넘겨 OCR 처리하거나
     * - Ollama 의 qwen2-vl 등 멀티모달 모델에 직접 전달할 때 사용한다.
     */
    public byte[] renderPageAsImage(File pdfFile, int pageIndex) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new DocumentProcessingException("PDF 페이지 이미지 렌더링 중 오류가 발생했습니다: " + pdfFile.getName(), e);
        }
    }
}
