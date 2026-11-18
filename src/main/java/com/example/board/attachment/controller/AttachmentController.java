package com.example.board.attachment.controller;

import com.example.board.attachment.dto.AttachmentResponse;
import com.example.board.attachment.service.AttachmentService;
import com.example.board.global.common.ApiResponse;
import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부 API. 정본: sp-docs/api-contract.md §4.7 (요청·성공·실패) §9.9 (예시).
 *
 * <p>경로별 인가는 {@code SecurityConfig} 가 선언한다 (B-16) — 올리기는 인증이다.
 *
 * <p>{@code @Transactional} 을 컨트롤러에 붙이지 않는다 (sp-docs/conventions.md §5).
 */
@RestController
@RequiredArgsConstructor
public class AttachmentController {

	private final AttachmentService attachmentService;

	/**
	 * 첨부 올리기 (P-10). <b>201</b> 이고 응답은 첨부 하나의 모양이다 (sp-docs/api-contract.md §4.7).
	 *
	 * <p><b>{@code multipart/form-data} 만 받는다</b>({@code consumes}). 아니면 핸들러를 고르는
	 * 단계에서 415 {@code C006} 이다 (§4.7 의 실패 표). 파트 이름은 {@code file} 이고, 없으면 400
	 * {@code C001} 이다. 크기 한도(413 {@code C008})와 함께 이 메서드 본문에 닿기 전에 나는 예외이고
	 * {@code GlobalExceptionHandler} 가 코드로 바꾼다 (sp-docs/conventions.md §6.1).
	 *
	 * <p><b>{@code @CurrentMember} 를 {@code file} 앞에 둔다.</b> 인자는 선언 순서로 해석되므로, claim
	 * 이 계약 밖인 토큰이 파트가 없는 요청을 보내도 401 {@code A002} 가 400 {@code C001} 보다 먼저다 —
	 * sp-docs/requirements/board.md §11 의 판정 순서가 인증을 multipart 앞에 둔다.
	 *
	 * <p><b>그 순서가 서지 않는 경우가 있다 — claim 이 계약 밖인 토큰이다.</b> 그 토큰은 필터를 지나고
	 * {@code A002} 는 이 인자를 해석할 때 나므로, 그보다 앞 단계의 판정이 먼저 난다. multipart 가 아닌
	 * 본문은 401 {@code A002} 가 아니라 415 {@code C006} 이다(2026-11-18 B-23 리뷰 실측, OSIV 켬·끔 둘
	 * 다). 크기 한도를 넘는 본문도 413 {@code C008} 이 먼저 날 것으로 본다(추정 — 재지 않았다, 통합 측정
	 * 대상). 토큰이 없는 401 {@code A001} 은 필터에서 나므로 언제나 먼저다.
	 */
	@PostMapping(path = "/api/v1/posts/{postId}/attachments",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<AttachmentResponse> upload(
			@PathVariable Long postId,
			@CurrentMember LoginMember loginMember,
			@RequestPart("file") MultipartFile file) {

		return ApiResponse.success(
				this.attachmentService.upload(postId, loginMember.accountId(), file));
	}

}
