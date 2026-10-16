package com.example.board.post.dto;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;

/**
 * 목록·검색 요청 파라미터. 정본: sp-docs/api-contract.md §4.1 (파라미터·기본값),
 * sp-docs/requirements/board.md §4 (허용 범위) §6 (검색).
 *
 * <p>요청에서 온 날값을 이 레코드가 한 번만 정규화한다. 기본값 채우기는 {@link #of} 가,
 * <b>{@code size} 절삭과 {@code searchType}·{@code keyword} 짝 검증은 정규 생성자</b>가 맡는다 —
 * 생성자에 두면 어떤 경로로 만들어도 거쳐야 하므로 정규화나 짝 검증이 빠진 인스턴스가 생기지
 * 않는다. <b>하한 검증만은 여기가 아니라 {@code PostController} 의 파라미터 제약이다</b> —
 * 아래가 그 이유다.
 *
 * <h2>상한은 절삭이고 하한 미달은 오류다</h2>
 *
 * <p>sp-docs/requirements/board.md §4 가 {@code page} 를 "0 이상", {@code size} 를
 * "1~50. 초과 시 50으로 절삭" 으로 정했다. 정규화를 허용한 쪽은 <b>상한 초과뿐</b>이므로
 * 하한 미달({@code page = -1}, {@code size = 0})은 입력 검증 위반이고 400 {@code C001} 이다
 * (sp-docs/api-contract.md §8.1).
 *
 * <p><b>하한은 {@link #MIN_PAGE}·{@link #MIN_SIZE} 를 쓰는 파라미터 제약으로 걸린다</b>
 * ({@code PostController.getList} 의 {@code @Min}). 정본이 그 응답의
 * {@code fieldErrors} 를 <b>1건, {@code field} 는 파라미터명</b>으로 정했으므로
 * (sp-docs/api-contract.md §4.1 §2.3) 담을 필드를 아는 자리에서 걸러야 한다 — 레코드가
 * {@code BusinessException} 을 던지면 그 예외는 {@code ErrorCode} 하나만 실어
 * {@code fieldErrors} 가 빈 배열이 된다(측정값). 그래서 이 값들은 여기에 두고 검증은 거기서
 * 한다 — 범위의 출처는 하나이고 집행하는 자리도 하나다.
 *
 * <p><b>하한을 거르지 않으면 {@code size = 0} 이 500 {@code C005} 가 된다</b> —
 * {@code PageRequest.of} 가 {@code IllegalArgumentException} 을 내고
 * {@code @Repository} 의 예외 변환이 그것을 {@code InvalidDataAccessApiUsageException} 으로
 * 바꿔 마지막 그물에 떨어진다(측정값). 흔한 클라이언트 실수가 {@code log.error} 경보를 만들면
 * 원인이 서버에 있는 500 과 구별되지 않는다 (sp-docs/conventions.md §6.1). 그래서 제약을
 * {@code PageRequest.of} 보다 <b>앞</b>에 두는 것이 요점이고, 그 자리가 파라미터다.
 *
 * <h2>{@code searchType} 과 {@code keyword} — 둘 다 또는 둘 다 아니다</h2>
 *
 * <p><b>한쪽만 오면 400 {@code C001} 이다</b> (sp-docs/api-contract.md §4.1,
 * sp-docs/requirements/board.md §6). 두 방향이 같은 판정이다 — 한쪽을 조용히 무시하면
 * 사용자는 검색이 걸린 줄 알고 전체 목록을 받는다 (sp-docs/AGENTS.md 불변식 9 의 silent
 * degradation). 그래서 가드가 방향별로 둘이 아니라 <b>"정확히 한쪽만 있는가" 하나</b>다.
 *
 * <p>둘 다 없으면 조건을 적용하지 않는다(전체 목록, §6).
 *
 * <p><b>공백만인 {@code keyword} 는 "없음" 으로 정규화하고, 그 정규화가 조합 검증보다
 * 먼저다</b> (§4.1). 그래서 {@code keyword=} 단독은 200 전체 목록이고
 * {@code searchType=TITLE&keyword=} 는 400 {@code C001} 이다. "없을 때" 와 "비어 있을 때" 를
 * 정규화로 합치는 것이므로 빈 값 전용 가드를 따로 두지 않는다.
 *
 * @param page 0부터 시작한다 (§4.1)
 * @param size 최대 {@value #MAX_SIZE}. 초과분은 절삭된다 (§4.1)
 * @param sort 화이트리스트 enum 이다. 컬럼명을 받지 않는다 (§6)
 * @param searchType 없으면 전체 목록이다
 * @param keyword 부분 일치({@code LIKE '%키워드%'})로 쓰인다 (§4.1)
 */
public record PostSearchCondition(
		int page,
		int size,
		PostSortType sort,
		SearchType searchType,
		String keyword
) {

	/** 전달되지 않았을 때의 값이다 (sp-docs/api-contract.md §4.1). */
	public static final int DEFAULT_PAGE = 0;

	/** 전달되지 않았을 때의 값이다 (sp-docs/api-contract.md §4.1). */
	public static final int DEFAULT_SIZE = 10;

	/** 전달되지 않았을 때의 값이다 (sp-docs/api-contract.md §4.1). */
	public static final PostSortType DEFAULT_SORT = PostSortType.latest;

	/**
	 * 허용 하한 (sp-docs/requirements/board.md §4 — "0 이상").
	 *
	 * <p>집행하는 자리는 {@code PostController.getList} 의 {@code @Min} 이다. 이유는 클래스
	 * 주석의 "상한은 절삭이고 하한 미달은 오류다" 에 있다.
	 */
	public static final int MIN_PAGE = 0;

	/** 허용 하한 (sp-docs/requirements/board.md §4 — "1~50"). 집행 자리는 {@link #MIN_PAGE} 와 같다. */
	public static final int MIN_SIZE = 1;

	/** 허용 상한. 초과분은 오류가 아니라 절삭이다 (sp-docs/requirements/board.md §4, §4.1). */
	public static final int MAX_SIZE = 50;

	public PostSearchCondition {
		// 상한 초과만 정본이 절삭으로 정했다. 하한은 파라미터 제약이 앞에서 걸렀다
		size = Math.min(size, MAX_SIZE);
		// 정규화가 조합 검증보다 먼저다 (sp-docs/api-contract.md §4.1)
		keyword = (keyword == null || keyword.isBlank()) ? null : keyword;
		if ((searchType == null) != (keyword == null)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}

	/**
	 * 전달되지 않은 파라미터에 §4.1 의 기본값을 채운다.
	 *
	 * <p>기본값을 {@code @RequestParam(defaultValue = ...)} 의 문자열로 흩지 않고 여기 모은다 —
	 * {@code sort} 의 기본값은 enum 상수이므로 문자열로 적으면 이름이 바뀔 때 조용히 깨진다.
	 */
	public static PostSearchCondition of(
			Integer page, Integer size, PostSortType sort, SearchType searchType, String keyword) {

		return new PostSearchCondition(
				page == null ? DEFAULT_PAGE : page,
				size == null ? DEFAULT_SIZE : size,
				sort == null ? DEFAULT_SORT : sort,
				searchType,
				keyword);
	}

	/**
	 * 검색 조건을 적용할지. 정규 생성자가 두 값이 함께 있거나 함께 없는 것만 통과시키므로
	 * {@code searchType} 하나를 보면 된다.
	 */
	public boolean hasSearchCondition() {
		return this.searchType != null;
	}

}
