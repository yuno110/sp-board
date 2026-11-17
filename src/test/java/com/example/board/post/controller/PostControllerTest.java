package com.example.board.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.client.MemberClient;
import com.example.board.global.error.BusinessException;
import com.example.board.post.entity.Post;
import com.example.board.post.entity.PostCategory;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.repository.PostViewRepository;
import com.example.board.post.support.PostViewCleanup;
import com.example.board.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * sp-docs/plan/phase1.md §6 B-05 의 "검증" 표 중 HTTP 쪽을 테스트로 옮긴 것이다. 저장된 값과
 * 조회수는 {@link com.example.board.post.service.PostServiceTest} 도 본다.
 *
 * <p>정본은 sp-docs/api-contract.md §4 (경로·성공 코드) §4.2 §4.3 (응답의 두 모양) §5 §5.1
 * (실패 판정) §8.3 (에러 코드), 입력 검증은 sp-docs/requirements/board.md §4 다.
 *
 * <p><b>실제 {@link MemberClient} 를 쓰고 member 의 응답만 스텁 서버로 흉내 낸다.</b> 검증표의
 * 네 줄(프로필 미등록 · 탈퇴 · 타임아웃 · 404)이 서로 다른 것은 <b>member 가 돌려준 응답</b>
 * 뿐이므로, 목으로 예외를 던지면 네 줄이 같은 테스트가 된다. 스텁 서버를 쓰면 "member 응답 →
 * 판정 → HTTP 응답 → 저장 안 됨" 이 한 줄에서 끝까지 관측된다. 응답과 {@code ErrorCode} 의
 * 대응만 보는 좁은 테스트는 B-04 의 {@code MemberClientTest} 다.
 *
 * <p>스텁 응답은 §5 의 봉투를 그대로 쓴다
 * ({@code {"success": true, "data": {"members": [...]}, "error": null}}) — 배열이 최상위가
 * 아니다.
 *
 * <p>픽스처의 {@code writerId} 는 작성·상세(B-05)가 521~539, 목록·검색(B-06)이 621~639,
 * 수정·삭제(B-07)가 741~759, 내가 쓴 글(B-09)이 861~869 를 쓴다. 이미 쓰이는 대역(201~210,
 * 301~317, 401~402, {@code PostServiceTest} 의 501~519·701~729,
 * {@code PostQueryRepositoryTest} 의 601~619)과 겹치지 않게 둔다
 * (sp-docs/tech-stack.md §5.3 — 같은 JVM 의 모든 테스트가 한 H2 DB 를 본다).
 *
 * <p>B-19 의 테스트는 그 대역 안의 527~534·628~630·755~756·869 를 쓰고 각 대역의
 * {@code @AfterEach} 가 지운다. 533·534·756 은 2026-11-16 에, 나머지는 2026-11-13 에 테스트 소스
 * 전체에서 그 값을 쓰는 곳이 없는 것을 확인하고 골랐다.
 *
 * <p>B-21 의 상세 조회 테스트(sp-docs/plan/phase2-board.md B-21 의 검증표)는 1201~1249 를 쓴다.
 * 2026-11-16 에 테스트 소스 전체에서 그 값을 쓰는 곳이 없는 것을 확인하고 골랐다. 그 대역의 글은
 * 좋아요 행을 가질 수 있어({@code fk_post_like_post_id}) {@code @AfterEach} 가 그것을 먼저 지우고,
 * 상세 조회가 남긴 판정 행도 먼저 지운다 — {@code post_view.post_id} 에는 FK 가 없어 글을 지워도
 * 남는다.
 *
 * <p><b>목록 쪽 단언은 검색 조건으로 범위를 좁힌다.</b> 이 클래스는 롤백에 기대지 않고 커밋하므로
 * 테이블 전체를 세는 단언은 다른 클래스의 픽스처에 흔들린다. 전체 건수를 보는 것은 롤백되는
 * {@code PostQueryRepositoryTest} 가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostControllerTest {

	private static final String PATH = "/api/v1/posts";

	private static final long ACCOUNT_ID = 521L;

	/** sp-docs/api-contract.md §6 의 {@code role} claim 값. */
	private static final String ROLE = "USER";

	private static final String ROLE_ADMIN = "ADMIN";

	/** member 의 내부 API 가 돌려주는 닉네임. 스냅샷의 유일한 출처다. */
	private static final String MEMBER_NICKNAME = "member가준닉네임";

	/** 정본: sp-docs/api-contract.md §5. 벌크 하나뿐이다. */
	private static final String BULK_PATH = "/internal/v1/members/bulk";

	/**
	 * 상세 응답의 키 집합. <b>리터럴로 적는다</b> — DTO 의 필드에서 뽑아 오면 필드가 늘거나 줄 때
	 * 기대값이 함께 움직여 단언이 아무것도 보지 못한다.
	 *
	 * <p><b>sp-docs/api-contract.md §4.3 의 상세 줄 전체가 아니다.</b> 목록 항목의 키에
	 * {@code content}·{@code liked} 를 더한 것이고, 그 줄의 {@code attachments} 는 B-21
	 * (2026-11-16) 시점에 싣지 않았다 — 계획이 그것을 B-24 에 배정했다
	 * (sp-docs/plan/phase2-board.md 의 B-24).
	 */
	private static final Set<String> DETAIL_KEYS = Set.of(
			"id", "title", "category", "content", "writerId", "writerNickname",
			"viewCount", "commentCount", "likeCount", "liked", "createdAt");

	/**
	 * <b>목록 항목</b>의 키 집합. sp-docs/api-contract.md §4.3 의 <b>목록 줄 그대로다</b>
	 * (§9.3 의 예시). {@code DETAIL_KEYS} 와 같은 이유로 리터럴이다.
	 */
	private static final Set<String> LIST_ITEM_KEYS = Set.of(
			"id", "title", "category", "writerId", "writerNickname",
			"viewCount", "commentCount", "likeCount", "createdAt");

	/** sp-docs/api-contract.md §7.1 의 페이징 봉투 키 집합. 여기도 리터럴이다. */
	private static final Set<String> PAGE_KEYS = Set.of(
			"content", "page", "size", "totalElements", "totalPages", "first", "last");

	private static final HttpServer STUB = startStub();

	private static final AtomicReference<StubResponder> RESPONDER = new AtomicReference<>();

	/** 스텁이 받은 요청 수. "member 호출 0회" 를 관측하는 지점이다. */
	private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();

	/** 스텁이 받은 마지막 요청 본문. 조회 키가 무엇이었는지를 본다. */
	private static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();

	@DynamicPropertySource
	static void testEnvironment(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
		registry.add("member-service.url", () -> "http://localhost:" + STUB.getAddress().getPort());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * 조회수 판정의 존재 확인에 끼워넣기를 거는 자리다 (B-21). 대역으로 바꾸지 않은 메서드는 실제 빈에
	 * 위임되므로 다른 테스트에는 그대로다.
	 */
	@MockitoSpyBean
	private PostViewRepository postViewRepository;

	/**
	 * 판정 행의 정리 작업을 대역으로 바꾼다 — 이 컨텍스트에서는 판정 행을 지우지 않는다.
	 *
	 * <p>정리 작업은 컨텍스트가 뜬 직후부터 주기마다 스케줄러 스레드에서 {@code postViewRepository} 의
	 * 삭제를 부른다 ({@link PostViewCleanup#run}). 이 클래스는 그 스파이에 스텁을 걸므로, 스텁을 거는
	 * 동안 그 호출이 끼면 스텁이 그 호출에 걸린다 — 매니저 스파이로 커밋을 세던 테스트가 정리를 5ms
	 * 주기로 돌린 프로브에서 스텁을 거는 줄의 {@code UnfinishedStubbingException} 으로 빨갰다
	 * (2026-11-17 B-21 리뷰·수정 실측). 스텁을 거는 동작 자체가 겨루므로 스텁의 답에서 스레드를
	 * 걸러서는 막지 못한다. 정리 작업 자체(지우는 행·등록된 주기·삭제의 예외)는
	 * {@code PostViewCleanupTest} 가 본다.
	 */
	@MockitoBean
	private PostViewCleanup postViewCleanup;

	/** 비로그인 판정 키의 기대값을 계산할 비밀키를 읽는다 — 컨텍스트의 설정값 문자열 그대로다. */
	@Autowired
	private Environment environment;

	@BeforeEach
	void 스텁_초기화() {
		REQUEST_COUNT.set(0);
		LAST_REQUEST_BODY.set(null);
		respondWithActiveProfile(ACCOUNT_ID);
	}

	@AfterEach
	void 픽스처_정리() {
		// post_view.post_id 에는 FK 가 없어 글을 지워도 판정 행이 남는다 — 글보다 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM post_view WHERE post_id IN (SELECT id FROM post "
				+ "WHERE writer_id BETWEEN 521 AND 539 OR writer_id BETWEEN 1201 AND 1249)");
		// post_like.post_id 에 FK 가 있으므로 (fk_post_like_post_id) 좋아요를 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM post_like WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 1201 AND 1249)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1201 AND 1249");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 521 AND 539");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 621 AND 639");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 741 AND 759");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 861 AND 869");
	}

	// --- 작성 (P-01, sp-docs/api-contract.md §4) ---

	@Test
	@DisplayName("정상 작성은 201 이고 상세 모양을 돌려준다")
	void 정상_작성은_201() throws Exception {
		MvcResult result = this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": "정상 작성", "content": "본문입니다"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.title").value("정상 작성"))
				.andExpect(jsonPath("$.data.content").value("본문입니다"))
				.andExpect(jsonPath("$.data.writerId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.data.writerNickname").value(MEMBER_NICKNAME))
				.andExpect(jsonPath("$.data.viewCount").value(0))
				.andExpect(jsonPath("$.data.commentCount").value(0))
				.andReturn();

		assertThat(dataKeys(result))
				.as("작성 응답도 §4.3 의 상세 모양을 쓴다. 세 번째 모양을 만들지 않는다")
				.isEqualTo(DETAIL_KEYS);
		assertThat(this.postRepository.findById(dataId(result)).orElseThrow().getWriterNickname())
				.isEqualTo(MEMBER_NICKNAME);
	}

	/**
	 * 요청 본문의 {@code writerId}·{@code nickname} 은 무시된다
	 * (sp-docs/requirements/board.md §3 규칙 1·2, sp-docs/architecture.md §5 의 금지 표).
	 *
	 * <p><b>그 값으로 조회하지도 않는다</b> (§3 규칙 3). 스텁이 받은 요청 본문을 보면 조회 키가
	 * 드러난다 — 본문의 999 로 조회하는 구현이면 {@code accountIds} 에 999 가 실린다.
	 */
	@Test
	@DisplayName("본문의 writerId·nickname 은 무시되고 조회 키도 JWT sub 다")
	void 본문의_작성자_정보는_무시된다() throws Exception {
		MvcResult result = this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": "위조 시도", "content": "본문",
								 "writerId": 999, "nickname": "가짜"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.writerId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.data.writerNickname").value(MEMBER_NICKNAME))
				.andReturn();

		Post saved = this.postRepository.findById(dataId(result)).orElseThrow();
		assertThat(saved.getWriterId()).isEqualTo(ACCOUNT_ID);
		assertThat(saved.getWriterNickname()).isEqualTo(MEMBER_NICKNAME);
		assertThat(LAST_REQUEST_BODY.get())
				.as("조회 키는 JWT 의 sub 다. 본문의 999 로 조회하지 않는다")
				.isEqualTo("{\"accountIds\":[%d]}".formatted(ACCOUNT_ID));
	}

	@Test
	@DisplayName("토큰 없이 작성하면 401 A001 이고 저장되지 않는다")
	void 토큰_없이_작성하면_401() throws Exception {
		this.mockMvc.perform(post(PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": "무토큰", "content": "본문"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));

		assertThat(rowCountByTitle("무토큰")).isZero();
		assertThat(REQUEST_COUNT.get())
				.as("인가에서 끊기므로 member 를 호출할 자리가 없다")
				.isZero();
	}

	/**
	 * 검증표 "{@code role = BOGUS} 로 {@code POST /posts} → 401 {@code A002}".
	 * <b>전에는 201 로 글이 생성됐다</b> — {@code role} 의 값을 보지 않았다 (I-04 리뷰 실측,
	 * sp-docs/plan/phase1.md §6 B-12). 서명이 유효해도 §6 의 열거 밖 값은 계약 위반 토큰이다
	 * (sp-docs/api-contract.md §6).
	 *
	 * <p>거부는 {@code @CurrentMember} 리졸버가 한다 (sp-docs/tech-stack.md §2.1 의 집행 지점
	 * 표) — 인자 해석에서 끊기므로 member 를 호출할 자리에 닿지 않는다.
	 */
	@Test
	@DisplayName("role 이 열거 밖인 토큰으로 작성하면 401 A002 이고 저장되지 않는다")
	void 열거_밖_role_로_작성하면_401_A002() throws Exception {
		// 프로필을 활성으로 스텁한다 — 그러지 않으면 403 S002 가 나서 401 이 아닌 이유가
		// role 인지 프로필인지 갈리지 않는다 (고치기 전 이 요청은 201 이었다)
		respondWithActiveProfile(526L);

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(526L, "BOGUS"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("열거 밖 role 로 작성", "본문")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"));

		assertThat(rowCountByTitle("열거 밖 role 로 작성"))
				.as("계약 위반 토큰으로 글이 생성되면 안 된다 (sp-docs/api-contract.md §6)")
				.isZero();
		assertThat(REQUEST_COUNT.get())
				.as("리졸버가 인자 해석에서 끊으므로 member 를 호출할 자리가 없다")
				.isZero();
	}

	// --- 생성 실패 (sp-docs/api-contract.md §5.1, requirements/board.md §3.0) ---

	/** 200 이지만 결과에서 제외됐다 = 프로필 미등록. 업무 오류이고 재시도는 무의미하다 (§5.1). */
	@Test
	@DisplayName("프로필 미등록 계정으로 작성하면 403 S002 이고 저장되지 않는다")
	void 프로필_미등록은_403_S002() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 200, envelope("")));

		assertCreateFails("프로필 미등록", status().isForbidden(), "S002");
	}

	@Test
	@DisplayName("탈퇴한 프로필로 작성하면 403 S002 이고 저장되지 않는다")
	void 탈퇴한_프로필은_403_S002() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 200, envelope("""
				{"accountId": %d, "nickname": "탈퇴한 회원", "deleted": true}""".formatted(ACCOUNT_ID))));

		assertCreateFails("탈퇴 프로필", status().isForbidden(), "S002");
	}

	/**
	 * <b>404 를 "프로필 없음" 으로 해석하지 않는다</b> (§5.1). 그렇게 하면
	 * {@code MEMBER_SERVICE_URL} 오설정이 업무 오류로 위장되어, 경보도 없이 전 사용자의 쓰기가
	 * 조용히 멈춘다.
	 */
	@Test
	@DisplayName("member 가 404 를 주면 503 S001 이다. S002 가 아니다")
	void member_404_는_503_S001() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 404, "{}"));

		assertCreateFails("경로 오설정", status().isServiceUnavailable(), "S001");
	}

	/**
	 * 응답이 오지 않는 경우도 {@code S001} 이다 (§5.1). 스텁의 지연을 정본 read 타임아웃
	 * (3초, sp-docs/nfr.md §3)보다 훨씬 길게 둔다.
	 */
	@Test
	@DisplayName("member 타임아웃은 503 S001 이고 저장되지 않는다")
	void member_타임아웃은_503_S001() throws Exception {
		RESPONDER.set(exchange -> {
			sleep(Duration.ofSeconds(10));
			writeResponse(exchange, 200, envelope("""
					{"accountId": %d, "nickname": "%s", "deleted": false}"""
					.formatted(ACCOUNT_ID, MEMBER_NICKNAME)));
		});

		assertCreateFails("타임아웃", status().isServiceUnavailable(), "S001");
	}

	// --- 입력 검증 (sp-docs/requirements/board.md §4) ---

	/**
	 * 제목은 1~200자이고 <b>공백만으로 채울 수 없다</b> (§4). 위반은 400 {@code C001} 이다.
	 *
	 * <p>두 케이스가 서로 다른 제약을 짚는다 — 201자는 {@code @Size} 가, 공백 200자는
	 * {@code @NotBlank} 가 막는다. 한쪽만 걸면 다른 쪽이 201 로 통과한다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"TITLE_TOO_LONG", "TITLE_BLANK", "TITLE_EMPTY",
			"CONTENT_TOO_LONG", "CONTENT_BLANK"})
	@DisplayName("제목 201자 · 공백만 · 내용 10,001자는 400 C001 이고 저장되지 않는다")
	void 입력_검증_위반은_400_C001(String violation) throws Exception {
		String title = switch (violation) {
			case "TITLE_TOO_LONG" -> "가".repeat(201);
			case "TITLE_BLANK" -> " ".repeat(200);
			case "TITLE_EMPTY" -> "";
			default -> violation;
		};
		String content = switch (violation) {
			case "CONTENT_TOO_LONG" -> "나".repeat(10_001);
			case "CONTENT_BLANK" -> "   ";
			default -> "본문";
		};

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(title, content)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		assertThat(rowCount(ACCOUNT_ID)).isZero();
	}

	/** 경계값은 통과한다 — 상한을 199·9,999 로 적는 어긋남을 잡는다. */
	@Test
	@DisplayName("제목 200자 · 내용 10,000자는 201 이다")
	void 경계값은_201() throws Exception {
		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("가".repeat(200), "나".repeat(10_000))))
				.andExpect(status().isCreated());
	}

	// --- 상세 조회 (P-04, P-05, sp-docs/api-contract.md §4.3) ---

	/**
	 * <b>비로그인 허용이다</b> (sp-docs/api-contract.md §4) 그리고 <b>member 를 호출하지
	 * 않는다</b> (§4.2) — 조회 경로의 board → member 호출은 금지되어 있다
	 * (sp-docs/architecture.md §5).
	 */
	@Test
	@DisplayName("상세 조회는 토큰 없이 200 이고 member 호출이 0회다")
	void 상세_조회는_비로그인_200_이고_member_호출이_없다() throws Exception {
		Post post = savedPost(522L, "저장된닉네임", "상세 대상", "상세 본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(post.getId()))
				.andExpect(jsonPath("$.data.title").value("상세 대상"))
				.andExpect(jsonPath("$.data.content").value("상세 본문"))
				.andExpect(jsonPath("$.data.writerId").value(522))
				.andExpect(jsonPath("$.data.writerNickname").value("저장된닉네임"));

		assertThat(REQUEST_COUNT.get())
				.as("조회 경로는 member 를 호출하지 않는다 (sp-docs/api-contract.md §4.2)")
				.isZero();
	}

	/**
	 * 상세 응답의 키 집합이 {@link #DETAIL_KEYS} 와 정확히 같다 (§4.3).
	 *
	 * <p>{@code deleted} 는 돌아온 글이 항상 활성이므로 넣지 않고, {@code updatedAt} 은 감사
	 * 흔적으로 쓰지 않으며 노출 요구가 없어 넣지 않는다 (§4.3,
	 * sp-docs/domain-model.md §1.1).
	 */
	@Test
	@DisplayName("상세 응답 키는 목록 항목의 키에 content·liked 를 더한 것이고 deleted·updatedAt 이 없다")
	void 상세_응답_키_집합() throws Exception {
		Post post = savedPost(523L, "키집합작성자", "키 집합", "본문");

		MvcResult result = this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(dataKeys(result)).isEqualTo(DETAIL_KEYS);
	}

	/**
	 * 상세 응답의 {@code likeCount} 는 {@code post.like_count} 다 (§4.3). {@link #DETAIL_KEYS}
	 * 대조는 키만 본다 — 이 테스트가 없을 때 {@code PostDetailResponse} 의 {@code likeCount}
	 * 자리에 {@code commentCount} 를 실은 변이에서 스위트 전체가 녹색이었다 (2026-11-16 실측).
	 * {@code like_count} 를 2 로 두어 0 이 아니고 {@code comment_count}(0)·조회 뒤의
	 * {@code view_count}(1)와도 다르게 한다.
	 */
	@Test
	@DisplayName("상세 응답의 likeCount 는 저장된 like_count 다")
	void 상세_응답의_likeCount_는_저장된_값이다() throws Exception {
		Post post = savedPost(534L, "좋아요수작성자", "좋아요 수", "본문");
		withLikeCount(post, 2);

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.likeCount").value(2));
	}

	/**
	 * <b>같은 조회자는 24시간에 한 번, 서로 다른 조회자는 각각 센다</b>
	 * (sp-docs/requirements/board.md §9.2). 1차의 이 테스트는 상세 조회 2회에 {@code viewCount} 2
	 * 를 기대했다 — 그 시점의 계약이다 (sp-docs/api-contract.md §4.8 의 블록인용).
	 */
	@Test
	@DisplayName("같은 조회자의 재조회는 viewCount 를 올리지 않고 다른 조회자의 조회는 올린다")
	void 같은_조회자의_재조회는_세지_않고_다른_조회자는_센다() throws Exception {
		Post post = savedPost(524L, "조회수작성자", "조회수", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		this.mockMvc.perform(get(PATH + "/{id}", post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID)))
				.andExpect(jsonPath("$.data.viewCount").value(2));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getViewCount())
				.isEqualTo(2L);
	}

	@Test
	@DisplayName("없는 id 는 404 P001 이다")
	void 없는_id_는_404_P001() throws Exception {
		this.mockMvc.perform(get(PATH + "/{id}", 987654323L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));
	}

	@Test
	@DisplayName("삭제된 게시글은 404 P001 이다")
	void 삭제된_게시글은_404_P001() throws Exception {
		Post post = savedPost(525L, "삭제된글작성자", "삭제된 글", "본문");
		post.softDelete();
		this.postRepository.saveAndFlush(post);

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));
	}

	// --- 상세 조회의 선택적 인증과 조회수 판정 (B-21, sp-docs/api-contract.md §4.8) ---

	/**
	 * 검증표 <b>"비로그인 첫 조회 → 200, {@code viewCount} +1, 판정 행의 키가 {@code i:} + 소문자
	 * hex 64자"</b> (sp-docs/plan/phase2-board.md B-21). 키의 모양은 sp-docs/requirements/board.md
	 * §9.1 이다.
	 */
	@Test
	@DisplayName("비로그인 첫 조회는 200 이고 viewCount 가 1 오르며 판정 키가 i: 와 소문자 hex 64자다")
	void 비로그인_첫_조회는_세고_판정_키가_i_와_소문자_hex_다() throws Exception {
		Post post = savedPost(1201L, "판정작성자", "비로그인 첫 조회", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(커밋된_조회수(post)).isEqualTo(1L);
		List<String> 키 = 판정_키(post);
		assertThat(키).hasSize(1);
		assertThat(키.get(0)).matches("i:[0-9a-f]{64}");
	}

	/**
	 * 검증표 <b>"비로그인 판정 키의 값 → {@code i:} 뒤가 테스트 비밀키로 계산한
	 * {@code HMAC-SHA256(비밀키, remoteAddr)} 의 소문자 hex 와 같다"</b>.
	 *
	 * <p>기대값은 이 테스트가 JDK 로 직접 계산한다({@link #주소의_판정_키}). 키 없는 SHA-256 이나 설정
	 * 밖의 키로 바꾸면 깨진다.
	 */
	@Test
	@DisplayName("비로그인 판정 키는 설정의 비밀키로 계산한 접속 주소의 HMAC-SHA256 소문자 hex 다")
	void 비로그인_판정_키는_설정의_비밀키로_계산한_주소의_HMAC_이다() throws Exception {
		Post post = savedPost(1202L, "판정작성자", "판정 키의 값", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.23")))
				.andExpect(status().isOk());

		assertThat(판정_키(post)).containsExactly(주소의_판정_키("198.51.100.23"));
	}

	/**
	 * 검증표 <b>"같은 주소의 재조회 (24시간 안) → {@code viewCount} 불변, {@code viewed_at} 불변"</b>.
	 *
	 * <p>두 조회 사이에 기준 시각을 한 시간 앞으로 옮긴다. 같은 초 안의 두 조회라면 창을 미는 구현도
	 * 기준 시각에 같은 값을 쓰므로 「불변」 이 관측되지 않는다 (sp-docs/requirements/board.md §9.2 —
	 * 24시간 안의 조회는 창을 밀지 않는다).
	 */
	@Test
	@DisplayName("같은 주소의 24시간 안 재조회는 viewCount 를 올리지 않고 기준 시각을 밀지 않는다")
	void 같은_주소의_24시간_안_재조회는_세지_않고_기준_시각을_밀지_않는다() throws Exception {
		Post post = savedPost(1203L, "판정작성자", "재조회", "본문");
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk());
		LocalDateTime 한_시간_전 = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);
		기준_시각을_옮긴다(post, 한_시간_전);

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(커밋된_조회수(post)).isEqualTo(1L);
		assertThat(기준_시각들(post)).containsExactly(한_시간_전);
	}

	/**
	 * 검증표 <b>"로그인 조회 → 키가 {@code a:{accountId}}"</b> (sp-docs/requirements/board.md §9.1).
	 * 조회자는 작성자가 아니다 — 키가 토큰의 계정인지 글의 작성자인지가 갈린다.
	 */
	@Test
	@DisplayName("로그인 조회의 판정 키는 a: 와 토큰의 계정 id 다")
	void 로그인_조회의_판정_키는_계정이다() throws Exception {
		Post post = savedPost(1204L, "판정작성자", "로그인 조회", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1205L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(판정_키(post)).containsExactly("a:1205");
	}

	/**
	 * 검증표 <b>"작성자가 자기 글을 처음 조회 → {@code viewCount} +1"</b> — 작성자의 조회도 센다
	 * (sp-docs/requirements/board.md §9.2).
	 */
	@Test
	@DisplayName("작성자가 자기 글을 처음 보면 viewCount 가 1 오른다")
	void 작성자의_첫_조회도_센다() throws Exception {
		Post post = savedPost(1206L, "판정작성자", "작성자 조회", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1206L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(커밋된_조회수(post)).isEqualTo(1L);
	}

	/**
	 * 검증표 <b>"같은 사람의 로그인 전·후 조회 → 두 조회자 — 각각 +1"</b>. 같은 주소에서 토큰 없이 한
	 * 번, 토큰을 실어 한 번 본다 (sp-docs/requirements/board.md §9.2).
	 */
	@Test
	@DisplayName("같은 주소에서 로그인 전과 후에 보면 두 조회자로 각각 센다")
	void 로그인_전후는_두_조회자다() throws Exception {
		Post post = savedPost(1207L, "판정작성자", "로그인 전후", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.7")))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.7"))
						.header(HttpHeaders.AUTHORIZATION, bearer(1208L)))
				.andExpect(jsonPath("$.data.viewCount").value(2));

		assertThat(판정_키(post)).containsExactlyInAnyOrder(주소의_판정_키("198.51.100.7"), "a:1208");
	}

	/** 검증표 <b>"다른 {@code remoteAddr} → 다른 키"</b>. */
	@Test
	@DisplayName("접속 주소가 다른 비로그인 조회는 다른 판정 키라 각각 센다")
	void 다른_주소는_다른_판정_키다() throws Exception {
		Post post = savedPost(1209L, "판정작성자", "다른 주소", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.31")))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.32")))
				.andExpect(jsonPath("$.data.viewCount").value(2));

		assertThat(판정_키(post)).containsExactlyInAnyOrder(
				주소의_판정_키("198.51.100.31"), 주소의_판정_키("198.51.100.32"));
	}

	/**
	 * 검증표 <b>"같은 {@code remoteAddr} 에 {@code X-Forwarded-For} 만 다르게 → 같은 키"</b>. 그 헤더는
	 * 클라이언트가 임의로 쓴다 (sp-docs/security.md §9). 키가 그 헤더의 값이 아니라 접속 주소의 HMAC
	 * 인 것까지 본다.
	 *
	 * <p>{@code MockMvc} 는 Tomcat 을 띄우지 않는다 — {@code forward-headers-strategy} 가
	 * {@code native} 일 때 컨테이너가 하는 주소 바꾸기는 여기서 보이지 않는다. 기동한 board 에서의 측정은
	 * sp-docs/plan/phase2-board.md §4.1 이 통합에 제안했다.
	 */
	@Test
	@DisplayName("접속 주소가 같고 X-Forwarded-For 만 다르면 같은 판정 키라 한 번만 센다")
	void X_Forwarded_For_만_다르면_같은_판정_키다() throws Exception {
		Post post = savedPost(1210L, "판정작성자", "전달 헤더", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.41"))
						.header("X-Forwarded-For", "203.0.113.51"))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("198.51.100.41"))
						.header("X-Forwarded-For", "203.0.113.52"))
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(판정_키(post)).containsExactly(주소의_판정_키("198.51.100.41"));
	}

	/**
	 * 검증표 <b>"판정 행의 {@code viewed_at} 을 24시간보다 이전으로 옮긴 뒤 조회 → +1,
	 * {@code viewed_at} 이 새 시각"</b>. 새 시각은 요청 동안의 앱 시계다 — 판정은 초 아래를 자르므로
	 * 요청 전의 시각도 초 단위로 잘라 비교한다.
	 */
	@Test
	@DisplayName("기준 시각이 24시간을 넘긴 뒤의 조회는 세고 그 시각이 새 기준이 된다")
	void 창이_지난_뒤의_조회는_세고_기준_시각이_새_시각이다() throws Exception {
		Post post = savedPost(1211L, "판정작성자", "창이 지난 조회", "본문");
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk());
		기준_시각을_옮긴다(post, LocalDateTime.now().minusHours(24).minusMinutes(1));

		LocalDateTime 요청_전 = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(2));
		LocalDateTime 요청_후 = LocalDateTime.now();

		assertThat(커밋된_조회수(post)).isEqualTo(2L);
		List<LocalDateTime> 기준_시각 = 기준_시각들(post);
		assertThat(기준_시각).hasSize(1);
		assertThat(기준_시각.get(0)).isBetween(요청_전, 요청_후);
	}

	/**
	 * 검증표 <b>"판정 INSERT 가 UNIQUE 충돌 (끼워넣기를 강제한다) → 200 — 500 이 아니다, 집계
	 * 불변"</b>.
	 *
	 * <p>존재 확인 자리에서 끼운다. 그 확인의 대역이 같은 키의 판정 행과 +1 을 다른 스레드에서 커밋한
	 * 뒤 「없음」 을 돌려준다 — 확인이 그 커밋 앞에서 읽은 답이다. 그러면 이 요청의 INSERT 가 UNIQUE
	 * 충돌이다 (sp-docs/requirements/board.md §9.3).
	 *
	 * <p>끼운 쪽의 +1 은 남고 이 요청은 더하지 않는다 — 집계는 1 이다. 응답도 1 이다 — 판정이 끝난 뒤에
	 * 시작한 읽기가 끼운 쪽의 커밋을 본다.
	 */
	@Test
	@DisplayName("판정 INSERT 가 UNIQUE 충돌이면 200 이고 그 요청은 집계를 바꾸지 않는다")
	void 판정_INSERT_의_UNIQUE_충돌은_200_이고_세지_않는다() throws Exception {
		Post post = savedPost(1212L, "판정작성자", "UNIQUE 충돌", "본문");
		AtomicInteger 끼운_행 = new AtomicInteger(-1);
		willAnswer(invocation -> {
			Long postId = invocation.getArgument(0);
			String viewerKey = invocation.getArgument(1);
			끼운_행.set(다른_트랜잭션에서_같은_키로_센다(postId, viewerKey));
			return false;
		}).given(this.postViewRepository).existsByPostIdAndViewerKey(anyLong(), anyString());

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(1));

		assertThat(끼운_행.get()).as("끼운 판정 행이 먼저 커밋돼야 이 요청의 INSERT 가 충돌한다").isEqualTo(1);
		assertThat(커밋된_조회수(post)).as("충돌한 요청은 세지 않는다").isEqualTo(1L);
		assertThat(판정_키(post)).hasSize(1);
	}

	/**
	 * 검증표 <b>"응답의 {@code viewCount} → 판정이 반영된 값 — 집계된 조회면 증가 뒤의 값"</b> 의 세지 않은
	 * 쪽이다 — 24시간 안의 재조회는 집계하지 않고 지금의 값을 돌려준다 (sp-docs/api-contract.md §4.8).
	 *
	 * <p>재조회의 존재 확인 자리에서 다른 조회자의 +1 을 다른 스레드로 커밋시키고, 응답이 그 커밋을 싣는지
	 * 본다. 요청 동안 열려 있는 영속성 컨텍스트에 판정 전에 읽은 글이 남아 있으면 응답이 그 사본의 값이
	 * 된다 — 404 판정이 엔티티를 읽지 않는 이유다 ({@code PostReader#requireActive}). 집계된 조회에서는
	 * {@code PostRepository#increaseViewCount} 의 비우기가 그 사본을 치우므로 이 결함은 세지 않은
	 * 재조회에서 드러난다 — 404 판정이 엔티티를 읽는 변이에서 집계된 조회의 응답을 보는 테스트는
	 * 녹색이었고 이 테스트가 빨갰다 (2026-11-16 B-21 실측).
	 *
	 * <p>응답의 읽기가 판정 커밋 뒤에 시작했는지는 이 테스트로 갈리지 않는다 — H2 의 테스트 연결은 커밋된
	 * 읽기라(2026-11-16 B-21 실측) 판정 전에 시작한 트랜잭션도 그 커밋을 본다. 그 경계는
	 * {@code ViewCountJudgeTest} 가 물리 트랜잭션의 순서로 본다.
	 */
	@Test
	@DisplayName("세지 않은 재조회의 응답도 판정 사이에 커밋된 다른 조회의 증가를 싣는다")
	void 세지_않은_재조회의_응답도_판정_뒤의_값이다() throws Exception {
		Post post = savedPost(1213L, "판정작성자", "세지 않은 재조회", "본문");
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		AtomicInteger 끼운_증가 = new AtomicInteger(-1);
		willAnswer(invocation -> {
			끼운_증가.set(다른_트랜잭션에서_조회수를_올린다(post.getId()));
			return true;
		}).given(this.postViewRepository).existsByPostIdAndViewerKey(anyLong(), anyString());

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewCount").value(2));

		assertThat(끼운_증가.get()).as("끼운 증가가 커밋돼야 이 측정이 일한다").isEqualTo(1);
		assertThat(판정_키(post)).as("재조회는 판정 행을 더하지 않는다").hasSize(1);
	}

	/**
	 * 검증표 <b>"없는·삭제된 글 → 404 {@code P001}, 판정 행 0"</b> 의 없는 글이다. 판정은 404 판정
	 * 뒤다 (sp-docs/domain-model.md §4.5) — {@code post_view.post_id} 에 FK 가 없으므로 판정이 먼저면
	 * 없는 글의 id 로 행이 생긴다. 자동 증가 id 는 음수가 되지 않으므로 음수 id 는 없는 글이다.
	 */
	@Test
	@DisplayName("없는 글의 상세 조회는 404 P001 이고 판정 행을 만들지 않는다")
	void 없는_글은_404_이고_판정_행이_없다() throws Exception {
		long 없는_id = -1214L;
		try {
			this.mockMvc.perform(get(PATH + "/{id}", 없는_id))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("P001"));

			assertThat(판정_행_수(없는_id)).isZero();
		}
		finally {
			// 판정이 404 판정보다 먼저인 구현이면 남는다 — 다음 실행에 끌고 가지 않는다
			this.jdbcTemplate.update("DELETE FROM post_view WHERE post_id = ?", 없는_id);
		}
	}

	/** 검증표 <b>"없는·삭제된 글 → 404 {@code P001}, 판정 행 0"</b> 의 삭제된 글이다. */
	@Test
	@DisplayName("삭제된 글의 상세 조회는 404 P001 이고 판정 행을 만들지 않으며 조회수가 그대로다")
	void 삭제된_글은_404_이고_판정_행이_없다() throws Exception {
		Post post = savedPost(1215L, "판정작성자", "삭제된 글", "본문");
		post.softDelete();
		this.postRepository.saveAndFlush(post);

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));

		assertThat(판정_키(post)).isEmpty();
		assertThat(커밋된_조회수(post)).isZero();
	}

	/**
	 * 검증표 <b>"{@code liked} — 토큰 없음 / 누른 계정 / 누르지 않은 계정 → {@code null} /
	 * {@code true} / {@code false}"</b> (sp-docs/api-contract.md §4.3 §4.8).
	 *
	 * <p><b>대각선을 피한다.</b> 대상 글에는 다른 계정의 좋아요가 있고, 누르지 않은 계정은 다른 글을
	 * 눌렀다 — 확인이 글이나 계정 하나만 보면 누르지 않은 계정이 {@code true} 가 된다. {@code null}
	 * 은 키가 있고 값이 없는 것으로 본다 — 키가 빠진 응답과 다르다 (§4.3).
	 */
	@Test
	@DisplayName("상세의 liked 는 토큰이 없으면 null, 누른 계정이면 true, 누르지 않은 계정이면 false 다")
	void 상세의_liked_는_요청한_계정의_좋아요다() throws Exception {
		Post 대상 = savedPost(1216L, "판정작성자", "좋아요 대상", "본문");
		Post 다른_글 = savedPost(1216L, "판정작성자", "다른 글", "본문");
		누른_것으로_둔다(대상, 1217L);
		누른_것으로_둔다(다른_글, 1218L);

		MvcResult 토큰_없음 = this.mockMvc.perform(get(PATH + "/{id}", 대상.getId()))
				.andExpect(status().isOk())
				.andReturn();
		Map<String, Object> data = JsonPath.read(bodyOf(토큰_없음), "$.data");
		assertThat(data).as("토큰이 없으면 키가 있고 값이 null 이다").containsEntry("liked", null);

		this.mockMvc.perform(get(PATH + "/{id}", 대상.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1217L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(true));
		this.mockMvc.perform(get(PATH + "/{id}", 대상.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1218L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(false));
	}

	/**
	 * 검증표 <b>"작성 응답의 {@code liked} / 수정 응답의 {@code liked} → {@code false} / 요청한 계정의
	 * 실제 상태"</b> 의 작성 쪽이다 (sp-docs/api-contract.md §4.4).
	 */
	@Test
	@DisplayName("작성 응답의 liked 는 false 다")
	void 작성_응답의_liked_는_false_다() throws Exception {
		respondWithActiveProfile(1219L);

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(1219L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("좋아요 없는 새 글", "본문")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.liked").value(false));
	}

	/**
	 * 검증표 <b>"작성 응답의 {@code liked} / 수정 응답의 {@code liked} → {@code false} / 요청한 계정의
	 * 실제 상태"</b> 의 수정 쪽이다 (sp-docs/api-contract.md §4.4). 작성자가 자기 글 하나는 눌렀고 다른 하나는 다른 계정만 눌렀다 — 확인이 글이나 계정 하나만
	 * 보면 둘째가 {@code true} 가 된다.
	 */
	@Test
	@DisplayName("수정 응답의 liked 는 요청한 계정이 그 글을 눌렀는지다")
	void 수정_응답의_liked_는_요청한_계정의_실제_상태다() throws Exception {
		Post 누른_글 = savedPost(1220L, "판정작성자", "누른 글", "본문");
		Post 남만_누른_글 = savedPost(1220L, "판정작성자", "남만 누른 글", "본문");
		누른_것으로_둔다(누른_글, 1220L);
		누른_것으로_둔다(남만_누른_글, 1221L);

		this.mockMvc.perform(put(PATH + "/{id}", 누른_글.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1220L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("고친 제목", "고친 본문")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(true));
		this.mockMvc.perform(put(PATH + "/{id}", 남만_누른_글.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1220L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("고친 제목", "고친 본문")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(false));
	}

	/**
	 * 검증표 <b>"claim 이 계약 밖인 토큰으로 상세 조회 → 401 {@code A002}"</b>
	 * (sp-docs/requirements/board.md §9.1). 서명이 유효해 필터 체인은 지나고 선택적 리졸버가 막는다 —
	 * 익명으로 낮추지 않는다 (sp-docs/api-contract.md §4.8). 판정에 닿지 않으므로 행도 증가도 없다.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"role 이 열거 밖", "sub 누락"})
	@DisplayName("claim 이 계약 밖인 토큰의 상세 조회는 401 A002 이고 판정하지 않는다")
	void claim_이_계약_밖인_토큰은_401_A002_다(String 종류) throws Exception {
		Post post = savedPost(1222L, "판정작성자", "claim 위반", "본문");
		String token = "sub 누락".equals(종류)
				? TestTokenFactory.tokenWithoutSubject(ROLE)
				: TestTokenFactory.accessToken(1223L, "BOGUS");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"));

		assertThat(판정_키(post)).isEmpty();
		assertThat(커밋된_조회수(post)).isZero();
	}

	/**
	 * 검증표 <b>"판정 행 → {@code viewer_key} 에 주소 문자열이 들어 있지 않다"</b> — 원 IP 를 저장하지
	 * 않는다 (sp-docs/security.md §9).
	 */
	@Test
	@DisplayName("판정 행의 viewer_key 에 접속 주소 문자열이 들어 있지 않다")
	void 판정_행에_주소_문자열이_없다() throws Exception {
		Post post = savedPost(1224L, "판정작성자", "주소 비저장", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()).with(remoteAddr("203.0.113.7")))
				.andExpect(status().isOk());

		assertThat(판정_키(post)).hasSize(1).noneMatch(key -> key.contains("203.0.113.7"));
	}

	// --- 목록·검색 (P-02, P-03, sp-docs/api-contract.md §4.1 §7.1) ---

	/**
	 * <b>비로그인 허용이고</b> (sp-docs/api-contract.md §4) <b>member 를 호출하지 않는다</b> —
	 * 조회 경로의 원격 호출은 0회가 기준이다 (sp-docs/nfr.md §1, §4.2). 목록에 실리는 닉네임은
	 * 작성 시점 스냅샷이다.
	 *
	 * <p>건수를 단언하지 않는다. 조건 없는 목록은 다른 테스트 클래스의 픽스처까지 센다.
	 */
	@Test
	@DisplayName("목록 조회는 토큰 없이 200 이고 member 호출이 0회다")
	void 목록_조회는_비로그인_200_이고_member_호출이_없다() throws Exception {
		savedPost(621L, "목록작성자", "목록 조회 대상", "본문");

		this.mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.content").isArray());

		assertThat(REQUEST_COUNT.get())
				.as("목록 조회는 member 를 호출하지 않는다 (sp-docs/api-contract.md §4.2)")
				.isZero();
	}

	/**
	 * 봉투는 §7.1, 항목은 §4.3 의 목록 모양이다 — 항목에 {@code content} 가 없다.
	 *
	 * <p>여기의 {@code totalElements} 는 한 페이지에 다 들어가는 결과라 보정된 값과 구별되지
	 * 않으므로 건수 쿼리를 보지 않는다 (sp-docs/process/review-policy.md §4.1) — 검색 조건이
	 * 걸린 건수 쿼리를 관측하는 것은 {@code PostQueryRepositoryTest.삭제분_제외()} 다. 꽉 찬
	 * 페이지만으로는 부족하고 조건이 <b>배제하는 행</b>이 픽스처에 있어야 하는데, 그 조건을
	 * 갖춘 것이 그쪽이다. 이 테스트가 보는 것은 <b>응답의 모양</b>이다.
	 */
	@Test
	@DisplayName("목록 응답은 §7.1 페이징 키를 갖고 항목 키는 §4.3 의 목록 줄과 같다")
	void 목록_응답_형식() throws Exception {
		Post post = savedPost(622L, "형식작성자", "응답형식확인", "본문입니다");

		MvcResult result = this.mockMvc.perform(get(PATH)
						.param("searchType", "TITLE")
						.param("keyword", "응답형식확인"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].id").value(post.getId()))
				.andExpect(jsonPath("$.data.content[0].writerNickname").value("형식작성자"))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.first").value(true))
				.andExpect(jsonPath("$.data.last").value(true))
				.andReturn();

		assertThat(dataKeys(result))
				.as("sp-docs/api-contract.md §7.1 의 페이징 봉투")
				.isEqualTo(PAGE_KEYS);
		assertThat(firstItemKeys(result))
				.as("목록 항목에는 content 가 없다 (§4.3) — 한 페이지 50건이면 본문만 500,000자다")
				.isEqualTo(LIST_ITEM_KEYS);
	}

	@Test
	@DisplayName("page·size 를 주지 않으면 page 0, size 10 이다")
	void 기본_페이징_값() throws Exception {
		savedPost(623L, "기본값작성자", "기본값확인 첫째", "본문");
		savedPost(623L, "기본값작성자", "기본값확인 둘째", "본문");

		this.mockMvc.perform(get(PATH)
						.param("searchType", "TITLE")
						.param("keyword", "기본값확인"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page").value(0))
				.andExpect(jsonPath("$.data.size").value(10))
				.andExpect(jsonPath("$.data.content.length()").value(2));
	}

	/**
	 * {@code size} 는 최대 50 이고 초과분은 절삭된다 (sp-docs/api-contract.md §4.1).
	 *
	 * <p>51건을 저장해 두고 51건을 요청한다. 픽스처가 상한보다 적으면 절삭이 없어도 같은 결과가
	 * 나와 단언이 아무것도 보지 못한다.
	 *
	 * <p><b>여기의 {@code totalElements} 는 건수 쿼리의 검색 조건을 구속하지 않는다.</b> 절삭
	 * 결과로 페이지가 꽉 차서 {@code PageImpl} 의 보정은 일어나지 않지만, 꽉 찬 페이지는
	 * <b>필요조건일 뿐이다</b> — 이 픽스처의 모든 활성 행이 {@code 절삭확인} 에 매칭되므로
	 * 조건을 떼어낸 건수와 붙인 건수가 같은 값이다 (측정값: 건수 쿼리에서
	 * {@code keywordCondition} 을 떼어낸 변이가 이 테스트를 통과했다). 조건이 배제하는 행을 둔
	 * 관측은 {@code PostQueryRepositoryTest.삭제분_제외()} 가 한다
	 * (sp-docs/process/review-policy.md §4.1). <b>이 테스트가 보는 것은 절삭이다.</b>
	 */
	@Test
	@DisplayName("size=51 은 50 으로 절삭된다")
	void size_상한_절삭() throws Exception {
		for (int i = 0; i < 51; i++) {
			savedPost(624L, "절삭작성자", "절삭확인 " + i, "본문");
		}

		this.mockMvc.perform(get(PATH)
						.param("searchType", "TITLE")
						.param("keyword", "절삭확인")
						.param("size", "51"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.size").value(50))
				.andExpect(jsonPath("$.data.content.length()").value(50))
				.andExpect(jsonPath("$.data.totalElements").value(51))
				.andExpect(jsonPath("$.data.totalPages").value(2));
	}

	/**
	 * {@code page} 는 0 이상, {@code size} 는 1~50 이다 (sp-docs/requirements/board.md §4).
	 * <b>상한 초과만 절삭으로 정해져 있으므로</b> 하한 미달은 입력 검증 위반이고 400
	 * {@code C001} 이다 (sp-docs/api-contract.md §8.1).
	 *
	 * <p>이 제약이 없으면 {@code PageRequest.of} 가 터져 <b>500 {@code C005}</b> 가 되고
	 * {@code log.error} 경보를 만든다 (측정값) — 클라이언트 실수가 서버 장애로 보고된다
	 * (sp-docs/conventions.md §6.1).
	 *
	 * <p><b>{@code fieldErrors} 가 1건이고 {@code field} 는 파라미터명이다</b>
	 * (sp-docs/api-contract.md §4.1 §2.3). 하한 위반은 <b>바인딩이 성공한 뒤</b> 범위 규칙을
	 * 어긴 것이므로 §2.3 이 말한 "바인딩된 필드의 오류" 이고, 조합 위반(아래 두 테스트)의 빈
	 * 배열과 갈린다. 기대하는 {@code field} 는 {@code @CsvSource} 의 첫 칼럼이므로 응답에서
	 * 뽑아 온 자기참조가 아니다.
	 */
	@ParameterizedTest
	@CsvSource({"page,-1", "size,0"})
	@DisplayName("page 0 미만 · size 1 미만은 400 C001 이고 fieldErrors 가 1건이다")
	void 페이징_하한_미달은_400_C001(String parameter, String value) throws Exception {
		this.mockMvc.perform(get(PATH).param(parameter, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value(parameter));
	}

	/**
	 * <b>경계는 양쪽이다 — 하한 값은 성공해야 한다</b> (sp-docs/conventions.md §9.2).
	 * {@code page} 는 0 이상, {@code size} 는 1 이상이므로 {@code page=0}·{@code size=1} 은
	 * 200 이다 (sp-docs/requirements/board.md §4, sp-docs/api-contract.md §4.1).
	 *
	 * <p><b>위의 하한 미달 테스트가 이것을 대신하지 않는다.</b> 위반하는 쪽만 보면
	 * <b>하한을 올리는 어긋남이 조용하다</b> — {@code MIN_SIZE} 를 1 에서 2 로 올려도
	 * {@code size=0} 은 여전히 400 이다(실측: 그 변이에서 263개가 전부 통과했다). 상한에서
	 * 배운 것과 방향만 반대다 (§9.2).
	 *
	 * <p>돌려받은 값을 함께 본다. 기대값은 {@code @CsvSource} 의 둘째 칼럼이므로 자기참조가
	 * 아니다. <b>다만 그 단언이 구속하는 것은 {@code size} 쪽뿐이다</b> —
	 * {@code DEFAULT_PAGE} 와 {@code MIN_PAGE} 가 <b>같은 값(0)</b>이라, {@code page} 를
	 * 핸들러가 통째로 무시해도 기본값이 채워져 {@code $.data.page} 가 0 으로 나온다(측정값:
	 * {@code page} 를 받지 않게 한 변이에서 {@code [page,0]} 이 녹색이었다). 그 결함을 잡는
	 * 것은 위의 하한 미달 테스트다 — 같은 변이에서 {@code [page,-1]} 이 빨개졌다. <b>이 행이
	 * 지키는 것은 하한 값 0 이 거부되지 않는 것</b>이고, 그것은 {@code MIN_PAGE} 를 올리는
	 * 변이가 깨뜨린다.
	 *
	 * <p>{@code DEFAULT_PAGE} 를 0 이 아닌 값으로 바꾸면 이 설명이 달라진다 — 그때는
	 * {@code page} 행도 "무시" 를 구속하게 된다.
	 *
	 * <p>{@code /posts/me} 쪽은 {@link #내_글_목록의_페이징_하한_값은_200} 이 따로 본다 —
	 * 두 경로가 같은 상수를 쓰므로 한쪽만으로는 부족하다 (§9.2).
	 */
	@ParameterizedTest
	@CsvSource({"page,0", "size,1"})
	@DisplayName("page=0 · size=1 은 200 이고 그 값이 응답에 실린다")
	void 페이징_하한_값은_200(String parameter, int value) throws Exception {
		savedPost(627L, "하한값작성자", "하한 값 대상", "본문");

		this.mockMvc.perform(get(PATH).param(parameter, String.valueOf(value)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data." + parameter).value(value));
	}

	/**
	 * 정본이 고정한 값은 소문자다 (sp-docs/api-contract.md §4.1). 상수 이름을 대문자로 바꾸면
	 * 변환이 대소문자를 구분하므로 이 두 줄이 400 이 된다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"latest", "views"})
	@DisplayName("sort=latest · sort=views 는 200 이다")
	void 정렬_화이트리스트_값(String sort) throws Exception {
		savedPost(625L, "정렬작성자", "정렬 대상", "본문");

		this.mockMvc.perform(get(PATH).param("sort", sort))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
	}

	/**
	 * <b>정렬 파라미터로 임의 컬럼명을 넣을 수 없다</b> (sp-docs/requirements/board.md §6).
	 * 화이트리스트 enum 으로 받으므로 변환 단계에서 걸린다 — 타입 변환 실패이므로
	 * {@code C002} 다 (sp-docs/api-contract.md §8.1, sp-docs/conventions.md §6.1).
	 *
	 * <p>{@code title} 은 실제 {@code post} 컬럼이다. 화이트리스트가 아니라 컬럼명을 받는
	 * 구현이면 {@code password} 는 막히고 이 값은 통과한다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"password", "title"})
	@DisplayName("sort=password · sort=title 은 400 이다")
	void 정렬_화이트리스트_밖은_400(String sort) throws Exception {
		this.mockMvc.perform(get(PATH).param("sort", sort))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C002"));
	}

	/**
	 * {@code searchType} 과 {@code keyword} 는 둘 다 있거나 둘 다 없어야 한다
	 * (sp-docs/api-contract.md §4.1, sp-docs/requirements/board.md §6). 아래 세 테스트가 그
	 * 규칙의 세 입력이다.
	 *
	 * <p><b>조합 위반의 {@code fieldErrors} 는 빈 배열이다</b> (§4.1). 한 필드의 오류가 아니라
	 * <b>요청 수준</b> 규칙이어서 담을 필드가 하나로 정해지지 않는다 — §2.3 이 가른 요청 수준과
	 * 바인딩 수준의 구분이고, 하한 위반의 1건과 짝을 이룬다. <b>두 방향 모두 단언한다</b> —
	 * 기대 결과가 대칭이므로 한쪽만 구속하면 다른 쪽이 조용히 어긋날 수 있다.
	 */
	@Test
	@DisplayName("searchType 만 전달하면 400 C001 이고 fieldErrors 는 빈 배열이다")
	void searchType_만_전달하면_400_C001() throws Exception {
		this.mockMvc.perform(get(PATH).param("searchType", "TITLE"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	/**
	 * <b>반대 방향도 같은 판정이다.</b> 키워드를 조용히 무시하면 사용자는 검색이 걸린 줄 알고
	 * 전체 목록을 받는다 (§4.1, sp-docs/AGENTS.md 불변식 9). {@code fieldErrors} 도 위와 같이
	 * 빈 배열이다.
	 */
	@Test
	@DisplayName("keyword 만 전달하면 400 C001 이다 — 조용히 무시하지 않는다")
	void keyword_만_전달하면_400_C001() throws Exception {
		this.mockMvc.perform(get(PATH).param("keyword", "공지"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	/** 공백만인 키워드는 "없음" 이므로 {@code searchType} 만 온 것과 같다 (§4.1). */
	@Test
	@DisplayName("공백만인 keyword 를 searchType 과 함께 보내면 400 C001 이다")
	void 공백_keyword_와_searchType_은_400_C001() throws Exception {
		this.mockMvc.perform(get(PATH)
						.param("searchType", "TITLE")
						.param("keyword", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));
	}

	/**
	 * <b>정규화가 조합 검증보다 먼저라는 것을 이 테스트가 핀한다</b> (§4.1). 순서가 뒤집히면
	 * 공백만인 키워드가 "한쪽만 온 것" 으로 걸려 400 이 된다.
	 */
	@Test
	@DisplayName("공백만인 keyword 단독은 200 전체 목록이다")
	void 공백_keyword_단독은_200() throws Exception {
		savedPost(626L, "공백키워드작성자", "공백 키워드 대상", "본문");

		this.mockMvc.perform(get(PATH).param("keyword", "   "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.content").isArray());
	}

	// --- 내가 쓴 글 (P-08, sp-docs/api-contract.md §4 §4.1) ---

	/**
	 * 검증표의 "내 글 5건 + 타인 글 3건 → 5건만 반환" 이다 (sp-docs/plan/phase1.md §6 B-09).
	 *
	 * <p><b>조회 키는 JWT 의 {@code sub} 다</b> (sp-docs/api-contract.md §4.1,
	 * sp-docs/requirements/board.md §3 규칙 3). 타인 글 3건을 토큰과 다른 {@code writer_id} 로
	 * 두므로, 작성자 조건이 빠지면 8건이 돌아와 두 단언이 함께 깨진다.
	 *
	 * <p><b>member 를 호출하지 않는다</b> (§4.2, sp-docs/nfr.md §1) — 조회 경로다. 목록에
	 * 실리는 닉네임은 작성 시점 스냅샷이다.
	 *
	 * <p>응답 모양은 <b>목록과 같다</b> (§4.3) — 봉투는 §7.1 이고 항목에 {@code content} 가
	 * 없다. 두 키 집합은 이 클래스가 리터럴로 고정한 것이다.
	 *
	 * <p>{@code totalElements} 는 한 페이지에 다 들어가는 결과라
	 * {@code PageImpl} 의 보정과 구별되지 않으므로 건수 쿼리를 보지 않는다
	 * (sp-docs/process/review-policy.md §4.1). 작성자 조건이 붙은 건수 쿼리를 관측하는 것은
	 * {@code PostQueryRepositoryTest.내가_쓴_글의_삭제분_제외()} 다.
	 */
	@Test
	@DisplayName("내가 쓴 글 목록은 내 글만 돌려주고 항목 키가 §4.3 의 목록 줄과 같다")
	void 내가_쓴_글만_돌려준다() throws Exception {
		for (int i = 0; i < 5; i++) {
			savedPost(861L, "내글작성자", "내 글 " + i, "본문");
		}
		for (int i = 0; i < 3; i++) {
			savedPost(862L, "타인작성자", "타인 글 " + i, "본문");
		}

		MvcResult result = this.mockMvc.perform(get(PATH + "/me")
						.header(HttpHeaders.AUTHORIZATION, bearer(861L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.content.length()").value(5))
				.andReturn();

		assertThat(itemTitles(result))
				.as("타인 글 3건이 섞이면 제목이 '타인 글 ...' 로 나온다")
				.allSatisfy(title -> assertThat(title).startsWith("내 글 "));
		assertThat(dataKeys(result))
				.as("sp-docs/api-contract.md §7.1 의 페이징 봉투")
				.isEqualTo(PAGE_KEYS);
		assertThat(firstItemKeys(result))
				.as("내가 쓴 글도 목록 항목 모양이다 (§4.3) — content 가 없다")
				.isEqualTo(LIST_ITEM_KEYS);
		assertThat(REQUEST_COUNT.get())
				.as("조회 경로는 member 를 호출하지 않는다 (sp-docs/api-contract.md §4.2)")
				.isZero();
	}

	/**
	 * <b>{@code writerId} 를 쿼리 파라미터로 받지 않는다</b> (sp-docs/api-contract.md §4.1).
	 * 받으면 아무 토큰으로나 남의 글 목록을 꺼낼 수 있다.
	 *
	 * <p>파라미터가 가리키는 쪽에 글을 하나 두므로, 그 값을 조회 키로 쓰는 구현이면 응답이
	 * 그 글 하나가 되어 제목 단언이 깨진다. Spring 이 선언되지 않은 파라미터를 무시하는 것에
	 * 기대는 테스트이고, 핸들러가 {@code writerId} 를 선언하는 순간 빨개진다.
	 */
	@Test
	@DisplayName("writerId 쿼리 파라미터는 조회 키가 되지 않는다")
	void writerId_파라미터는_무시된다() throws Exception {
		savedPost(863L, "내글작성자", "파라미터 내 글", "본문");
		savedPost(864L, "타인작성자", "파라미터 타인 글", "본문");

		MvcResult result = this.mockMvc.perform(get(PATH + "/me")
						.param("writerId", "864")
						.header(HttpHeaders.AUTHORIZATION, bearer(863L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(1))
				.andReturn();

		assertThat(itemTitles(result)).containsExactly("파라미터 내 글");
	}

	/**
	 * <b>{@code /posts/me} 의 인가 규칙이 {@code /posts/{id}} 의 {@code permitAll} 보다 먼저
	 * 평가되는지를 잡는다</b> (sp-docs/api-contract.md §4 — {@code authorizeHttpRequests} 는
	 * 선언 순서대로 평가한다).
	 *
	 * <p><b>상태 코드만으로는 그 순서가 관측되지 않는다</b>(측정값). 두 선언을 뒤집으면
	 * {@code permitAll} 이 이 경로를 먼저 잡아 요청이 인증 없이 디스패치되지만, 핸들러의
	 * {@code @CurrentMember} 가 익명 principal 을 보고 {@code BusinessException(UNAUTHORIZED)}
	 * 을 던져 <b>응답이 똑같이 401 {@code A001} 이 된다.</b> 그래서 뒤집은 변이에서
	 * {@code JwtAuthenticationTest.인증이_필요한_경로} 를 포함한 <b>262개가 전부 통과했다</b> —
	 * 핸들러가 생기기 전에는 그 테스트가 순서를 잡았지만 B-09 가 핸들러를 만든 뒤로는 잡지
	 * 못한다.
	 *
	 * <p><b>그때의 응답은 400 {@code C002} 였다</b> (B-09 2라운드 리뷰 측정값,
	 * sp-docs/api-contract.md §4). 매핑이 없어 404 가 되는 것이 아니라
	 * {@code GET /posts/{id}} 의 {@code @PathVariable Long id} 가 {@code "me"} 를 잡아
	 * {@code MethodArgumentTypeMismatchException} 이 된다 — 같은 기준 경로에 경로 변수
	 * 핸들러가 있으면 그것이 먼저 잡는다. 어느 쪽이든 401 이 아니므로 그때는 그 줄이 순서를
	 * 잡았다는 결론은 같다.
	 *
	 * <p><b>그래서 거부한 계층을 본다.</b> 규칙이 {@code authenticated()} 로 걸리면 요청이
	 * {@code DispatcherServlet} 에 닿지 않으므로 핸들러가 선택되지 않고 해석된 예외도 없다.
	 * {@code permitAll} 이 먼저 잡으면 둘 다 채워진다. 401 과 {@code A001} 단언은
	 * {@code JwtAuthenticationTest} 와 겹치지만, <b>그 겹치는 부분이 아래 두 단언을 의미 있게
	 * 만든다</b> — 어느 계층이 냈는지는 "401 이 났다" 를 전제로만 말이 된다.
	 */
	@Test
	@DisplayName("토큰 없는 내 글 목록 요청은 핸들러에 닿기 전에 필터 체인이 막는다")
	void 내_글_목록은_필터_체인이_막는다() throws Exception {
		MvcResult result = this.mockMvc.perform(get(PATH + "/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"))
				.andReturn();

		assertThat(result.getHandler())
				.as("핸들러가 선택됐다면 permitAll 이 /posts/me 를 먼저 잡은 것이다 "
						+ "(sp-docs/api-contract.md §4) — SecurityConfig 의 선언 순서를 되돌려야 한다")
				.isNull();
		assertThat(result.getResolvedException())
				.as("필터 체인이 막으면 디스패치가 없으므로 해석된 예외도 없다. "
						+ "CurrentMemberArgumentResolver 가 낸 401 이면 여기에 BusinessException 이 담긴다")
				.isNull();
	}

	/**
	 * 검증표 "{@code role = BOGUS} 로 {@code GET /posts/me} → 401 {@code A002}".
	 * <b>전에는 200 이었다</b> (I-04 리뷰 실측, sp-docs/plan/phase1.md §6 B-12).
	 *
	 * <p><b>무토큰과 거부한 계층이 다르다.</b> 서명이 유효하므로 필터 체인은 통과하고
	 * {@code @CurrentMember} 리졸버가 끊는다 (sp-docs/tech-stack.md §2.1) — 그래서 위
	 * 테스트와 달리 핸들러가 선택되고 해석된 예외가 남는다. <b>그 대비가 집행 지점을
	 * 고정한다</b> — 디코더에 claim 검증기를 더해 끊으면 필터 체인이 먼저 막아 핸들러가
	 * {@code null} 이 되고, 코드도 {@code A003}(만료)으로 갈린다
	 * ({@code JwtDecoderValidatorsTest} 가 그 집합을 고정하는 이유다).
	 */
	@Test
	@DisplayName("role 이 열거 밖인 내 글 목록 요청은 리졸버가 401 A002 로 끊는다")
	void 열거_밖_role_의_내_글_목록은_401_A002() throws Exception {
		MvcResult result = this.mockMvc.perform(get(PATH + "/me")
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(868L, "BOGUS")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"))
				.andReturn();

		assertThat(result.getHandler())
				.as("서명이 유효한 토큰은 필터 체인을 통과한다 — 집행 지점은 리졸버다 "
						+ "(sp-docs/tech-stack.md §2.1)")
				.isNotNull();
		assertThat(result.getResolvedException())
				.as("리졸버가 던진 예외가 여기 담긴다. 디코더에서 끊으면 디스패치가 없어 null 이다")
				.isInstanceOf(BusinessException.class);
	}

	/**
	 * 검증표의 "내 글 목록에 {@code size=0} / {@code page=-1} → 400 {@code C001},
	 * {@code fieldErrors} 1건. 500 이 아니다" 다.
	 *
	 * <p><b>목록 쪽 테스트가 이 핸들러를 덮지 않는다.</b> 하한을 집행하는 자리가 파라미터
	 * 제약이므로 (sp-docs/api-contract.md §4.1 §2.3) 핸들러마다 따로 붙어야 하고, 이 핸들러에서
	 * 빠지면 {@code size=0} 이 {@code PageRequest.of} 까지 내려가 <b>500 {@code C005}</b> 와
	 * {@code log.error} 경보가 된다 — 400 이어야 할 클라이언트 실수가 서버 장애로 보고된다
	 * (sp-docs/conventions.md §6.1).
	 *
	 * <p>기대하는 {@code field} 는 {@code @CsvSource} 의 첫 칼럼이므로 응답에서 뽑아 온
	 * 자기참조가 아니다.
	 */
	@ParameterizedTest
	@CsvSource({"page,-1", "size,0"})
	@DisplayName("내 글 목록의 page 0 미만 · size 1 미만은 400 C001 이고 fieldErrors 가 1건이다")
	void 내_글_목록의_페이징_하한_미달은_400_C001(String parameter, String value) throws Exception {
		this.mockMvc.perform(get(PATH + "/me")
						.param(parameter, value)
						.header(HttpHeaders.AUTHORIZATION, bearer(865L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value(parameter));
	}

	/**
	 * <b>이 경로의 하한 값도 성공해야 한다</b> (sp-docs/conventions.md §9.2). 이유는
	 * {@link #페이징_하한_값은_200} 과 같고, <b>두 경로가 {@code MIN_PAGE}·{@code MIN_SIZE} 를
	 * 공유하므로 한쪽만 보면 부족하다</b> — 하한을 올리는 어긋남은 두 경로에 동시에 번진다
	 * (§9.2 의 "상수를 두 경로가 공유하면").
	 */
	@ParameterizedTest
	@CsvSource({"page,0", "size,1"})
	@DisplayName("내 글 목록의 page=0 · size=1 은 200 이고 그 값이 응답에 실린다")
	void 내_글_목록의_페이징_하한_값은_200(String parameter, int value) throws Exception {
		savedPost(867L, "하한값내글작성자", "하한 값 내 글", "본문");

		this.mockMvc.perform(get(PATH + "/me")
						.param(parameter, String.valueOf(value))
						.header(HttpHeaders.AUTHORIZATION, bearer(867L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data." + parameter).value(value))
				.andExpect(jsonPath("$.data.content.length()").value(1));
	}

	/**
	 * {@code /posts/me} 는 {@code page}·{@code size}·{@code sort} 를 받는다
	 * (sp-docs/api-contract.md §4.1). <b>{@code sort} 를 핸들러가 선언하지 않으면 그 값이
	 * 조용히 무시되고</b> 사용자는 정렬이 걸린 줄 알고 기본 순서를 받는다
	 * (sp-docs/AGENTS.md 불변식 9 의 silent degradation).
	 *
	 * <p>그래서 픽스처를 <b>기본 정렬과 다른 순서가 나오게</b> 둔다. 저장 순서가
	 * 1 → 3 → 2 (조회수)이므로 기본 {@code latest}({@code created_at DESC, id DESC})는
	 * 2 → 3 → 1 이고 {@code views} 는 3 → 2 → 1 이다. 두 순서가 다르므로
	 * {@code sort} 가 무시되면 단언이 깨진다.
	 */
	@Test
	@DisplayName("내 글 목록의 sort=views 는 view_count 내림차순이다")
	void 내_글_목록의_sort_가_적용된다() throws Exception {
		withViewCount(savedPost(866L, "정렬작성자", "내 글 조회수 1", "본문"), 1);
		withViewCount(savedPost(866L, "정렬작성자", "내 글 조회수 3", "본문"), 3);
		withViewCount(savedPost(866L, "정렬작성자", "내 글 조회수 2", "본문"), 2);

		MvcResult result = this.mockMvc.perform(get(PATH + "/me")
						.param("sort", "views")
						.header(HttpHeaders.AUTHORIZATION, bearer(866L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(3))
				.andReturn();

		assertThat(itemTitles(result))
				.as("sort 가 무시되면 기본 latest 순서(조회수 2 → 3 → 1)가 나온다")
				.containsExactly("내 글 조회수 3", "내 글 조회수 2", "내 글 조회수 1");
	}

	// --- 수정 (P-06, sp-docs/api-contract.md §4 §4.3, sp-docs/security.md §5.2) ---

	/**
	 * 검증표 "본인 글 수정 → 200, 반영됨". <b>응답은 상세와 같은 모양이다</b> — 쓰기 응답이
	 * 세 번째 모양을 만들지 않는다 (sp-docs/api-contract.md §4.3). 저장된 값은
	 * {@code PostServiceTest} 도 본다.
	 */
	@Test
	@DisplayName("본인 글 수정은 200 이고 상세 모양을 돌려준다")
	void 본인_글_수정은_200() throws Exception {
		Post post = savedPost(741L, "작성자닉네임", "원래 제목", "원래 본문");

		MvcResult result = this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(741L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("바뀐 제목", "바뀐 본문")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.id").value(post.getId()))
				.andExpect(jsonPath("$.data.title").value("바뀐 제목"))
				.andExpect(jsonPath("$.data.content").value("바뀐 본문"))
				.andExpect(jsonPath("$.data.writerId").value(741L))
				.andExpect(jsonPath("$.data.writerNickname").value("작성자닉네임"))
				.andReturn();

		assertThat(dataKeys(result))
				.as("수정 응답은 상세와 같은 모양이다 (sp-docs/api-contract.md §4.3)")
				.isEqualTo(DETAIL_KEYS);
		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("바뀐 제목");
	}

	/** 검증표 "타인 글 수정 → 403 {@code P002}". */
	@Test
	@DisplayName("타인 글 수정은 403 P002 다")
	void 타인_글_수정은_403_P002() throws Exception {
		Post post = savedPost(742L, "작성자닉네임", "원래 제목", "원래 본문");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(743L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("탈취 제목", "탈취 본문")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P002"));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("원래 제목");
	}

	/**
	 * 검증표 "ADMIN 이 타인 글 수정 → 403 {@code P002}". <b>삭제는 할 수 있다</b> —
	 * {@link #ADMIN_이_타인_글_삭제는_204()} 와 쌍으로 두 경로의 권한이 다른 것을 고정한다
	 * (sp-docs/security.md §5.2).
	 */
	@Test
	@DisplayName("ADMIN 이 타인 글을 수정하면 403 P002 다")
	void ADMIN_이_타인_글_수정은_403_P002() throws Exception {
		Post post = savedPost(744L, "작성자닉네임", "원래 제목", "원래 본문");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, adminBearer(745L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("관리자 수정", "관리자 본문")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P002"));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("원래 제목");
	}

	/** 검증표 "없는 글 수정 → 404 {@code P001}". */
	@Test
	@DisplayName("없는 글 수정은 404 P001 이다")
	void 없는_글_수정은_404_P001() throws Exception {
		this.mockMvc.perform(put(PATH + "/987654324")
						.header(HttpHeaders.AUTHORIZATION, bearer(746L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("제목", "본문")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));
	}

	/**
	 * 검증표 "토큰 없이 수정 → 401 {@code A001}". {@code SecurityConfig} 가
	 * {@code PUT /posts/{id}} 를 {@code authenticated} 로 선언한 것이 집행 지점이다
	 * (sp-docs/api-contract.md §4).
	 */
	@Test
	@DisplayName("토큰 없이 수정하면 401 A001 이고 내용이 바뀌지 않는다")
	void 토큰_없이_수정하면_401_A001() throws Exception {
		Post post = savedPost(747L, "작성자닉네임", "원래 제목", "원래 본문");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("무토큰 제목", "무토큰 본문")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("원래 제목");
	}

	/**
	 * 검증표에 없는 회귀 테스트다 — <b>{@code @Valid} 가 수정 핸들러에도 걸려 있는지</b>를 본다.
	 * {@link com.example.board.post.dto.PostUpdateRequest} 가 §4 의 제약을 작성과 같은 값으로
	 * 쓰므로 (sp-docs/requirements/board.md §4), 애노테이션이 빠지면 201자 제목이 저장된다 —
	 * 컬럼 폭이 200 이라 거기서 500 이 된다.
	 */
	@Test
	@DisplayName("제목 201자로 수정하면 400 C001 이고 내용이 바뀌지 않는다")
	void 입력_검증_위반_수정은_400_C001() throws Exception {
		Post post = savedPost(748L, "작성자닉네임", "원래 제목", "원래 본문");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(748L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("가".repeat(201), "본문")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().getTitle())
				.isEqualTo("원래 제목");
	}

	// --- 삭제 (P-07, sp-docs/api-contract.md §4) ---

	/**
	 * 검증표 "본인 글 삭제 → 204, {@code deleted = true}, 행 존재". <b>본문이 없다</b>
	 * (sp-docs/api-contract.md §4).
	 */
	@Test
	@DisplayName("본인 글 삭제는 204 이고 행이 deleted = true 로 남는다")
	void 본인_글_삭제는_204() throws Exception {
		Post post = savedPost(749L, "작성자닉네임", "삭제될 글", "본문");

		this.mockMvc.perform(delete(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(749L)))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(this.postRepository.findById(post.getId()))
				.as("Soft Delete 다 — 행이 남는다")
				.isPresent()
				.get()
				.extracting(Post::isDeleted)
				.isEqualTo(true);
	}

	/** 검증표 "ADMIN 이 타인 글 삭제 → 204". 수정과 달리 허용된다 (sp-docs/security.md §5.2). */
	@Test
	@DisplayName("ADMIN 이 타인 글을 삭제하면 204 다")
	void ADMIN_이_타인_글_삭제는_204() throws Exception {
		Post post = savedPost(750L, "작성자닉네임", "관리자가 지울 글", "본문");

		this.mockMvc.perform(delete(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, adminBearer(751L)))
				.andExpect(status().isNoContent());

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().isDeleted()).isTrue();
	}

	/** 검증표 "타인(일반)이 삭제 → 403 {@code P002}". */
	@Test
	@DisplayName("타인(일반 USER)이 삭제하면 403 P002 이고 글이 남는다")
	void 타인_일반_사용자의_삭제는_403_P002() throws Exception {
		Post post = savedPost(752L, "작성자닉네임", "남의 글", "본문");

		this.mockMvc.perform(delete(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(753L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P002"));

		assertThat(this.postRepository.findById(post.getId()).orElseThrow().isDeleted()).isFalse();
	}

	/**
	 * <b>수정·삭제 경로는 member 를 호출하지 않는다</b> (sp-docs/api-contract.md §4.2).
	 * 스텁이 받은 요청 수가 0 인 것으로 관측한다 — 이 클래스는 실제 {@code MemberClient} 를
	 * 쓰므로 호출이 있으면 스텁에 닿는다. 수정 시 스냅샷을 갱신하지 않는 것에서 따라오고,
	 * 그래서 <b>member 장애 중에도 수정·삭제가 된다.</b>
	 */
	@Test
	@DisplayName("수정·삭제는 member 호출이 0회다")
	void 수정과_삭제는_member_호출이_없다() throws Exception {
		Post post = savedPost(754L, "작성자닉네임", "원래 제목", "원래 본문");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(754L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("바뀐 제목", "바뀐 본문")))
				.andExpect(status().isOk());
		this.mockMvc.perform(delete(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(754L)))
				.andExpect(status().isNoContent());

		assertThat(REQUEST_COUNT.get())
				.as("수정·삭제에는 활성 프로필이 필요하지 않다 "
						+ "(sp-docs/api-contract.md §4.2, sp-docs/requirements/board.md §5 규칙 8)")
				.isZero();
	}

	// --- 카테고리 (P-11, sp-docs/requirements/board.md §8, sp-docs/api-contract.md §4.1 §4.4) ---
	//
	// sp-docs/plan/phase2-board.md B-19 검증표의 작성·입력·목록 행이다. 수정의 판정 순서와 공지
	// 권한은 PostServiceTest 가 본다.

	/** B-19 검증표 "{@code category} 없이 작성 → 201, {@code category: FREE}" (§8 의 작성 규칙). */
	@Test
	@DisplayName("category 없이 작성하면 201 이고 FREE 다")
	void category_없이_작성하면_FREE() throws Exception {
		respondWithActiveProfile(527L);

		MvcResult result = this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(527L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("카테고리 생략", "본문")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.category").value("FREE"))
				.andReturn();

		assertThat(this.postRepository.findById(dataId(result)).orElseThrow().getCategory())
				.isEqualTo(PostCategory.FREE);
	}

	/**
	 * B-19 검증표 "USER 가 {@code QNA} 로 작성 → 201, {@code category: QNA}". 공지가 아니면 역할을
	 * 보지 않는다 (§8).
	 */
	@Test
	@DisplayName("USER 가 QNA 로 작성하면 201 이고 QNA 다")
	void USER_가_QNA_로_작성하면_201() throws Exception {
		respondWithActiveProfile(528L);

		MvcResult result = this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(528L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("질문 글", "본문", "QNA")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.category").value("QNA"))
				.andReturn();

		assertThat(this.postRepository.findById(dataId(result)).orElseThrow().getCategory())
				.isEqualTo(PostCategory.QNA);
	}

	/**
	 * B-19 검증표 "USER 가 {@code NOTICE} 로 작성 → 403 {@code P003}, 글 0개". 프로필은 활성으로
	 * 스텁한다 — 거부가 프로필이 아니라 역할 때문인 것을 가르기 위해서다. 역할만 다른 반대
	 * 조합은 {@link #ADMIN_이_NOTICE_로_작성하면_201()} 이다.
	 */
	@Test
	@DisplayName("USER 가 NOTICE 로 작성하면 403 P003 이고 저장되지 않는다")
	void USER_가_NOTICE_로_작성하면_403_P003() throws Exception {
		respondWithActiveProfile(529L);

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(529L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("USER 의 공지", "본문", "NOTICE")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P003"));

		assertThat(rowCount(529L)).isZero();
	}

	/**
	 * B-19 검증표 <b>"프로필 없는 USER 가 {@code NOTICE} 로 작성 → 403 {@code P003}
	 * ({@code S002} 아님), {@code MemberClient} 호출 0회"</b>. 작성은 {@code P003} 을 member
	 * 호출보다 먼저 판정한다 — 「Claim 만으로 정해지는 거부에 원격 호출을 쓰지 않는다」
	 * (sp-docs/requirements/board.md §8 의 판정 순서).
	 *
	 * <p>스텁은 {@link #프로필_미등록은_403_S002()} 와 같은 응답(200, 결과에서 제외)이다. 그
	 * 테스트는 member 를 불러 {@code S002} 를 받고, 이 테스트는 부르지 않아 {@code P003} 이다.
	 * <b>호출 수를 코드와 따로 단언한다</b> — 검증표가 요구한 관측이고, 「원격 호출을 쓰지
	 * 않는다」 를 거부 코드가 아니라 스텁이 받은 요청으로 직접 본다.
	 *
	 * <p><b>판정을 member 호출 뒤로 옮긴 변이에서 빨개진 것은 이 테스트 하나였다</b> (B-19 실측,
	 * {@code S002} 가 나왔다). 활성 프로필로 스텁한 {@link #USER_가_NOTICE_로_작성하면_403_P003()}
	 * 은 member 를 부른 뒤에도 {@code P003} 이 나서 녹색이었다. 그 변이에서 순서가 응답에 드러나는
	 * 것은 member 호출이 예외로 끝날 때다 — 프로필이 없거나 탈퇴했으면 {@code S002}, 그 밖의
	 * 실패면 {@code S001} 이 {@code P003} 대신 나간다 ({@code MemberClient.findActiveNickname},
	 * 2026-11-16 코드 읽기).
	 */
	@Test
	@DisplayName("프로필 없는 USER 가 NOTICE 로 작성하면 S002 가 아니라 403 P003 이고 member 호출이 0회다")
	void 프로필_없는_USER_의_공지_작성은_P003_이고_member_를_부르지_않는다() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 200, envelope("")));

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(530L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("프로필 없는 공지", "본문", "NOTICE")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P003"));

		assertThat(REQUEST_COUNT.get())
				.as("Claim 만으로 정해지는 거부에 원격 호출을 쓰지 않는다 "
						+ "(sp-docs/requirements/board.md §8)")
				.isZero();
		assertThat(rowCount(530L)).isZero();
	}

	/**
	 * B-19 검증표 "ADMIN 이 {@code NOTICE} 로 작성 → 201". ADMIN 이 공지로 쓰는 것은 자기 글이다
	 * (§8 의 "ADMIN 과 남의 글"). {@link #USER_가_NOTICE_로_작성하면_403_P003()} 과 역할만 다르다.
	 */
	@Test
	@DisplayName("ADMIN 이 NOTICE 로 작성하면 201 이고 NOTICE 다")
	void ADMIN_이_NOTICE_로_작성하면_201() throws Exception {
		respondWithActiveProfile(531L);

		MvcResult result = this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, adminBearer(531L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("관리자 공지", "본문", "NOTICE")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.category").value("NOTICE"))
				.andReturn();

		assertThat(this.postRepository.findById(dataId(result)).orElseThrow().getCategory())
				.isEqualTo(PostCategory.NOTICE);
	}

	/**
	 * B-19 검증표 "본문 {@code category: "BOGUS"} → 400 {@code C001}" 의 작성 쪽이다. 본문의
	 * 잘못된 enum 은 JSON 해석 실패다 (sp-docs/requirements/board.md §4,
	 * sp-docs/api-contract.md §4.4). 같은 값이 쿼리로 오면
	 * {@link #쿼리의_잘못된_category_는_400_C002()} 처럼 {@code C002} 다 — 들어온 자리가 코드를
	 * 가른다 (§4.1).
	 */
	@Test
	@DisplayName("작성 본문의 category 가 BOGUS 면 400 C001 이고 저장되지 않는다")
	void 작성_본문의_잘못된_category_는_400_C001() throws Exception {
		respondWithActiveProfile(532L);

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(532L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("잘못된 카테고리", "본문", "BOGUS")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		assertThat(rowCount(532L)).isZero();
	}

	/** 같은 행의 수정 쪽이다 — §4.4 가 작성·수정 본문을 함께 정한다. 글이 그대로다. */
	@Test
	@DisplayName("수정 본문의 category 가 BOGUS 면 400 C001 이고 글이 그대로다")
	void 수정_본문의_잘못된_category_는_400_C001() throws Exception {
		Post post = savedPost(755L, "작성자닉네임", "원래 제목", "원래 본문");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(755L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("바뀐 제목", "바뀐 본문", "BOGUS")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		Post saved = this.postRepository.findById(post.getId()).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("원래 제목");
		assertThat(saved.getCategory()).isEqualTo(PostCategory.FREE);
	}

	/**
	 * 본문의 {@code category} 가 <b>숫자</b>여도 400 {@code C001} 이다 — 따옴표 없는 {@code 1} 도,
	 * 따옴표 친 {@code "2"} 도. 본문의 {@code category} 는 {@code NOTICE}·{@code FREE}·{@code QNA}
	 * 중 하나이거나 생략이고, 잘못된 값은 JSON 해석 실패라 400 {@code C001} 이다
	 * (sp-docs/requirements/board.md §4, sp-docs/api-contract.md §4.4).
	 *
	 * <p><b>막는 것은 {@code application.yml} 의
	 * {@code spring.jackson.deserialization.fail-on-numbers-for-enums} 다.</b> 이 테스트는 test
	 * 프로필의 컨텍스트에서 그 설정이 적용된 응답을 본다 — 그 키를 지우거나 {@code false} 로 바꾸면
	 * 이 테스트와 {@link #수정_본문의_숫자_category_는_400_C001(String)} 이 값마다 빨개졌다(작성 201,
	 * 수정 200. 2026-11-16 실측).
	 */
	@ParameterizedTest
	@ValueSource(strings = {"1", "\"2\""})
	@DisplayName("작성 본문의 category 가 숫자(1 · \"2\")면 400 C001 이고 저장되지 않는다")
	void 작성_본문의_숫자_category_는_400_C001(String rawCategory) throws Exception {
		respondWithActiveProfile(533L);

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(533L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyWithRawCategory("숫자 카테고리", "본문", rawCategory)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		assertThat(rowCount(533L)).isZero();
	}

	/**
	 * {@link #작성_본문의_숫자_category_는_400_C001(String)} 의 수정 쪽이다 — §4.4 가 작성·수정 본문을
	 * 함께 정한다.
	 *
	 * <p>글은 <b>ADMIN 자신의 {@code NOTICE} 글</b>이다. 2026-11-16 의 {@link PostCategory} 선언
	 * 순서에서 서수 1·2 는 {@code FREE}·{@code QNA} 라 둘 다 지금 값과 다르고, ADMIN 은 자기 글을
	 * 공지에서 뺄 수 있다 (sp-docs/requirements/board.md §8). 숫자를 받아들이는 것은 상태 단언이
	 * 잡는다. 제목·카테고리 단언은 400 인데 글이 바뀐 경우를 본다 — 작성자가 USER 면 공지에서 빼는
	 * 수정이 403 {@code P003} 이라 그 단언이 가를 것이 없다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"1", "\"2\""})
	@DisplayName("수정 본문의 category 가 숫자(1 · \"2\")면 400 C001 이고 글이 그대로다")
	void 수정_본문의_숫자_category_는_400_C001(String rawCategory) throws Exception {
		Post post = savedPost(756L, PostCategory.NOTICE, "관리자닉네임", "원래 공지");

		this.mockMvc.perform(put(PATH + "/" + post.getId())
						.header(HttpHeaders.AUTHORIZATION, adminBearer(756L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(bodyWithRawCategory("바뀐 제목", "바뀐 본문", rawCategory)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		Post saved = this.postRepository.findById(post.getId()).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("원래 공지");
		assertThat(saved.getCategory()).isEqualTo(PostCategory.NOTICE);
	}

	/**
	 * B-19 검증표 "쿼리 {@code category=BOGUS} → 400 {@code C002}". enum 바인딩의 실패이고
	 * ({@code MethodArgumentTypeMismatchException}, sp-docs/conventions.md §6.1) {@code sort} 의
	 * 잘못된 값과 같은 길이다 (sp-docs/api-contract.md §4.1).
	 */
	@Test
	@DisplayName("목록 쿼리의 category 가 BOGUS 면 400 C002 다")
	void 쿼리의_잘못된_category_는_400_C002() throws Exception {
		this.mockMvc.perform(get(PATH).param("category", "BOGUS"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C002"));
	}

	/**
	 * B-19 검증표 "{@code category=NOTICE} + 검색 + 정렬 → 세 조건이 AND 로 걸린다" 의 HTTP
	 * 쪽이다 — <b>쿼리 파라미터가 조건에 닿는지</b>를 본다. 같은 작성자의 {@code FREE} 글이
	 * 있으므로 핸들러가 {@code category} 를 넘기지 않으면 그 글이 섞이고, 다른 작성자의 공지가
	 * 있으므로 검색 조건이 빠지면 그 글이 섞인다. 정렬은 저장 순서와 조회수 순서를 어긋나게
	 * 두어 순서로 본다 — {@code latest} 로 나오면 단언이 깨진다.
	 *
	 * <p>건수 쿼리까지 AND 인지는 꽉 찬 페이지와 각 조건이 배제하는 행이 있어야 관측되고
	 * (sp-docs/process/review-policy.md §4.1), 그것은
	 * {@code PostQueryRepositoryTest.카테고리는_검색_정렬과_AND_로_걸린다()} 가 본다. 그쪽은
	 * 핸들러를 지나지 않으므로 이 테스트를 대신하지 않는다 — 핸들러가 {@code category} 대신
	 * {@code null} 을 넘기는 변이에서 빨개진 것은 이 테스트 하나였다 (B-19 실측).
	 *
	 * <p><b>항목의 {@code category}·{@code likeCount} 값도 여기서 본다</b> —
	 * {@link #LIST_ITEM_KEYS} 대조는 키만 본다. {@code like_count} 는 '공지 조회수 1' 한 행만 2 로
	 * 두어 0 이 아니고 그 행의 {@code comment_count}(0)·{@code view_count}(1)와도 다르게 한다.
	 * {@code PostResponse} 가 {@code category} 를 {@code FREE} 로 고정한 변이와 {@code likeCount}
	 * 자리에 {@code commentCount} 를 실은 변이가 B-19 1라운드 리뷰에서 녹색이었고, 이 단언들을 더한
	 * 뒤로는 각각 이 테스트에서 빨개진다 (2026-11-16 실측).
	 */
	@Test
	@DisplayName("목록의 category=NOTICE 는 검색·정렬과 함께 걸리고 항목은 그 글의 category·likeCount 를 싣는다")
	void 목록의_category_는_검색_정렬과_함께_걸린다() throws Exception {
		withViewCount(savedPost(628L, PostCategory.NOTICE, "공지필터작성자", "공지 조회수 2"), 2);
		Post likedNotice = savedPost(628L, PostCategory.NOTICE, "공지필터작성자", "공지 조회수 1");
		withViewCount(likedNotice, 1);
		withLikeCount(likedNotice, 2);
		withViewCount(savedPost(628L, PostCategory.FREE, "공지필터작성자", "자유 조회수 3"), 3);
		withViewCount(savedPost(629L, PostCategory.NOTICE, "다른공지작성자", "다른 작성자의 공지"), 4);

		MvcResult result = this.mockMvc.perform(get(PATH)
						.param("category", "NOTICE")
						.param("searchType", "WRITER")
						.param("keyword", "공지필터작성자")
						.param("sort", "views"))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(itemTitles(result))
				.as("세 조건이 함께 걸린다 — 자유 글·다른 작성자의 공지가 섞이면 조건이 빠진 것이고, "
						+ "나중에 저장한 '공지 조회수 1' 이 앞이면 정렬이 latest 로 떨어진 것이다")
				.containsExactly("공지 조회수 2", "공지 조회수 1");
		assertThat(itemCategories(result))
				.as("항목의 category 는 그 글의 값이다 — NOTICE 로 걸렀으니 전부 NOTICE 다 (§4.3)")
				.containsOnly("NOTICE");
		assertThat(itemLikeCounts(result))
				.as("항목의 likeCount 는 post.like_count 다 (§4.3) — '공지 조회수 1' 만 2 로 두었다")
				.containsExactly(0, 2);
	}

	/**
	 * B-19 검증표 "{@code category} 없는 목록 → 공지가 들어 있다". 생략은 <b>모든 카테고리</b>이고
	 * 기본값이 없다 (§8 의 목록 필터, sp-docs/api-contract.md §4.1). 세 카테고리를 다 두어 어느
	 * 하나로 기본값을 채우는 어긋남이 모두 보이게 한다.
	 */
	@Test
	@DisplayName("category 없는 목록에는 공지를 포함한 모든 카테고리가 들어 있다")
	void category_없는_목록에는_공지가_들어_있다() throws Exception {
		savedPost(630L, PostCategory.NOTICE, "전체카테고리작성자", "전체 중 공지");
		savedPost(630L, PostCategory.FREE, "전체카테고리작성자", "전체 중 자유");
		savedPost(630L, PostCategory.QNA, "전체카테고리작성자", "전체 중 질문");

		MvcResult result = this.mockMvc.perform(get(PATH)
						.param("searchType", "WRITER")
						.param("keyword", "전체카테고리작성자"))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(itemTitles(result))
				.containsExactlyInAnyOrder("전체 중 공지", "전체 중 자유", "전체 중 질문");
		List<String> titles = itemTitles(result);
		List<String> categories = itemCategories(result);
		assertThat(java.util.stream.IntStream.range(0, titles.size())
						.mapToObj(i -> titles.get(i) + "=" + categories.get(i)).toList())
				.as("항목의 category 는 그 글의 값이다 (§4.3) — 세 카테고리가 섞여 있어 상수로는 맞출 수 없다")
				.containsExactlyInAnyOrder("전체 중 공지=NOTICE", "전체 중 자유=FREE", "전체 중 질문=QNA");
	}

	/**
	 * B-19 완료 기준 "{@code GET /posts/me} 에는 이 필터를 두지 않는다" (sp-docs/api-contract.md
	 * §4.1, sp-docs/requirements/board.md §8). 자기 {@code FREE} 글 하나를 두고
	 * {@code category=NOTICE} 를 붙여 부른다 — 핸들러가 그 파라미터를 받아 걸면 글이 빠진다.
	 * {@link #writerId_파라미터는_무시된다()} 와 같이 Spring 이 선언되지 않은 파라미터를 무시하는
	 * 것에 기댄다.
	 */
	@Test
	@DisplayName("내가 쓴 글 목록은 category 파라미터를 걸지 않는다")
	void 내_글_목록은_category_를_걸지_않는다() throws Exception {
		savedPost(869L, PostCategory.FREE, "내글카테고리작성자", "내 자유 글");

		MvcResult result = this.mockMvc.perform(get(PATH + "/me")
						.param("category", "NOTICE")
						.header(HttpHeaders.AUTHORIZATION, bearer(869L)))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(itemTitles(result)).containsExactly("내 자유 글");
	}

	// --- 헬퍼 ---

	private void assertCreateFails(
			String title,
			ResultMatcher expectedStatus,
			String expectedCode) throws Exception {

		this.mockMvc.perform(post(PATH)
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(title, "본문")))
				.andExpect(expectedStatus)
				.andExpect(jsonPath("$.error.code").value(expectedCode));

		assertThat(rowCount(ACCOUNT_ID))
				.as("확인이 실패했으면 글이 저장되지 않는다 (sp-docs/requirements/board.md §3.0)")
				.isZero();
		assertThat(rowCountByTitle(title)).isZero();
	}

	private static String body(String title, String content) {
		return "{\"title\":%s,\"content\":%s}".formatted(quote(title), quote(content));
	}

	/** {@code category} 를 실은 본문. 값은 enum 밖의 문자열도 받는다 — 잘못된 값의 케이스다. */
	private static String body(String title, String content, String category) {
		return bodyWithRawCategory(title, content, quote(category));
	}

	/**
	 * {@code category} 자리에 JSON 값을 감싸지 않고 그대로 싣는 본문. 따옴표 없는 숫자
	 * ({@code 1})와 따옴표 친 숫자({@code "2"})를 따로 보내려고 둔다.
	 */
	private static String bodyWithRawCategory(String title, String content, String rawCategory) {
		return "{\"title\":%s,\"content\":%s,\"category\":%s}".formatted(
				quote(title), quote(content), rawCategory);
	}

	/** JSON 문자열 리터럴로 감싼다. 제목에 공백·따옴표가 들어가는 케이스가 있다. */
	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String bearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, ROLE);
	}

	/**
	 * {@code role = ADMIN} 토큰 (sp-docs/api-contract.md §6). ADMIN 이라서 열리는 것과 ADMIN
	 * 이어도 닫히는 것을 함께 보는 데 쓴다 (sp-docs/security.md §5.2) — 예: 타인 글의 삭제는
	 * 열리고 수정은 닫힌다.
	 */
	private static String adminBearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, ROLE_ADMIN);
	}

	private Post savedPost(long writerId, String nickname, String title, String content) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(nickname)
				.title(title)
				.content(content)
				.build());
	}

	/** 카테고리를 정해 둔 글. 목록 필터의 픽스처다. */
	private Post savedPost(long writerId, PostCategory category, String nickname, String title) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(nickname)
				.title(title)
				.content("본문")
				.category(category)
				.build());
	}

	/**
	 * 조회수를 박는다. {@code view_count} 기본값이 0 이라 손대지 않으면 전체가 동률이고
	 * {@code sort=views} 가 적용됐는지 관측되지 않는다 (sp-docs/api-contract.md §7.1).
	 */
	private void withViewCount(Post post, int times) {
		for (int i = 0; i < times; i++) {
			post.increaseViewCount();
		}
		this.postRepository.saveAndFlush(post);
	}

	/**
	 * 좋아요 수를 박는다. 좁은 UPDATE({@link PostRepository#increaseLikeCount})를 자기 트랜잭션으로
	 * 커밋하고, 영향 행이 1 이 아니면 그 자리에서 터진다 — 픽스처가 만들어지지 않은 것을 단언
	 * 실패와 가른다.
	 *
	 * <p>이 문장은 엔티티를 지나지 않으므로 손에 든 {@code Post} 는 증가 전 {@code likeCount} 를
	 * 들고 있다 — 그 객체를 이 뒤에 다시 저장하지 않는다. {@link #withViewCount} 가 그렇게
	 * 저장하므로 함께 쓸 때는 그것을 먼저 부른다.
	 */
	private void withLikeCount(Post post, int times) {
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);
		for (int i = 0; i < times; i++) {
			Integer updated = transaction.execute(
					status -> this.postRepository.increaseLikeCount(post.getId()));
			if (updated == null || updated != 1) {
				throw new IllegalStateException("like_count 의 좁은 UPDATE 가 1 행이 아니다: " + updated);
			}
		}
	}

	private int rowCount(long writerId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post WHERE writer_id = ?", Integer.class, writerId);
		return count == null ? 0 : count;
	}

	private int rowCountByTitle(String title) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post WHERE title = ?", Integer.class, title);
		return count == null ? 0 : count;
	}

	private static Set<String> dataKeys(MvcResult result) throws Exception {
		Map<String, Object> data = JsonPath.read(bodyOf(result), "$.data");
		return data.keySet();
	}

	private static Set<String> firstItemKeys(MvcResult result) throws Exception {
		Map<String, Object> item = JsonPath.read(bodyOf(result), "$.data.content[0]");
		return item.keySet();
	}

	/**
	 * 목록 항목의 제목을 응답 순서대로 읽는다. 어느 행이 실렸는지와 그 순서를 함께 본다 —
	 * {@code id}·{@code writerId} 는 JSON 숫자의 자바 타입에 기대야 하지만 제목은 문자열이다.
	 */
	private static List<String> itemTitles(MvcResult result) throws Exception {
		return JsonPath.read(bodyOf(result), "$.data.content[*].title");
	}

	/** 목록 항목의 {@code category} 를 응답 순서대로 읽는다. */
	private static List<String> itemCategories(MvcResult result) throws Exception {
		return JsonPath.read(bodyOf(result), "$.data.content[*].category");
	}

	/**
	 * 목록 항목의 {@code likeCount} 를 응답 순서대로 읽는다. JSON 숫자가 어느 자바 타입으로
	 * 읽히는지에 기대지 않으려고 {@code int} 로 맞춘다.
	 */
	private static List<Integer> itemLikeCounts(MvcResult result) throws Exception {
		List<Number> likeCounts = JsonPath.read(bodyOf(result), "$.data.content[*].likeCount");
		return likeCounts.stream().map(Number::intValue).toList();
	}

	private static long dataId(MvcResult result) throws Exception {
		return ((Number) JsonPath.read(bodyOf(result), "$.data.id")).longValue();
	}

	private static String bodyOf(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** 접속 주소를 정한다. {@code MockMvc} 의 기본값은 {@code 127.0.0.1} 이다. */
	private static RequestPostProcessor remoteAddr(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	/**
	 * 이 주소의 비로그인 판정 키 — {@code i:} + {@code HMAC-SHA256(비밀키, 주소)} 의 소문자 hex
	 * (sp-docs/requirements/board.md §9.1). 비밀키는 컨텍스트의 설정값 문자열의 UTF-8 바이트다
	 * (sp-docs/tech-stack.md §4.5). {@code application-test.yml} 의 리터럴을 옮겨 적지 않는다 — 환경변수
	 * {@code VIEW_COUNT_HMAC_SECRET} 이 그 값을 덮는 실행에서도 서버와 같은 값을 쓴다.
	 */
	private String 주소의_판정_키(String address) throws GeneralSecurityException {
		byte[] secret = this.environment.getRequiredProperty("view-count.hmac-secret")
				.getBytes(StandardCharsets.UTF_8);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret, "HmacSHA256"));
		return "i:" + HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)));
	}

	private List<String> 판정_키(Post post) {
		return this.jdbcTemplate.queryForList(
				"SELECT viewer_key FROM post_view WHERE post_id = ?", String.class, post.getId());
	}

	private List<LocalDateTime> 기준_시각들(Post post) {
		return this.jdbcTemplate.queryForList(
				"SELECT viewed_at FROM post_view WHERE post_id = ?", LocalDateTime.class, post.getId());
	}

	private int 판정_행_수(long postId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post_view WHERE post_id = ?", Integer.class, postId);
		return count == null ? 0 : count;
	}

	/** 판정 행의 기준 시각을 옮긴다 — 시간이 흐른 것을 흉내 낸다. 영향 행이 1 이 아니면 그 자리에서 터진다. */
	private void 기준_시각을_옮긴다(Post post, LocalDateTime viewedAt) {
		int updated = this.jdbcTemplate.update(
				"UPDATE post_view SET viewed_at = ? WHERE post_id = ?", viewedAt, post.getId());
		if (updated != 1) {
			throw new IllegalStateException("판정 행이 1 행이 아니다: " + updated);
		}
	}

	private long 커밋된_조회수(Post post) {
		Long count = this.jdbcTemplate.queryForObject(
				"SELECT view_count FROM post WHERE id = ?", Long.class, post.getId());
		return count == null ? 0 : count;
	}

	/**
	 * 좋아요의 근거 행만 둔다 — {@code liked} 는 그 행이 있는지다 (sp-docs/domain-model.md §6.1).
	 * 영향 행이 1 이 아니면 그 자리에서 터진다.
	 */
	private void 누른_것으로_둔다(Post post, long accountId) {
		int inserted = this.jdbcTemplate.update(
				"INSERT INTO post_like (post_id, account_id, created_at) VALUES (?, ?, ?)",
				post.getId(), accountId, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
		if (inserted != 1) {
			throw new IllegalStateException("좋아요 픽스처가 1 행이 아니다: " + inserted);
		}
	}

	/**
	 * 같은 키의 판정 행과 +1 을 다른 스레드에서 커밋한다 — 같은 키의 동시 첫 조회가 먼저 끝난 모양이다.
	 * 판정 행의 영향 행 수를 돌려준다.
	 */
	private int 다른_트랜잭션에서_같은_키로_센다(Long postId, String viewerKey) {
		return 다른_스레드에서(() -> {
			int inserted = this.jdbcTemplate.update(
					"INSERT INTO post_view (post_id, viewer_key, viewed_at) VALUES (?, ?, ?)",
					postId, viewerKey, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
			this.jdbcTemplate.update("UPDATE post SET view_count = view_count + 1 WHERE id = ?", postId);
			return inserted;
		});
	}

	/** 다른 조회자의 +1 을 다른 스레드에서 커밋한다. 영향 행 수를 돌려준다. */
	private int 다른_트랜잭션에서_조회수를_올린다(Long postId) {
		return 다른_스레드에서(() -> this.jdbcTemplate.update(
				"UPDATE post SET view_count = view_count + 1 WHERE id = ?", postId));
	}

	/**
	 * 쓰기를 <b>다른 스레드에서</b> 커밋까지 돌린다. 부르는 스레드에는 판정 트랜잭션이 묶여 있어 같은
	 * 스레드의 {@code JdbcTemplate} 은 그 트랜잭션에 참여한다 — 따로 커밋되지 않는다.
	 */
	private static int 다른_스레드에서(Callable<Integer> 쓰기) {
		ExecutorService 다른_스레드 = Executors.newSingleThreadExecutor();
		try {
			return 다른_스레드.submit(쓰기).get(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException | ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("끼운 쓰기가 커밋되지 않았다 — 이 측정은 끼워넣기를 볼 수 없다", ex);
		}
		finally {
			다른_스레드.shutdownNow();
		}
	}

	// --- member 스텁 서버 (sp-docs/api-contract.md §5 의 봉투) ---

	private static void respondWithActiveProfile(long accountId) {
		RESPONDER.set(exchange -> writeResponse(exchange, 200, envelope("""
				{"accountId": %d, "nickname": "%s", "deleted": false}"""
				.formatted(accountId, MEMBER_NICKNAME))));
	}

	/** sp-docs/api-contract.md §5 의 응답 봉투. {@code members} 는 {@code data} 아래에 있다. */
	private static String envelope(String members) {
		return "{\"success\":true,\"data\":{\"members\":[%s]},\"error\":null}".formatted(members);
	}

	private static void writeResponse(HttpExchange exchange, int status, String body) {
		try {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		} catch (IOException e) {
			// 클라이언트가 타임아웃으로 끊은 뒤의 쓰기 실패다
			throw new UncheckedIOException(e);
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static HttpServer startStub() {
		try {
			// 포트 0 — 운영체제가 빈 포트를 준다
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", exchange -> {
				REQUEST_COUNT.incrementAndGet();
				LAST_REQUEST_BODY.set(readBody(exchange));
				assertRequestPath(exchange);
				RESPONDER.get().respond(exchange);
			});
			// 타임아웃 케이스의 핸들러가 다른 요청을 막지 않도록 요청마다 스레드를 쓴다
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
			return server;
		} catch (IOException e) {
			throw new IllegalStateException("스텁 서버를 띄울 수 없다", e);
		}
	}

	/** 벌크 엔드포인트 하나만 호출한다 (§5). 다른 경로로 오면 테스트가 그 자리에서 깨진다. */
	private static void assertRequestPath(HttpExchange exchange) {
		if (!BULK_PATH.equals(exchange.getRequestURI().getPath())) {
			throw new IllegalStateException(
					"벌크 경로가 아닌 요청을 받았다: " + exchange.getRequestURI().getPath());
		}
	}

	private static String readBody(HttpExchange exchange) {
		try (InputStream in = exchange.getRequestBody()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@FunctionalInterface
	private interface StubResponder {
		void respond(HttpExchange exchange);
	}

}
