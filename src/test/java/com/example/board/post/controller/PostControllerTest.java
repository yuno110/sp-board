package com.example.board.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import java.util.List;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.MvcResult;

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
 * 301~317, 401~402, {@code PostServiceTest} 의 501~519·701~719,
 * {@code PostQueryRepositoryTest} 의 601~619)과 겹치지 않게 둔다
 * (sp-docs/tech-stack.md §5.3 — 같은 JVM 의 모든 테스트가 한 H2 DB 를 본다).
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
	 * sp-docs/api-contract.md §4.3 이 정한 상세 응답의 키 집합. <b>리터럴로 적는다</b> — DTO 의
	 * 필드에서 뽑아 오면 필드가 늘거나 줄 때 기대값이 함께 움직여 단언이 아무것도 보지 못한다.
	 */
	private static final Set<String> DETAIL_KEYS = Set.of(
			"id", "title", "content", "writerId", "writerNickname",
			"viewCount", "commentCount", "createdAt");

	/**
	 * sp-docs/api-contract.md §4.3 §9.3 이 정한 <b>목록 항목</b>의 키 집합. 상세에서
	 * {@code content} 를 뺀 것이다. {@code DETAIL_KEYS} 와 같은 이유로 리터럴이다.
	 */
	private static final Set<String> LIST_ITEM_KEYS = Set.of(
			"id", "title", "writerId", "writerNickname",
			"viewCount", "commentCount", "createdAt");

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

	@BeforeEach
	void 스텁_초기화() {
		REQUEST_COUNT.set(0);
		LAST_REQUEST_BODY.set(null);
		respondWithActiveProfile(ACCOUNT_ID);
	}

	@AfterEach
	void 픽스처_정리() {
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
	 * 상세 응답의 키 집합이 <b>정확히 여덟</b>이다 (§4.3).
	 *
	 * <p>{@code deleted} 는 돌아온 글이 항상 활성이므로 넣지 않고, {@code updatedAt} 은 감사
	 * 흔적으로 쓰지 않으며 노출 요구가 없어 넣지 않는다 (§4.3,
	 * sp-docs/domain-model.md §1.1).
	 */
	@Test
	@DisplayName("상세 응답 키는 정확히 여덟이고 deleted·updatedAt 이 없다")
	void 상세_응답_키_집합() throws Exception {
		Post post = savedPost(523L, "키집합작성자", "키 집합", "본문");

		MvcResult result = this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(dataKeys(result)).isEqualTo(DETAIL_KEYS);
	}

	@Test
	@DisplayName("상세 조회 2회면 viewCount 가 2다")
	void 상세_조회_2회면_조회수가_2() throws Exception {
		Post post = savedPost(524L, "조회수작성자", "조회수", "본문");

		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
				.andExpect(jsonPath("$.data.viewCount").value(1));
		this.mockMvc.perform(get(PATH + "/{id}", post.getId()))
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
	@DisplayName("목록 응답은 §7.1 페이징 키를 갖고 항목 키는 정확히 일곱이다")
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
	@DisplayName("내가 쓴 글 목록은 내 글만 돌려주고 항목 키가 정확히 일곱이다")
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
	@DisplayName("본인 글 수정은 200 이고 상세 모양(여덟 키)을 돌려준다")
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

	/** JSON 문자열 리터럴로 감싼다. 제목에 공백·따옴표가 들어가는 케이스가 있다. */
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

	private Post savedPost(long writerId, String nickname, String title, String content) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(nickname)
				.title(title)
				.content(content)
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
