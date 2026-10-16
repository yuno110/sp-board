package com.example.board.post.controller;

import com.example.board.global.common.ApiResponse;
import com.example.board.global.common.PageResponse;
import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostSearchCondition;
import com.example.board.post.dto.PostSortType;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.post.dto.SearchType;
import com.example.board.post.service.PostService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
	 * 게시글 목록·검색. <b>비로그인 허용이다</b> (sp-docs/api-contract.md §4). 파라미터는 §4.1,
	 * 응답은 §7.1 의 페이징 형식이고 항목은 §4.3 의 목록 모양이다.
	 *
	 * <p><b>파라미터를 하나씩 받는다.</b> {@code Pageable} 을 받으면 Spring Data 의 리졸버가
	 * {@code sort} 를 "컬럼명[,방향]" 으로 해석해 §4.1 이 요구한 화이트리스트 enum 이 아니게
	 * 된다 — 임의 컬럼명이 정렬에 실린다 (sp-docs/requirements/board.md §6).
	 *
	 * <p><b>기본값·절삭을 여기 두지 않는다.</b> {@link PostSearchCondition} 이 전부 맡으므로
	 * 기본값이 애노테이션 문자열과 그 레코드 양쪽에 생기지 않는다.
	 *
	 * <p><b>{@code page}·{@code size} 의 하한만은 여기가 집행 자리다.</b> 정본이 하한 위반의
	 * {@code fieldErrors} 를 <b>1건, {@code field} 는 파라미터명</b>으로 정했고
	 * (sp-docs/api-contract.md §4.1 §2.3) 그 필드명을 아는 것은 파라미터 제약뿐이다 —
	 * {@code HandlerMethodValidationException} 핸들러가
	 * {@code getParameterName()} 으로 채운다 (sp-docs/conventions.md §6.1). 레코드가 던지는
	 * {@code BusinessException} 에는 필드를 실을 자리가 없어 빈 배열이 된다. <b>하한 값 자체는
	 * {@link PostSearchCondition#MIN_PAGE}·{@link PostSearchCondition#MIN_SIZE} 에서 가져온다</b> —
	 * 범위의 출처는 그 레코드 하나다.
	 *
	 * <p>{@code Integer} 에 {@code required = false} 이므로 미전달이면 {@code null} 이고 Bean
	 * Validation 은 {@code null} 을 건너뛴다. 기본값 채우기는 그대로 레코드가 한다.
	 *
	 * <p>{@code sort}·{@code searchType} 이 화이트리스트 밖이면 바인딩이 실패해 400 이 된다
	 * ({@code C002}, sp-docs/conventions.md §6.1). {@code searchType} 만 오고
	 * {@code keyword} 가 없으면 {@link PostSearchCondition} 이 400 {@code C001} 로 만든다 —
	 * 그쪽은 <b>요청 수준</b> 오류라 {@code fieldErrors} 가 빈 배열이고 (§4.1) 하한 위반과
	 * 갈린다.
	 */
	@GetMapping
	public ApiResponse<PageResponse<PostResponse>> getList(
			@RequestParam(required = false) @Min(PostSearchCondition.MIN_PAGE) Integer page,
			@RequestParam(required = false) @Min(PostSearchCondition.MIN_SIZE) Integer size,
			@RequestParam(required = false) PostSortType sort,
			@RequestParam(required = false) SearchType searchType,
			@RequestParam(required = false) String keyword) {

		return ApiResponse.success(this.postService.getList(
				PostSearchCondition.of(page, size, sort, searchType, keyword)));
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

	/**
	 * 게시글 수정 (P-06). <b>200</b> 이고 응답은 <b>상세와 같은 모양</b>이다
	 * (sp-docs/api-contract.md §4 §4.3).
	 *
	 * <p><b>소유자 검증을 여기서 하지 않는다.</b> {@code PostService} 가 한다 —
	 * 리소스를 조회해야 판정할 수 있으므로 Controller 나 {@code @PreAuthorize} 에 둘 수 없다
	 * (sp-docs/security.md §5.1). 이 경로에 인증이 필요한 것까지가 {@code SecurityConfig} 의
	 * 몫이고, "본인 글인가" 는 서비스의 몫이다. <b>ADMIN 도 수정할 수 없다</b> (§5.2) — 그
	 * 판정도 서비스에 있다.
	 *
	 * <p>{@code loginMember} 는 {@code @CurrentMember} 가 검증된 JWT 에서 꺼낸 것이다. 수정
	 * 대상의 작성자와 비교할 값이므로 요청 본문·경로에서 받지 않는다
	 * (sp-docs/requirements/board.md §3 규칙 1).
	 */
	@PutMapping("/{id}")
	public ApiResponse<PostDetailResponse> update(
			@PathVariable Long id,
			@CurrentMember LoginMember loginMember,
			@Valid @RequestBody PostUpdateRequest request) {

		return ApiResponse.success(this.postService.update(id, loginMember, request));
	}

	/**
	 * 게시글 삭제 (P-07). <b>204 이고 본문이 없다</b> (sp-docs/api-contract.md §4).
	 *
	 * <p><b>작성자 본인과 ADMIN 이 삭제할 수 있다</b> (sp-docs/security.md §5.2) — 수정과
	 * 권한이 다르다. 그 판정은 {@code PostService} 가 하고 여기서 복제하지 않는다 (§5.1).
	 *
	 * <p>Soft Delete 이고 하위 댓글도 함께 논리 삭제된다
	 * (sp-docs/requirements/board.md §5 규칙 3). 그 둘이 한 트랜잭션인 것은 서비스의 경계가
	 * 보장한다 (sp-docs/domain-model.md §5).
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @CurrentMember LoginMember loginMember) {
		this.postService.delete(id, loginMember);
	}

}
