package egovframework.example.ocr.service.impl;

import egovframework.example.ocr.exception.DocumentProcessingException;
import egovframework.example.ocr.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 설계서 2.1절 Tess4J 를 이용한 OCR 구현체.
 *
 * <p>tessdata 경로와 언어 설정은 application.yml 의
 * {@code ocr.tesseract.*} 속성으로 주입받는다.</p>
 */
@Slf4j
@Service
public class Tess4jOcrService implements OcrService {

    private final Tesseract tesseract;

    public Tess4jOcrService(
            @Value("${ocr.tesseract.data-path:/usr/share/tesseract-ocr/5/tessdata}") String dataPath,
            @Value("${ocr.tesseract.language:kor+eng}") String language) {
        this.tesseract = new Tesseract();
        this.tesseract.setDatapath(dataPath);
        this.tesseract.setLanguage(language);
    }

    @Override
    public String extractTextFromImage(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new DocumentProcessingException("OCR 대상 이미지를 읽을 수 없습니다.");
            }
            return tesseract.doOCR(image);
        } catch (IOException | TesseractException e) {
            throw new DocumentProcessingException("OCR 처리 중 오류가 발생했습니다.", e);
        }
    }
}
