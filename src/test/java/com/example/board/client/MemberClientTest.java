package com.example.board.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * sp-docs/plan/phase1.md §6 B-04 의 {@code MemberClientTest} 검증표를 테스트로 옮긴 것이다.
 * 실패 판정의 정본은 sp-docs/api-contract.md §5 §5.1 이다.
 *
 * <p><b>스텁 서버로 member 의 응답을 흉내 낸다</b> — M-10(제공 측)을 기다리지 않는다. 실제
 * 연동은 I-01 이 확인한다.
 *
 * <p><b>스텁 응답은 §5 의 봉투를 그대로 쓴다</b>
 * ({@code {"success": true, "data": {"members": [...]}, "error": null}}) — 배열이 최상위가
 * 아니다. 봉투를 빼고 쓰면 이 테스트는 통과하면서 M-10 이 돌려주는 모양과 달라지고, 그 어긋남은
 * §5.1 이 "본문 파싱 실패 → {@code S001}" 로 분류하므로 board 가 전 사용자의 쓰기를 멈춘 뒤에야
 * 드러난다. 봉투의 모양은 {@code sp-member} 의
 * {@code internal/dto/MemberBulkResponse}·{@code MemberSummaryResponse} 와
 * {@code InternalMemberController} 를 읽어 확인했다.
 *
 * <p><b>스프링이 조립한 {@link MemberClient} 를 쓴다.</b> 테스트가 직접 {@code RestClient} 를
 * 만들면 타임아웃·헤더가 테스트의 값이 되어 {@code application.yml} 의 설정이 실제로 적용되는지
 * 알 수 없다. 그래서 스텁 서버의 주소만 {@code @DynamicPropertySource} 로 덮고, 타임아웃
 * (1s / 3s, sp-docs/nfr.md §3)과 키 헤더는 설정에서 오게 둔다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberClientTest {

	/** 픽스처 {@code accountId}. 다른 테스트가 쓰는 범위(201~210, 301~317)를 피해 401 이상이다. */
	private static final long ACCOUNT_ID = 402L;

	/** application-test.yml 이 주는 테스트 전용 키다. 실제 키가 아니다. */
	private static final String TEST_API_KEY = "test-internal-api-key";

	/** 정본: sp-docs/api-contract.md §5. 벌크 하나뿐이다. */
	private static final String BULK_PATH = "/internal/v1/members/bulk";

	private static final HttpServer STUB = startStub();

	/** 각 테스트가 돌려줄 응답. 요청을 받으면 이 핸들러가 실행된다. */
	private static final AtomicReference<StubResponder> RESPONDER = new AtomicReference<>();

	/** 스텁이 받은 마지막 요청. 헤더·본문·경로를 단언한다. */
	private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();

	@DynamicPropertySource
	static void stubServerUrl(DynamicPropertyRegistry registry) {
		registry.add("member-service.url", () -> "http://localhost:" + STUB.getAddress().getPort());
	}

	@Autowired
	private MemberClient memberClient;

	@Autowired
	private Environment environment;

	// --- 타임아웃 (sp-docs/nfr.md §3) ---

	/**
	 * 타임아웃을 <b>설정값으로</b> 단언한다. 기대값은 리터럴이다 — 정본이 connect 1초 /
	 * read 3초를 <b>값</b>으로 못박았고, 1차에 재시도·서킷브레이커가 없으므로 이 타임아웃이
	 * 유일한 보호다 (sp-docs/nfr.md §2 §3).
	 *
	 * <p><b>끊기는 시간만 보면 창 안의 드리프트를 놓친다</b> — read 를 7초로 올려도 "응답
	 * 없음" 테스트의 시간 단언은 받아들인다(리뷰 실측). 그래서 두 단언을 함께 둔다: 이쪽이
	 * 값을 핀하고, {@link #타임아웃은_S001()} 이 "미지정(무제한)" 을 잡는다.
	 *
	 * <p>{@code Duration} 으로 읽으므로 표기({@code 1s}·{@code 1000ms})가 아니라 값을 본다.
	 */
	@Test
	@DisplayName("connect 1초 / read 3초가 설정값으로 핀되어 있다")
	void 타임아웃_설정값이_정본과_일치한다() {
		assertThat(this.environment.getProperty("member-service.connect-timeout", Duration.class))
				.as("connect 타임아웃 (sp-docs/nfr.md §3)")
				.isEqualTo(Duration.ofSeconds(1));
		assertThat(this.environment.getProperty("member-service.read-timeout", Duration.class))
				.as("read 타임아웃 (sp-docs/nfr.md §3). 올리면 member 장애 때 board 가 스레드를 "
						+ "그만큼 더 오래 쥔다 (§2 의 장애 격리)")
				.isEqualTo(Duration.ofSeconds(3));
	}

	// --- 200 (sp-docs/api-contract.md §5 §5.1) ---

	@Test
	@DisplayName("200 과 data.members 의 활성 프로필이면 닉네임을 돌려준다")
	void 활성_프로필이면_닉네임을_돌려준다() {
		respondWith(200, envelope("""
				{"accountId": %d, "nickname": "홍길동", "deleted": false}""".formatted(ACCOUNT_ID)));

		assertThat(this.memberClient.findActiveNickname(ACCOUNT_ID)).isEqualTo("홍길동");
	}

	@Test
	@DisplayName("200 이고 members 가 빈 배열이면 S002 다 (결과에서 제외됨)")
	void 결과에서_제외되면_S002() {
		respondWith(200, envelope(""));

		assertBusinessException(ErrorCode.PROFILE_REQUIRED);
	}

	/**
	 * 요청 순서와 응답 순서를 맞추지 않으므로 소비 측이 {@code accountId} 로 매칭한다 (§5).
	 * 매칭을 빼면 남의 닉네임이 작성자 스냅샷으로 저장된다.
	 */
	@Test
	@DisplayName("200 이지만 members 에 그 accountId 가 없으면 S002 다")
	void 다른_accountId_만_오면_S002() {
		respondWith(200, envelope("""
				{"accountId": %d, "nickname": "남의닉네임", "deleted": false}"""
				.formatted(ACCOUNT_ID + 1)));

		assertBusinessException(ErrorCode.PROFILE_REQUIRED);
	}

	@Test
	@DisplayName("200 이고 deleted = true 면 S002 다")
	void 탈퇴한_프로필이면_S002() {
		respondWith(200, envelope("""
				{"accountId": %d, "nickname": "탈퇴한 회원", "deleted": true}""".formatted(ACCOUNT_ID)));

		assertBusinessException(ErrorCode.PROFILE_REQUIRED);
	}

	// --- 그 밖의 모든 응답 → S001 (§5.1) ---

	/**
	 * <b>404 를 업무 의미로 쓰지 않는다.</b> {@code S002} 로 처리하면 {@code MEMBER_SERVICE_URL}
	 * 오설정이 "프로필 없음" 으로 위장되어 경보 없이 전 사용자의 쓰기가 멈춘다 (§5.1).
	 */
	@Test
	@DisplayName("404(경로 오설정)는 S001 이다. S002 가 아니다")
	void 응답_404_는_S001() {
		respondWith(404, "{\"timestamp\":\"...\",\"status\":404,\"path\":\"/wrong\"}");

		assertBusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
	}

	/**
	 * <b>키 거부도 {@code S001} 이다.</b> 키를 회전하고 board 만 갱신을 놓치면 전 사용자의 쓰기가
	 * 멈추는데, 업무 오류로 분류하면 경보가 뜨지 않는다 (sp-docs/security.md §6).
	 */
	@Test
	@DisplayName("401(API 키 거부)은 S001 이다")
	void 응답_401_은_S001() {
		respondWith(401, "{\"success\":false,\"data\":null,"
				+ "\"error\":{\"code\":\"A001\",\"message\":\"인증이 필요합니다.\",\"fieldErrors\":[]}}");

		assertBusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
	}

	@Test
	@DisplayName("500 은 S001 이다")
	void 응답_500_은_S001() {
		respondWith(500, "{\"success\":false,\"data\":null,"
				+ "\"error\":{\"code\":\"C005\",\"message\":\"서버 내부 오류가 발생했습니다.\","
				+ "\"fieldErrors\":[]}}");

		assertBusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
	}

	@Test
	@DisplayName("본문 파싱 실패는 S001 이다")
	void 파싱_실패는_S001() {
		respondWith(200, "<html><body>member is not here</body></html>");

		assertBusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
	}

	/**
	 * 봉투를 벗긴 모양(배열이 최상위)을 받아도 {@code S001} 이다 — 그것이 §5 의 응답이 아니다.
	 * 이 단언이 없으면 클라이언트가 두 모양을 모두 받아들이게 고쳐져도 드러나지 않는다.
	 */
	@Test
	@DisplayName("최상위가 배열인 응답은 S001 이다 (§5 의 봉투가 아니다)")
	void 봉투가_없는_응답은_S001() {
		respondWith(200, """
				[{"accountId": %d, "nickname": "홍길동", "deleted": false}]""".formatted(ACCOUNT_ID));

		assertBusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
	}

	/**
	 * 응답이 오지 않는 경우(타임아웃)도 {@code S001} 이다.
	 *
	 * <p><b>read 타임아웃이 설정되지 않으면(기본값 무제한) 이 테스트는 스텁의 지연을 그대로
	 * 기다린 뒤 닉네임을 받아 빨개진다</b> (sp-docs/nfr.md §3). 그래서 지연을 정본 타임아웃보다
	 * 훨씬 길게 두고, 걸린 시간이 그 지연보다 짧다는 것까지 함께 본다.
	 */
	@Test
	@DisplayName("응답 없음(타임아웃)은 S001 이고 read 타임아웃 안에 끊긴다")
	void 타임아웃은_S001() {
		Duration stubDelay = Duration.ofSeconds(10);
		RESPONDER.set(exchange -> {
			sleep(stubDelay);
			writeResponse(exchange, 200, envelope("""
					{"accountId": %d, "nickname": "홍길동", "deleted": false}""".formatted(ACCOUNT_ID)));
		});

		long startedAt = System.nanoTime();
		assertBusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(elapsed)
				.as("걸린 시간 %s. read 타임아웃(3s, sp-docs/nfr.md §3)이 설정되지 않았으면 "
						+ "스텁의 지연 %s 을 그대로 기다린다", elapsed, stubDelay)
				.isLessThan(stubDelay.minusSeconds(2))
				.isGreaterThanOrEqualTo(Duration.ofSeconds(3));
	}

	// --- 요청 (§5, sp-docs/security.md §6) ---

	@Test
	@DisplayName("요청에 X-Internal-Api-Key 헤더를 싣고 벌크 경로에 accountIds 를 보낸다")
	void 요청_헤더와_본문() {
		respondWith(200, envelope("""
				{"accountId": %d, "nickname": "홍길동", "deleted": false}""".formatted(ACCOUNT_ID)));

		this.memberClient.findActiveNickname(ACCOUNT_ID);

		CapturedRequest request = LAST_REQUEST.get();
		assertThat(request).isNotNull();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path())
				.as("벌크 엔드포인트 하나만 호출한다 (sp-docs/api-contract.md §5)")
				.isEqualTo(BULK_PATH);
		assertThat(request.header(MemberClient.INTERNAL_API_KEY_HEADER))
				.as("board 는 키를 싣는 쪽이다 (sp-docs/security.md §6)")
				.isEqualTo(TEST_API_KEY);
		assertThat(request.body())
				.as("요청 키는 accountIds 다. 1건만 필요해도 벌크에 담는다 (§5)")
				.isEqualTo("{\"accountIds\":[%d]}".formatted(ACCOUNT_ID));
	}

	/**
	 * 내부 API 키가 빈 문자열이면 기동을 실패시킨다.
	 *
	 * <p>환경변수가 <b>없을 때</b>는 {@code @Value} 가 placeholder 를 해석하지 못해 빈 생성이
	 * 실패하지만, <b>빈 문자열로 설정된 경우는 해석이 성공하고 기동도 성공한다</b>(M-10 실측,
	 * sp-docs/tech-stack.md §4.3). 그 상태로 뜨면 모든 글·댓글 작성이 member 에서 거부되어
	 * 503 {@code S001} 경보로만 드러난다.
	 */
	@Test
	@DisplayName("내부 API 키가 비어 있거나 해석되지 않은 placeholder 면 기동이 실패한다")
	void 빈_내부_API_키는_기동을_실패시킨다() {
		assertThatThrownBy(() -> newClientWithApiKey(""))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> newClientWithApiKey("   "))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> newClientWithApiKey("${INTERNAL_API_KEY}"))
				.as("해석되지 않은 placeholder 가 내부 API 의 키가 되면 안 된다")
				.isInstanceOf(IllegalStateException.class);
	}

	private static MemberClient newClientWithApiKey(String apiKey) {
		return new MemberClient(RestClient.builder(), "http://localhost:1", apiKey,
				Duration.ofSeconds(1), Duration.ofSeconds(3));
	}

	private void assertBusinessException(ErrorCode expected) {
		assertThatThrownBy(() -> this.memberClient.findActiveNickname(ACCOUNT_ID))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(expected);
	}

	/** sp-docs/api-contract.md §5 의 응답 봉투. {@code members} 는 {@code data} 아래에 있다. */
	private static String envelope(String members) {
		return "{\"success\":true,\"data\":{\"members\":[%s]},\"error\":null}".formatted(members);
	}

	private static void respondWith(int status, String body) {
		RESPONDER.set(exchange -> writeResponse(exchange, status, body));
	}

	private static void writeResponse(HttpExchange exchange, int status, String body) {
		try {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		} catch (IOException e) {
			// 클라이언트가 타임아웃으로 끊은 뒤의 쓰기 실패다. 테스트 결과에 영향이 없다
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
				LAST_REQUEST.set(CapturedRequest.of(exchange));
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

	@FunctionalInterface
	private interface StubResponder {
		void respond(HttpExchange exchange);
	}

	private record CapturedRequest(String method, String path, Headers headers, String body) {

		static CapturedRequest of(HttpExchange exchange) {
			try (InputStream in = exchange.getRequestBody()) {
				return new CapturedRequest(
						exchange.getRequestMethod(),
						exchange.getRequestURI().getPath(),
						exchange.getRequestHeaders(),
						new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		String header(String name) {
			return this.headers.getFirst(name);
		}

	}

}
