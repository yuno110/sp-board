package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import com.example.board.attachment.entity.Attachment;
import com.example.board.attachment.repository.AttachmentRepository;
import com.example.board.like.dto.LikeResponse;
import com.example.board.like.entity.PostLike;
import com.example.board.like.repository.PostLikeRepository;
import com.example.board.like.service.LikeService;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.repository.PostViewRepository;
import com.example.board.post.support.PostViewCleanup;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code src/main/resources/logback-spring.xml} 이 정상 경합의 UNIQUE 충돌에 Hibernate 가 남기는
 * ERROR 줄만 버리는지 <b>실제 Hibernate 출력</b>으로 본다 (sp-docs/plan/phase2-board.md B-26,
 * sp-docs/conventions.md §8.1, sp-docs/tech-stack.md §4.5).
 *
 * <p><b>필터를 손으로 붙이지 않는다.</b> 받는 쪽은 {@code SqlExceptionHelper} 로거에 단
 * {@link ListAppender} 하나이고, 필터는 Boot 가 읽은 설정 파일이 컨텍스트에 건 것이다 — TurboFilter 는
 * appender 로 가기 전에 로거 컨텍스트에서 판정하므로 버린 줄은 이 appender 에도 오지 않는다. 그래서
 * 설정 파일을 지우거나 필터 줄을 뺀 변이에서 두 충돌의 「ERROR 0」 이 빨개진다.
 *
 * <p><b>WARN 과 다른 ERROR 를 함께 단언한다.</b> 로거를 끄거나 수준을 올린 변이에서는 「ERROR 0」 이
 * 녹색이다 — 실패할 수 없는 단언이 되지 않게(sp-docs/process/review-policy.md §4.1) 같은 충돌의 WARN
 * 한 줄과, 다른 제약 위반의 ERROR 한 줄이 남는 것을 본다.
 *
 * <p>로거 이름은 리터럴로 적는다 — 필터의 상수를 가져다 쓰면 그 상수를 바꾼 변이에서 받는 쪽이 함께
 * 움직인다.
 *
 * <p>픽스처의 {@code writerId}·{@code accountId} 는 2601~2619 를 쓴다. 같은 JVM 의 모든 테스트가
 * 하나의 H2 DB 를 보므로 (sp-docs/tech-stack.md §5.3) 2026-11-20 에 테스트 소스 전체에서 그 값을 쓰는
 * 곳이 없는 것을 확인하고 골랐다. {@code @AfterEach} 가 그 대역의 글과 그 글의 행만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogbackSpringConfigTest {

	private static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

	/** H2 의 UNIQUE·FK 위반이 남기는 WARN 줄 (D-25 리뷰의 H2 2.3.232 프로브). */
	private static final String H2_UNIQUE_WARN = "SQL Error: 23505, SQLState: 23505";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LikeService likeService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private AttachmentRepository attachmentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 판정의 존재 확인에 끼워넣기를 거는 자리다 — B-21 의 {@code PostControllerTest} 와 같다. */
	@MockitoSpyBean
	private PostViewRepository postViewRepository;

	/** 좋아요의 확인을 「없음」 으로 바꿔 INSERT 를 실제 UNIQUE 에 걸리게 한다 — B-20 의 끼워넣기다. */
	@MockitoSpyBean
	private PostLikeRepository postLikeRepository;

	/**
	 * 판정 행의 정리 작업을 대역으로 바꾼다. 정리는 스케줄러 스레드에서 판정 리포지터리 스파이를
	 * 부르므로 스텁을 거는 동안 그 호출이 끼면 스텁이 그 호출에 걸린다 ({@code PostControllerTest} 의
	 * 같은 필드 주석, 2026-11-17 B-21 실측).
	 */
	@MockitoBean
	private PostViewCleanup postViewCleanup;

	private final ListAppender<ILoggingEvent> 받은_줄 = new ListAppender<>();

	private Logger sqlExceptionHelper;

	@BeforeEach
	void 로거에_받는_쪽을_단다() {
		this.sqlExceptionHelper = (Logger) LoggerFactory.getLogger(SQL_EXCEPTION_HELPER);
		this.받은_줄.start();
		this.sqlExceptionHelper.addAppender(this.받은_줄);
	}

	@AfterEach
	void 정리() {
		this.sqlExceptionHelper.detachAppender(this.받은_줄);
		this.받은_줄.stop();
		this.jdbcTemplate.update("DELETE FROM post_view WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 2601 AND 2619)");
		this.jdbcTemplate.update("DELETE FROM post_like WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 2601 AND 2619)");
		this.jdbcTemplate.update("DELETE FROM attachment WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 2601 AND 2619)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 2601 AND 2619");
	}

	/**
	 * 검증 표 <b>「판정 행의 UNIQUE 충돌(B-21 의 끼워넣기)」</b>. 존재 확인의 대역이 같은 키의 판정
	 * 행과 +1 을 다른 스레드에서 커밋한 뒤 「없음」 을 돌려준다 — 그러면 이 요청의 INSERT 가
	 * {@code uk_post_view_post_viewer} 에 실제로 걸린다. 응답·집계는 B-21 의 기대 그대로다.
	 */
	@Test
	@DisplayName("판정 행의 UNIQUE 충돌은 그 로거의 ERROR 를 남기지 않고 WARN 한 줄은 남긴다")
	void 판정_행의_UNIQUE_충돌은_ERROR_를_남기지_않는다() throws Exception {
		Post post = 게시글을_만든다(2601L);
		AtomicInteger 끼운_행 = new AtomicInteger(-1);
		willAnswer(invocation -> {
			끼운_행.set(다른_스레드에서_같은_키로_센다(invocation.getArgument(0), invocation.getArgument(1)));
			return false;
		}).given(this.postViewRepository).existsByPostIdAndViewerKey(anyLong(), anyString());

		this.mockMvc.perform(get("/api/v1/posts/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(끼운_행.get()).as("끼운 판정 행이 먼저 커밋돼야 이 요청의 INSERT 가 충돌한다").isEqualTo(1);
		assertThat(커밋된_조회수(post)).as("충돌한 요청은 세지 않는다").isEqualTo(1L);
		assertThat(수준별(Level.ERROR)).as("정상 경합의 충돌은 ERROR 가 아니다 (§8.1)").isEmpty();
		assertThat(수준별(Level.WARN)).as("충돌의 흔적인 WARN 한 줄은 남는다 (§8.1)")
				.containsExactly(H2_UNIQUE_WARN);
	}

	/**
	 * 검증 표 <b>「좋아요의 UNIQUE 충돌(B-20 의 끼워넣기)」</b>. 행을 실제로 두고 락 뒤의 확인만
	 * 「없음」 으로 바꾼다 — INSERT 가 {@code uk_post_like_post_account} 에 실제로 걸린다. 응답·집계는
	 * B-20 의 기대 그대로다.
	 */
	@Test
	@DisplayName("좋아요의 UNIQUE 충돌은 그 로거의 ERROR 를 남기지 않고 WARN 한 줄은 남긴다")
	void 좋아요의_UNIQUE_충돌은_ERROR_를_남기지_않는다() {
		Post post = 게시글을_만든다(2602L);
		this.jdbcTemplate.update("INSERT INTO post_like (post_id, account_id, created_at) VALUES (?, ?, ?)",
				post.getId(), 2603L, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
		this.jdbcTemplate.update("UPDATE post SET like_count = 1 WHERE id = ?", post.getId());
		willReturn(false).given(this.postLikeRepository).existsByPostIdAndAccountId(post.getId(), 2603L);

		LikeResponse response = this.likeService.like(post.getId(), 2603L);

		assertThat(response).isEqualTo(new LikeResponse(true, 1));
		assertThat(this.jdbcTemplate.queryForObject("SELECT like_count FROM post WHERE id = ?",
				Integer.class, post.getId())).as("그 요청의 증가가 남지 않는다").isEqualTo(1);
		assertThat(수준별(Level.ERROR)).as("정상 경합의 충돌은 ERROR 가 아니다 (§8.1)").isEmpty();
		assertThat(수준별(Level.WARN)).as("충돌의 흔적인 WARN 한 줄은 남는다 (§8.1)")
				.containsExactly(H2_UNIQUE_WARN);
	}

	/**
	 * 검증 표 <b>「{@code uk_attachment_storage_key} 의 중복 INSERT → 그 로거의 ERROR 1」</b>. 잡지
	 * 않는 제약의 위반은 그대로 ERROR 다 — 필터를 「중복 키 위반 문면 전부」 로 넓힌 변이를 이 줄이
	 * 잡는다. 저장 키는 지어낸 값이다.
	 */
	@Test
	@DisplayName("다른 UNIQUE 제약(uk_attachment_storage_key)의 위반은 그 로거의 ERROR 로 남는다")
	void 다른_UNIQUE_제약의_위반은_ERROR_로_남는다() {
		Post post = 게시글을_만든다(2604L);
		String 같은_키 = "00000000-0000-4000-8000-000000002604";
		this.attachmentRepository.saveAndFlush(첨부(post, 같은_키));

		assertThatThrownBy(() -> this.attachmentRepository.saveAndFlush(첨부(post, 같은_키)))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(수준별(Level.ERROR)).as("잡지 않는 제약의 위반은 ERROR 로 남는다 (§8.1)")
				.singleElement(STRING)
				.containsIgnoringCase("uk_attachment_storage_key");
		assertThat(수준별(Level.WARN)).containsExactly(H2_UNIQUE_WARN);
	}

	/**
	 * 검증 표 <b>「{@code fk_post_like_post_id} 의 위반 → 그 로거의 ERROR 1」</b>. Hibernate 를 거쳐
	 * 낸다 — 없는 글을 참조해 {@code saveAndFlush}. {@code JdbcTemplate} 은
	 * {@code SqlExceptionHelper} 를 지나지 않는다. 필터를 「그 로거의 ERROR 전부」 로 넓힌 변이를 이
	 * 줄이 잡는다.
	 */
	@Test
	@DisplayName("FK 위반(fk_post_like_post_id)은 그 로거의 ERROR 로 남는다")
	void FK_위반은_ERROR_로_남는다() {
		long 없는_글 = 9_000_002_605L;

		assertThatThrownBy(() -> this.postLikeRepository.saveAndFlush(PostLike.builder()
				.post(this.postRepository.getReferenceById(없는_글))
				.accountId(2605L)
				.build()))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(수준별(Level.ERROR)).as("다른 SQL 오류는 ERROR 로 남는다 (§8.1)")
				.singleElement(STRING)
				.containsIgnoringCase("fk_post_like_post_id");
		assertThat(수준별(Level.WARN)).as("WARN 줄도 그대로다").hasSize(1);
	}

	/**
	 * 검증 표 <b>「루트 logger 의 appender → 콘솔만」</b>. Boot 의 기본 출력을 바꾸지 않는다 —
	 * {@code base.xml} 을 포함하면 파일 appender 가 함께 달린다 (sp-docs/tech-stack.md §4.5).
	 */
	@Test
	@DisplayName("루트 logger 에는 콘솔 appender 하나만 있고 수준은 INFO 다")
	void 루트에는_콘솔만_있다() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
		for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext();) {
			appenders.add(it.next());
		}

		assertThat(appenders).singleElement().isInstanceOf(ConsoleAppender.class);
		assertThat(root.getLevel()).isEqualTo(Level.INFO);
	}

	// --- 도우미 ---

	private List<String> 수준별(Level level) {
		return this.받은_줄.list.stream()
				.filter(event -> event.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	private Post 게시글을_만든다(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("로그작성자")
				.title("로그 대상")
				.content("본문")
				.build());
	}

	private static Attachment 첨부(Post post, String storageKey) {
		return Attachment.builder()
				.post(post)
				.storageKey(storageKey)
				.originalFilename("a.png")
				.contentType("image/png")
				.size(1L)
				.build();
	}

	private long 커밋된_조회수(Post post) {
		Long count = this.jdbcTemplate.queryForObject(
				"SELECT view_count FROM post WHERE id = ?", Long.class, post.getId());
		return count == null ? 0L : count;
	}

	/**
	 * 같은 키의 판정 행과 +1 을 <b>다른 스레드에서</b> 커밋한다 — 부르는 스레드에는 판정 트랜잭션이
	 * 묶여 있어 같은 스레드의 {@code JdbcTemplate} 은 그 트랜잭션에 참여한다.
	 */
	private int 다른_스레드에서_같은_키로_센다(Long postId, String viewerKey) {
		ExecutorService 다른_스레드 = Executors.newSingleThreadExecutor();
		try {
			return 다른_스레드.submit(() -> {
				int inserted = this.jdbcTemplate.update(
						"INSERT INTO post_view (post_id, viewer_key, viewed_at) VALUES (?, ?, ?)",
						postId, viewerKey, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
				this.jdbcTemplate.update("UPDATE post SET view_count = view_count + 1 WHERE id = ?", postId);
				return inserted;
			}).get(10, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("끼운 쓰기가 커밋되지 않았다 — 이 측정은 충돌을 볼 수 없다", ex);
		}
		finally {
			다른_스레드.shutdownNow();
		}
	}

}
