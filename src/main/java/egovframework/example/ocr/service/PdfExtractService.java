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

    /**
     * 한 페이지가 "텍스트가 있는 페이지"로 간주되기 위한 최소 글자 수.
     * 이 값 미만이면(예: 페이지 번호 하나만 텍스트로 존재) 사실상 이미지 기반
     * 페이지로 판단하여 OCR 대상으로 분류한다.
     */
    private static final int MIN_TEXT_LENGTH_PER_PAGE = 5;

    /**
     * PDF 전체 텍스트를 추출한다.
     *
     * <p>{@link PDFTextStripper} 로 문서 전체를 한 번에 읽어들이며, 텍스트 스트림이
     * 없는(이미지 기반) 페이지는 결과에서 빈 문자열로 표시된다. 따라서 이 메서드는
     * {@link #hasExtractableText} 가 true 를 반환하는, 즉 최소한 일부 페이지에
     * 텍스트가 존재함이 확인된 문서에 대해서만 호출하는 것이 적절하다.</p>
     *
     * @param pdfFile 텍스트를 추출할 PDF 파일
     * @return 추출된 전체 텍스트 (앞뒤 공백 제거됨, 텍스트가 전혀 없으면 빈 문자열)
     * @throws DocumentProcessingException PDF 로드/파싱 중 I/O 오류(암호화된 PDF 포함)가 발생한 경우
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
     *
     * <p>{@link PDFTextStripper} 의 시작/끝 페이지를 한 페이지씩으로 좁혀가며
     * 반복 호출해, 페이지별로 추출된 텍스트 길이가 {@link #MIN_TEXT_LENGTH_PER_PAGE}
     * 이상이면 "텍스트가 있는 페이지({@code true})"로 판단한다. {@code false} 인
     * 페이지는 이미지 기반(스캔) 페이지로 간주되어 {@link #renderPageAsImage} +
     * OCR 처리 대상이 된다.</p>
     *
     * @param pdfFile 분석할 PDF 파일
     * @return 페이지 순서(0번 인덱스 = 1페이지)대로 텍스트 존재 여부를 담은 리스트
     * @throws DocumentProcessingException PDF 로드/파싱 중 오류가 발생한 경우
     */
    public List<Boolean> analyzeTextAvailability(File pdfFile) {
        List<Boolean> pageHasText = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                // PDFTextStripper는 1-based 페이지 번호를 사용한다.
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

    /**
     * 문서 내 적어도 한 페이지라도 추출 가능한 텍스트가 있는지 여부를 반환한다.
     *
     * <p>{@link DocumentProcessorService} 가 이 값을 기준으로 전체 처리 경로를
     * 분기한다: true 면 {@link #extractText} 로 바로 텍스트를 뽑고,
     * false 면(순수 이미지 기반 스캔 문서) OCR 파이프라인으로 넘어간다.</p>
     *
     * @param pdfFile 판단할 PDF 파일
     * @return 텍스트 스트림이 있는 페이지가 하나라도 있으면 true
     */
    public boolean hasExtractableText(File pdfFile) {
        return analyzeTextAvailability(pdfFile).stream().anyMatch(Boolean::booleanValue);
    }

    /**
     * 특정 페이지를 비트맵 이미지(PNG)로 렌더링한다.
     *
     * <p>용도:</p>
     * <ul>
     *   <li>텍스트 스트림이 없는 페이지를 Tess4J/PaddleOCR 등 OCR 엔진에 넘겨
     *       텍스트를 인식시킬 때</li>
     *   <li>Ollama 의 qwen 계열 멀티모달(Vision-Language) 모델에 이미지를
     *       직접 입력으로 전달할 때</li>
     * </ul>
     *
     * @param pdfFile   렌더링할 PDF 파일
     * @param pageIndex 렌더링할 페이지의 0-based 인덱스
     * @return PNG 형식으로 인코딩된 이미지 바이트 배열 (해상도 200 DPI, RGB)
     * @throws DocumentProcessingException 렌더링/인코딩 중 오류가 발생한 경우
     */
    public byte[] renderPageAsImage(File pdfFile, int pageIndex) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            // 200 DPI, RGB로 렌더링 - OCR 인식률과 처리 속도 사이의 실용적인 절충값.
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new DocumentProcessingException("PDF 페이지 이미지 렌더링 중 오류가 발생했습니다: " + pdfFile.getName(), e);
        }
    }
}
