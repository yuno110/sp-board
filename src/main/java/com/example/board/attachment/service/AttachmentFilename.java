package com.example.board.attachment.service;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;

/**
 * 원본 파일명의 정리와 검증. 정본: sp-docs/requirements/board.md §11 의 「파일명」 줄 — 경로
 * 구분자({@code /}·{@code \}) 앞부분과 제어 문자를 버린 뒤 1~255자여야 하고, 아니면 400
 * {@code C001} 이다.
 *
 * <p><b>정리한 값은 메타데이터로만 쓴다</b> — 저장 이름·경로에 들어가지 않는다 (§11,
 * sp-docs/domain-model.md §4.6). 경로 탐색을 막는 것은 이 정리가 아니라 그 구조다 — 저장 이름이
 * 서버가 만든 UUID 라 원본 파일명이 저장 경로에 닿을 길이 없다 (§4.6).
 *
 * <p><b>구분자는 두 문자로 직접 찾는다.</b> {@code Path} 로 해석하지 않는다 — 구분자와 허용 문자가
 * 운영체제마다 달라 같은 이름이 서버가 도는 OS 에 따라 다르게 정리되거나 예외가 된다(Windows 에서
 * {@code C:b.png} 는 드라이브 상대 경로로 해석되고 {@code ?} 가 든 이름은 예외다 — 2026-11-18 B-23
 * 실측). 그래서 {@code C:b.png} 처럼 두 구분자가 없는 이름은 그대로 남는다.
 *
 * <p><b>제어 문자</b>는 {@link Character#isISOControl(char)} 가 참인 문자(U+0000~U+001F,
 * U+007F~U+009F)다. <b>길이</b>는 Java 문자열의 길이(UTF-16 단위)로 센다 — 255 이하이면 코드
 * 포인트도 255 이하라 {@code original_filename} 의 폭(255자, §4.6)을 넘지 않는다.
 * <b>정본(§11 의 「1~255자」)은 「자」의 단위를 정하지 않는다 — 이 구현은 {@link String#length()}
 * (UTF-16)다.</b> 그래서 보조 평면 문자(예: 이모지)가 많은 이름은 코드 포인트로 255 이하여도 거부될
 * 수 있다. 다른 길이 규칙의 {@code @Size} 도 같은 단위로 센다.
 */
final class AttachmentFilename {

	/** 정리한 뒤의 최대 길이 (§11). {@code original_filename} 의 폭과 같다 (§4.6). */
	static final int MAX_LENGTH = 255;

	private AttachmentFilename() {
	}

	/**
	 * 마지막 구분자 뒤만 남기고 제어 문자를 버린다. 결과가 1~{@value #MAX_LENGTH}자가 아니면 400
	 * {@code C001} 이다.
	 *
	 * @param submitted 요청이 보낸 파일명. 없으면 빈 이름으로 본다
	 */
	static String clean(String submitted) {
		String name = submitted == null ? "" : submitted;
		int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		String base = name.substring(lastSeparator + 1);

		StringBuilder cleaned = new StringBuilder(base.length());
		for (int i = 0; i < base.length(); i++) {
			char c = base.charAt(i);
			if (!Character.isISOControl(c)) {
				cleaned.append(c);
			}
		}

		if (cleaned.isEmpty() || cleaned.length() > MAX_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
		}
		return cleaned.toString();
	}

}
