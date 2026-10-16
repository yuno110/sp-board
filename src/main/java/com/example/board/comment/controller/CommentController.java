package com.example.board.comment.controller;

import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentPageCondition;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.comment.dto.CommentUpdateRequest;
import com.example.board.comment.service.CommentService;
import com.example.board.global.common.ApiResponse;
import com.example.board.global.common.PageResponse;
import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 댓글 API. 정본: sp-docs/api-contract.md §4 (경로·성공 코드) §4.5 (응답의 한 모양).
 *
 * <p><b>클래스 수준 {@code @RequestMapping} 이 없다.</b> 네 엔드포인트가 두 기준 경로로
 * 갈린다 — 작성·목록은 {@code /api/v1/posts/{postId}/comments}, 수정·삭제는
 * {@code /api/v1/comments/{id}} 다 (§4). 한쪽을 클래스에 올리면 다른 쪽이 그 접두어를 벗어나
 * 읽는 사람이 경로를 조립해야 한다.
 *
 * <p>경로별 인가는 {@code SecurityConfig} 가 선언한다 (B-04) — 목록은 {@code permitAll},
 * 작성·수정·삭제는 인증이다.
 *
 * <p>{@code @Transactional} 을 컨트롤러에 붙이지 않는다 (sp-docs/conventions.md §5).
 */
@RestController
@RequiredArgsConstructor
public class CommentController {

	private final CommentService commentService;

	/**
	 * 댓글 작성 (C-01). <b>201</b> 이다 (sp-docs/api-contract.md §4).
	 *
	 * <p>{@code accountId} 는 {@code @CurrentMember} 가 검증된 JWT 의 {@code sub} 에서 꺼낸
	 * 것이다. <b>요청 본문에서 받지 않는다</b> (sp-docs/requirements/board.md §3 규칙 1) —
	 * {@link CommentCreateRequest} 에 그 필드가 없다.
	 *
	 * <p>대상 게시글은 경로 변수 하나로 정해진다. 없거나 삭제된 게시글이면 404 {@code P001}
	 * 이고, 그 판정은 서비스가 한다.
	 */
	@PostMapping("/api/v1/posts/{postId}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CommentResponse> create(
			@PathVariable Long postId,
			@CurrentMember LoginMember loginMember,
			@Valid @RequestBody CommentCreateRequest request) {

		return ApiResponse.success(
				this.commentService.create(postId, loginMember.accountId(), request));
	}

	/**
	 * 댓글 목록 (C-02). <b>비로그인 허용이다</b> (sp-docs/api-contract.md §4). 응답은 §7.1 의
	 * 페이징 형식이고 항목은 §4.5 의 모양이다.
	 *
	 * <p>{@code @CurrentMember} 를 받지 않는다 — 무토큰 요청에서 해석할 Claim 이 없다.
	 *
	 * <p><b>기본값·절삭을 여기 두지 않는다.</b> {@link CommentPageCondition} 이 전부 맡으므로
	 * 기본값이 애노테이션 문자열과 그 레코드 양쪽에 생기지 않는다.
	 *
	 * <p><b>{@code page}·{@code size} 의 하한만은 여기가 집행 자리다.</b> 정본이 하한 위반의
	 * {@code fieldErrors} 를 <b>1건, {@code field} 는 파라미터명</b>으로 정했고
	 * (sp-docs/api-contract.md §4.1 §2.3) 그 필드명을 아는 것은 파라미터 제약뿐이다 —
	 * {@code HandlerMethodValidationException} 핸들러가 {@code getParameterName()} 으로
	 * 채운다 (sp-docs/conventions.md §6.1). 게시글 목록과 같은 모양이다.
	 *
	 * <p>{@code Integer} 에 {@code required = false} 이므로 미전달이면 {@code null} 이고 Bean
	 * Validation 은 {@code null} 을 건너뛴다. 기본값 채우기는 그대로 레코드가 한다.
	 */
	@GetMapping("/api/v1/posts/{postId}/comments")
	public ApiResponse<PageResponse<CommentResponse>> getList(
			@PathVariable Long postId,
			@RequestParam(required = false) @Min(CommentPageCondition.MIN_PAGE) Integer page,
			@RequestParam(required = false) @Min(CommentPageCondition.MIN_SIZE) Integer size) {

		return ApiResponse.success(
				this.commentService.getList(postId, CommentPageCondition.of(page, size)));
	}

	/**
	 * 댓글 수정 (C-03). <b>200</b> 이고 응답은 <b>작성·목록과 같은 모양</b>이다
	 * (sp-docs/api-contract.md §4 §4.5).
	 *
	 * <p><b>소유자 검증을 여기서 하지 않는다.</b> {@code CommentService} 가 한다 — 리소스를
	 * 조회해야 판정할 수 있으므로 Controller 나 {@code @PreAuthorize} 에 둘 수 없다
	 * (sp-docs/security.md §5.1). 이 경로에 인증이 필요한 것까지가 {@code SecurityConfig} 의
	 * 몫이고, "본인 댓글인가" 는 서비스의 몫이다. <b>ADMIN 도 수정할 수 없다</b> (§5.2).
	 *
	 * <p>경로에 {@code postId} 가 없다 — 댓글 id 하나로 정해진다 (§4).
	 */
	@PutMapping("/api/v1/comments/{id}")
	public ApiResponse<CommentResponse> update(
			@PathVariable Long id,
			@CurrentMember LoginMember loginMember,
			@Valid @RequestBody CommentUpdateRequest request) {

		return ApiResponse.success(this.commentService.update(id, loginMember, request));
	}

	/**
	 * 댓글 삭제 (C-04). <b>204 이고 본문이 없다</b> (sp-docs/api-contract.md §4).
	 *
	 * <p><b>작성자 본인과 ADMIN 이 삭제할 수 있다</b> (sp-docs/security.md §5.2) — 수정과
	 * 권한이 다르다. 그 판정은 {@code CommentService} 가 하고 여기서 복제하지 않는다 (§5.1).
	 *
	 * <p>Soft Delete 이고 {@code comment_count} 가 1 감소한다. 그 둘이 한 트랜잭션인 것은
	 * 서비스의 경계가 보장한다 (sp-docs/domain-model.md §5).
	 */
	@DeleteMapping("/api/v1/comments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @CurrentMember LoginMember loginMember) {
		this.commentService.delete(id, loginMember);
	}

}
