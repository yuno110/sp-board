package com.example.board.post.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.post.dto.PostDetailResponse;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.service.PostService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * sp-docs/plan/phase2-board.md B-21 의 완료 기준 중 판정의 순서·창의 경계·트랜잭션을 본다. HTTP 쪽
 * 검증표는 {@link com.example.board.post.controller.PostControllerTest}, 동시성은
 * {@link com.example.board.post.service.PostServiceTest} 가 본다.
 *
 * <p>정본은 sp-docs/requirements/board.md §9.2 §9.3, sp-docs/domain-model.md §4.5 다.
 *
 * <p><b>판정 시각을 인자로 준다.</b> 실제 시계의 열흘 뒤를 초 단위로 자른 값이다. 창의 경계를 초
 * 단위로 정확히 놓을 수 있고, 판정 시각 대신 DB 의 {@code NOW()} 나 실제 시계를 쓰는 구현이 그
 * 자리에서 어긋난다. 스케줄러가 돌리는 정리({@link PostViewCleanup#run})는 실제 시계의 24시간
 * 전보다 이른 행을 지우므로 이 픽스처를 지우지 않는다. <b>24시간은 리터럴로 적는다</b> — 판정의
 * 상수를 가져다 쓰면 그 상수를 바꾼 변이에서 픽스처가 함께 움직인다.
 *
 * <p><b>문장과 물리 트랜잭션을 한 줄로 기록한다.</b> {@link StatementRecorder} 가 테스트 스레드의
 * SQL 을 모으고, 같은 수집기가 트랜잭션 매니저의 실행 리스너로서 물리 트랜잭션의 시작·커밋·되돌림을
 * 같은 목록에 표시한다. {@code isNewTransaction()} 이 거짓인 것은 참여한 트랜잭션이라 표시하지 않는다.
 * 기록을 테스트 스레드로 한정하는 것은 정리 작업처럼 다른 스레드가 같은 시간에 낸 문장이 섞이지 않게
 * 하려는 것이다.
 *
 * <p><b>매니저를 스파이로 바꾸지 않는다.</b> 정리 작업({@link PostViewCleanup})이 스케줄러 스레드에서
 * 같은 매니저로 트랜잭션을 열므로, 스파이에 스텁을 거는 동안 그 스레드가 스파이를 부를 수 있다. 이
 * 클래스가 매니저 스파이에 스텁을 걸어 경계를 기록하던 판이 정리를 5ms 주기로 돌린 프로브에서 스텁을
 * 거는 줄의 {@code UnfinishedStubbingException} 으로 빨갰다 (2026-11-17 B-21 리뷰·수정 실측).
 * 리스너는 스텁이 없어 그 호출과 겨루지 않는다.
 *
 * <p>픽스처의 {@code writerId} 는 1281~1299 를 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3) 2026-11-16 에 테스트 소스 전체에서 그 값을 쓰는 곳이 없는
 * 것을 확인하고 골랐다. {@code @AfterEach} 가 그 대역의 글과 그 글의 판정 행만 지운다 —
 * {@code post_view.post_id} 에는 FK 가 없어 글을 지워도 판정 행이 남는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(classes = ViewCountJudgeTest.StatementRecorderConfig.class)
class ViewCountJudgeTest {

	/** 판정 시각. 실제 시계의 열흘 뒤 — 이유는 클래스 주석에 있다. */
	private static final LocalDateTime 판정_시각 =
			LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);

	private static final String 시작 = "#begin";

	private static final String 커밋 = "#commit";

	private static final String 되돌림 = "#rollback";

	@Autowired
	private ViewCountJudge viewCountJudge;

	@Autowired
	private PostService postService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StatementRecorder 수집기;

	@AfterEach
	void 픽스처_정리() {
		this.수집기.멈춘다();
		this.jdbcTemplate.update("DELETE FROM post_view WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 1281 AND 1299)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1281 AND 1299");
	}

	/**
	 * <b>행이 없으면 §9.3 의 순서대로 간다</b> — 조건부 UPDATE(0 행) → 존재 확인(없음) → INSERT →
	 * 좁은 UPDATE 로 +1. 네 문장이 <b>한 물리 트랜잭션</b>이다 — +1 이 판정 트랜잭션 안이라는 완료
	 * 기준이 그것이다 (sp-docs/requirements/board.md §9.3).
	 *
	 * <p>{@code INSERT IGNORE}·{@code ON DUPLICATE KEY UPDATE} 를 쓰지 않는다는 기준은 INSERT 문장의
	 * 모양으로 본다 (§9.3).
	 *
	 * <p>기준 시각은 판정 시각이다 — 앱 시계의 값이 그대로 저장되고 DB 의 {@code NOW()} 가 아니다
	 * (§9.2).
	 */
	@Test
	@DisplayName("행이 없으면 조건부 UPDATE·존재 확인·INSERT·+1 순서로 한 트랜잭션에서 센다")
	void 행이_없으면_조건부_UPDATE_존재_확인_INSERT_순서로_센다() {
		Post post = 게시글을_만든다(1281L);
		기록을_시작한다();

		this.viewCountJudge.judge(post.getId(), "a:1282", 판정_시각);

		List<String> 기록 = this.수집기.전부();
		assertThat(문장_종류(기록))
				.as("판정 순서 — sp-docs/requirements/board.md §9.3")
				.containsExactly(시작, "조건부 UPDATE", "존재 확인", "INSERT", "+1", 커밋);
		assertThat(기록)
				.as("INSERT IGNORE·ON DUPLICATE KEY UPDATE 로 판정하지 않는다 (§9.3)")
				.noneMatch(sql -> sql.contains("ignore") || sql.contains("on duplicate"));
		assertThat(커밋된_조회수(post)).isEqualTo(1L);
		assertThat(기준_시각(post, "a:1282")).as("앱 시계의 판정 시각이 기준이 된다").isEqualTo(판정_시각);
	}

	/**
	 * <b>기준 시각이 정확히 24시간 전이면 창이 지난 조회다</b> — §9.3 의 조건 「기준 시각이 24시간 전
	 * 이하」 의 경계다. 조건부 UPDATE 가 1 행이므로 존재 확인과 INSERT 를 거치지 않고, 같은 트랜잭션에서
	 * +1 하며 기준 시각이 판정 시각이 된다 (sp-docs/requirements/board.md §9.2 의 표).
	 */
	@Test
	@DisplayName("기준 시각이 정확히 24시간 전이면 조건부 UPDATE 하나로 세고 기준 시각을 판정 시각으로 옮긴다")
	void 기준_시각이_정확히_24시간_전이면_센다() {
		Post post = 게시글을_만든다(1283L);
		판정_행을_둔다(post, "a:1284", 판정_시각.minusHours(24));
		기록을_시작한다();

		this.viewCountJudge.judge(post.getId(), "a:1284", 판정_시각);

		assertThat(문장_종류(this.수집기.전부()))
				.containsExactly(시작, "조건부 UPDATE", "+1", 커밋);
		assertThat(커밋된_조회수(post)).isEqualTo(1L);
		assertThat(기준_시각(post, "a:1284")).as("그 시각이 새 기준이 된다").isEqualTo(판정_시각);
		assertThat(판정_행_수(post)).as("행을 새로 만들지 않고 옮긴다").isEqualTo(1);
	}

	/**
	 * <b>24시간에서 1초 모자라면 창 안이다</b> — 조건부 UPDATE 가 0 행이고 존재 확인이 행을 찾아 INSERT
	 * 없이 끝난다. <b>창을 밀지 않는다</b> — 기준 시각이 그대로다 (sp-docs/requirements/board.md
	 * §9.2).
	 */
	@Test
	@DisplayName("기준 시각이 24시간에서 1초 모자라면 세지 않고 INSERT 도 없고 기준 시각을 밀지 않는다")
	void 창_안의_조회는_세지_않고_기준_시각을_밀지_않는다() {
		Post post = 게시글을_만든다(1285L);
		LocalDateTime 처음_기준 = 판정_시각.minusHours(24).plusSeconds(1);
		판정_행을_둔다(post, "a:1286", 처음_기준);
		기록을_시작한다();

		this.viewCountJudge.judge(post.getId(), "a:1286", 판정_시각);

		assertThat(문장_종류(this.수집기.전부()))
				.containsExactly(시작, "조건부 UPDATE", "존재 확인", 커밋);
		assertThat(커밋된_조회수(post)).isZero();
		assertThat(기준_시각(post, "a:1286")).isEqualTo(처음_기준);
	}

	/**
	 * <b>UNIQUE 가 아닌 무결성 위반은 삼키지 않는다</b> (sp-docs/AGENTS.md 불변식 9). 판정 키 칸
	 * ({@code varchar(80)}, sp-docs/domain-model.md §4.5)보다 긴 키로 INSERT 를 실패시킨다 — 판정 키를
	 * 만드는 쪽은 이런 키를 만들지 않으므로 대역이다. 삼키면 실패가 「세지 않은 조회」 로 위장된다.
	 */
	@Test
	@DisplayName("판정 INSERT 의 UNIQUE 가 아닌 무결성 위반은 그대로 던지고 아무것도 남기지 않는다")
	void UNIQUE_가_아닌_무결성_위반은_던진다() {
		Post post = 게시글을_만든다(1287L);
		String 너무_긴_키 = "a:" + "9".repeat(79);
		기록을_시작한다();

		assertThatThrownBy(() -> this.viewCountJudge.judge(post.getId(), 너무_긴_키, 판정_시각))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(문장_종류(this.수집기.전부()))
				.as("위반은 INSERT 에서 났다 — 그 앞의 문장에서 났다면 INSERT 의 예외 처리를 지나지 않는다")
				.containsExactly(시작, "조건부 UPDATE", "존재 확인", "INSERT", 되돌림);
		assertThat(판정_행_수(post)).isZero();
		assertThat(커밋된_조회수(post)).isZero();
	}

	/**
	 * <b>상세 조회는 판정 트랜잭션을 커밋한 뒤에 새 트랜잭션에서 응답을 읽는다</b>
	 * (sp-docs/requirements/board.md §9.3, sp-docs/api-contract.md §4.8). 완료 기준의 두 줄이다 —
	 * 판정이 상세 조회와 다른 트랜잭션이고, 응답의 {@code viewCount} 는 판정 커밋 뒤에 시작된 읽기의
	 * 값이다.
	 *
	 * <p><b>H2 의 결과로는 이 순서가 관측되지 않는다.</b> 테스트 연결의 격리 수준이 커밋된 읽기라서
	 * (B-21 실측) 판정 전에 연 트랜잭션도 뒤의 읽기에서 판정이 커밋한 값을 본다 — MySQL 의 반복 읽기와
	 * 다르다. 그래서 응답 값이 아니라 물리 트랜잭션의 경계를 본다
	 * (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p>응답을 읽은 문장은 {@code post} 의 {@code view_count} 를 읽는 SELECT 다. 404 판정의 SELECT
	 * 는 존재만 보므로 그 칸을 읽지 않는다.
	 */
	@Test
	@DisplayName("상세 조회는 판정 트랜잭션을 커밋한 뒤에 새 물리 트랜잭션에서 응답을 읽는다")
	void 판정이_커밋된_뒤에_응답의_읽기가_시작된다() {
		Post post = 게시글을_만든다(1288L);
		기록을_시작한다();

		PostDetailResponse detail = this.postService.getDetail(post.getId(), null, "192.0.2.1");

		List<String> 기록 = this.수집기.전부();
		int 판정_INSERT = 처음(기록, sql -> sql.startsWith("insert into post_view"));
		int 판정_시작 = 앞의_마지막(기록, 판정_INSERT, 시작);
		int 판정_끝 = 뒤의_처음_끝(기록, 판정_INSERT);
		assertThat(기록.get(판정_끝)).as("판정 트랜잭션은 커밋으로 끝난다").isEqualTo(커밋);
		assertThat(기록.subList(판정_시작, 판정_끝))
				.as("+1 이 판정 트랜잭션 안이다 (§9.3)")
				.anyMatch(sql -> sql.startsWith("update post "));

		int 응답_읽기 = 마지막(기록, sql -> sql.startsWith("select ")
				&& sql.contains(" from post ") && sql.contains("view_count"));
		int 응답_읽기_시작 = 앞의_마지막(기록, 응답_읽기, 시작);
		assertThat(응답_읽기_시작)
				.as("응답의 읽기는 판정이 커밋된 뒤에 시작된 물리 트랜잭션이다 — 판정 전에 연 트랜잭션이 아니다")
				.isGreaterThan(판정_끝);
		assertThat(detail.viewCount()).isEqualTo(1L);
	}

	// --- 헬퍼 ---

	/** 테스트 스레드의 문장과 물리 트랜잭션의 시작·끝을 기록하기 시작한다. */
	private void 기록을_시작한다() {
		this.수집기.시작한다();
	}

	private Post 게시글을_만든다(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("판정작성자")
				.title("판정 대상")
				.content("본문")
				.build());
	}

	/** 판정을 거치지 않고 판정 행을 둔다. 영향 행이 1 이 아니면 그 자리에서 터진다. */
	private void 판정_행을_둔다(Post post, String viewerKey, LocalDateTime viewedAt) {
		int inserted = this.jdbcTemplate.update(
				"INSERT INTO post_view (post_id, viewer_key, viewed_at) VALUES (?, ?, ?)",
				post.getId(), viewerKey, viewedAt);
		if (inserted != 1) {
			throw new IllegalStateException("판정 행 픽스처가 1 행이 아니다: " + inserted);
		}
	}

	private long 커밋된_조회수(Post post) {
		Long count = this.jdbcTemplate.queryForObject(
				"SELECT view_count FROM post WHERE id = ?", Long.class, post.getId());
		return count == null ? 0 : count;
	}

	private LocalDateTime 기준_시각(Post post, String viewerKey) {
		return this.jdbcTemplate.queryForObject(
				"SELECT viewed_at FROM post_view WHERE post_id = ? AND viewer_key = ?",
				LocalDateTime.class, post.getId(), viewerKey);
	}

	private int 판정_행_수(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post_view WHERE post_id = ?", Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	/** 기록을 판정의 문장 종류로 바꾼다. 표시는 그대로 두고 그 밖의 문장은 원문으로 남긴다. */
	private static List<String> 문장_종류(List<String> 기록) {
		return 기록.stream().map(ViewCountJudgeTest::종류).toList();
	}

	private static String 종류(String sql) {
		if (sql.startsWith("update post_view ")) {
			return "조건부 UPDATE";
		}
		if (sql.startsWith("select ") && sql.contains(" from post_view ")) {
			return "존재 확인";
		}
		if (sql.startsWith("insert into post_view ")) {
			return "INSERT";
		}
		if (sql.startsWith("update post ") && sql.contains("view_count")) {
			return "+1";
		}
		return sql;
	}

	private static int 처음(List<String> 기록, Predicate<String> 조건) {
		for (int i = 0; i < 기록.size(); i++) {
			if (조건.test(기록.get(i))) {
				return i;
			}
		}
		throw new AssertionError("기록에 찾는 문장이 없다 — 수집기가 꽂히지 않았거나 그 문장이 나가지 않았다: " + 기록);
	}

	private static int 마지막(List<String> 기록, Predicate<String> 조건) {
		for (int i = 기록.size() - 1; i >= 0; i--) {
			if (조건.test(기록.get(i))) {
				return i;
			}
		}
		throw new AssertionError("기록에 찾는 문장이 없다 — 수집기가 꽂히지 않았거나 그 문장이 나가지 않았다: " + 기록);
	}

	private static int 앞의_마지막(List<String> 기록, int 위치, String 표시) {
		for (int i = 위치 - 1; i >= 0; i--) {
			if (표시.equals(기록.get(i))) {
				return i;
			}
		}
		throw new AssertionError(위치 + " 앞에 " + 표시 + " 가 없다 — 그 문장이 물리 트랜잭션 밖에서 나갔다: " + 기록);
	}

	private static int 뒤의_처음_끝(List<String> 기록, int 위치) {
		for (int i = 위치 + 1; i < 기록.size(); i++) {
			if (커밋.equals(기록.get(i)) || 되돌림.equals(기록.get(i))) {
				return i;
			}
		}
		throw new AssertionError(위치 + " 뒤에 물리 트랜잭션의 끝이 없다: " + 기록);
	}

	/**
	 * 테스트 스레드의 SQL 과 물리 트랜잭션의 경계를 한 목록에 모은다. Hibernate 의
	 * {@code StatementInspector} 라 문장이 JDBC 로 넘어가기 전에 불린다.
	 *
	 * <p><b>로그·통계 설정에 기대지 않는다.</b> 그 설정이 꺼지면 조용히 빈 결과를 돌려주는 수단이 된다.
	 * 이 수집기는 테스트가 직접 꽂고, 꽂히지 않으면 단언이 빈 목록에서 터진다
	 * (sp-docs/process/review-policy.md §4.1). {@code PostServiceTest}·{@code LikeServiceTest} 에도
	 * SQL 을 모으는 수집기가 있다 — 다른 패키지의 package-private 이라 여기 둔다.
	 *
	 * <p><b>경계는 매니저가 알려 준다</b> — 이 수집기는 {@link TransactionExecutionListener} 이기도
	 * 하고, Boot 가 그 타입의 빈을 JPA 트랜잭션 매니저에 붙인다
	 * ({@link com.example.board.support.PhysicalCommitRecorder} 의 주석). 시작은 연 뒤에, 커밋·되돌림은
	 * 끝난 뒤에 표시한다. 되돌림 표시를 든 트랜잭션의 커밋은 매니저가 되돌리고 되돌림으로 알린다.
	 * 실패한 시작·커밋·되돌림은 표시하지 않는다.
	 */
	static class StatementRecorder implements StatementInspector, TransactionExecutionListener {

		private final List<String> 기록 = new CopyOnWriteArrayList<>();

		private volatile Thread 기록할_스레드;

		@Override
		public String inspect(String sql) {
			if (Thread.currentThread() == this.기록할_스레드) {
				this.기록.add(정규화(sql));
			}
			return sql;
		}

		/** 부르는 스레드의 문장만 기록하기 시작한다. 앞의 기록은 버린다. */
		void 시작한다() {
			this.기록.clear();
			this.기록할_스레드 = Thread.currentThread();
		}

		void 멈춘다() {
			this.기록할_스레드 = null;
			this.기록.clear();
		}

		List<String> 전부() {
			return List.copyOf(this.기록);
		}

		@Override
		public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
			if (beginFailure == null) {
				표시한다(transaction, 시작);
			}
		}

		@Override
		public void afterCommit(TransactionExecution transaction, @Nullable Throwable commitFailure) {
			if (commitFailure == null) {
				표시한다(transaction, 커밋);
			}
		}

		@Override
		public void afterRollback(TransactionExecution transaction, @Nullable Throwable rollbackFailure) {
			if (rollbackFailure == null) {
				표시한다(transaction, 되돌림);
			}
		}

		/** 참여한 트랜잭션은 물리 트랜잭션이 아니므로 표시하지 않는다. */
		private void 표시한다(TransactionExecution transaction, String 표시) {
			if (transaction.isNewTransaction() && Thread.currentThread() == this.기록할_스레드) {
				this.기록.add(표시);
			}
		}

		private static String 정규화(String sql) {
			return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
		}

	}

	/**
	 * {@link StatementRecorder} 를 Hibernate 에 꽂는다 — 트랜잭션 매니저에는 Boot 가 이 빈을 리스너로
	 * 붙인다. {@code @TestConfiguration} 이므로 {@code @SpringBootConfiguration} 탐색을 막지 않고
	 * 덧붙는다.
	 */
	@TestConfiguration
	static class StatementRecorderConfig {

		@Bean
		StatementRecorder statementRecorder() {
			return new StatementRecorder();
		}

		@Bean
		HibernatePropertiesCustomizer statementRecorderRegistration(StatementRecorder recorder) {
			return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, recorder);
		}

	}

}
