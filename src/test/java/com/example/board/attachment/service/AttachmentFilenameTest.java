package com.example.board.attachment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 파일명 정리 — sp-docs/plan/phase2-board.md B-23 검증표의 파일명 줄들의 정리·검증 부분이다. 규칙은
 * sp-docs/requirements/board.md §11 의 「파일명」 줄이다 — 경로 구분자({@code /}·{@code \}) 앞부분과
 * 제어 문자를 버린 뒤 1~255자, 아니면 400 {@code C001}.
 *
 * <p>길이의 경계(255)는 정본 값을 리터럴로 쓴다 — 운영 코드의 상수를 참조하지 않는다. 저장 경로와
 * 무관하다는 것(원본 파일명이 저장 이름에 닿지 않는다)은 서비스·저장소 테스트가 본다.
 */
class AttachmentFilenameTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("정리되는_이름")
	@DisplayName("경로 구분자 앞부분과 제어 문자를 버린 값이 된다")
	void 앞부분과_제어_문자를_버린다(String submitted, String expected) {
		assertThat(AttachmentFilename.clean(submitted)).isEqualTo(expected);
	}

	static Stream<Arguments> 정리되는_이름() {
		return Stream.of(
				Arguments.of("map.png", "map.png"),
				Arguments.of("../../x.png", "x.png"),
				Arguments.of("C:\\a\\b.png", "b.png"),
				Arguments.of("a/b\\c.png", "c.png"),
				Arguments.of("..\\..\\x.png", "x.png"),
				Arguments.of("ma\u0000p\u0007.png", "map.png"),
				Arguments.of("\tmap\r\n.png\u007f", "map.png"),
				Arguments.of("ma\u0085p.png", "map.png"),
				Arguments.of("dir/\u0001x.png", "x.png"),
				Arguments.of("지도 사진.png", "지도 사진.png"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("정리_뒤_빈_이름")
	@DisplayName("정리한 뒤 빈 이름이면 400 C001 이다")
	void 정리_뒤_비면_거부한다(String description, String submitted) {
		assertRejected(submitted);
	}

	static Stream<Arguments> 정리_뒤_빈_이름() {
		return Stream.of(
				Arguments.of("빈 문자열", ""),
				Arguments.of("이름이 없다(null)", null),
				Arguments.of("구분자로 끝난다", "../dir/"),
				Arguments.of("역슬래시로 끝난다", "C:\\a\\"),
				Arguments.of("제어 문자뿐이다", "\u0000\u001f\u007f"),
				Arguments.of("구분자 뒤가 제어 문자뿐이다", "x.png/\r\n"));
	}

	@Test
	@DisplayName("정리 뒤 1자 이름은 그 값이다")
	void 한_자는_받는다() {
		assertThat(AttachmentFilename.clean("../a")).isEqualTo("a");
	}

	@Test
	@DisplayName("정리 뒤 255자 이름은 그 값이다")
	void 이백오십오_자는_받는다() {
		String name = "a".repeat(251) + ".png";
		assertThat(name).hasSize(255);

		assertThat(AttachmentFilename.clean("dir/" + name)).isEqualTo(name);
	}

	@Test
	@DisplayName("제어 문자를 버린 뒤 255자면 받는다 — 길이는 정리한 뒤에 센다")
	void 버린_뒤의_길이를_센다() {
		String name = "a".repeat(251) + ".png";

		assertThat(AttachmentFilename.clean(name + "\u0000\u0000")).isEqualTo(name);
	}

	@Test
	@DisplayName("정리 뒤 256자 이름은 400 C001 이다")
	void 이백오십육_자는_거부한다() {
		String name = "a".repeat(252) + ".png";
		assertThat(name).hasSize(256);

		assertRejected("dir/" + name);
	}

	private static void assertRejected(String submitted) {
		BusinessException thrown = catchThrowableOfType(BusinessException.class,
				() -> AttachmentFilename.clean(submitted));

		assertThat(thrown).isNotNull();
		assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
		assertThat(thrown.getErrorCode().getCode()).isEqualTo("C001");
		assertThat(thrown.getErrorCode().getStatus().value()).isEqualTo(400);
	}

}
