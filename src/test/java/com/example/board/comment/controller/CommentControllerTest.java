package com.example.board.comment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.client.MemberClient;
import com.example.board.comment.entity.Comment;
import com.example.board.comment.repository.CommentRepository;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * sp-docs/plan/phase1.md §6 B-08 의 "검증" 표 중 HTTP 쪽 — 상태 코드·응답 키 집합·권한
 * 판정·404 와 403 의 선후·입력 검증·member 응답별 판정 — 을 테스트로 옮긴 것이다. 저장된 값과
 * 집계·목록 순서는 {@link com.example.board.comment.service.CommentServiceTest} 가 본다.
 *
 * <p>정본은 sp-docs/api-contract.md §4 (경로·성공 코드) §4.2 §4.4 (404 와 403 의 선후) §4.5
 * (응답의 한 모양) §5 §5.1 (실패 판정) §8.3 (에러 코드), 권한은 sp-docs/security.md §5.2,
 * 입력 검증은 sp-docs/requirements/board.md §4 다.
 *
 * <p><b>실제 {@link MemberClient} 를 쓰고 member 의 응답만 스텁 서버로 흉내 낸다.</b> 검증표의
 * 네 줄(프로필 미등록 · 탈퇴 · 타임아웃 · 404)이 서로 다른 것은 <b>member 가 돌려준 응답</b>
 * 뿐이므로, 목으로 예외를 던지면 네 줄이 같은 테스트가 된다. 스텁 서버를 쓰면 "member 응답 →
 * 판정 → HTTP 응답 → 저장 안 됨" 이 한 줄에서 끝까지 관측된다.
 *
 * <p>스텁 응답은 §5 의 봉투를 그대로 쓴다
 * ({@code {"success": true, "data": {"members": [...]}, "error": null}}) — 배열이 최상위가
 * 아니다.
 *
 * <p>픽스처의 {@code writerId} 는 831~859 를 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2 DB 를
 * 보므로 (sp-docs/tech-stack.md §5.3) 이미 쓰이는 대역과 겹치지 않게 둔다. 이 클래스는 롤백에
 * 기대지 않고 커밋하므로 {@code @AfterEach} 가 자기 대역만 지운다 —
 * {@code comment.post_id} 에 FK 가 있으므로 ({@code fk_comment_post_id}) 댓글을 먼저 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommentControllerTest {

	private static final long ACCOUNT_ID = 831L;

	/** sp-docs/api-contract.md §6 의 {@code role} claim 값. */
	private static final String ROLE = "USER";

	private static final String ROLE_ADMIN = "ADMIN";

	/** member 의 내부 API 가 돌려주는 닉네임. 스냅샷의 유일한 출처다. */
	private static final String MEMBER_NICKNAME = "member가준닉네임";

	/** 정본: sp-docs/api-contract.md §5. 벌크 하나뿐이다. */
	private static final String BULK_PATH = "/internal/v1/members/bulk";

	/**
	 * sp-docs/api-contract.md §4.5 가 정한 댓글 응답의 키 집합. <b>리터럴로 적는다</b> — DTO 의
	 * 필드에서 뽑아 오면 필드가 늘거나 줄 때 기대값이 함께 움직여 단언이 아무것도 보지 못한다.
	 *
	 * <p>{@code deleted}·{@code postId}·{@code updatedAt} 이 없다 (§4.5).
	 */
	private static final Set<String> COMMENT_KEYS = Set.of(
			"id", "writerId", "writerNickname", "content", "createdAt");

	/**
	 * {@code created_at} 을 직접 박을 때 쓰는 값. 과거로 두어 {@code updated_at} 과 갈라
	 * 놓는다 — {@link #응답의_createdAt_은_저장된_created_at_이다()} 에 이유가 있다.
	 */
	private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 3, 1, 12, 0, 0);

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
	private CommentRepository commentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void 스텁_초기화() {
		REQUEST_COUNT.set(0);
		LAST_REQUEST_BODY.set(null);
		respondWithActiveProfile(ACCOUNT_ID);
	}

	@AfterEach
	void 픽스처_정리() {
		this.jdbcTemplate.update("DELETE FROM comment WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 831 AND 859)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 831 AND 859");
	}

	// --- 작성 (C-01, sp-docs/api-contract.md §4) ---

	/** 검증표 "댓글 작성 → 201" 과 "댓글 응답 키 집합 → 다섯 키 정확히" (§4.5). */
	@Test
	@DisplayName("정상 작성은 201 이고 §4.5 의 다섯 키를 돌려준다")
	void 정상_작성은_201() throws Exception {
		Post post = savedPost(ACCOUNT_ID);

		MvcResult result = this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("정상 댓글")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.content").value("정상 댓글"))
				.andExpect(jsonPath("$.data.writerId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.data.writerNickname").value(MEMBER_NICKNAME))
				.andExpect(jsonPath("$.data.createdAt").exists())
				.andReturn();

		assertThat(dataKeys(result)).isEqualTo(COMMENT_KEYS);
	}

	/**
	 * 검증표 "댓글 응답 키 집합 → <b>다섯 키 정확히</b>. 리터럴 집합으로 대조한다" (§4.5).
	 *
	 * <p><b>세 엔드포인트를 한 테스트에서 본다.</b> §4.5 가 못박은 것은 "세 엔드포인트가 같은
	 * 모양" 이므로, 한 쪽만 보면 나머지가 갈라져도 녹색이다.
	 */
	@Test
	@DisplayName("작성 201 · 목록 항목 · 수정 200 이 모두 같은 다섯 키다")
	void 세_엔드포인트의_응답이_같은_다섯_키다() throws Exception {
		Post post = savedPost(832L);

		MvcResult created = this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("모양 확인")))
				.andExpect(status().isCreated())
				.andReturn();
		long commentId = dataId(created);

		MvcResult list = this.mockMvc.perform(get(commentsPath(post.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andReturn();

		MvcResult updated = this.mockMvc.perform(put(commentPath(commentId))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("모양 확인 수정")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content").value("모양 확인 수정"))
				.andReturn();

		assertThat(dataKeys(created)).as("작성 201").isEqualTo(COMMENT_KEYS);
		assertThat(firstItemKeys(list)).as("목록 항목").isEqualTo(COMMENT_KEYS);
		assertThat(dataKeys(updated)).as("수정 200").isEqualTo(COMMENT_KEYS);
	}

	/**
	 * 다섯 키 중 {@code createdAt} 의 <b>값</b>을 못박는다. 키 집합만 보면
	 * <b>{@code createdAt} 자리에 {@code updated_at} 을 싣는 어긋남이 조용하다</b> — 키는 그대로
	 * 다섯이고 타입도 같다. 실측: {@code CommentResponse.from} 의 {@code getCreatedAt()} 을
	 * {@code getUpdatedAt()} 으로 바꾸면 이 테스트가 깨진다.
	 *
	 * <p><b>시각을 직접 박아 두 값을 갈라 둔다.</b> 작성 직후에는 {@code created_at} 과
	 * {@code updated_at} 이 같아서 어느 쪽을 실어도 같은 값이 나온다 — 구별되지 않는다.
	 * {@code created_at} 을 과거로 박으면 그 순간부터 두 값이 다르다.
	 *
	 * <p><b>수정 응답에서도 본다.</b> 거기가 운영에서 두 값이 갈라지는 자리다 — 편집 시각을
	 * 생성 시각으로 보고하게 되고, {@code updatedAt} 을 응답에 넣지 않기로 한 결정과 정면으로
	 * 어긋난다 (sp-docs/api-contract.md §4.5).
	 */
	@Test
	@DisplayName("목록·수정 응답의 createdAt 은 저장된 created_at 이고 updated_at 이 아니다")
	void 응답의_createdAt_은_저장된_created_at_이다() throws Exception {
		Post post = savedPost(858L);
		Comment comment = 작성한_댓글(post, "시각 확인");
		setCreatedAt(comment.getId(), FIXED_TIME);

		MvcResult list = this.mockMvc.perform(get(commentsPath(post.getId())))
				.andExpect(status().isOk())
				.andReturn();
		MvcResult updated = this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("시각 확인 수정")))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(저장된_시각(comment.getId(), "updated_at"))
				.as("두 값이 갈라져야 아래 단언이 둘을 구별한다 — 같으면 무엇을 실어도 통과한다")
				.isNotEqualTo(FIXED_TIME);
		assertThat(저장된_시각(comment.getId(), "created_at"))
				.as("수정이 created_at 을 건드리지 않는다 (sp-docs/domain-model.md §1.1)")
				.isEqualTo(FIXED_TIME);
		// 세 엔드포인트가 CommentResponse.from 하나를 공유하므로 한 변이로는 둘 중 하나만
		// 반증된다. 운영에서 두 값이 갈라지는 자리인 수정 응답을 앞에 둔다 — getUpdatedAt()
		// 변이가 거기서 빨개지는 것을 측정했다. 목록 단언은 경로마다 매핑이 갈릴 때의 가드다
		assertThat(응답의_createdAt(updated, "$.data.createdAt"))
				.as("수정 응답의 createdAt 은 created_at 이다 — 편집 시각을 싣지 않는다 "
						+ "(sp-docs/api-contract.md §4.5)")
				.isEqualTo(FIXED_TIME);
		assertThat(응답의_createdAt(list, "$.data.content[0].createdAt"))
				.as("목록 항목의 createdAt 도 저장된 created_at 이다")
				.isEqualTo(FIXED_TIME);
	}

	/**
	 * {@code writer_id} 는 검증된 JWT 의 {@code sub} 에서만, {@code writer_nickname} 은 내부
	 * API 응답에서만 온다 (sp-docs/requirements/board.md §3 규칙 1·2). 본문에 그 키를 실어도
	 * 바인딩될 자리가 없다.
	 *
	 * <p><b>조회 키도 그 {@code sub} 다</b> (§3 규칙 3). 스텁이 받은 본문에 그것이 드러난다 —
	 * 본문의 999 로 조회하는 구현이면 {@code accountIds} 에 999 가 실린다.
	 */
	@Test
	@DisplayName("본문의 writerId·nickname 은 무시되고 조회 키도 JWT sub 다")
	void 본문의_작성자_정보는_무시된다() throws Exception {
		Post post = savedPost(833L);

		MvcResult result = this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content": "위조 시도", "writerId": 999, "nickname": "가짜"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.writerId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.data.writerNickname").value(MEMBER_NICKNAME))
				.andReturn();

		Comment saved = this.commentRepository.findById(dataId(result)).orElseThrow();
		assertThat(saved.getWriterId()).isEqualTo(ACCOUNT_ID);
		assertThat(saved.getWriterNickname()).isEqualTo(MEMBER_NICKNAME);
		assertThat(LAST_REQUEST_BODY.get())
				.as("조회 키는 JWT 의 sub 다. 본문의 999 로 조회하지 않는다")
				.isEqualTo("{\"accountIds\":[%d]}".formatted(ACCOUNT_ID));
	}

	// --- 생성 실패 (sp-docs/api-contract.md §5.1, requirements/board.md §3.0) ---

	/**
	 * 검증표 "프로필 미등록 계정으로 작성 → 403 {@code S002}. 댓글 0개,
	 * {@code comment_count} 불변". 200 이지만 결과에서 제외됐다 = 프로필 미등록이고, 업무
	 * 오류이므로 재시도는 무의미하다 (§5.1).
	 */
	@Test
	@DisplayName("프로필 미등록 계정으로 작성하면 403 S002 이고 댓글도 집계도 남지 않는다")
	void 프로필_미등록은_403_S002() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 200, envelope("")));

		assertCreateFails(834L, "프로필 미등록", status().isForbidden(), "S002");
	}

	/** 완료 기준 "프로필이 없거나 <b>탈퇴했으면</b> 403 {@code S002}" (§5.1). */
	@Test
	@DisplayName("탈퇴한 프로필로 작성하면 403 S002 이고 댓글도 집계도 남지 않는다")
	void 탈퇴한_프로필은_403_S002() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 200, envelope("""
				{"accountId": %d, "nickname": "탈퇴한 회원", "deleted": true}"""
				.formatted(ACCOUNT_ID))));

		assertCreateFails(835L, "탈퇴 프로필", status().isForbidden(), "S002");
	}

	/**
	 * 검증표 "member 가 404 반환 → 503 {@code S001}". <b>404 를 "프로필 없음" 으로 해석하지
	 * 않는다</b> (§5.1). 그렇게 하면 {@code MEMBER_SERVICE_URL} 오설정이 업무 오류로 위장되어,
	 * 경보도 없이 전 사용자의 쓰기가 조용히 멈춘다.
	 */
	@Test
	@DisplayName("member 가 404 를 주면 503 S001 이다. S002 가 아니다")
	void member_404_는_503_S001() throws Exception {
		RESPONDER.set(exchange -> writeResponse(exchange, 404, "{}"));

		assertCreateFails(836L, "경로 오설정", status().isServiceUnavailable(), "S001");
	}

	/**
	 * 검증표 "member 타임아웃 → 503 {@code S001}. 댓글 0개, {@code comment_count} 불변".
	 * 스텁의 지연을 정본 read 타임아웃(3초, sp-docs/nfr.md §3)보다 훨씬 길게 둔다.
	 */
	@Test
	@DisplayName("member 타임아웃은 503 S001 이고 댓글도 집계도 남지 않는다")
	void member_타임아웃은_503_S001() throws Exception {
		RESPONDER.set(exchange -> {
			sleep(Duration.ofSeconds(10));
			writeResponse(exchange, 200, envelope("""
					{"accountId": %d, "nickname": "%s", "deleted": false}"""
					.formatted(ACCOUNT_ID, MEMBER_NICKNAME)));
		});

		assertCreateFails(837L, "타임아웃", status().isServiceUnavailable(), "S001");
	}

	// --- 입력 검증 (sp-docs/requirements/board.md §4) ---

	/**
	 * 검증표 "501자 댓글 → 400 {@code C001}". 댓글 {@code content} 는 1~500자다 (§4).
	 *
	 * <p>세 케이스가 서로 다른 제약을 짚는다 — 501자는 {@code @Size} 가, 공백 500자와 빈
	 * 문자열은 {@code @NotBlank} 가 막는다. 한쪽만 걸면 다른 쪽이 201 로 통과한다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"TOO_LONG", "BLANK", "EMPTY"})
	@DisplayName("501자 · 공백만 · 빈 문자열 댓글은 400 C001 이고 저장되지 않는다")
	void 작성_입력_검증_위반은_400_C001(String violation) throws Exception {
		Post post = savedPost(838L);

		this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(violatingContent(violation))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		assertThat(활성_댓글_수(post.getId())).isZero();
		assertThat(집계(post.getId())).isZero();
		assertThat(REQUEST_COUNT.get())
				.as("입력 검증에서 끊기므로 member 를 호출할 자리가 없다")
				.isZero();
	}

	/**
	 * <b>상한의 유효한 쪽을 본다</b> (sp-docs/conventions.md §9.2). 초과 입력이 400 인 것만
	 * 보면 상한을 내리는 어긋남이 조용하다 — 정확히 최대 길이인 입력이 <b>성공</b>하는 테스트가
	 * 그것을 잡는다. 실측: {@code CONTENT_MAX_LENGTH} 를 499 로 내리면 이 테스트가 깨진다.
	 *
	 * <p>댓글 {@code content} 는 1~500자다 (sp-docs/requirements/board.md §4). 500자는
	 * <b>정당한 입력</b>이므로 201 이어야 한다.
	 *
	 * <p><b>B-03 의 컬럼 폭 테스트가 이것을 대신하지 않는다</b> (§9.2). 그쪽은
	 * {@code information_schema} 의 폭이고 여기는 DTO 제약이다 — 둘이 어긋나면 폭은 맞는데
	 * 요청이 400 으로 거부된다.
	 *
	 * <p>저장된 길이까지 본다 — 요청이 통과해도 잘려서 들어가면 201 만으로는 드러나지 않는다.
	 */
	@Test
	@DisplayName("정확히 500자 댓글 작성은 201 이고 500자가 그대로 저장된다")
	void 작성에_500자는_201() throws Exception {
		Post post = savedPost(856L);
		String 최대_길이 = "가".repeat(500);

		MvcResult result = this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(최대_길이)))
				.andExpect(status().isCreated())
				.andReturn();

		assertThat(this.commentRepository.findById(dataId(result)).orElseThrow().getContent())
				.hasSize(500)
				.isEqualTo(최대_길이);
	}

	/**
	 * 같은 상한의 수정 경로다. <b>두 경로가 상수를 공유하므로 각각에서 봐야 한다</b>
	 * (sp-docs/conventions.md §9.2) — 상한을 내리는 어긋남은 두 경로에 동시에 번지지만, 한
	 * 경로만 보는 테스트는 두 경로의 제약이 갈릴 때를 못 본다.
	 */
	@Test
	@DisplayName("정확히 500자로 수정하면 200 이고 500자가 그대로 저장된다")
	void 수정에_500자는_200() throws Exception {
		Post post = savedPost(857L);
		Comment comment = savedComment(post, ACCOUNT_ID, "원래 댓글");
		String 최대_길이 = "나".repeat(500);

		this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(최대_길이)))
				.andExpect(status().isOk());

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().getContent())
				.hasSize(500)
				.isEqualTo(최대_길이);
	}

	/** 수정도 같은 행의 규칙을 받는다 (§4). 작성 쪽만 걸면 수정으로 501자가 들어간다. */
	@ParameterizedTest
	@ValueSource(strings = {"TOO_LONG", "BLANK", "EMPTY"})
	@DisplayName("501자 · 공백만 · 빈 문자열로 수정하면 400 C001 이고 내용이 바뀌지 않는다")
	void 수정_입력_검증_위반은_400_C001(String violation) throws Exception {
		Post post = savedPost(839L);
		Comment comment = savedComment(post, ACCOUNT_ID, "원래 댓글");

		this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(violatingContent(violation))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().getContent())
				.isEqualTo("원래 댓글");
	}

	/**
	 * 하한 미달은 400 {@code C001} 이고 {@code fieldErrors} 가 <b>1건</b>이다
	 * (sp-docs/api-contract.md §4.1 §2.3, sp-docs/conventions.md §6.1). 500 {@code C005} 가
	 * 아니다 — 흔한 클라이언트 실수가 {@code log.error} 경보를 만들면 원인이 서버에 있는 500 과
	 * 구별되지 않는다.
	 */
	@ParameterizedTest
	@CsvSource({"size,0", "page,-1"})
	@DisplayName("size = 0 · page = -1 은 400 C001 이고 fieldErrors 가 1건이다")
	void 목록_파라미터_하한_미달은_400_C001(String parameter, String value) throws Exception {
		Post post = savedPost(840L);

		this.mockMvc.perform(get(commentsPath(post.getId())).param(parameter, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value(parameter));
	}

	/**
	 * 상한 초과는 오류가 아니라 <b>절삭</b>이다 (sp-docs/requirements/board.md §4). 하한과
	 * 갈리는 것이 정본에서 이미 갈려 있으므로 한쪽으로 합치지 않는다
	 * (sp-docs/api-contract.md §4.1 의 블록인용).
	 */
	@Test
	@DisplayName("size = 51 은 400 이 아니라 50 으로 절삭된다")
	void 목록_size_상한_초과는_절삭이다() throws Exception {
		Post post = savedPost(841L);

		this.mockMvc.perform(get(commentsPath(post.getId())).param("size", "51"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.size").value(50));
	}

	/**
	 * 검증표 "없는·삭제된 게시글의 댓글 목록 → 404 {@code P001}. <b>빈 페이지 200이 아니다</b>"
	 * (sp-docs/api-contract.md §4.5).
	 *
	 * <p><b>같은 서비스가 같은 게시글에 두 가지 답을 하지 않는다.</b>
	 * {@code GET /posts/{id}} 가 404 인데 그 댓글 목록이 200 이면 모순이고, 없는 자원에 대해
	 * "댓글이 없다" 고 답하는 것은 사실이 아닌 답이다 (sp-docs/AGENTS.md 불변식 9).
	 *
	 * <p>이 경로는 {@code permitAll} 이므로 토큰을 싣지 않는다 (§4).
	 */
	@Test
	@DisplayName("없는 게시글의 댓글 목록은 404 P001 이다. 빈 페이지 200 이 아니다")
	void 없는_게시글의_댓글_목록은_404_P001() throws Exception {
		this.mockMvc.perform(get(commentsPath(987654324L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	/**
	 * 같은 행의 <b>삭제된 게시글</b> 쪽이다 (§4.5 — "삭제된 게시글도 같다").
	 *
	 * <p><b>픽스처는 연쇄 삭제가 끝난 상태다</b> — 게시글이 논리 삭제되면 하위 댓글도 함께
	 * {@code deleted = true} 가 된다 (sp-docs/requirements/board.md §5 규칙 3). 그래서 게시글을
	 * 확인하지 않는 구현은 <b>빈 페이지 200</b> 을 돌려주고, 이 단언이 그것을 404 와 가른다.
	 */
	@Test
	@DisplayName("삭제된 게시글의 댓글 목록은 404 P001 이다. 빈 페이지 200 이 아니다")
	void 삭제된_게시글의_댓글_목록은_404_P001() throws Exception {
		Post post = savedPost(855L);
		삭제된_댓글(post, 855L, "연쇄 삭제된 댓글");
		post.softDelete();
		this.postRepository.saveAndFlush(post);

		this.mockMvc.perform(get(commentsPath(post.getId())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"))
				.andExpect(jsonPath("$.data").value(nullValue()));

		assertThat(this.postRepository.findById(post.getId()))
				.as("논리 삭제이므로 게시글 행은 남아 있다 — 없는 id 와 다른 상태다")
				.isPresent();
	}

	// --- 수정 권한 (sp-docs/security.md §5.2, requirements/board.md §5 규칙 6) ---

	/** 검증표 "본인 댓글 수정 → 200". */
	@Test
	@DisplayName("본인 댓글 수정은 200 이고 내용이 바뀐다")
	void 본인_댓글_수정은_200() throws Exception {
		Post post = savedPost(842L);
		Comment comment = savedComment(post, 842L, "원래 댓글");

		this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(842L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("바뀐 댓글")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content").value("바뀐 댓글"));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().getContent())
				.isEqualTo("바뀐 댓글");
		assertThat(REQUEST_COUNT.get())
				.as("수정 경로는 member 를 호출하지 않는다 (sp-docs/api-contract.md §4.2)")
				.isZero();
	}

	/** 검증표 "타인 댓글 수정 → 403 {@code CM002}". */
	@Test
	@DisplayName("타인 댓글 수정은 403 CM002 이고 내용이 바뀌지 않는다")
	void 타인_댓글_수정은_403_CM002() throws Exception {
		Post post = savedPost(843L);
		Comment comment = savedComment(post, 843L, "남의 댓글");

		this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(844L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("남의 댓글 고치기")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("CM002"));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().getContent())
				.isEqualTo("남의 댓글");
	}

	/**
	 * 검증표 "ADMIN 이 타인 댓글 수정 → 403 {@code CM002}". <b>ADMIN 에게 수정 권한을 주지
	 * 않는다</b> (sp-docs/security.md §5.2, sp-docs/requirements/board.md §5 규칙 6) — 타인
	 * 댓글의 내용 변조를 막기 위한 것이고, 부적절한 댓글은 삭제로만 처리한다.
	 */
	@Test
	@DisplayName("ADMIN 이 타인 댓글을 수정하면 403 CM002 다")
	void ADMIN_도_타인_댓글을_수정할_수_없다() throws Exception {
		Post post = savedPost(845L);
		Comment comment = savedComment(post, 845L, "남의 댓글");

		this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, adminBearer(846L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("관리자가 고치기")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("CM002"));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().getContent())
				.isEqualTo("남의 댓글");
	}

	// --- 삭제 권한 (sp-docs/security.md §5.2) ---

	/** 완료 기준 "작성자 본인만 수정, <b>본인·ADMIN 이 삭제</b>" 중 타인(일반)의 삭제 쪽이다. */
	@Test
	@DisplayName("타인(일반 USER)이 댓글을 삭제하면 403 CM002 이고 행이 그대로다")
	void 타인_댓글_삭제는_403_CM002() throws Exception {
		Post post = savedPost(847L);
		Comment comment = savedComment(post, 847L, "남의 댓글");

		this.mockMvc.perform(delete(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(848L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("CM002"));

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().isDeleted())
				.isFalse();
	}

	/** 검증표 "ADMIN 이 타인 댓글 삭제 → 204". 본문이 없다 (sp-docs/api-contract.md §4). */
	@Test
	@DisplayName("ADMIN 이 타인 댓글을 삭제하면 204 이고 행이 남은 채 deleted = true 다")
	void ADMIN_이_타인_댓글을_삭제하면_204() throws Exception {
		Post post = savedPost(849L);
		Comment comment = 작성한_댓글(post, "관리자가 지울 댓글");

		this.mockMvc.perform(delete(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, adminBearer(850L)))
				.andExpect(status().isNoContent())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

		assertThat(this.commentRepository.findById(comment.getId()).orElseThrow().isDeleted())
				.as("Soft Delete 이므로 행은 남는다 (sp-docs/requirements/board.md §5 규칙 2)")
				.isTrue();
		assertThat(집계(post.getId()))
				.as("삭제 시 comment_count 가 1 감소한다 (sp-docs/domain-model.md §5)")
				.isZero();
	}

	// --- 404 와 403 의 선후 (sp-docs/api-contract.md §4.4) ---

	/**
	 * 검증표 "<b>타인의 삭제된 댓글</b> 수정 → 404 {@code CM001}. 403 {@code CM002} 가 아니다"
	 * (§4.4).
	 *
	 * <p><b>선후를 관측하려면 행이 남아 있어야 한다.</b> 행이 없는 id 로는 두 순서가 같은 답을
	 * 낸다 — 비교할 {@code writer_id} 가 없으므로 권한 판정을 앞에 둔 구현도 404 밖에 낼 수
	 * 없다. 삭제된 댓글(행은 있고 보이지만 않는 상태)에서만 두 순서가 서로 다른 답을 낸다.
	 *
	 * <p>정보 노출이 아니다: 댓글 목록이 비로그인에 열려 있어 존재 여부는 이미 공개 정보이고
	 * (§4.2), 403 을 먼저 내보내면 공개 조회와 어긋난 두 답이 나간다 (§4.4).
	 */
	@Test
	@DisplayName("타인의 삭제된 댓글을 수정하면 404 CM001 이다. CM002 가 아니다")
	void 타인의_삭제된_댓글_수정은_404_CM001() throws Exception {
		Post post = savedPost(851L);
		Comment comment = 삭제된_댓글(post, 851L, "삭제된 남의 댓글");

		this.mockMvc.perform(put(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(852L))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("삭제된 남의 댓글 고치기")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CM001"));
	}

	/**
	 * 검증표 "<b>타인의 삭제된 댓글</b> 삭제 (<b>호출자는 일반 {@code USER}</b>) → 404
	 * {@code CM001}". §4.4 는 수정과 <b>삭제</b>를 함께 못박는다.
	 *
	 * <p><b>수정 경로의 테스트가 삭제 경로를 덮지 않는다.</b> 두 경로의 조회·판정 순서가 각각
	 * 어긋날 수 있다. <b>한 행이 변이 둘을 잡는다</b> — 권한 판정을 앞으로 옮기면 403 이 되고,
	 * 조용히 204 를 돌려주면 204 가 된다.
	 *
	 * <p><b>호출자가 ADMIN 이면 앞의 변이가 숨는다.</b> 삭제가 ADMIN 에게 열려 있어 순서를
	 * 뒤집어도 권한 판정을 통과하기 때문이다. 그래서 일반 {@code USER} 로 호출한다.
	 */
	@Test
	@DisplayName("타인의 삭제된 댓글을 일반 USER 가 삭제하면 404 CM001 이다. CM002 도 204 도 아니다")
	void 타인의_삭제된_댓글_삭제는_404_CM001() throws Exception {
		Post post = savedPost(853L);
		Comment comment = 삭제된_댓글(post, 853L, "삭제된 남의 댓글");

		this.mockMvc.perform(delete(commentPath(comment.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(854L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CM001"));
	}

	// --- 헬퍼 ---

	/**
	 * 작성 실패 세 줄이 공통으로 요구하는 것 — <b>그 상태·코드이고, 댓글도 집계도 남지
	 * 않는다</b> (검증표, sp-docs/requirements/board.md §3.0).
	 */
	private void assertCreateFails(
			long postWriterId, String content, ResultMatcher expectedStatus, String expectedCode)
			throws Exception {

		Post post = savedPost(postWriterId);

		this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(content)))
				.andExpect(expectedStatus)
				.andExpect(jsonPath("$.error.code").value(expectedCode));

		assertThat(활성_댓글_수(post.getId()))
				.as("확인이 실패했으면 댓글이 저장되지 않는다 "
						+ "(sp-docs/requirements/board.md §3.0)")
				.isZero();
		assertThat(집계(post.getId()))
				.as("원격 호출이 트랜잭션 밖이므로 comment_count 도 증가하지 않는다")
				.isZero();
	}

	private static String violatingContent(String violation) {
		return switch (violation) {
			case "TOO_LONG" -> "가".repeat(501);
			case "BLANK" -> " ".repeat(500);
			case "EMPTY" -> "";
			default -> throw new IllegalArgumentException(violation);
		};
	}

	private static String commentsPath(Long postId) {
		return "/api/v1/posts/" + postId + "/comments";
	}

	private static String commentPath(long commentId) {
		return "/api/v1/comments/" + commentId;
	}

	private static String body(String content) {
		return "{\"content\":%s}".formatted(quote(content));
	}

	/** JSON 문자열 리터럴로 감싼다. 내용에 공백·따옴표가 들어가는 케이스가 있다. */
	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String bearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, ROLE);
	}

	/**
	 * {@code role = ADMIN} 토큰 (sp-docs/api-contract.md §6). ADMIN 은 <b>삭제만</b> 할 수
	 * 있으므로 수정·삭제 두 쪽에서 쓰인다 (sp-docs/security.md §5.2).
	 */
	private static String adminBearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, ROLE_ADMIN);
	}

	private Post savedPost(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("댓글이 달릴 글")
				.content("본문")
				.build());
	}

	/** 집계를 건드리지 않는 픽스처. 집계가 필요한 단언은 {@link #작성한_댓글} 을 쓴다. */
	private Comment savedComment(Post post, long writerId, String content) {
		return this.commentRepository.saveAndFlush(Comment.builder()
				.post(post)
				.writerId(writerId)
				.writerNickname("댓글작성자")
				.content(content)
				.build());
	}

	/** 작성 엔드포인트를 지나 만든 댓글. 집계가 1 이 된 상태에서 삭제를 본다. */
	private Comment 작성한_댓글(Post post, String content) throws Exception {
		MvcResult result = this.mockMvc.perform(post(commentsPath(post.getId()))
						.header(HttpHeaders.AUTHORIZATION, bearer(ACCOUNT_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(content)))
				.andExpect(status().isCreated())
				.andReturn();
		return this.commentRepository.findById(dataId(result)).orElseThrow();
	}

	/** 선후를 관측하려면 <b>행이 남은</b> 삭제 상태가 필요하다 (§4.4). */
	private Comment 삭제된_댓글(Post post, long writerId, String content) {
		Comment comment = savedComment(post, writerId, content);
		comment.softDelete();
		return this.commentRepository.saveAndFlush(comment);
	}

	private long 활성_댓글_수(Long postId) {
		Long count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM comment WHERE post_id = ? AND deleted = FALSE",
				Long.class, postId);
		return count == null ? 0 : count;
	}

	private int 집계(Long postId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
		return count == null ? 0 : count;
	}

	/**
	 * {@code created_at} 을 직접 박는다. {@code @Column(updatable = false)} 이고 감사가
	 * 채우므로 JPA 로는 바꿀 수 없다 (sp-docs/domain-model.md §1.1).
	 */
	private void setCreatedAt(Long commentId, LocalDateTime createdAt) {
		this.jdbcTemplate.update(
				"UPDATE comment SET created_at = ? WHERE id = ?", createdAt, commentId);
	}

	/** JPA 를 거치지 않고 커밋된 감사 컬럼을 읽는다. */
	private LocalDateTime 저장된_시각(Long commentId, String column) {
		return this.jdbcTemplate.queryForObject(
				"SELECT " + column + " FROM comment WHERE id = ?", LocalDateTime.class, commentId);
	}

	/** 응답 본문의 시각 문자열을 값으로 비교한다 — 직렬화 형식을 리터럴로 적지 않는다. */
	private static LocalDateTime 응답의_createdAt(MvcResult result, String jsonPath) throws Exception {
		return LocalDateTime.parse(JsonPath.read(bodyOf(result), jsonPath));
	}

	private static Set<String> dataKeys(MvcResult result) throws Exception {
		Map<String, Object> data = JsonPath.read(bodyOf(result), "$.data");
		return data.keySet();
	}

	private static Set<String> firstItemKeys(MvcResult result) throws Exception {
		Map<String, Object> item = JsonPath.read(bodyOf(result), "$.data.content[0]");
		return item.keySet();
	}

	private static long dataId(MvcResult result) throws Exception {
		return ((Number) JsonPath.read(bodyOf(result), "$.data.id")).longValue();
	}

	private static String bodyOf(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
