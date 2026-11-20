package com.example.board.attachment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.board.attachment.dto.AttachmentResponse;
import com.example.board.attachment.repository.AttachmentRepository;
import com.example.board.attachment.storage.AttachmentStorage;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * sp-docs/plan/phase2-board.md B-23 검증 표 중 HTTP 로 만들 수 없는 경로다 — 동시 올리기, INSERT 가
 * 실패하는 경로, 판정과 조건부 UPDATE 사이에 지워진 글. B-24 검증 표의 같은 첨부의 동시 지우기도
 * 여기다. 상태 코드·응답·판정 순서는
 * {@link com.example.board.attachment.controller.AttachmentControllerTest} 가 본다.
 *
 * <p>정본은 sp-docs/domain-model.md §6.2 (흐름·상한의 원자적 판정·실패하면 쓴 파일을 지운다),
 * sp-docs/requirements/board.md §11 이다.
 *
 * <p>리포지터리는 H2 를 그대로 쓴다 — 상한은 커밋된 행과 집계를 봐야 확인된다.
 * {@link AttachmentRepository}·{@link AttachmentStorage} 는 스파이다. 대역으로 바꾸는 것은 INSERT
 * 의 실패와, 파일 쓰기에서 스레드를 세우는 집결지뿐이고 나머지는 실제 빈에 위임된다. 커밋된 값은
 * {@code JdbcTemplate} 으로 읽는다 — 서비스가 스스로 커밋한다.
 *
 * <p><b>저장 루트는 테스트마다 비운 임시 디렉터리다</b> (sp-docs/tech-stack.md §4.5). 「남은 파일」 은
 * 그 디렉터리를 실제로 세어 본다.
 *
 * <p>픽스처의 {@code writerId} 는 1401~1449 를 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3) 2026-11-18 에 테스트 소스 전체에서 그 값을 쓰는 곳이 없는
 * 것을 확인하고 골랐다. {@code @AfterEach} 가 자기 대역의 글과 그 글의 첨부만 지운다 — 첨부를
 * 먼저 지운다({@code fk_attachment_post_id}).
 */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentServiceTest {

	/** 글당 활성 첨부의 상한. 정본: sp-docs/requirements/board.md §11 의 「개수」 줄. 리터럴이다. */
	private static final int 상한 = 10;

	/** 동시 요청 수. 하나만 성공하고 나머지가 상한에 걸리려면 둘 이상이어야 한다. */
	private static final int 동시_요청 = 6;

	/** 집결지·출발선을 기다리는 한도. 넘으면 끼워넣기가 강제되지 않은 것이므로 터진다. */
	private static final int 대기_한도_초 = 10;

	/** PNG 머리 (sp-docs/requirements/board.md §11 의 표) 뒤에 이미지가 아닌 바이트. */
	private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("attachment.storage-root", () -> storageRoot.toString());
		// Hibernate 가 이 이름으로 인스턴스를 만든다 — 훅은 정적 필드라 테스트가 건다
		registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
				PostUpdateBarrier.class::getName);
	}

	@Autowired
	private AttachmentService attachmentService;

	@Autowired
	private PostRepository postRepository;

	@MockitoSpyBean
	private AttachmentRepository attachmentRepository;

	@MockitoSpyBean
	private AttachmentStorage attachmentStorage;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void 저장_루트를_비운다() throws IOException {
		for (Path entry : 저장된_파일()) {
			Files.delete(entry);
		}
	}

	@AfterEach
	void 픽스처_정리() {
		PostUpdateBarrier.푼다();
		this.jdbcTemplate.update("DELETE FROM attachment WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 1401 AND 1449)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1401 AND 1449");
	}

	/**
	 * 검증 표 <b>「상한 하나 아래에서 동시 올리기 N건 → 성공 1, 나머지 409 {@code AT003},
	 * {@code attachment_count} 와 활성 행이 상한, 남은 파일 수 = 행 수」</b> (sp-docs/domain-model.md
	 * §6.2 — 상한은 조건부 UPDATE 하나가 판정한다).
	 *
	 * <p><b>끼워넣기를 강제한다.</b> 스레드는 출발선({@link CountDownLatch})에서 함께 출발하고, 판정을
	 * 모두 지나 파일 쓰기에 들어가는 자리({@link AttachmentStorage#store})에서 집결지를 기다린다 — 모든
	 * 요청이 판정을 지난 뒤에야 첫 파일 쓰기와 그 뒤의 UPDATE 가 시작된다. 그래서 판정 단계(글 읽기)에
	 * 상한 검사를 두는 변이는 모든 요청을 통과시켜 상한을 넘긴다.
	 *
	 * <p>상한 아래의 9개는 파일과 행을 함께 둔다 — 「남은 파일 수 = 행 수」 가 그 9개를 포함해 성립한다.
	 */
	@Test
	@DisplayName("활성 첨부 9개인 글에 동시에 올리면 하나만 성공하고 나머지는 AT003, 집계·활성 행·파일이 모두 10 이다")
	void 동시_올리기는_상한을_넘지_않는다() throws Exception {
		Post post = 게시글을_만든다(1401L);
		파일과_첨부를_둔다(post, 상한 - 1);
		CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
		willAnswer(invocation -> {
			집결지.await(대기_한도_초, TimeUnit.SECONDS);
			return invocation.callRealMethod();
		}).given(this.attachmentStorage).store(any());

		List<Object> 결과 = 동시에_올린다(post, 1401L);

		List<AttachmentResponse> 성공 = 결과.stream()
				.filter(AttachmentResponse.class::isInstance).map(AttachmentResponse.class::cast).toList();
		List<Object> 실패 = 결과.stream().filter(r -> !(r instanceof AttachmentResponse)).toList();
		assertThat(성공).as("상한 하나 아래에서는 하나만 들어간다").hasSize(1);
		assertThat(실패).hasSize(동시_요청 - 1).allSatisfy(failure -> assertThat(failure)
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED)));
		assertThat(커밋된_집계(post)).isEqualTo(상한);
		assertThat(활성_첨부_수(post)).isEqualTo(상한);
		assertThat(저장된_파일()).as("상한에 걸린 요청이 쓴 파일은 지워진다 — 남은 파일 수 = 행 수")
				.hasSize(활성_첨부_수(post));
		assertThat(저장된_파일()).as("남은 파일은 모두 행의 storage_key 다")
				.extracting(path -> path.getFileName().toString())
				.containsExactlyInAnyOrderElementsOf(this.jdbcTemplate.queryForList(
						"SELECT storage_key FROM attachment WHERE post_id = ?", String.class, post.getId()));
	}

	/**
	 * 검증 표 <b>「INSERT 가 실패하는 경로 → 쓴 파일이 지워진다」</b> (sp-docs/domain-model.md §6.2 의
	 * 「실패하면 0)의 파일을 지운다」). 같은 트랜잭션의 조건부 UPDATE 도 되돌아간다 — 집계와 근거 행의
	 * 쓰기는 한 트랜잭션이다 (§6).
	 *
	 * <p><b>파일이 실제로 쓰였다가 지워졌는지 본다.</b> 쓰지 않아서 없는 것과 구별하려고 저장소가 만든
	 * 이름과 지우기에 넘어간 이름이 같은지 확인한다.
	 */
	@Test
	@DisplayName("INSERT 가 실패하면 쓴 파일을 지우고 집계 증가도 되돌아가며 원래 예외가 나간다")
	void INSERT_가_실패하면_쓴_파일을_지운다() throws Exception {
		Post post = 게시글을_만든다(1402L);
		DataIntegrityViolationException insertFailure = new DataIntegrityViolationException("INSERT 실패 대역");
		willThrow(insertFailure).given(this.attachmentRepository).saveAndFlush(any());
		AtomicReference<String> 쓴_이름 = new AtomicReference<>();
		willAnswer(invocation -> {
			String storageKey = (String) invocation.callRealMethod();
			쓴_이름.set(storageKey);
			return storageKey;
		}).given(this.attachmentStorage).store(any());

		Throwable thrown = catchThrowable(() -> this.attachmentService.upload(post.getId(), 1402L, 파일()));

		assertThat(thrown).as("지우기가 원래의 실패를 바꾸지 않는다").isSameAs(insertFailure);
		assertThat(쓴_이름.get()).as("파일은 판정을 지나 실제로 쓰였다").isNotNull();
		then(this.attachmentStorage).should().delete(쓴_이름.get());
		assertThat(저장된_파일()).as("쓴 파일이 지워졌다").isEmpty();
		assertThat(커밋된_집계(post)).as("조건부 UPDATE 도 되돌아갔다").isZero();
		assertThat(활성_첨부_수(post)).isZero();
	}

	/**
	 * 판정(글 확인)과 조건부 UPDATE 사이에 글이 지워진 경로 — sp-docs/domain-model.md §6.2 의 「1)의
	 * 404 는 그 확인과 커밋 사이에 글이 지워진 경우다」. UPDATE 가 0 행이고 글이 없으므로 409 가 아니라
	 * 404 {@code P001} 이고, 쓴 파일을 지운다. 글은 파일 쓰기 도중에 다른 커넥션으로 지운다.
	 */
	@Test
	@DisplayName("판정 뒤 UPDATE 전에 글이 지워지면 404 P001 이고 쓴 파일을 지운다")
	void 판정_뒤에_지워진_글은_P001() throws Exception {
		Post post = 게시글을_만든다(1403L);
		willAnswer(invocation -> {
			this.jdbcTemplate.update("UPDATE post SET deleted = TRUE WHERE id = ?", post.getId());
			return invocation.callRealMethod();
		}).given(this.attachmentStorage).store(any());

		Throwable thrown = catchThrowable(() -> this.attachmentService.upload(post.getId(), 1403L, 파일()));

		assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
		then(this.attachmentStorage).should().store(any());
		assertThat(저장된_파일()).isEmpty();
		assertThat(커밋된_집계(post)).isZero();
		assertThat(활성_첨부_수(post)).isZero();
	}

	/**
	 * B-25 검증 표 <b>「첨부 올리기의 발행 순서 — {@code update post} 가 {@code insert into attachment}
	 * 보다 먼저다」</b> (sp-docs/domain-model.md §6.2 의 1)·2)). 1) 이 {@code post} 행의 배타 락을 먼저
	 * 잡아야 2) 의 FK 공유 락과 교착하지 않는다 — 2026-11-19 I-07 의 두 연결 프로브에서 순서를 뒤집으면
	 * 실 MySQL 이 5/5 교착했다. H2 는 그 교착을 재현하지 않으므로 결과가 아니라 발행된 문장의 순서를
	 * 본다 — 순서를 뒤집은 변이가 B-23 의 테스트를 모두 통과했던 빈틈이다.
	 *
	 * <p>두 쓰기는 한 트랜잭션의 문장이고 테스트 스레드에서 나간다. 판정의 글 읽기(SELECT)는 셈하지
	 * 않는다.
	 */
	@Test
	@DisplayName("올리기는 update post(attachment_count) 를 insert into attachment 보다 먼저 발행한다")
	void 올리기는_집계_UPDATE_를_INSERT_보다_먼저_발행한다() throws Exception {
		Post post = 게시글을_만든다(1406L);
		PostUpdateBarrier.기록한다();

		this.attachmentService.upload(post.getId(), 1406L, 파일());

		assertThat(PostUpdateBarrier.기록된_문장().stream()
				.filter(sql -> sql.startsWith("update post ") || sql.startsWith("insert into attachment "))
				.map(sql -> sql.startsWith("update post ") ? "update post" : "insert into attachment")
				.toList())
				.as("sp-docs/domain-model.md §6.2 — 집계의 조건부 UPDATE 가 먼저, INSERT 가 뒤다")
				.containsExactly("update post", "insert into attachment");
		assertThat(PostUpdateBarrier.기록된_문장())
				.as("집계 UPDATE 는 attachment_count 를 올리는 문장이다")
				.anyMatch(sql -> sql.startsWith("update post ") && sql.contains("attachment_count"));
	}

	/**
	 * B-24 검증 표 <b>「같은 첨부의 동시 지우기 N건 — 판정 읽기가 어느 쓰기보다 먼저 일어나게 강제한다 →
	 * 성공 1, 나머지 404 {@code AT001}, {@code attachment_count} 가 1 만 준다」</b>
	 * (sp-docs/domain-model.md §6.2 — 첨부의 조건부 전환이 최종 판정이다).
	 *
	 * <p><b>끼워넣기를 강제한다.</b> 스레드는 출발선에서 함께 출발하고, 판정 읽기를 지나 첫 쓰기
	 * ({@code update post …}, 집계 감소)가 발행되기 직전에 집결지를 기다린다 — 모든 요청이 그 첨부를
	 * 활성으로 읽은 뒤에야 첫 쓰기가 실행된다. 그래서 전환에 {@code deleted = false} 조건이 없거나 영향 행
	 * 수를 보지 않는 변이는 모든 요청을 성공시켜 집계를 여러 번 줄인다.
	 *
	 * <p>집계를 활성 첨부 수(3)로 둔다 — 0 에 닿지 않으므로 「1 만 준다」 가 감소의 0 행 가드에 가려지지
	 * 않는다. 파일은 어느 경우에도 지우지 않는다 (sp-docs/requirements/board.md §11).
	 */
	@Test
	@DisplayName("같은 첨부를 동시에 지우면 하나만 204 이고 나머지는 AT001, 집계는 한 번만 준다")
	void 동시_지우기는_집계를_한_번만_줄인다() throws Exception {
		Post post = 게시글을_만든다(1404L);
		파일과_첨부를_둔다(post, 3);
		long 대상 = this.jdbcTemplate.queryForObject(
				"SELECT MIN(id) FROM attachment WHERE post_id = ?", Long.class, post.getId());
		CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
		PostUpdateBarrier.건다(() -> {
			try {
				집결지.await(대기_한도_초, TimeUnit.SECONDS);
			}
			catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
				throw new IllegalStateException("첫 쓰기 직전에 모든 스레드가 모이지 않았다 — 끼워넣기가 "
						+ "강제되지 않았으므로 이 측정은 이중 감소를 볼 수 없다", ex);
			}
		});

		List<Object> 결과 = 동시에_실행한다(() -> {
			this.attachmentService.delete(대상, 1404L);
			return 성공;
		});

		assertThat(결과).filteredOn(성공::equals).as("같은 첨부는 한 번만 지워진다").hasSize(1);
		assertThat(결과).filteredOn(r -> !성공.equals(r)).hasSize(동시_요청 - 1)
				.allSatisfy(failure -> assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ATTACHMENT_NOT_FOUND)));
		assertThat(PostUpdateBarrier.통과한_스레드_수())
				.as("모든 요청이 판정을 지나 첫 쓰기에 닿았다 — 강제가 일어났다").isEqualTo(동시_요청);
		assertThat(커밋된_집계(post)).as("집계가 1 만 준다").isEqualTo(2);
		assertThat(활성_첨부_수(post)).isEqualTo(2);
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT deleted FROM attachment WHERE id = ?", Boolean.class, 대상)).isTrue();
		assertThat(저장된_파일()).as("파일은 지우지 않는다").hasSize(3);
		then(this.attachmentStorage).should(never()).delete(any());
	}

	/**
	 * B-24 검증 표 「집계가 0 인데 활성 첨부를 지우기 → WARN」 의 동시 판이다. <b>WARN 은 전환이 1 행인
	 * 요청에서만 정확히 1 회</b>이고, 지는 요청은 남기지 않는다 — sp-docs/domain-model.md §6.2 의 WARN
	 * 조건(1) 이 0 행이고 2) 가 1 행, 2026-11-19 {@code D-23})을 고정한다.
	 *
	 * <p>집계가 0 이므로 모든 요청의 감소가 0 행이다. 그래서 WARN 을 감소 직후에 남기는 변이는 요청 수만큼
	 * 남긴다. 끼워넣기는 위 테스트와 같다 — 모든 요청이 첨부를 활성으로 읽은 뒤에야 첫 쓰기가 실행된다.
	 */
	@Test
	@DisplayName("집계 0 인 글의 같은 활성 첨부를 동시에 지우면 하나만 204 이고 WARN 은 정확히 1 회다")
	void 집계_0_의_동시_지우기는_WARN_이_한_번이다() throws Exception {
		Post post = 게시글을_만든다(1405L);
		파일과_첨부를_둔다(post, 1);
		this.jdbcTemplate.update("UPDATE post SET attachment_count = 0 WHERE id = ?", post.getId());
		long 대상 = this.jdbcTemplate.queryForObject(
				"SELECT id FROM attachment WHERE post_id = ?", Long.class, post.getId());
		CyclicBarrier 집결지 = new CyclicBarrier(동시_요청);
		PostUpdateBarrier.건다(() -> {
			try {
				집결지.await(대기_한도_초, TimeUnit.SECONDS);
			}
			catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
				throw new IllegalStateException("첫 쓰기 직전에 모든 스레드가 모이지 않았다", ex);
			}
		});

		Logger logger = (Logger) LoggerFactory.getLogger(AttachmentService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level original = logger.getLevel();
		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);
		List<Object> 결과;
		try {
			결과 = 동시에_실행한다(() -> {
				this.attachmentService.delete(대상, 1405L);
				return 성공;
			});
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(original);
			appender.stop();
		}

		assertThat(결과).filteredOn(성공::equals).hasSize(1);
		assertThat(결과).filteredOn(r -> !성공.equals(r)).hasSize(동시_요청 - 1)
				.allSatisfy(failure -> assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ATTACHMENT_NOT_FOUND)));
		assertThat(PostUpdateBarrier.통과한_스레드_수()).isEqualTo(동시_요청);
		assertThat(appender.list).extracting(ILoggingEvent::getLevel)
				.as("어긋난 상태는 전환에 성공한 요청 하나만 알린다 — 되돌아간 요청은 WARN 을 남기지 않는다")
				.containsExactly(Level.WARN);
		assertThat(커밋된_집계(post)).as("음수로 내리지 않는다").isZero();
		assertThat(활성_첨부_수(post)).isZero();
	}

	// --- 도구 ---

	/** 동시 지우기의 성공 표지. 지우기는 돌려주는 값이 없다. */
	private static final String 성공 = "204";

	/**
	 * {@code 동시_요청} 개의 스레드가 출발선에서 함께 출발해 같은 작업을 돌린다. 결과는 성공이면 작업이
	 * 돌려준 값, 실패면 그 예외다. 한도 안에 끝나지 않은 요청은 {@link TimeoutException} 으로 담긴다.
	 * 스레드 이름은 {@link PostUpdateBarrier#스레드_접두어} 로 시작한다 — 훅은 그 스레드에서만 돈다.
	 */
	private List<Object> 동시에_실행한다(Callable<Object> 작업) throws InterruptedException {
		AtomicInteger 번호 = new AtomicInteger();
		ExecutorService 풀 = Executors.newFixedThreadPool(동시_요청,
				runnable -> new Thread(runnable, PostUpdateBarrier.스레드_접두어 + 번호.incrementAndGet()));
		CountDownLatch 출발선 = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < 동시_요청; i++) {
				futures.add(풀.submit(() -> {
					if (!출발선.await(대기_한도_초, TimeUnit.SECONDS)) {
						throw new IllegalStateException("출발 신호가 오지 않았다");
					}
					return 작업.call();
				}));
			}
			출발선.countDown();

			List<Object> 결과 = new ArrayList<>();
			for (Future<Object> future : futures) {
				try {
					결과.add(future.get(대기_한도_초 * 3L, TimeUnit.SECONDS));
				}
				catch (ExecutionException ex) {
					결과.add(ex.getCause());
				}
				catch (TimeoutException ex) {
					결과.add(ex);
				}
			}
			return 결과;
		}
		finally {
			풀.shutdownNow();
		}
	}

	/**
	 * {@code update post …} 가 발행되기 직전에 훅을 돌리는 Hibernate {@link StatementInspector}. 한
	 * 스레드의 문장을 순서대로 모으기도 한다({@link #기록한다}, B-25 의 발행 순서 테스트).
	 * {@code @DynamicPropertySource} 가 클래스 이름으로 꽂고 Hibernate 가 인스턴스를 만들므로 훅은 정적
	 * 필드다.
	 *
	 * <p><b>{@link #스레드_접두어} 로 시작하는 스레드에서만, 스레드마다 한 번만 돈다.</b> 다른 스레드(예:
	 * 판정 행 정리의 스케줄러, B-21)의 문장에 훅이 걸리지 않게 하고, 한 스레드가 두 번 모여 집결지를
	 * 다시 닫지 않게 한다. 훅이 걸려 있지 않으면 문장을 그대로 돌려줄 뿐이다.
	 */
	public static class PostUpdateBarrier implements StatementInspector {

		static final String 스레드_접두어 = "b24-동시-지우기-";

		private static volatile Runnable 훅;

		private static final Set<String> 통과한_스레드 = ConcurrentHashMap.newKeySet();

		/** {@link #기록한다} 를 부른 스레드. 훅과 별개로 그 스레드의 문장만 모은다. */
		private static volatile Thread 기록할_스레드;

		private static final List<String> 기록 = new CopyOnWriteArrayList<>();

		/**
		 * 부르는 스레드가 내는 문장을 모으기 시작한다 — 훅을 걸지 않는다. Hibernate 에는 inspector 를
		 * 하나만 꽂을 수 있어 이 클래스가 두 역할을 한다 (B-25).
		 */
		static void 기록한다() {
			기록.clear();
			기록할_스레드 = Thread.currentThread();
		}

		static List<String> 기록된_문장() {
			return List.copyOf(기록);
		}

		static void 건다(Runnable 걸_훅) {
			통과한_스레드.clear();
			훅 = 걸_훅;
		}

		static void 푼다() {
			훅 = null;
			통과한_스레드.clear();
			기록할_스레드 = null;
			기록.clear();
		}

		static int 통과한_스레드_수() {
			return 통과한_스레드.size();
		}

		@Override
		public String inspect(String sql) {
			if (Thread.currentThread() == 기록할_스레드) {
				기록.add(sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim());
			}
			Runnable 지금_훅 = 훅;
			String 스레드 = Thread.currentThread().getName();
			if (지금_훅 != null && 스레드.startsWith(스레드_접두어)
					&& sql.toLowerCase(Locale.ROOT).trim().startsWith("update post")
					&& 통과한_스레드.add(스레드)) {
				지금_훅.run();
			}
			return sql;
		}

	}

	/**
	 * {@code 동시_요청} 개의 스레드가 출발선에서 함께 출발해 같은 글에 올린다. 결과는 성공이면
	 * {@link AttachmentResponse}, 실패면 그 예외다. 한도 안에 끝나지 않은 요청은 {@link TimeoutException}
	 * 으로 담긴다.
	 */
	private List<Object> 동시에_올린다(Post post, long accountId) throws InterruptedException {
		ExecutorService 풀 = Executors.newFixedThreadPool(동시_요청);
		CountDownLatch 출발선 = new CountDownLatch(1);
		try {
			List<Future<AttachmentResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 동시_요청; i++) {
				futures.add(풀.submit(() -> {
					if (!출발선.await(대기_한도_초, TimeUnit.SECONDS)) {
						throw new IllegalStateException("출발 신호가 오지 않았다");
					}
					return this.attachmentService.upload(post.getId(), accountId, 파일());
				}));
			}
			출발선.countDown();

			List<Object> 결과 = new ArrayList<>();
			for (Future<AttachmentResponse> future : futures) {
				try {
					결과.add(future.get(대기_한도_초 * 3L, TimeUnit.SECONDS));
				}
				catch (ExecutionException ex) {
					결과.add(ex.getCause());
				}
				catch (TimeoutException ex) {
					결과.add(ex);
				}
			}
			return 결과;
		}
		finally {
			풀.shutdownNow();
		}
	}

	private static MockMultipartFile 파일() {
		return new MockMultipartFile("file", "a.png", "image/png", PNG);
	}

	private Post 게시글을_만든다(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("첨부 대상")
				.content("본문")
				.build());
	}

	/**
	 * 활성 첨부 {@code count} 개를 파일과 행으로 두고 집계를 그 수로 맞춘다 — 근거 행·집계·파일이 같은
	 * 상태다. 서비스를 거치지 않는다 — 서비스에 넣은 변이가 픽스처까지 바꾸지 않게 한다.
	 */
	private void 파일과_첨부를_둔다(Post post, int count) throws IOException {
		for (int i = 0; i < count; i++) {
			String storageKey = UUID.randomUUID().toString();
			Files.write(storageRoot.resolve(storageKey), PNG);
			this.jdbcTemplate.update("INSERT INTO attachment (post_id, storage_key, original_filename, "
							+ "content_type, size, deleted, created_at, updated_at) "
							+ "VALUES (?, ?, ?, 'image/png', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
					post.getId(), storageKey, "fixture-" + i + ".png", PNG.length);
		}
		int updated = this.jdbcTemplate.update(
				"UPDATE post SET attachment_count = ? WHERE id = ?", count, post.getId());
		if (updated != 1) {
			throw new IllegalStateException("attachment_count 픽스처가 1 행이 아니다: " + updated);
		}
	}

	private int 커밋된_집계(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT attachment_count FROM post WHERE id = ?", Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	private int 활성_첨부_수(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM attachment WHERE post_id = ? AND deleted = FALSE",
				Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	private static List<Path> 저장된_파일() throws IOException {
		try (Stream<Path> entries = Files.list(storageRoot)) {
			return entries.toList();
		}
	}

}
