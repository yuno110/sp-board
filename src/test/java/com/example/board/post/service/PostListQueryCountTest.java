package com.example.board.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.global.common.PageResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostSearchCondition;
import com.example.board.post.dto.SearchType;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 목록 조회에 N+1 이 없다는 것의 <b>관측 수단</b>이다
 * (sp-docs/plan/phase1.md §6 B-09 의 완료 기준과 검증표의 "목록 조회 쿼리 수").
 *
 * <h2>쿼리 수를 예측값으로 쓰지 않는다</h2>
 *
 * <p>"2회" 같은 상수를 단언하면 페이징 구현이 바뀔 때마다 숫자가 낡고, 그 숫자가 맞는지는
 * 아무것도 증명하지 않는다 (sp-docs/process/review-policy.md §4.1 의 같은 금지다). 그래서
 * <b>같은 조건에서 행 수만 바꾼 두 측정이 같은지</b> 본다 — N+1 이 생기면 집계가 행 수에
 * 비례해 갈라진다.
 *
 * <h2>수단은 Hibernate 통계다</h2>
 *
 * <p>{@code hibernate.generate_statistics} 를 <b>테스트 프로파일에만</b> 켜고
 * ({@code src/test/resources/application-test.yml}) {@code getPrepareStatementCount()} 를
 * 읽는다. 측정 직전에 {@link Statistics#clear()} 로 비우므로 픽스처 저장이 섞이지 않는다.
 *
 * <p><b>이 수단이 실제로 N+1 을 잡는다</b>(실측). 서비스의 매핑에서 행마다
 * {@code postRepository.findByIdAndDeletedFalse} 를 부르는 변이를 넣으니 두 집계가
 * <b>5 와 32</b> 로 갈라졌다 — 올바른 구현에서는 둘 다 <b>2</b> 였다. 변이는 되돌렸다.
 *
 * <h2>두 목록을 따로 재는 이유 — 변환 지점이 공유가 아니다</h2>
 *
 * <p><b>조회 지점은 공유다.</b> 내가 쓴 글과 검색 목록이 {@code PostQueryRepository} 의 한
 * 페이징 메서드를 지나므로, 거기에 생긴 N+1 은 어느 쪽 측정으로도 잡힌다.
 *
 * <p><b>변환 지점은 공유가 아니다.</b> {@code PostService.getList} 와
 * {@code PostService.getMyPosts} 가 {@code .map(PostResponse::from)} <b>호출 자리를 각자</b>
 * 갖는다. 실측: 행당 파생 쿼리를 {@code getMyPosts} 쪽 매핑에 넣으면 아래 첫 테스트가
 * 갈라지지만 <b>두 번째는 통과하고</b>, {@code getList} 쪽 매핑에 넣으면 그 반대다. 한
 * 경로만 재면 <b>다른 경로의 매핑에 들어온 N+1 이 덮이지 않는다</b> — 그래서 두 측정은
 * 복제가 아니라 서로 다른 결함을 본다 (sp-docs/AGENTS.md 불변식 11).
 *
 * <p>덮이지 않는 것이 하나 남는다. {@code PostResponse.from} <b>자체</b>에 생긴 N+1 은 두
 * 측정이 함께 잡으므로 어느 쪽이 깨졌는지 가리지 못한다 — 그것은 이 측정의 몫이 아니다.
 *
 * <h2>지금은 N+1 이 날 자리가 없다 — 회귀 가드다</h2>
 *
 * <p>{@code Post} 에 컬렉션이 없고 {@code comment_count} 가 비정규화되어 있어
 * (sp-docs/domain-model.md §4.1) 집계 쿼리도 없다. 이 테스트는 <b>나중에 연관관계를 더하는
 * 사람이 목록을 깨뜨리는 것</b>을 잡는다 — 지연 로딩 컬렉션을 하나 더하면 매핑이 행마다
 * 쿼리를 내고 두 집계가 갈라진다.
 *
 * <p>픽스처의 {@code writerId} 는 881~889 를 쓴다. 이 클래스는 롤백에 기대지 않고 커밋하므로
 * ({@code @Transactional} 을 붙이면 조회가 픽스처와 같은 세션을 써서 측정이 달라진다)
 * {@code @AfterEach} 로 자기 대역만 지운다 (sp-docs/tech-stack.md §5.3).
 */
@SpringBootTest
@ActiveProfiles("test")
class PostListQueryCountTest {

	private static final long FEW_ROWS_WRITER = 881L;

	private static final long MANY_ROWS_WRITER = 882L;

	/**
	 * 검색 목록은 작성자 조건을 {@code writer_nickname} 부분 일치로 받으므로
	 * (sp-docs/api-contract.md §4.1) 두 픽스처의 닉네임이 서로의 부분 문자열이 아니어야 한다 —
	 * 그렇지 않으면 한 조회가 두 대역을 함께 세어 행 수가 달라지지 않는다.
	 */
	private static final String FEW_ROWS_NICKNAME = "쿼리수측정적은쪽";

	private static final String MANY_ROWS_NICKNAME = "쿼리수측정많은쪽";

	private static final int FEW_ROWS = 3;

	private static final int MANY_ROWS = 30;

	/**
	 * 두 측정이 모두 한 페이지에 다 들어가야 행 수가 실제로 달라진다. 기본 {@code size} 는
	 * 10 이므로 30건 쪽이 10건으로 잘려 두 측정의 차이가 7건으로 줄어든다. 50 이 상한이다
	 * (sp-docs/api-contract.md §4.1).
	 */
	private static final int PAGE_SIZE = 50;

	@Autowired
	private PostService postService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@AfterEach
	void 픽스처_정리() {
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 881 AND 889");
	}

	/**
	 * 내가 쓴 글 목록 (B-09). 행 수만 다른 두 조회의 statement 집계가 <b>같다.</b>
	 *
	 * <p>단언 셋이 각각 다른 것을 지킨다 — {@link #assertSameStatementCount} 의 주석에 있다.
	 */
	@Test
	@DisplayName("내가 쓴 글 목록의 statement 수가 게시글 수와 무관하게 같다")
	void 내_글_목록의_statement_수가_행_수와_무관하다() {
		saveFixtures();

		assertSameStatementCount(
				measure(() -> this.postService.getMyPosts(FEW_ROWS_WRITER, condition(null))),
				measure(() -> this.postService.getMyPosts(MANY_ROWS_WRITER, condition(null))));
	}

	/**
	 * 검색 목록 (B-06). <b>위 테스트가 이 경로를 덮지 않는다</b> — 변환 지점이 공유가 아니다
	 * (클래스 주석의 "두 목록을 따로 재는 이유").
	 *
	 * <p>작성자 조건으로 좁히는 것은 이 클래스의 두 대역을 가르기 위한 것이다. 조건 없이
	 * 전체를 조회하면 두 측정이 같은 행 집합을 받아 <b>행 수가 달라지지 않는다.</b>
	 */
	@Test
	@DisplayName("검색 목록의 statement 수가 게시글 수와 무관하게 같다")
	void 검색_목록의_statement_수가_행_수와_무관하다() {
		saveFixtures();

		assertSameStatementCount(
				measure(() -> this.postService.getList(condition(FEW_ROWS_NICKNAME))),
				measure(() -> this.postService.getList(condition(MANY_ROWS_NICKNAME))));
	}

	/**
	 * <ul>
	 * <li>돌아온 행 수가 <b>3 과 30 이라는 것</b> — 행 수가 실제로 달라지지 않았으면 집계가
	 * 같은 것은 아무 의미가 없다. 기대값은 상수가 아니라 리터럴이므로 픽스처 상수를 바꾸면
	 * 여기서 깨진다
	 * <li>집계가 <b>0 이 아니라는 것</b> — {@code generate_statistics} 가 꺼지면 두 집계가
	 * 모두 0 이 되어 마지막 단언이 조용히 통과한다. 수단 자체가 살아 있는지를 이것이 본다
	 * (sp-docs/process/review-policy.md §4.1 의 "실패할 수 없는 단언")
	 * <li>두 집계가 <b>같다는 것</b> — N+1 이 없다는 것이다
	 * </ul>
	 */
	private void assertSameStatementCount(Measurement few, Measurement many) {
		assertThat(few.rows())
				.as("행 수가 달라지지 않으면 집계가 같은 것이 N+1 의 부재를 뜻하지 않는다")
				.isEqualTo(3);
		assertThat(many.rows()).isEqualTo(30);
		assertThat(few.statements())
				.as("통계가 꺼져 있으면 두 집계가 모두 0 이고 아래 단언이 무의미해진다")
				.isPositive();
		assertThat(many.statements())
				.as("행 수가 열 배인데 statement 가 늘면 N+1 이다 "
						+ "(sp-docs/nfr.md §1, sp-docs/plan/phase1.md §6 B-09)")
				.isEqualTo(few.statements());
	}

	/**
	 * 한 번의 목록 조회가 발행한 statement 수와 돌아온 행 수.
	 *
	 * <p>{@code clear()} 를 조회 <b>직전</b>에 한다 — 픽스처 저장의 INSERT 가 섞이면 집계가
	 * 행 수에 비례하게 되어 올바른 구현에서도 갈라진다.
	 */
	private Measurement measure(ListQuery query) {
		Statistics statistics = this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		PageResponse<PostResponse> response = query.fetch();

		return new Measurement(response.content().size(), statistics.getPrepareStatementCount());
	}

	/** {@code nickname} 이 {@code null} 이면 검색 조건이 없는 조건이다 (내가 쓴 글 목록용). */
	private static PostSearchCondition condition(String nickname) {
		return PostSearchCondition.of(0, PAGE_SIZE, null,
				nickname == null ? null : SearchType.WRITER, nickname);
	}

	private void saveFixtures() {
		savePosts(FEW_ROWS_WRITER, FEW_ROWS_NICKNAME, FEW_ROWS);
		savePosts(MANY_ROWS_WRITER, MANY_ROWS_NICKNAME, MANY_ROWS);
	}

	private void savePosts(long writerId, String nickname, int count) {
		for (int i = 0; i < count; i++) {
			this.postRepository.saveAndFlush(Post.builder()
					.writerId(writerId)
					.writerNickname(nickname)
					.title("쿼리 수 측정 " + i)
					.content("본문")
					.build());
		}
	}

	@FunctionalInterface
	private interface ListQuery {
		PageResponse<PostResponse> fetch();
	}

	private record Measurement(int rows, long statements) {
	}

}
