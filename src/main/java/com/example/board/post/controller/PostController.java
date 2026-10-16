package com.example.board.post.controller;

import com.example.board.global.common.ApiResponse;
import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게시글 API. 정본: sp-docs/api-contract.md §4 (경로·성공 코드) §4.3 (응답 모양).
 *
 * <p>경로별 인가는 {@code SecurityConfig} 가 선언한다 (B-04) — {@code POST /posts} 는 인증,
 * {@code GET /posts/{id}} 는 {@code permitAll} 이다.
 *
 * <p>{@code @Transactional} 을 컨트롤러에 붙이지 않는다 (sp-docs/conventions.md §5).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/posts")
public class PostController {

	private final PostService postService;

	/**
	 * 게시글 작성. <b>201</b> 이다 (sp-docs/api-contract.md §4).
	 *
	 * <p>{@code accountId} 는 {@code @CurrentMember} 가 검증된 JWT 의 {@code sub} 에서 꺼낸
	 * 것이다. <b>요청 본문에서 받지 않는다</b> (sp-docs/requirements/board.md §3 규칙 1) —
	 * {@link PostCreateRequest} 에 그 필드가 없다.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<PostDetailResponse> create(
			@CurrentMember LoginMember loginMember,
			@Valid @RequestBody PostCreateRequest request) {

		return ApiResponse.success(this.postService.create(loginMember.accountId(), request));
	}

	/**
	 * 게시글 상세 조회. <b>비로그인 허용이고</b> 조회수가 1 증가한다
	 * (sp-docs/requirements/board.md §1 P-04 P-05).
	 *
	 * <p>{@code @CurrentMember} 를 받지 않는다 — 무토큰 요청에서 해석할 Claim 이 없다.
	 */
	@GetMapping("/{id}")
	public ApiResponse<PostDetailResponse> getDetail(@PathVariable Long id) {
		return ApiResponse.success(this.postService.getDetail(id));
	}

}
