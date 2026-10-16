package com.example.board.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.global.config.JpaConfig;
import com.example.board.global.config.QuerydslConfig;
import com.example.board.post.dto.PostSearchCondition;
import com.example.board.post.dto.PostSortType;
import com.example.board.post.dto.SearchType;
import com.example.board.post.entity.Post;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

/**
 * sp-docs/plan/phase1.md §6 B-06 의 "검증" 표 중 쿼리 쪽을 테스트로 옮긴 것이다. HTTP
 * 파라미터·에러 코드·응답 형식은 {@link com.example.board.post.controller.PostControllerTest}
 * 가 본다.
 *
 * <p>정본은 sp-docs/requirements/board.md §6 (검색 대상 컬럼) §5 규칙 1 (삭제분 제외),
 * sp-docs/api-contract.md §4.1 (파라미터·부분 일치·정렬 키) §7.1 (동률과 페이지 경계) 다.
 *
 * <p>{@code @AutoConfigureTestDatabase(replace = NONE)} 와 {@code @Import(JpaConfig.class)} 는
 * 둘 다 필요하다 (sp-docs/conventions.md §9.3). {@code QuerydslConfig} 도 직접 올린다 —
 * {@code @DataJpaTest} 슬라이스는 {@code @Configuration} 을 컴포넌트 스캔하지 않아
 * {@code JPAQueryFactory} 빈이 없고, {@code PostQueryRepository} 역시 슬라이스의 스캔 대상이
 * 아니다.
 *
 * <p>픽스처의 {@code writerId} 는 목록·검색(B-06)이 601~619, 내가 쓴 글(B-09)이 871~879 를
 * 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3) 이미 쓰이는 대역과 겹치지 않게 둔다. 각 테스트는
 * {@code @DataJpaTest} 의 기본 롤백으로 정리되어 행이 남지 않는다.
 *
 * <h2>전체 건수를 보는 단언은 테이블이 비어 있음을 전제한다</h2>
 *
 * <p>"15건 저장 후 {@code totalElements = 15}" 와 "키워드 없이 조회하면 전체" 는 테이블 전체를
 * 센다. 롤백되지 않는 테스트 클래스가 자기 대역을 지우지 않으면 그 전제가 깨지므로,
 * {@link #assertNoRowsFromOtherClasses()} 로 전제를 먼저 단언한다 — 깨졌을 때 기대 건수가
 * 어긋난 이유가 바로 드러난다.
 *
 * <h2>{@code created_at} 을 직접 박는 이유</h2>
 *
 * <p>저장 순서에 기대면 <b>H2 에서만 통하는 테스트</b>가 된다. H2 의 {@code DATETIME} 은
 * {@code TIMESTAMP(6)} 으로 만들어져 연속 저장 15건의 {@code created_at} 이 모두 다르지만
 * (측정값: 15건 전부 서로 다름), MySQL 의 {@code DATETIME} 은 초 단위라 같은 초에 저장된
 * 행들이 동률이 된다 (sp-docs/api-contract.md §7.1 이 이 실측을 근거로 적었다).
 *
 * <p>그래서 시각을 두 방향으로 직접 박는다. 1차 키의 순서를 보는 테스트는 {@code created_at}
 * 을 <b>초 단위로 벌려</b> 두 엔진에서 같은 순서가 나오게 하고, 동률을 보는 테스트는
 * <b>같은 값으로 묶어</b> H2 에서도 동률이 재현되게 한다 — 저장만 해서는 H2 에서 동률이
 * 생기지 않는다. {@code view_count} 는 기본값이 0 이라 박을 것이 없다.
 *
 * <h2>키워드를 한글로 두는 이유</h2>
 *
 * <p>운영 스키마의 콜레이션은 {@code utf8mb4_general_ci} 라 대소문자를 구분하지 않는데
 * (sp-docs/tech-stack.md §4.1) H2 는 {@code IGNORECASE} 를 켜지 않아 구분한다 (측정값:
 * {@code LIKE '%notice%'} 가 {@code "Notice Board"} 를 찾지 못한다). 검증표의 키워드가 한글이라
 * (§6 의 {@code 공지}·{@code 홍길동}) 그 차이에 걸리지 않으므로 한글 그대로 쓰고, 대소문자
 * 동작은 단언하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, PostQueryRepository.class})
@ActiveProfiles("test")
class PostQueryRepositoryTest {

	/** sp-docs/api-contract.md §4.1 의 기본 size. 리터럴로 적는다 — DTO 의 상수를 쓰면 자기참조다. */
	private static final int DEFAULT_SIZE = 10;

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

	/** 동률 픽스처 수와 페이지 크기. 두 페이지로 갈리고 둘째 페이지가 비지 않게 둔다. */
	private static final int TIED_FIXTURE_COUNT = 7;

	private static final int TIED_PAGE_SIZE = 4;

	@Autowired
	private TestEntityManager em;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PostQueryRepository postQueryRepository;

	// --- 페이징 (sp-docs/api-contract.md §4.1 §7.1) ---

	/**
	 * 첫 페이지가 <b>꽉 차므로</b> 여기의 {@code totalElements} 는 건수 쿼리를 실제로 본다 —
	 * {@code PageImpl} 의 보정은 마지막 페이지에서만 일어난다
	 * (sp-docs/process/review-policy.md §4.1).
	 */
	@Test
	@DisplayName("15건을 저장하면 첫 페이지가 10건이고 totalElements 가 15다")
	void 열다섯건의_첫_페이지() {
		assertNoRowsFromOtherClasses();
		for (int i = 0; i < 15; i++) {
			save(601L, "페이징작성자", "페이징 " + i, "본문");
		}

		Page<Post> first = search(condition(0, DEFAULT_SIZE));

		assertThat(first.getContent()).hasSize(DEFAULT_SIZE);
		assertThat(first.getTotalElements()).isEqualTo(15);
		assertThat(first.getTotalPages()).isEqualTo(2);
		assertThat(first.isFirst()).isTrue();
		assertThat(first.isLast()).isFalse();

		Page<Post> last = search(condition(1, DEFAULT_SIZE));

		// 마지막 페이지의 totalElements 는 단언하지 않는다. 그 자리의 값은 보정된
		// offset + content.size() 와 구별되지 않아 어떤 건수 쿼리에서도 15 가 된다
		// (review-policy.md §4.1). 건수는 위의 첫 페이지가 본다
		assertThat(last.getContent()).hasSize(5);
		assertThat(last.isLast()).isTrue();
	}

	// --- 정렬 (sp-docs/api-contract.md §4.1) ---

	/**
	 * {@code created_at} 내림차순이다. 픽스처는 저장 순서와 시각 순서를 <b>어긋나게</b> 둔다 —
	 * {@code id} 로 정렬하는 구현이 통과하지 못하게 한다.
	 */
	@Test
	@DisplayName("sort=latest 는 created_at 내림차순이다")
	void 최신순_정렬() {
		Long middle = save(602L, "정렬작성자", "가운데", "본문").getId();
		Long newest = save(602L, "정렬작성자", "최신", "본문").getId();
		Long oldest = save(602L, "정렬작성자", "가장 오래된", "본문").getId();
		setCreatedAt(middle, BASE_TIME.plusSeconds(2));
		setCreatedAt(newest, BASE_TIME.plusSeconds(3));
		setCreatedAt(oldest, BASE_TIME.plusSeconds(1));

		Page<Post> page = search(condition(SearchType.WRITER, "정렬작성자", PostSortType.latest));

		assertThat(page.getContent()).extracting(Post::getId)
				.containsExactly(newest, middle, oldest);
	}

	/**
	 * <b>{@code sort} 를 주지 않으면 {@code latest} 다</b> (sp-docs/api-contract.md §4.1 의 기본값
	 * 칼럼). {@link PostSearchCondition#of} 가 {@code null} 을 {@code DEFAULT_SORT} 로 채우는
	 * 자리를 본다.
	 *
	 * <p>픽스처는 세 순서를 <b>셋 다 다르게</b> 둔다 — {@code created_at} 내림차순, {@code
	 * view_count} 내림차순, {@code id} 내림차순이 모두 갈린다. 그래야 기본값을 {@code views} 로
	 * 바꾸는 변이도, 정렬이 아예 빠져 {@code id} 순으로 나오는 변이도 이 단언에 걸린다.
	 */
	@Test
	@DisplayName("sort 를 주지 않으면 latest 와 같은 순서다")
	void 기본_정렬은_latest() {
		// 저장 순서가 id 순서다. 시각·조회수를 저장 순서와 어긋나게 박아 세 순서를 모두 가른다
		Post middleManyViews = save(613L, "기본정렬작성자", "가운데이고 조회 많음", "본문");
		Post newestNoViews = save(613L, "기본정렬작성자", "최신이고 조회 없음", "본문");
		Post oldestFewViews = save(613L, "기본정렬작성자", "가장 오래되고 조회 적음", "본문");
		viewed(middleManyViews, 5);
		viewed(oldestFewViews, 1);
		this.em.flush();
		setCreatedAt(middleManyViews.getId(), BASE_TIME.plusSeconds(2));
		setCreatedAt(newestNoViews.getId(), BASE_TIME.plusSeconds(3));
		setCreatedAt(oldestFewViews.getId(), BASE_TIME.plusSeconds(1));

		Page<Post> page = search(condition(SearchType.WRITER, "기본정렬작성자", null));

		// latest -> [최신, 가운데, 오래됨] / views -> [가운데(5), 오래됨(1), 최신(0)]
		// id DESC -> [오래됨, 최신, 가운데]. 셋이 모두 다르다
		assertThat(page.getContent()).extracting(Post::getId)
				.as("sort 미전달의 기본값은 latest 다 (sp-docs/api-contract.md §4.1)")
				.containsExactly(
						newestNoViews.getId(), middleManyViews.getId(), oldestFewViews.getId());
	}

	@Test
	@DisplayName("sort=views 는 view_count 내림차순이다")
	void 조회순_정렬() {
		Long few = viewed(save(603L, "조회순작성자", "조회수 적음", "본문"), 1).getId();
		Long many = viewed(save(603L, "조회순작성자", "조회수 많음", "본문"), 5).getId();
		Long none = save(603L, "조회순작성자", "조회수 없음", "본문").getId();
		this.em.flush();
		this.em.clear();

		Page<Post> page = search(condition(SearchType.WRITER, "조회순작성자", PostSortType.views));

		assertThat(page.getContent()).extracting(Post::getId)
				.containsExactly(many, few, none);
		assertThat(page.getContent()).extracting(Post::getViewCount)
				.containsExactly(5L, 1L, 0L);
	}

	// --- 동률과 페이지 경계 (sp-docs/api-contract.md §4.1 §7.1) ---

	/**
	 * {@code created_at} 이 전부 같아도 1·2페이지에 <b>중복도 누락도 없다</b>
	 * (sp-docs/api-contract.md §7.1).
	 *
	 * <p>동률을 만들려면 <b>시각을 직접 박아야 한다.</b> H2 는 마이크로초를 유지하므로
	 * (측정값) 연속 저장만으로는 동률이 재현되지 않는다 — MySQL `DATETIME`(초 단위)에서는
	 * 같은 초에 생성된 글이 그냥 동률이다.
	 */
	@Test
	@DisplayName("created_at 이 전부 같아도 1·2페이지에 중복·누락이 없다")
	void created_at_동률의_페이지_경계() {
		List<Long> all = new ArrayList<>();
		for (int i = 0; i < TIED_FIXTURE_COUNT; i++) {
			all.add(save(611L, "동률시각작성자", "동률 " + i, "본문").getId());
		}
		setCreatedAtForWriter(611L, BASE_TIME);

		assertStablePageBoundary("동률시각작성자", PostSortType.latest, all);
	}

	/**
	 * {@code view_count} 가 전부 0 이어도 1·2페이지에 <b>중복도 누락도 없다</b>
	 * (sp-docs/api-contract.md §7.1 — "조회가 한 번도 없는 목록은 전체가 동률이다").
	 *
	 * <p>기본값이 0 이므로 픽스처를 손댈 것이 없다. 운영에서 가장 흔한 동률이다.
	 */
	@Test
	@DisplayName("view_count 가 전부 0 이어도 1·2페이지에 중복·누락이 없다")
	void view_count_동률의_페이지_경계() {
		List<Long> all = new ArrayList<>();
		for (int i = 0; i < TIED_FIXTURE_COUNT; i++) {
			all.add(save(612L, "동률조회수작성자", "동률 조회수 " + i, "본문").getId());
		}

		assertStablePageBoundary("동률조회수작성자", PostSortType.views, all);
	}

	// --- 삭제분 제외 (sp-docs/requirements/board.md §5 규칙 1) ---

	/**
	 * 목록과 건수 <b>양쪽</b>에서 빠져야 한다. 두 쿼리를 따로 단언한다.
	 *
	 * <p><b>건수 쿼리의 조건을 관측하려면 두 가지가 모두 필요하다</b>
	 * (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p>하나는 <b>페이지가 꽉 차는 것</b>이다. {@code PageImpl} 은 마지막 페이지에서
	 * {@code totalElements} 를 {@code offset + content.size()} 로 보정하므로, 결과가 한 페이지에
	 * 다 들어가면 건수 쿼리의 조건이 빠져도 보정이 그것을 덮는다 (측정값: 건수 쿼리에서
	 * {@code deleted} 조건을 지우고 돌렸을 때 단언이 통과했다). 그래서 {@code size} 를 2 로 두고
	 * 다음 페이지가 남게 만든다 — {@code offset 0 + size 2 = 2} 이고 올바른 건수는 3 이라
	 * {@code 2 > 3} 이 거짓이므로 보정이 일어나지 않는다.
	 *
	 * <p>다른 하나는 <b>그 조건이 배제하는 행이 픽스처에 있는 것</b>이다. 꽉 찬 페이지는
	 * <b>필요조건일 뿐이다</b> — 테이블의 모든 비삭제 행이 조건에 매칭되면 조건을 떼어낸 건수와
	 * 붙인 건수가 <b>같은 값</b>이라 단언이 아무것도 보지 못한다. 그래서 {@code deleted} 를 위한
	 * 삭제분 하나와 <b>{@code keyword} 를 위한 비매칭 활성 행 하나</b>를 둘 다 둔다. 측정값:
	 * 비매칭 행이 없을 때 {@code keywordCondition} 을 건수 쿼리에서 떼어낸 변이가 통과했고,
	 * 더한 뒤에는 건수가 4 가 되어 터진다.
	 *
	 * <p>{@code wholeResult} 는 {@code WRITER} 로 좁히므로 비매칭 행을 받지 않고,
	 * {@code totalPages} 는 {@code ceil(3/2) = 2} 로 그대로다.
	 */
	@Test
	@DisplayName("삭제된 게시글은 목록과 건수에서 모두 제외된다")
	void 삭제분_제외() {
		Long first = save(604L, "삭제작성자", "남는 글 하나", "본문").getId();
		Long second = save(604L, "삭제작성자", "남는 글 둘", "본문").getId();
		Long third = save(604L, "삭제작성자", "남는 글 셋", "본문").getId();
		Post removed = save(604L, "삭제작성자", "삭제된 글", "본문");
		removed.softDelete();
		// 활성이지만 keyword 에 걸리지 않는 행. 이것이 없으면 건수 쿼리의 keyword 조건이
		// 관측되지 않는다 (위 주석의 두 번째 조건)
		save(614L, "검색비대상작성자", "키워드에 걸리지 않는 활성 글", "본문");
		this.em.flush();
		this.em.clear();

		Page<Post> wholeResult = search(condition(SearchType.WRITER, "삭제작성자", PostSortType.latest));

		assertThat(wholeResult.getContent()).extracting(Post::getId)
				.containsExactlyInAnyOrder(first, second, third);

		Page<Post> fullPage = search(PostSearchCondition.of(
				0, 2, PostSortType.latest, SearchType.WRITER, "삭제작성자"));

		assertThat(fullPage.getContent()).hasSize(2);
		assertThat(fullPage.getTotalElements())
				.as("건수 쿼리에도 deleted = false 와 keyword 조건이 둘 다 붙어야 한다")
				.isEqualTo(3);
		assertThat(fullPage.getTotalPages()).isEqualTo(2);
		assertThat(this.postRepository.findById(removed.getId()))
				.as("논리 삭제이므로 행은 남아 있다")
				.isPresent();
	}

	// --- 내가 쓴 글 (B-09, P-08 / sp-docs/api-contract.md §4.1) ---

	/**
	 * 검증표의 "내 글 중 삭제분 포함 → 삭제분 제외" 다 (sp-docs/plan/phase1.md §6 B-09).
	 * <b>목록과 건수 양쪽에서</b> 빠져야 하므로 두 쿼리를 따로 단언한다.
	 *
	 * <p>건수 쿼리의 조건을 관측하는 두 요건은 {@link #삭제분_제외()} 와 같다
	 * (sp-docs/process/review-policy.md §4.1). 하나는 <b>페이지가 꽉 차는 것</b>이다 —
	 * {@code size} 를 2 로 두어 {@code offset 0 + 2 = 2} 가 올바른 건수 3 보다 작게 만든다.
	 * 다른 하나는 <b>각 조건이 배제하는 행이 픽스처에 있는 것</b>이다. 그래서
	 * {@code deleted} 를 위한 삭제분 하나와 <b>{@code writer_id} 를 위한 다른 작성자의 활성 행
	 * 하나</b>를 둘 다 둔다 — 후자가 없으면 건수 쿼리에서 작성자 조건을 떼어낸 변이가 같은 3 을
	 * 내어 단언이 아무것도 보지 못한다.
	 */
	@Test
	@DisplayName("내가 쓴 글 목록에서 삭제된 글이 목록과 건수에서 모두 제외된다")
	void 내가_쓴_글의_삭제분_제외() {
		Long first = save(871L, "내글작성자", "내 글 하나", "본문").getId();
		Long second = save(871L, "내글작성자", "내 글 둘", "본문").getId();
		Long third = save(871L, "내글작성자", "내 글 셋", "본문").getId();
		Post removed = save(871L, "내글작성자", "삭제된 내 글", "본문");
		removed.softDelete();
		// 다른 작성자의 활성 행. 이것이 없으면 건수 쿼리의 writer_id 조건이 관측되지 않는다
		save(872L, "타인작성자", "타인의 활성 글", "본문");
		this.em.flush();
		this.em.clear();

		Page<Post> wholeResult = searchByWriter(871L, condition(0, DEFAULT_SIZE));

		assertThat(wholeResult.getContent()).extracting(Post::getId)
				.containsExactlyInAnyOrder(first, second, third);

		Page<Post> fullPage = searchByWriter(871L, condition(0, 2));

		assertThat(fullPage.getContent()).hasSize(2);
		assertThat(fullPage.getTotalElements())
				.as("건수 쿼리에도 deleted = false 와 writer_id 조건이 둘 다 붙어야 한다")
				.isEqualTo(3);
		assertThat(fullPage.getTotalPages()).isEqualTo(2);
		assertThat(this.postRepository.findById(removed.getId()))
				.as("논리 삭제이므로 행은 남아 있다")
				.isPresent();
	}

	/**
	 * <b>마지막 정렬 키가 {@code id} 다</b> (sp-docs/api-contract.md §7.1, B-09 완료 기준).
	 * 1차 키가 전부 동률이어도 1·2페이지에 중복도 누락도 없다.
	 *
	 * <p>동률을 만들려면 시각을 직접 박아야 한다 — H2 는 마이크로초를 유지하므로 연속 저장만으로는
	 * 재현되지 않는다 (클래스 주석의 "{@code created_at} 을 직접 박는 이유").
	 *
	 * <p>작성자 조건으로 좁히므로 {@code 871} 대역의 다른 테스트 픽스처와 섞이지 않는다 —
	 * {@code @DataJpaTest} 의 롤백이 테스트마다 비운다.
	 */
	@Test
	@DisplayName("created_at 이 전부 같아도 내가 쓴 글의 1·2페이지에 중복·누락이 없다")
	void 내가_쓴_글의_동률_페이지_경계() {
		List<Long> all = new ArrayList<>();
		for (int i = 0; i < TIED_FIXTURE_COUNT; i++) {
			all.add(save(873L, "동률내글작성자", "동률 내 글 " + i, "본문").getId());
		}
		setCreatedAtForWriter(873L, BASE_TIME);

		assertStablePageBoundary(
				page -> searchByWriter(873L, condition(page, TIED_PAGE_SIZE)), all);
	}

	// --- 검색 (sp-docs/requirements/board.md §6) ---

	/**
	 * 제목 기준이다. 매칭되는 픽스처 하나는 키워드를 <b>제목 가운데</b>에 둔다 — {@code keyword}
	 * 는 부분 일치이고 전방 일치가 아니므로 (sp-docs/api-contract.md §4.1)
	 * {@code startsWith} 구현이 통과하지 못하게 한다.
	 */
	@Test
	@DisplayName("searchType=TITLE 은 제목에 키워드가 든 글만 돌려준다")
	void 제목_검색() {
		Long head = save(605L, "검색작성자", "공지사항입니다", "본문").getId();
		Long middle = save(605L, "검색작성자", "주말 공지 전달", "본문").getId();
		save(605L, "검색작성자", "자유 게시글", "공지라는 말이 본문에 있다");

		Page<Post> page = search(condition(SearchType.TITLE, "공지", PostSortType.latest));

		assertThat(page.getContent()).extracting(Post::getId)
				.containsExactlyInAnyOrder(head, middle);
	}

	@Test
	@DisplayName("searchType=CONTENT 는 본문 기준이다")
	void 본문_검색() {
		Long inContent = save(606L, "본문검색작성자", "제목에는 없다", "본문에 공지가 있다").getId();
		save(606L, "본문검색작성자", "공지가 제목에만 있다", "본문은 평범하다");

		Page<Post> page = search(condition(SearchType.CONTENT, "공지", PostSortType.latest));

		assertThat(page.getContent()).extracting(Post::getId).containsExactly(inContent);
	}

	@Test
	@DisplayName("searchType=TITLE_CONTENT 는 제목·본문 중 하나라도 포함하면 돌려준다")
	void 제목_본문_검색() {
		Long inTitle = save(607L, "둘다검색작성자", "공지가 제목에", "본문은 평범하다").getId();
		Long inContent = save(607L, "둘다검색작성자", "제목은 평범하다", "본문에 공지가").getId();
		save(607L, "둘다검색작성자", "어디에도 없다", "평범한 본문");

		Page<Post> page = search(condition(SearchType.TITLE_CONTENT, "공지", PostSortType.latest));

		assertThat(page.getContent()).extracting(Post::getId)
				.containsExactlyInAnyOrder(inTitle, inContent);
	}

	/** {@code writer_nickname} 기준이다. member 를 조회하지 않고 스냅샷을 본다 (§3.1). */
	@Test
	@DisplayName("searchType=WRITER 는 writer_nickname 기준이다")
	void 작성자_검색() {
		Long written = save(608L, "홍길동", "작성자 검색 대상", "본문").getId();
		save(608L, "다른사람", "작성자 검색 비대상", "홍길동이 본문에 있다");

		Page<Post> page = search(condition(SearchType.WRITER, "홍길동", PostSortType.latest));

		assertThat(page.getContent()).extracting(Post::getId).containsExactly(written);
	}

	/**
	 * 이 행이 보는 것은 <b>돌아온 목록</b>이다. 결과가 한 페이지에 다 들어가므로
	 * {@code totalElements} 는 보정된 값과 구별되지 않고 건수 쿼리를 보지 않는다
	 * (sp-docs/process/review-policy.md §4.1) — 조건 없는 건수 쿼리는
	 * {@link #열다섯건의_첫_페이지()} 가 꽉 찬 페이지로 본다.
	 */
	@Test
	@DisplayName("키워드가 없으면 조건을 적용하지 않고 전체를 돌려준다")
	void 키워드_없으면_전체() {
		assertNoRowsFromOtherClasses();
		save(609L, "전체조회작성자", "공지 포함", "본문");
		save(609L, "전체조회작성자", "공지 없음", "본문");
		save(610L, "다른작성자", "또 다른 글", "본문");

		Page<Post> page = search(condition(0, DEFAULT_SIZE));

		assertThat(page.getContent()).hasSize(3);
		assertThat(page.getTotalElements()).isEqualTo(3);
	}

	// --- 헬퍼 ---

	private Page<Post> search(PostSearchCondition condition) {
		return this.postQueryRepository.search(condition);
	}

	private Page<Post> searchByWriter(long writerId, PostSearchCondition condition) {
		return this.postQueryRepository.searchByWriter(writerId, condition);
	}

	/**
	 * 두 페이지를 이어 받아 <b>합집합이 전체와 같고 교집합이 빈다</b>는 것을 본다 — 그것이
	 * "중복·누락 없음" 이다 (sp-docs/api-contract.md §7.1). 순서가 결정적이라는 단언만으로는
	 * 두 페이지에 같은 행이 실리는 것을 잡지 못한다.
	 *
	 * <p>이어 붙인 목록이 {@code id} 내림차순인 것도 함께 본다. 1차 키가 전부 동률이므로
	 * <b>남는 순서는 마지막 정렬 키가 정한 것뿐</b>이고, §4.1 이 그것을 {@code id DESC} 로
	 * 고정했다.
	 */
	private void assertStablePageBoundary(String nickname, PostSortType sort, List<Long> all) {
		assertStablePageBoundary(page -> search(PostSearchCondition.of(
				page, TIED_PAGE_SIZE, sort, SearchType.WRITER, nickname)), all);
	}

	/**
	 * 목록을 가리는 조건과 무관한 부분이다. 검색 목록과 내가 쓴 글 목록이 <b>같은 정렬</b>을
	 * 쓰므로 (두 쪽이 한 메서드를 지난다) 단언을 복제하지 않고 조회만 달리 넘긴다.
	 *
	 * @param pageAt 페이지 번호를 받아 그 페이지를 돌려주는 조회
	 */
	private void assertStablePageBoundary(IntFunction<Page<Post>> pageAt, List<Long> all) {
		List<Long> firstPage = idsOf(pageAt.apply(0));
		List<Long> secondPage = idsOf(pageAt.apply(1));

		assertThat(firstPage).hasSize(TIED_PAGE_SIZE);
		assertThat(secondPage).hasSize(TIED_FIXTURE_COUNT - TIED_PAGE_SIZE);
		assertThat(firstPage)
				.as("같은 행이 두 페이지에 실리지 않는다")
				.doesNotContainAnyElementsOf(secondPage);

		List<Long> merged = new ArrayList<>(firstPage);
		merged.addAll(secondPage);
		assertThat(merged)
				.as("두 페이지를 합치면 전체가 된다 — 어느 페이지에도 없는 행이 생기지 않는다")
				.containsExactlyInAnyOrderElementsOf(all);
		assertThat(merged)
				.as("1차 키가 전부 동률이므로 남는 순서는 마지막 정렬 키뿐이고 그것은 id DESC 다")
				.isSortedAccordingTo(Comparator.reverseOrder());
	}

	private static List<Long> idsOf(Page<Post> page) {
		return page.getContent().stream().map(Post::getId).toList();
	}

	private static PostSearchCondition condition(int page, int size) {
		return PostSearchCondition.of(page, size, PostSortType.latest, null, null);
	}

	/** 다른 테스트 픽스처와 섞이지 않게 작성자로 범위를 좁히는 조건이다. */
	private static PostSearchCondition condition(
			SearchType searchType, String keyword, PostSortType sort) {
		return PostSearchCondition.of(0, DEFAULT_SIZE, sort, searchType, keyword);
	}

	private Post save(long writerId, String writerNickname, String title, String content) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(writerNickname)
				.title(title)
				.content(content)
				.build());
	}

	private static Post viewed(Post post, int times) {
		for (int i = 0; i < times; i++) {
			post.increaseViewCount();
		}
		return post;
	}

	/**
	 * 한 작성자의 픽스처를 모두 같은 시각으로 묶는다 — 동률을 재현하는 유일한 방법이다.
	 *
	 * <p>{@code created_at} 은 {@code @Column(updatable = false)} 이고 감사가 채우므로 JPA 로는
	 * 바꿀 수 없다. 네이티브로 박은 뒤 영속성 컨텍스트를 비워 조회가 DB 값을 읽게 한다.
	 * {@link #setCreatedAt} 도 같은 이유로 네이티브다.
	 */
	private void setCreatedAtForWriter(long writerId, LocalDateTime createdAt) {
		this.em.getEntityManager()
				.createNativeQuery("update post set created_at = :createdAt where writer_id = :writerId")
				.setParameter("createdAt", createdAt)
				.setParameter("writerId", writerId)
				.executeUpdate();
		this.em.flush();
		this.em.clear();
	}

	/** 한 행의 시각만 박는다. 네이티브인 이유는 {@link #setCreatedAtForWriter} 와 같다. */
	private void setCreatedAt(Long id, LocalDateTime createdAt) {
		this.em.getEntityManager()
				.createNativeQuery("update post set created_at = :createdAt where id = :id")
				.setParameter("createdAt", createdAt)
				.setParameter("id", id)
				.executeUpdate();
		this.em.flush();
		this.em.clear();
	}

	private void assertNoRowsFromOtherClasses() {
		List<Long> leftovers = this.postRepository.findAll().stream().map(Post::getId).toList();
		assertThat(leftovers)
				.as("이 테스트는 테이블 전체를 센다. 자기 대역을 지우지 않는 테스트 클래스가 "
						+ "있으면 기대 건수가 어긋난다 (sp-docs/tech-stack.md §5.3)")
				.isEmpty();
	}

}
