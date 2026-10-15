package com.example.board.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 정본: sp-docs/api-contract.md §8.1(공통) §8.3(board-service).
 *
 * <p>서비스마다 코드가 다르므로 복제하지 않는다 (sp-docs/conventions.md §7.2). 코드를
 * 추가하려면 정본을 먼저 개정한다 (§6).
 *
 * <p>member 전용 {@code M0xx} 와 auth 전용 {@code AU0xx} 는 여기에 두지 않는다 — 서비스별
 * 코드가 섞이면 응답의 접두어가 어느 서비스에서 난 오류인지 식별하지 못한다 (§8).
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	// 공통 (세 서비스) — §8.1
	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
	INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "C002", "잘못된 타입의 값입니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "지원하지 않는 HTTP 메서드입니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "요청한 리소스를 찾을 수 없습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C005", "서버 내부 오류가 발생했습니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "C006", "지원하지 않는 미디어 타입입니다."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "C007", "응답할 수 있는 형식이 없습니다."),

	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "인증이 필요합니다."),
	INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "유효하지 않은 토큰입니다."),
	EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "A003", "만료된 토큰입니다."),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "A004", "권한이 없습니다."),

	// board-service — §8.3
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "게시글을 찾을 수 없습니다."),
	POST_FORBIDDEN(HttpStatus.FORBIDDEN, "P002", "게시글에 대한 권한이 없습니다."),
	COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "CM001", "댓글을 찾을 수 없습니다."),
	COMMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "CM002", "댓글에 대한 권한이 없습니다."),

	// S001 은 내부 API 호출 실패(4xx·5xx·타임아웃·파싱 실패)에, S002 는 글·댓글 생성 시 활성
	// 프로필이 없을 때 쓴다. 판정 규칙은 §5.1 이 정본이고, 쓰는 쪽은 B-05 이후다.
	EXTERNAL_SERVICE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "S001", "일시적으로 서비스 연동에 실패했습니다."),
	PROFILE_REQUIRED(HttpStatus.FORBIDDEN, "S002", "프로필 등록이 필요합니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

}
