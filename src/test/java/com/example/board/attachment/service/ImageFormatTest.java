package com.example.board.attachment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 형식 판정 — sp-docs/plan/phase2-board.md B-23 검증표의 「네 형식의 머리」·「SVG / 텍스트 / 머리보다
 * 짧은 파일」 줄의 판정 부분이다. 머리와 {@code Content-Type} 의 값은 sp-docs/requirements/board.md
 * §11 의 「형식 판정의 매직 바이트」 표에서 옮겨 적는다 — 운영 코드의 상수를 참조하지 않는다.
 *
 * <p>판정기는 {@link AttachmentService} 가 읽은 앞부분({@link ImageFormat#HEAD_LENGTH} 바이트 또는 그보다
 * 짧은 파일 전체)을 받는다. 그래서 여기서는 머리 뒤에 이미지가 아닌 바이트를 붙여도 판정이 같다는 것
 * (§11 의 「매직 바이트는 앞부분만 본다」)과 머리에 못 미치는 입력이 판정되지 않는다는 것을 본다.
 */
class ImageFormatTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("네_형식의_머리")
	@DisplayName("네 형식의 머리는 그 형식으로 판정되고 contentType 이 §11 의 값이다")
	void 네_형식의_머리를_판정한다(String name, byte[] head, String expectedContentType) {
		Optional<ImageFormat> detected = ImageFormat.detect(head);

		assertThat(detected).isPresent();
		assertThat(detected.get().contentType()).isEqualTo(expectedContentType);
	}

	static Stream<Arguments> 네_형식의_머리() {
		return Stream.of(
				Arguments.of("JPEG", bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01),
						"image/jpeg"),
				Arguments.of("JPEG 머리만(3바이트)", bytes(0xFF, 0xD8, 0xFF), "image/jpeg"),
				Arguments.of("PNG", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D),
						"image/png"),
				Arguments.of("GIF87a", concat(ascii("GIF87a"), bytes(0x01, 0x00, 0x01, 0x00, 0x00, 0x00)),
						"image/gif"),
				Arguments.of("GIF89a", concat(ascii("GIF89a"), bytes(0x01, 0x00, 0x01, 0x00, 0x00, 0x00)),
						"image/gif"),
				Arguments.of("WebP", concat(ascii("RIFF"), bytes(0x24, 0x00, 0x00, 0x00), ascii("WEBP")),
						"image/webp"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("판정되지_않는_앞부분")
	@DisplayName("SVG·텍스트·머리보다 짧은 파일·머리가 다른 파일은 판정되지 않는다")
	void 네_형식이_아니면_판정되지_않는다(String name, byte[] head) {
		assertThat(ImageFormat.detect(head)).isEmpty();
	}

	static Stream<Arguments> 판정되지_않는_앞부분() {
		return Stream.of(
				Arguments.of("SVG", ascii("<svg xmlns=\"")),
				Arguments.of("XML 선언으로 시작하는 SVG", ascii("<?xml versio")),
				Arguments.of("텍스트", "안녕하세요 첨부".getBytes(StandardCharsets.UTF_8)),
				Arguments.of("빈 앞부분", new byte[0]),
				Arguments.of("JPEG 머리보다 짧다(2바이트)", bytes(0xFF, 0xD8)),
				Arguments.of("PNG 머리보다 짧다(7바이트)", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A)),
				Arguments.of("GIF 머리보다 짧다(5바이트)", ascii("GIF89")),
				Arguments.of("WebP 표지에 못 미친다(11바이트)",
						concat(ascii("RIFF"), bytes(0x24, 0x00, 0x00, 0x00), ascii("WEB"))),
				Arguments.of("RIFF 이지만 WEBP 가 아니다(WAVE)",
						concat(ascii("RIFF"), bytes(0x24, 0x00, 0x00, 0x00), ascii("WAVE"))),
				Arguments.of("WEBP 표지만 있고 RIFF 가 없다",
						concat(ascii("RIFX"), bytes(0x24, 0x00, 0x00, 0x00), ascii("WEBP"))),
				Arguments.of("GIF 의 다른 판(GIF88a)", ascii("GIF88a")),
				Arguments.of("PNG 머리의 한 바이트가 다르다",
						bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0B)));
	}

	private static byte[] bytes(int... values) {
		byte[] result = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = (byte) values[i];
		}
		return result;
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		byte[] result = new byte[length];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, result, offset, part.length);
			offset += part.length;
		}
		return result;
	}

}
