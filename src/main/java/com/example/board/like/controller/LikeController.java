package com.example.board.like.controller;

import com.example.board.global.common.ApiResponse;
import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import com.example.board.like.dto.LikeResponse;
import com.example.board.like.service.LikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 좋아요 API. 정본: sp-docs/api-contract.md §4 (경로·성공 코드) §4.6 (응답) §9.8 (예시).
 *
 * <p>한 경로를 메서드로 가른다 — {@code PUT} 이 누르고 {@code DELETE} 가 취소한다. <b>둘 다
 * 본문이 없고 200 이다</b> — 201·204 를 쓰지 않는 것은 두 메서드의 응답을 한 모양으로 두기
 * 위해서다 (§4.6).
 *
 * <p>경로의 인가는 {@code SecurityConfig} 가 선언한다 (B-16) — 둘 다 인증이다. <b>두 핸들러가
 * {@code @CurrentMember} 를 받는다.</b> board 의 claim 계약 검사는 그 리졸버에서 집행되므로
 * {@code .authenticated()} 만 걸린 핸들러에서는 {@code sub}·{@code role} 이 계약 밖인 토큰이
 * 통과한다 (sp-docs/tech-stack.md §2.1). 그 토큰은 여기서 401 {@code A002} 다.
 *
 * <p>{@code @Transactional} 을 컨트롤러에 붙이지 않는다 (sp-docs/conventions.md §5).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/posts/{id}/like")
public class LikeController {

	private final LikeService likeService;

	/**
	 * 좋아요 (P-09). 이미 누른 글이어도 200 이고 응답이 같은 모양이다 (sp-docs/api-contract.md
	 * §4.6).
	 *
	 * <p>누른 계정은 {@code @CurrentMember} 가 검증된 JWT 의 {@code sub} 에서 꺼낸 것이다. 없거나
	 * 삭제된 글이면 404 {@code P001} 이고, 그 판정은 서비스가 한다.
	 */
	@PutMapping
	public ApiResponse<LikeResponse> like(
			@PathVariable Long id,
			@CurrentMember LoginMember loginMember) {
		return ApiResponse.success(this.likeService.like(id, loginMember.accountId()));
	}

	/**
	 * 좋아요 취소 (P-09). 누르지 않은 글이어도 200 이고 응답이 같은 모양이다
	 * (sp-docs/api-contract.md §4.6). 나머지는 {@link #like} 와 같다.
	 */
	@DeleteMapping
	public ApiResponse<LikeResponse> unlike(
			@PathVariable Long id,
			@CurrentMember LoginMember loginMember) {
		return ApiResponse.success(this.likeService.unlike(id, loginMember.accountId()));
	}

}
