package com.example.board.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.client.MemberClient;
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
 * <p>픽스처의 {@code writerId} 는 작성·상세(B-05)가 521~539, 목록·검색(B-06)이 621~639 를
 * 쓴다. 이미 쓰이는 대역(201~210, 301~317, 401~402, {@code PostServiceTest} 의 501~519,
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

	private Post savedPost(long writerId, String nickname, String title, String content) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname(nickname)
				.title(title)
				.content(content)
				.build());
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
