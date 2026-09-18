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

    /** Tess4J OCR 엔진 인스턴스. 빈 생성 시 1회 초기화되어 재사용된다(요청마다 새로 만들지 않음). */
    private final Tesseract tesseract;

    /**
     * tessdata 경로와 인식 언어를 {@code application.yml} 설정값으로 주입받아
     * Tesseract 엔진을 초기화한다.
     *
     * @param dataPath tessdata(학습된 언어 모델) 디렉터리 경로.
     *                 예: {@code kor.traineddata}, {@code eng.traineddata} 가 위치한 폴더.
     *                 설정이 없으면 {@code /usr/share/tesseract-ocr/5/tessdata} 사용.
     * @param language 인식할 언어 조합. {@code "kor+eng"} 처럼 {@code +} 로 여러 언어를 동시에 지정 가능.
     *                 설정이 없으면 기본값 {@code kor+eng} 사용.
     */
    public Tess4jOcrService(
            @Value("${ocr.tesseract.data-path:/usr/share/tesseract-ocr/5/tessdata}") String dataPath,
            @Value("${ocr.tesseract.language:kor+eng}") String language) {
        this.tesseract = new Tesseract();
        this.tesseract.setDatapath(dataPath);
        this.tesseract.setLanguage(language);
    }

    /**
     * 이미지 바이트 배열을 {@link BufferedImage} 로 디코딩한 뒤 Tesseract 엔진으로 OCR을 수행한다.
     *
     * <p>{@link egovframework.example.ocr.service.PdfExtractService#renderPageAsImage}
     * 로 렌더링된 PNG 페이지 이미지가 주 입력으로 사용된다.</p>
     *
     * @param imageBytes PNG/JPEG 등 이미지 바이너리
     * @return 인식된 텍스트
     * @throws DocumentProcessingException 이미지 디코딩 실패 또는 OCR 엔진 처리 중 오류가 발생한 경우
     */
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
