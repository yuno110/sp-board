package com.example.board.comment.dto;

/**
 * 댓글 목록 요청 파라미터 (C-02).
 *
 * <p>값의 출처는 둘이다. <b>{@code size} 기본값 20</b> 은
 * sp-docs/requirements/board.md §2 의 C-02("게시글별 등록순 페이징(기본 20)")이고,
 * <b>하한·상한·절삭</b>은 같은 문서 §4 의 입력 검증 표다 ({@code page} 0 이상,
 * {@code size} 1~50, 초과 시 50으로 절삭). {@code page} 기본값 0 은 게시글 목록과 같다
 * (sp-docs/api-contract.md §4.1).
 *
 * <p><b>정렬 파라미터가 없다.</b> 댓글 목록은 등록순 하나뿐이므로
 * (sp-docs/requirements/board.md §2 의 C-02) 고를 것이 없고, 순서는
 * {@code CommentRepository} 의 쿼리가 {@code created_at ASC, id ASC} 로 고정한다
 * (sp-docs/api-contract.md §7.1). 검색 파라미터도 없다 — 댓글 검색은 요구에 없다.
 *
 * <p>게시글 목록과 같이 <b>상한 초과는 절삭이고 하한 미달은 400 {@code C001}</b> 이다.
 * 정규화를 허용한 쪽은 상한뿐이므로 ({@code requirements/board.md} §4) {@code size = 0} ·
 * {@code page = -1} 은 입력 검증 위반이다. <b>하한 집행은 {@code CommentController} 의
 * 파라미터 제약이다</b> — 정본이 그 응답의 {@code fieldErrors} 를 1건, {@code field} 는
 * 파라미터명으로 정했으므로 (sp-docs/api-contract.md §4.1 §2.3) 담을 필드를 아는 자리에서
 * 걸러야 한다. 값은 여기서 가져간다.
 *
 * @param page 0부터 시작한다
 * @param size 최대 {@value #MAX_SIZE}. 초과분은 절삭된다
 */
public record CommentPageCondition(int page, int size) {

	/** 전달되지 않았을 때의 값이다 (sp-docs/api-contract.md §4.1 의 게시글 목록과 같다). */
	public static final int DEFAULT_PAGE = 0;

	/** 전달되지 않았을 때의 값이다 (sp-docs/requirements/board.md §2 의 C-02). */
	public static final int DEFAULT_SIZE = 20;

	/** 허용 하한 (sp-docs/requirements/board.md §4 — "0 이상"). */
	public static final int MIN_PAGE = 0;

	/** 허용 하한 (sp-docs/requirements/board.md §4 — "1~50"). */
	public static final int MIN_SIZE = 1;

	/** 허용 상한. 초과분은 오류가 아니라 절삭이다 (sp-docs/requirements/board.md §4). */
	public static final int MAX_SIZE = 50;

	public CommentPageCondition {
		// 상한 초과만 정본이 절삭으로 정했다. 하한은 파라미터 제약이 앞에서 걸렀다
		size = Math.min(size, MAX_SIZE);
	}

	/**
	 * 전달되지 않은 파라미터에 기본값을 채운다.
	 *
	 * <p>기본값을 {@code @RequestParam(defaultValue = ...)} 의 문자열로 흩지 않고 여기 모은다 —
	 * 절삭과 기본값이 한 자리에 있어야 어느 경로로 만들어도 같은 값이 된다.
	 */
	public static CommentPageCondition of(Integer page, Integer size) {
		return new CommentPageCondition(
				page == null ? DEFAULT_PAGE : page,
				size == null ? DEFAULT_SIZE : size);
	}

}
