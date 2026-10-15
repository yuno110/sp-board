package com.example.board.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * sp-docs/api-contract.md §8.1 §8.3 의 표를 그대로 옮긴 것과 enum 전체를 맞춘다. 기대값은
 * 정본에서 읽은 리터럴이다 — {@code ErrorCode} 를 가져다 쓰면 자기 전제를 스스로 공급하게 되어
 * 어떤 값이 들어 있어도 통과한다.
 *
 * <p>집합 비교이므로 정본에 없는 코드가 하나라도 있으면 실패한다. member 전용 {@code M0xx} 나
 * auth 전용 {@code AU0xx} 를 끌어오는 실수가 여기서 잡힌다 (§8 — 접두어로 어느 서비스에서 난
 * 오류인지 식별한다).
 */
class ErrorCodeTest {

	@Test
	@DisplayName("ErrorCode 의 코드·HTTP 상태·메시지가 api-contract.md §8.1 §8.3 과 정확히 일치한다")
	void 정본과_일치한다() {
		Map<String, String> contract = new LinkedHashMap<>();
		// §8.1 공통 (세 서비스)
		contract.put("C001", "400 잘못된 입력값입니다.");
		contract.put("C002", "400 잘못된 타입의 값입니다.");
		contract.put("C003", "405 지원하지 않는 HTTP 메서드입니다.");
		contract.put("C004", "404 요청한 리소스를 찾을 수 없습니다.");
		contract.put("C005", "500 서버 내부 오류가 발생했습니다.");
		contract.put("C006", "415 지원하지 않는 미디어 타입입니다.");
		contract.put("C007", "406 응답할 수 있는 형식이 없습니다.");
		contract.put("A001", "401 인증이 필요합니다.");
		contract.put("A002", "401 유효하지 않은 토큰입니다.");
		contract.put("A003", "401 만료된 토큰입니다.");
		contract.put("A004", "403 권한이 없습니다.");
		// §8.3 board-service
		contract.put("P001", "404 게시글을 찾을 수 없습니다.");
		contract.put("P002", "403 게시글에 대한 권한이 없습니다.");
		contract.put("CM001", "404 댓글을 찾을 수 없습니다.");
		contract.put("CM002", "403 댓글에 대한 권한이 없습니다.");
		contract.put("S001", "503 일시적으로 서비스 연동에 실패했습니다.");
		contract.put("S002", "403 프로필 등록이 필요합니다.");

		Map<String, String> implemented = Stream.of(ErrorCode.values())
				.collect(Collectors.toMap(
						ErrorCode::getCode,
						errorCode -> errorCode.getStatus().value() + " " + errorCode.getMessage(),
						(a, b) -> {
							throw new IllegalStateException("중복된 에러 코드: " + a);
						},
						LinkedHashMap::new));

		assertThat(implemented).containsExactlyInAnyOrderEntriesOf(contract);
	}

	/**
	 * 코드 접두어로 어느 서비스에서 난 오류인지 식별한다 (§8). board 의 enum 에는 공통(§8.1)과
	 * board 전용(§8.3) 접두어만 있어야 한다 — 위의 집합 비교와 달리 이 단언은 정본의 표가
	 * 늘어나도 그대로 성립한다.
	 */
	@Test
	@DisplayName("member 전용 M0xx 와 auth 전용 AU0xx 가 없다")
	void 다른_서비스의_코드가_섞이지_않는다() {
		assertThat(Stream.of(ErrorCode.values()).map(ErrorCode::getCode))
				.as("M0xx 는 member(§8.2), AU0xx 는 auth(§8.4) 의 것이다")
				.allSatisfy(code -> assertThat(code)
						.doesNotMatch("^M\\d.*")
						.doesNotMatch("^AU\\d.*"));
	}

	/**
	 * {@code S002} 는 B-02 가 정의만 하고 쓰는 쪽은 B-05 이후다(글·댓글 생성 시 활성 프로필이
	 * 없을 때). 쓰는 곳이 없는 동안에도 값이 정본과 어긋나지 않게 못박는다.
	 */
	@Test
	@DisplayName("S002 가 403 이고 메시지가 \"프로필 등록이 필요합니다.\" 다")
	void S002_는_403_이다() {
		assertThat(ErrorCode.PROFILE_REQUIRED.getStatus().value()).isEqualTo(403);
		assertThat(ErrorCode.PROFILE_REQUIRED.getCode()).isEqualTo("S002");
		assertThat(ErrorCode.PROFILE_REQUIRED.getMessage()).isEqualTo("프로필 등록이 필요합니다.");
	}

	/** {@code S001} 은 내부 API 호출 실패에 쓴다 — 경보 대상이므로 503 이다 (§5.1). */
	@Test
	@DisplayName("S001 이 503 이고 메시지가 \"일시적으로 서비스 연동에 실패했습니다.\" 다")
	void S001_은_503_이다() {
		assertThat(ErrorCode.EXTERNAL_SERVICE_ERROR.getStatus().value()).isEqualTo(503);
		assertThat(ErrorCode.EXTERNAL_SERVICE_ERROR.getCode()).isEqualTo("S001");
		assertThat(ErrorCode.EXTERNAL_SERVICE_ERROR.getMessage())
				.isEqualTo("일시적으로 서비스 연동에 실패했습니다.");
	}

}
