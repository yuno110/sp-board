package com.example.board.like.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.willReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.global.error.BusinessException;
import com.example.board.like.entity.PostLike;
import com.example.board.like.repository.PostLikeRepository;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * sp-docs/plan/phase2-board.md B-20 의 검증 표 중 HTTP 쪽이다 — 상태 코드·응답 본문·인증·member
 * 호출 0회, 그리고 요청이 연 영속성 컨텍스트(OSIV) 안에서의 UNIQUE 충돌. 저장·집계·순서·동시성은
 * {@link com.example.board.like.service.LikeServiceTest} 가 본다.
 *
 * <p>정본은 sp-docs/api-contract.md §4 §4.2 §4.6 §9.8 (경로·성공 코드·응답), §6 (claim 계약),
 * sp-docs/requirements/board.md §10 이다.
 *
 * <p><b>member 스텁은 언제나 프로필이 미등록이라고 답한다</b> — 200 이고 결과에서 제외된 응답이다
 * (sp-docs/api-contract.md §5.1). 좋아요가 member 를 부르면 그 응답이 403 {@code S002} 가 되어
 * 200 단언이 빨개지고, 스텁이 받은 요청 수로 호출 0회를 본다.
 *
 * <p>픽스처의 {@code writerId} 와 좋아요를 누르는 {@code accountId} 는 1151~1179 를 쓴다. 같은 JVM 의
 * 모든 테스트가 하나의 H2 DB 를 보므로 (sp-docs/tech-stack.md §5.3) 2026-11-16 에 테스트 소스
 * 전체에서 그 값을 쓰는 곳이 없는 것을 확인하고 골랐다. {@code @AfterEach} 가 자기 대역의 글과 그
 * 글의 좋아요만 지운다 — {@code post_like.post_id} 에 FK 가 있으므로 좋아요를 먼저 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LikeControllerTest {

	private static final String PATH = "/api/v1/posts/{id}/like";

	/** sp-docs/api-contract.md §6 의 {@code role} claim 값. */
	private static final String ROLE = "USER";

	/**
	 * 응답 {@code data} 의 키 집합. sp-docs/api-contract.md §4.6 의 표 그대로다. <b>리터럴로
	 * 적는다</b> — DTO 의 필드에서 뽑아 오면 필드가 늘거나 줄 때 기대값이 함께 움직인다.
	 */
	private static final Set<String> LIKE_KEYS = Set.of("liked", "likeCount");

	/** 어떤 글에도 쓰이지 않는 id. IDENTITY 는 양수만 준다. */
	private static final long 없는_글 = -1L;

	private static final HttpServer STUB = startStub();

	/** 스텁이 받은 요청 수. 「{@code MemberClient} 호출 0회」 를 관측하는 지점이다. */
	private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();

	@DynamicPropertySource
	static void testEnvironment(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
		registry.add("member-service.url", () -> "http://localhost:" + STUB.getAddress().getPort());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	/** UNIQUE 충돌 경로의 테스트가 확인을 대역으로 바꾼다. 다른 테스트에서는 실제 빈에 위임된다. */
	@MockitoSpyBean
	private PostLikeRepository postLikeRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void 스텁_초기화() {
		REQUEST_COUNT.set(0);
	}

	@AfterEach
	void 픽스처_정리() {
		// post_like.post_id 에 FK 가 있으므로 (fk_post_like_post_id) 좋아요를 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM post_like WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 1151 AND 1179)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1151 AND 1179");
	}

	/**
	 * 검증 표 <b>「누르기 → 200 {@code liked: true}, {@code likeCount} 1, 행 1」</b>. 응답의 키 집합이
	 * §4.6 의 표와 정확히 같은지도 본다 — 봉투는 sp-docs/api-contract.md §7 의 성공 모양이다.
	 */
	@Test
	@DisplayName("누르면 200 이고 data 의 키가 liked·likeCount 이며 liked:true·likeCount 1 이고 행이 생긴다")
	void 누르기는_200_이다() throws Exception {
		Post post = 게시글을_만든다(1151L);

		MvcResult result = this.mockMvc.perform(request(HttpMethod.PUT, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1152L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.liked").value(true))
				.andExpect(jsonPath("$.data.likeCount").value(1))
				.andReturn();

		assertThat(dataKeys(result)).isEqualTo(LIKE_KEYS);
		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(좋아요_행_수(post, 1152L)).isEqualTo(1);
	}

	/**
	 * 검증 표 <b>「누르고 취소 → 200 {@code liked: false}, {@code likeCount} 0, 행 0」</b>. 누르기도
	 * 이 API 로 한다. 취소의 응답도 누르기와 같은 키 집합이다 (§4.6 — 두 메서드가 한 모양).
	 */
	@Test
	@DisplayName("누르고 취소하면 200 이고 liked:false·likeCount 0 이며 행이 없다")
	void 누르고_취소하면_200_이다() throws Exception {
		Post post = 게시글을_만든다(1153L);
		누른다(post, 1154L);

		MvcResult result = this.mockMvc.perform(request(HttpMethod.DELETE, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1154L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.liked").value(false))
				.andExpect(jsonPath("$.data.likeCount").value(0))
				.andReturn();

		assertThat(dataKeys(result)).isEqualTo(LIKE_KEYS);
		assertThat(커밋된_집계(post)).isZero();
		assertThat(좋아요_행_수(post, 1154L)).isZero();
	}

	/**
	 * 검증 표 <b>「응답의 {@code likeCount} → 커밋된 {@code like_count} 와 같다 — 좁은 UPDATE 뒤의
	 * 낡은 사본이 아니다」</b>. 요청마다 응답 값을 그 요청 뒤에 {@code JdbcTemplate} 으로 읽은 값과
	 * 맞춘다.
	 *
	 * <p><b>요청이 영속성 컨텍스트를 연다(OSIV).</b> 쓰는 트랜잭션도 되돌린 뒤의 읽기도 그 하나의
	 * 컨텍스트를 쓰므로, 낡은 사본이 응답으로 나갈 자리가 서비스 테스트보다 넓다.
	 *
	 * <p>다른 계정의 좋아요 셋으로 시작해 쓴 값(4·3)과 되돌린 뒤의 값(4·3)을 둘 다 지난다. 이미 누른 글에
	 * 다시 누르기, 누르지 않은 글에 취소도 200 이다 (§4.6 의 멱등) — 검증 표 「다시 누르기」·「누르지 않은
	 * 글 취소」 의 HTTP 쪽을 이 테스트가 함께 본다.
	 */
	@Test
	@DisplayName("응답의 likeCount 는 쓴 경로와 되돌린 경로 모두 그 요청 뒤에 커밋된 like_count 와 같다")
	void 응답의_likeCount_는_커밋된_값이다() throws Exception {
		Post post = 게시글을_만든다(1155L);
		누른_상태로_둔다(post, 1156L, 1157L, 1158L);

		assertThat(응답_뒤의_값(HttpMethod.PUT, post, 1159L))
				.as("누르기 — 쓴 경로").containsExactly(1, 4, 4);
		assertThat(응답_뒤의_값(HttpMethod.PUT, post, 1159L))
				.as("다시 누르기 — 되돌린 경로").containsExactly(1, 4, 4);
		assertThat(응답_뒤의_값(HttpMethod.DELETE, post, 1159L))
				.as("취소 — 쓴 경로").containsExactly(0, 3, 3);
		assertThat(응답_뒤의_값(HttpMethod.DELETE, post, 1159L))
				.as("누르지 않은 글 취소 — 되돌린 경로").containsExactly(0, 3, 3);
	}

	/**
	 * 검증 표 <b>「UNIQUE 충돌 경로 → 200 {@code liked: true}, 커밋된 {@code like_count} 불변, 500
	 * 아님」</b>의 HTTP 쪽이다. 수단은 {@code LikeServiceTest} 와 같다 — 확인을 대역으로 바꿔 행이
	 * 없다고 답하게 하고 행은 실제로 둔다.
	 *
	 * <p><b>OSIV 가 이 경로를 서비스 테스트와 다르게 만든다.</b> 요청이 연 영속성 컨텍스트가 쓰는
	 * 트랜잭션에 묶이고 그 트랜잭션이 끝난 뒤에도 남는다 — 되돌린 뒤의 읽기가 실패한 INSERT 를 겪은
	 * 그 컨텍스트에서 나간다. 그 읽기가 깨지면 이 요청은 500 이 된다.
	 */
	@Test
	@DisplayName("요청의 영속성 컨텍스트 안에서 INSERT 가 UNIQUE 에 걸려도 200 liked:true 이고 집계가 그대로다")
	void UNIQUE_충돌도_200_이다() throws Exception {
		Post post = 게시글을_만든다(1160L);
		누른_상태로_둔다(post, 1161L);
		willReturn(false).given(this.postLikeRepository)
				.existsByPostIdAndAccountId(post.getId(), 1161L);

		this.mockMvc.perform(request(HttpMethod.PUT, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1161L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(true))
				.andExpect(jsonPath("$.data.likeCount").value(1));

		assertThat(커밋된_집계(post)).as("그 요청의 증가가 남지 않는다").isEqualTo(1);
		assertThat(좋아요_행_수(post, 1161L)).isEqualTo(1);
	}

	/**
	 * 검증 표 <b>「행은 있는데 집계가 0 인 글을 취소 → 200 {@code liked: false}, 집계 0, WARN」</b>의
	 * HTTP 쪽이다. WARN 과 그 반대 조합(행도 없는 글)은 {@code LikeServiceTest} 가 본다.
	 */
	@Test
	@DisplayName("행은 있는데 집계가 0 인 글을 취소하면 200 liked:false·likeCount 0 이고 행이 지워진다")
	void 어긋난_상태의_취소도_200_이다() throws Exception {
		Post post = 게시글을_만든다(1171L);
		// 집계를 올리지 않고 근거 행만 둔다 — 행은 있는데 집계가 0 이다
		this.postLikeRepository.saveAndFlush(PostLike.builder().post(post).accountId(1172L).build());

		this.mockMvc.perform(request(HttpMethod.DELETE, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1172L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(false))
				.andExpect(jsonPath("$.data.likeCount").value(0));

		assertThat(커밋된_집계(post)).as("0 아래로 내려가지 않는다").isZero();
		assertThat(좋아요_행_수(post, 1172L)).isZero();
	}

	/** 검증 표 <b>「없는·삭제된 글에 누르기·취소 → 404 {@code P001}」</b>의 없는 글 쪽이다. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"PUT", "DELETE"})
	@DisplayName("없는 글에 누르기·취소하면 404 P001 이다")
	void 없는_글은_404_P001(String method) throws Exception {
		this.mockMvc.perform(request(HttpMethod.valueOf(method), PATH, 없는_글)
						.header(HttpHeaders.AUTHORIZATION, bearer(1162L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("P001"));
	}

	/**
	 * 검증 표 <b>「없는·삭제된 글에 누르기·취소 → 404 {@code P001}」</b>의 삭제된 글 쪽이다. 지우기 전에
	 * 그 글에 좋아요가 있었다 — 취소하는 계정이 누른 글이고, 누르는 계정은 누르지 않은 글이다. 둘 다
	 * 404 이고 행과 집계가 그대로다.
	 */
	@Test
	@DisplayName("삭제된 글에 누르기·취소하면 404 P001 이고 행과 집계가 그대로다")
	void 삭제된_글은_404_P001() throws Exception {
		Post post = 게시글을_만든다(1163L);
		누른_상태로_둔다(post, 1164L);
		글을_지운다(post, 1163L);

		this.mockMvc.perform(request(HttpMethod.PUT, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1165L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));
		this.mockMvc.perform(request(HttpMethod.DELETE, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1164L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));

		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(좋아요_행_수(post, 1164L)).as("지워진 글의 좋아요 행은 취소로도 지워지지 않는다").isEqualTo(1);
		assertThat(좋아요_행_수(post, 1165L)).isZero();
	}

	/**
	 * 검증 표 <b>「자기 글에 누르고 그 글을 지운다 → 누르기 200 {@code liked: true}, 지운 뒤에도
	 * {@code post_like} 행이 남는다」</b> (sp-docs/requirements/board.md §10 의 「자기 글」·「글을
	 * 지우면」 줄). 지우기는 게시글 삭제 API 로 한다 — 그 경로가 좋아요 행을 건드리지 않는지가 대상이다.
	 */
	@Test
	@DisplayName("자기 글에도 누를 수 있고 그 글을 지워도 post_like 행이 남는다")
	void 자기_글에_누르고_지워도_행이_남는다() throws Exception {
		Post post = 게시글을_만든다(1166L);

		this.mockMvc.perform(request(HttpMethod.PUT, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1166L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liked").value(true))
				.andExpect(jsonPath("$.data.likeCount").value(1));
		글을_지운다(post, 1166L);

		assertThat(좋아요_행_수(post, 1166L)).as("글을 지워도 행은 남는다").isEqualTo(1);
	}

	/**
	 * 검증 표 <b>「프로필 없는 계정 → 200, {@code MemberClient} 호출 0회」</b>
	 * (sp-docs/api-contract.md §4.2 의 좋아요 줄). 스텁이 프로필 미등록으로 답하므로 member 를
	 * 부르면 403 {@code S002} 가 된다.
	 */
	@Test
	@DisplayName("프로필 없는 계정도 누르고 취소하며 member 를 한 번도 부르지 않는다")
	void 프로필_없는_계정도_누르고_취소한다() throws Exception {
		Post post = 게시글을_만든다(1167L);

		this.mockMvc.perform(request(HttpMethod.PUT, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1168L)))
				.andExpect(status().isOk());
		this.mockMvc.perform(request(HttpMethod.DELETE, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(1168L)))
				.andExpect(status().isOk());

		assertThat(REQUEST_COUNT.get()).as("좋아요는 프로필이 필요 없다 — S002 가 나지 않는다").isZero();
	}

	/**
	 * 검증 표 <b>「claim 이 계약 밖인 토큰 → 401 {@code A002}」</b> — {@code PostControllerTest} 의
	 * {@code role = BOGUS} 테스트와 같은 방식이다. 서명이 유효하므로 필터 체인은 통과하고
	 * {@code @CurrentMember} 리졸버가 끊는다 (sp-docs/tech-stack.md §2.1 의 집행 지점 표).
	 *
	 * <p><b>거부한 계층까지 본다.</b> 핸들러가 선택되고 해석된 예외가 남아야 리졸버가 끊은 것이다 —
	 * 핸들러가 {@code @CurrentMember} 를 받지 않으면 이 토큰이 그대로 통과해 누르기는 행을 만들고
	 * 취소는 픽스처의 행을 지운다.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"PUT", "DELETE"})
	@DisplayName("role 이 열거 밖인 토큰이면 리졸버가 401 A002 로 끊고 아무것도 바뀌지 않는다")
	void 열거_밖_role_이면_401_A002(String method) throws Exception {
		Post post = 게시글을_만든다(1169L);
		누른_상태로_둔다(post, 1170L);

		MvcResult result = this.mockMvc.perform(request(HttpMethod.valueOf(method), PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(1170L, "BOGUS")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"))
				.andReturn();

		assertThat(result.getHandler())
				.as("서명이 유효한 토큰은 필터 체인을 통과한다 — 집행 지점은 리졸버다").isNotNull();
		assertThat(result.getResolvedException())
				.as("리졸버가 던진 예외가 여기 담긴다").isInstanceOf(BusinessException.class);
		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(좋아요_행_수(post, 1170L)).isEqualTo(1);
		assertThat(REQUEST_COUNT.get()).isZero();
	}

	// --- 도구 ---

	/**
	 * 한 요청을 보내 200 인지 보고 {@code [liked(0·1), 응답의 likeCount, 그 뒤에 커밋된 like_count]}
	 * 를 돌려준다. {@code liked} 를 수로 바꾸는 것은 세 값을 한 단언에 담기 위해서다.
	 */
	private int[] 응답_뒤의_값(HttpMethod method, Post post, long accountId) throws Exception {
		MvcResult result = this.mockMvc.perform(request(method, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(accountId)))
				.andExpect(status().isOk())
				.andReturn();
		boolean liked = JsonPath.read(bodyOf(result), "$.data.liked");
		int likeCount = ((Number) JsonPath.read(bodyOf(result), "$.data.likeCount")).intValue();
		return new int[] {liked ? 1 : 0, likeCount, 커밋된_집계(post)};
	}

	/** 이 API 로 누른다. 200 이 아니면 그 자리에서 터진다. */
	private void 누른다(Post post, long accountId) throws Exception {
		this.mockMvc.perform(request(HttpMethod.PUT, PATH, post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(accountId)))
				.andExpect(status().isOk());
	}

	/** 게시글 삭제 API 로 지운다 (sp-docs/api-contract.md §4 — 작성자 본인이면 204). */
	private void 글을_지운다(Post post, long writerId) throws Exception {
		this.mockMvc.perform(delete("/api/v1/posts/{id}", post.getId())
						.header(HttpHeaders.AUTHORIZATION, bearer(writerId)))
				.andExpect(status().isNoContent());
	}

	private Post 게시글을_만든다(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("좋아요 대상")
				.content("본문")
				.build());
	}

	/**
	 * 계정마다 근거 행 하나를 두고 집계를 그 수만큼 올린다 — 근거 행과 집계가 같은 상태다. 이 API 를
	 * 거치지 않는다. 집계는 {@code JdbcTemplate} 으로 올리고, 영향 행이 1 이 아니면 그 자리에서 터진다.
	 */
	private void 누른_상태로_둔다(Post post, long... accountIds) {
		for (long accountId : accountIds) {
			this.postLikeRepository.saveAndFlush(
					PostLike.builder().post(post).accountId(accountId).build());
		}
		int updated = this.jdbcTemplate.update(
				"UPDATE post SET like_count = like_count + ? WHERE id = ?",
				accountIds.length, post.getId());
		if (updated != 1) {
			throw new IllegalStateException("like_count 픽스처가 1 행이 아니다: " + updated);
		}
	}

	private int 커밋된_집계(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT like_count FROM post WHERE id = ?", Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	private int 좋아요_행_수(Post post, long accountId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post_like WHERE post_id = ? AND account_id = ?",
				Integer.class, post.getId(), accountId);
		return count == null ? 0 : count;
	}

	private static String bearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, ROLE);
	}

	private static Set<String> dataKeys(MvcResult result) throws Exception {
		Map<String, Object> data = JsonPath.read(bodyOf(result), "$.data");
		return data.keySet();
	}

	private static String bodyOf(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/**
	 * member 스텁. 언제나 프로필 미등록 — 200 이고 {@code members} 가 빈 배열이다
	 * (sp-docs/api-contract.md §5 의 봉투, §5.1 의 판정).
	 */
	private static HttpServer startStub() {
		try {
			// 포트 0 — 운영체제가 빈 포트를 준다
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", exchange -> {
				REQUEST_COUNT.incrementAndGet();
				byte[] body = "{\"success\":true,\"data\":{\"members\":[]},\"error\":null}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException("스텁 서버를 띄울 수 없다", e);
		}
	}

}
