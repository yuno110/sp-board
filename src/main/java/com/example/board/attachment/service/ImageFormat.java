package com.example.board.attachment.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * 받는 이미지 형식과 그 매직 바이트. 정본: sp-docs/requirements/board.md §11 의 「형식 판정의 매직
 * 바이트」 표.
 *
 * <p><b>파일 앞부분만 본다.</b> 올린 요청의 {@code Content-Type}·확장자를 쓰지 않는다 (§11 의 「형식
 * 판정」 줄). 판정 라이브러리를 들이지 않는 것이 결정이다 (sp-docs/tech-stack.md §4.5) — 표의 머리를
 * 바이트로 비교한다.
 *
 * <p>SVG 는 이 열거에 없다 — §11 의 「형식」 줄이 받지 않는다고 정했다. 앞부분이 어느 머리와도 맞지
 * 않거나 머리보다 짧은 파일은 판정되지 않는다.
 */
enum ImageFormat {

	JPEG("image/jpeg"),
	PNG("image/png"),
	GIF("image/gif"),
	WEBP("image/webp");

	/** 판정에 읽는 앞부분의 길이 — WebP 의 8~11바이트까지다. */
	static final int HEAD_LENGTH = 12;

	private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

	private static final byte[] PNG_HEAD = {
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

	private static final byte[] GIF87A_HEAD = ascii("GIF87a");

	private static final byte[] GIF89A_HEAD = ascii("GIF89a");

	private static final byte[] RIFF = ascii("RIFF");

	/** WebP 의 8~11바이트. */
	private static final byte[] WEBP_MARK = ascii("WEBP");

	private static final int WEBP_MARK_OFFSET = 8;

	private final String contentType;

	ImageFormat(String contentType) {
		this.contentType = contentType;
	}

	/** 저장하고 응답에 싣는 {@code Content-Type} (§11 의 표 셋째 열). */
	String contentType() {
		return this.contentType;
	}

	/**
	 * 앞부분으로 형식을 판정한다. 어느 머리와도 맞지 않으면 비어 있다.
	 *
	 * @param head 파일의 앞부분. 파일이 {@link #HEAD_LENGTH} 보다 짧으면 파일 전체다
	 */
	static Optional<ImageFormat> detect(byte[] head) {
		if (startsWith(head, 0, JPEG_HEAD)) {
			return Optional.of(JPEG);
		}
		if (startsWith(head, 0, PNG_HEAD)) {
			return Optional.of(PNG);
		}
		if (startsWith(head, 0, GIF87A_HEAD) || startsWith(head, 0, GIF89A_HEAD)) {
			return Optional.of(GIF);
		}
		if (startsWith(head, 0, RIFF) && startsWith(head, WEBP_MARK_OFFSET, WEBP_MARK)) {
			return Optional.of(WEBP);
		}
		return Optional.empty();
	}

	/** {@code head} 의 {@code offset} 부터가 {@code mark} 와 같은가. 그 자리까지 닿지 않으면 거짓이다. */
	private static boolean startsWith(byte[] head, int offset, byte[] mark) {
		if (head.length < offset + mark.length) {
			return false;
		}
		return Arrays.equals(head, offset, offset + mark.length, mark, 0, mark.length);
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

}
