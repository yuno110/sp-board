package com.example.board.attachment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.board.attachment.service.AttachmentContent;
import com.example.board.attachment.service.AttachmentService;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.GlobalExceptionHandler;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.DelegatingServletOutputStream;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * sp-docs/plan/phase2-board.md B-23 검증 표 중 HTTP 쪽이다 — 판정 순서·상태 코드·응답 본문·저장된
 * 행과 파일·인증·member 호출 0회. B-24 검증 표의 내려받기·지우기 줄도 여기다(같은 첨부의 동시 지우기는
 * 서비스 테스트). 동시 올리기·INSERT 실패·판정과 UPDATE 사이에 지워진 글은
 * {@link com.example.board.attachment.service.AttachmentServiceTest} 가 본다.
 *
 * <p>정본은 sp-docs/requirements/board.md §11 (판정 순서·형식·파일명·개수),
 * sp-docs/api-contract.md §4.7 §9.9 (경로·응답·실패), §4.3 (쓰기 응답의 {@code createdAt}),
 * sp-docs/domain-model.md §4.6 §6.2, sp-docs/security.md §8 이다.
 *
 * <p><b>저장 루트는 테스트마다 비운 임시 디렉터리다</b> (sp-docs/tech-stack.md §4.5) — 이 클래스의
 * 임시 디렉터리를 {@code attachment.storage-root} 로 주고, 테스트마다 비운다. 그래서 「파일 없음」 은
 * 그 디렉터리를 실제로 세어 0 인 것으로 단언한다. 테스트 프로파일의 {@code ${java.io.tmpdir}} 는
 * 쓰지 않는다.
 *
 * <p><b>정본 값은 리터럴로 적는다</b> — 상한 10, 파일명 255자, 응답 키, 형식의 {@code Content-Type}.
 * 운영 코드의 상수를 참조하면 그 값이 틀려도 기대값이 함께 움직인다.
 *
 * <p><b>member 스텁은 언제나 프로필이 미등록이라고 답한다</b> (sp-docs/api-contract.md §5.1). 올리기가
 * member 를 부르면 403 {@code S002} 가 되어 201 단언이 빨개지고, 스텁이 받은 요청 수로 호출 0회를
 * 본다.
 *
 * <p>픽스처의 {@code writerId} 와 요청 계정은 1451~1499 를 쓴다. 같은 JVM 의 모든 테스트가 하나의 H2
 * DB 를 보므로 (sp-docs/tech-stack.md §5.3) 2026-11-18 에 테스트 소스 전체에서 그 값을 쓰는 곳이
 * 없는 것을 확인하고 골랐다. {@code @AfterEach} 가 자기 대역의 글과 그 글의 첨부만 지운다 —
 * {@code attachment.post_id} 에 FK 가 있으므로 ({@code fk_attachment_post_id}) 첨부를 먼저 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttachmentControllerTest {

	private static final String PATH = "/api/v1/posts/{postId}/attachments";

	/** 내려받기·지우기의 경로 (sp-docs/api-contract.md §4.7). */
	private static final String DOWNLOAD_PATH = "/api/v1/attachments/{id}";

	/** 어떤 첨부에도 쓰이지 않는 id. IDENTITY 는 양수만 준다. */
	private static final long 없는_첨부 = -1L;

	/** sp-docs/api-contract.md §6 의 {@code role} claim 값. */
	private static final String USER = "USER";

	private static final String ADMIN = "ADMIN";

	/** 글당 활성 첨부의 상한. 정본: sp-docs/requirements/board.md §11 의 「개수」 줄. */
	private static final int 상한 = 10;

	/** 첨부 하나의 모양. sp-docs/api-contract.md §4.7 의 표 그대로 — 리터럴로 적는다. */
	private static final Set<String> ATTACHMENT_KEYS =
			Set.of("id", "originalFilename", "contentType", "size", "createdAt");

	/** 어떤 글에도 쓰이지 않는 id. IDENTITY 는 양수만 준다. */
	private static final long 없는_글 = -1L;

	// sp-docs/requirements/board.md §11 의 「형식 판정의 매직 바이트」 표. 머리 뒤는 이미지가 아닌 바이트다
	private static final byte[] PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4);

	private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4);

	private static final byte[] GIF = "GIF89a-body".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] WEBP = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] SVG =
			"<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
					.getBytes(StandardCharsets.US_ASCII);

	private static final HttpServer STUB = startStub();

	/** 스텁이 받은 요청 수. 「{@code MemberClient} 호출 0회」 를 관측하는 지점이다. */
	private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();

	/** 이 클래스의 저장 루트. 테스트마다 {@link #저장_루트를_비운다} 가 비운다. */
	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void testEnvironment(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
		registry.add("member-service.url", () -> "http://localhost:" + STUB.getAddress().getPort());
		registry.add("attachment.storage-root", () -> storageRoot.toString());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AttachmentService attachmentService;

	@BeforeEach
	void 저장_루트를_비운다() throws IOException {
		REQUEST_COUNT.set(0);
		for (Path entry : 저장된_파일()) {
			Files.delete(entry);
		}
	}

	@AfterEach
	void 픽스처_정리() {
		// attachment.post_id 에 FK 가 있으므로 (fk_attachment_post_id) 첨부를 먼저 지운다
		this.jdbcTemplate.update("DELETE FROM attachment WHERE post_id IN "
				+ "(SELECT id FROM post WHERE writer_id BETWEEN 1451 AND 1499)");
		this.jdbcTemplate.update("DELETE FROM post WHERE writer_id BETWEEN 1451 AND 1499");
	}

	// --- 성공과 응답 (sp-docs/api-contract.md §4.7 §9.9) ---

	/** 검증 표 <b>「네 형식의 머리를 가진 파일 → 201, {@code contentType} 이 판정한 값」</b>. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("네_형식")
	@DisplayName("네 형식의 머리를 가진 파일은 201 이고 contentType 이 판정한 값이며 행에 그 값이 저장된다")
	void 네_형식은_201_이다(String expectedContentType, byte[] content) throws Exception {
		Post post = 게시글을_만든다(1451L);

		MvcResult result = 올린다(post.getId(), 1451L, USER, 파일("a.bin", "application/octet-stream", content))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.contentType").value(expectedContentType))
				.andExpect(jsonPath("$.data.size").value(content.length))
				.andReturn();

		assertThat(저장된_열(idOf(result), "content_type")).isEqualTo(expectedContentType);
		assertThat(저장된_파일()).hasSize(1);
	}

	static Stream<Arguments> 네_형식() {
		return Stream.of(
				Arguments.of("image/jpeg", JPEG),
				Arguments.of("image/png", PNG),
				Arguments.of("image/gif", GIF),
				Arguments.of("image/webp", WEBP));
	}

	/**
	 * 검증 표 <b>「PNG 머리 + 요청 {@code Content-Type: image/jpeg} + 확장자 {@code .jpg} → 201,
	 * {@code contentType: image/png}」</b> — 요청의 값과 확장자를 쓰지 않는다 (§11 의 「형식 판정」 줄).
	 */
	@Test
	@DisplayName("PNG 머리에 요청 Content-Type 이 image/jpeg 이고 확장자가 .jpg 여도 image/png 다")
	void 요청의_Content_Type_과_확장자를_쓰지_않는다() throws Exception {
		Post post = 게시글을_만든다(1452L);

		MvcResult result = 올린다(post.getId(), 1452L, USER, 파일("photo.jpg", "image/jpeg", PNG))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.contentType").value("image/png"))
				.andExpect(jsonPath("$.data.originalFilename").value("photo.jpg"))
				.andReturn();

		assertThat(저장된_열(idOf(result), "content_type")).isEqualTo("image/png");
	}

	/**
	 * 검증 표 <b>「응답 키 집합」</b>·<b>「응답의 {@code createdAt}」</b>·<b>「저장된 파일」</b>.
	 *
	 * <p>키 집합은 §4.7 의 첨부 하나의 모양과 정확히 같다 — 저장 이름이 나가지 않는다. {@code createdAt}
	 * 은 다시 읽은 저장값과 같다 (§4.3 의 쓰기 응답 문단). 파일은 저장 루트 바로 아래 하나이고 그
	 * 이름이 행의 {@code storage_key} 이며 서버가 만든 UUID 다 (sp-docs/domain-model.md §4.6). 내용은
	 * 올린 바이트 그대로다 — 서버가 바이트를 바꾸지 않는다 (sp-docs/security.md §8 의 EXIF 문단).
	 */
	@Test
	@DisplayName("응답 키가 §4.7 과 같고 createdAt 이 저장값이며 파일이 루트 바로 아래 UUID 이름 하나다")
	void 응답과_저장된_행과_파일() throws Exception {
		Post post = 게시글을_만든다(1453L);

		MvcResult result = 올린다(post.getId(), 1453L, USER, 파일("map.png", "image/png", PNG))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andReturn();
		long id = idOf(result);

		Map<String, Object> data = JsonPath.read(bodyOf(result), "$.data");
		assertThat(data.keySet()).isEqualTo(ATTACHMENT_KEYS);
		assertThat(data.get("originalFilename")).isEqualTo("map.png");
		assertThat(((Number) data.get("size")).longValue()).isEqualTo(PNG.length);

		LocalDateTime stored = this.jdbcTemplate.queryForObject(
				"SELECT created_at FROM attachment WHERE id = ?", LocalDateTime.class, id);
		assertThat(LocalDateTime.parse((String) data.get("createdAt")))
				.as("쓰기 응답의 createdAt 은 저장값과 같다 (sp-docs/api-contract.md §4.3)")
				.isEqualTo(stored);

		String storageKey = 저장된_열(id, "storage_key");
		assertThat(UUID.fromString(storageKey).toString()).isEqualTo(storageKey);
		assertThat(bodyOf(result)).doesNotContain(storageKey);
		assertThat(저장된_파일()).containsExactly(storageRoot.resolve(storageKey));
		assertThat(Files.readAllBytes(storageRoot.resolve(storageKey))).isEqualTo(PNG);
		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(활성_첨부_수(post)).isEqualTo(1);
	}

	/**
	 * 검증 표 <b>「프로필 없는 계정 → 201, {@code MemberClient} 호출 0회」</b> (§11 의 「프로필」 줄).
	 * 스텁이 프로필 미등록으로 답하므로 member 를 부르면 403 {@code S002} 가 된다.
	 */
	@Test
	@DisplayName("프로필 없는 계정도 올리고 member 를 한 번도 부르지 않는다")
	void 프로필_없는_계정도_올린다() throws Exception {
		Post post = 게시글을_만든다(1454L);

		올린다(post.getId(), 1454L, USER, 파일("a.png", "image/png", PNG)).andExpect(status().isCreated());

		assertThat(REQUEST_COUNT.get()).as("첨부는 프로필이 필요 없다 — S002 가 나지 않는다").isZero();
	}

	// --- 파일명 (§11 의 「파일명」 줄) ---

	/**
	 * 검증 표 <b>「파일명 {@code ../../x.png}·{@code C:\a\b.png} → 201, 원본 파일명은 앞부분을 버린 값,
	 * 저장 경로와 무관」</b>·<b>「제어 문자가 든 파일명 → 제어 문자를 버린 값」</b>.
	 *
	 * <p>저장 경로와 무관하다는 것은 저장 루트 바로 아래의 파일 하나가 행의 UUID 이름이라는 것으로
	 * 본다 — 루트 밖이나 그 파일명의 파일이 생기지 않는다.
	 */
	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("정리되는_파일명")
	@DisplayName("경로 앞부분과 제어 문자를 버린 값이 원본 파일명이 되고 저장 경로와 무관하다")
	void 파일명을_정리한다(String submitted, String expected) throws Exception {
		Post post = 게시글을_만든다(1455L);

		MvcResult result = 올린다(post.getId(), 1455L, USER, 파일(submitted, "image/png", PNG))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.originalFilename").value(expected))
				.andReturn();
		long id = idOf(result);

		assertThat(저장된_열(id, "original_filename")).isEqualTo(expected);
		String storageKey = 저장된_열(id, "storage_key");
		assertThat(storageKey).isNotEqualTo(expected);
		assertThat(저장된_파일()).containsExactly(storageRoot.resolve(storageKey));
		assertThat(storageRoot.resolve("../../x.png").normalize())
				.as("../../x.png 가 가리키는 루트 밖의 자리에 파일이 생기지 않는다").doesNotExist();
	}

	static Stream<Arguments> 정리되는_파일명() {
		return Stream.of(
				Arguments.of("../../x.png", "x.png"),
				Arguments.of("C:\\a\\b.png", "b.png"),
				Arguments.of("ma\u0000p\u001f\u007f.png", "map.png"));
	}

	/** 검증 표 <b>「정리 뒤 1자 / 255자 파일명 → 201, 저장된 원본 파일명이 그 값」</b>. 255 는 정본 값이다. */
	@ParameterizedTest(name = "{0}자")
	@ValueSource(ints = {1, 255})
	@DisplayName("정리 뒤 1자·255자 파일명은 201 이고 저장된 원본 파일명이 그 값이다")
	void 경계_안의_파일명은_받는다(int length) throws Exception {
		Post post = 게시글을_만든다(1456L);
		String name = "n".repeat(length);

		MvcResult result = 올린다(post.getId(), 1456L, USER, 파일("dir/" + name, "image/png", PNG))
				.andExpect(status().isCreated())
				.andReturn();

		assertThat(저장된_열(idOf(result), "original_filename")).isEqualTo(name).hasSize(length);
	}

	/** 검증 표 <b>「정리 뒤 빈 파일명 / 256자 → 400 {@code C001}, 파일 없음」</b>. */
	@ParameterizedTest(name = "[{index}]")
	@MethodSource("규칙_밖_파일명")
	@DisplayName("정리 뒤 빈 파일명·256자 파일명은 400 C001 이고 파일을 쓰지 않는다")
	void 규칙_밖_파일명은_C001(String submitted) throws Exception {
		Post post = 게시글을_만든다(1457L);

		올린다(post.getId(), 1457L, USER, 파일(submitted, "image/png", PNG))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	static Stream<String> 규칙_밖_파일명() {
		return Stream.of("../dir/", "\u0000\u0001", "C:\\a\\", "n".repeat(256), "dir/" + "n".repeat(256));
	}

	// --- 형식·크기 (§11 의 「형식」 줄, §4.7 의 실패 표) ---

	/** 검증 표 <b>「SVG / 텍스트 / 머리보다 짧은 파일 → 400 {@code AT002}, 파일 없음」</b>. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("네_형식이_아닌_파일")
	@DisplayName("SVG·텍스트·머리보다 짧은 파일은 400 AT002 이고 파일을 쓰지 않는다")
	void 네_형식이_아니면_AT002(String description, String contentType, byte[] content) throws Exception {
		Post post = 게시글을_만든다(1458L);

		올린다(post.getId(), 1458L, USER, 파일("a.png", contentType, content))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("AT002"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	static Stream<Arguments> 네_형식이_아닌_파일() {
		return Stream.of(
				Arguments.of("SVG (요청은 image/png 라고 한다)", "image/png", SVG),
				Arguments.of("SVG (요청도 image/svg+xml)", "image/svg+xml", SVG),
				Arguments.of("텍스트", "image/png", "그냥 글자".getBytes(StandardCharsets.UTF_8)),
				Arguments.of("PNG 머리보다 짧다", "image/png", bytes(0x89, 0x50, 0x4E, 0x47)));
	}

	/** 검증 표 <b>「0바이트 → 400 {@code C001}, 파일 없음」</b>. */
	@Test
	@DisplayName("0바이트 파일은 400 C001 이고 파일을 쓰지 않는다")
	void 영_바이트는_C001() throws Exception {
		Post post = 게시글을_만든다(1459L);

		올린다(post.getId(), 1459L, USER, 파일("a.png", "image/png", new byte[0]))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	/** 검증 표 <b>「삭제된 글에 0바이트 → 400 {@code C001} ({@code P001} 아님 — 순서), 파일 없음」</b>. */
	@Test
	@DisplayName("삭제된 글에 0바이트 파일은 P001 이 아니라 400 C001 이다")
	void 삭제된_글에_영_바이트는_C001() throws Exception {
		Post post = 게시글을_만든다(1460L);
		지운다(post);

		올린다(post.getId(), 1460L, USER, 파일("a.png", "image/png", new byte[0]))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	/** 검증 표 <b>「{@code file} 파트 없음 → 400 {@code C001}」</b>. 이름이 다른 파트만 있는 요청이다. */
	@Test
	@DisplayName("file 파트가 없으면 400 C001 이다")
	void file_파트가_없으면_C001() throws Exception {
		Post post = 게시글을_만든다(1461L);

		this.mockMvc.perform(multipart(PATH, post.getId())
						.file(new MockMultipartFile("image", "a.png", "image/png", PNG))
						.header(HttpHeaders.AUTHORIZATION, bearer(1461L, USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	/** 검증 표 <b>「multipart 가 아닌 요청 → 415 {@code C006}」</b> — 매핑의 {@code consumes} (§4.7). */
	@Test
	@DisplayName("multipart/form-data 가 아닌 요청은 415 C006 이다")
	void multipart_가_아니면_C006() throws Exception {
		Post post = 게시글을_만든다(1462L);

		this.mockMvc.perform(post(PATH, post.getId())
						.contentType(MediaType.IMAGE_PNG)
						.content(PNG)
						.header(HttpHeaders.AUTHORIZATION, bearer(1462L, USER)))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("C006"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	// --- 글과 작성자 (§11 의 「누가」 줄, sp-docs/api-contract.md §4.4) ---

	/** 검증 표 <b>「남의 글 (USER·ADMIN) → 403 {@code P002}, 파일 없음」</b>. ADMIN 도 남의 글에는 못 올린다. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {USER, ADMIN})
	@DisplayName("남의 글에는 USER 도 ADMIN 도 403 P002 이고 파일을 쓰지 않는다")
	void 남의_글은_P002(String role) throws Exception {
		Post post = 게시글을_만든다(1463L);

		올린다(post.getId(), 1464L, role, 파일("a.png", "image/png", PNG))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P002"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	/** 검증 표 <b>「남의 글에 SVG → 403 {@code P002} ({@code AT002} 아님 — 순서), 파일 없음」</b>. */
	@Test
	@DisplayName("남의 글에 SVG 를 올리면 AT002 가 아니라 403 P002 다")
	void 남의_글에_SVG_는_P002() throws Exception {
		Post post = 게시글을_만든다(1465L);

		올린다(post.getId(), 1466L, USER, 파일("a.svg", "image/svg+xml", SVG))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P002"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	/** 검증 표 <b>「없는·삭제된 글 → 404 {@code P001}, 파일 없음」</b>. */
	@Test
	@DisplayName("없는 글과 삭제된 글은 404 P001 이고 파일을 쓰지 않는다")
	void 없는_글과_삭제된_글은_P001() throws Exception {
		Post post = 게시글을_만든다(1467L);
		지운다(post);

		올린다(없는_글, 1467L, USER, 파일("a.png", "image/png", PNG))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));
		올린다(post.getId(), 1467L, USER, 파일("a.png", "image/png", PNG))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	/** 검증 표 <b>「남의 삭제된 글 → 404 {@code P001} ({@code P002} 아님 — 순서), 파일 없음」</b>. */
	@Test
	@DisplayName("남의 삭제된 글은 P002 가 아니라 404 P001 이다")
	void 남의_삭제된_글은_P001() throws Exception {
		Post post = 게시글을_만든다(1468L);
		지운다(post);

		올린다(post.getId(), 1469L, USER, 파일("a.png", "image/png", PNG))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("P001"));

		거부된_뒤_아무것도_남지_않는다(post);
	}

	// --- 개수 (§11 의 「개수」 줄, sp-docs/domain-model.md §6.2) ---

	/**
	 * 검증 표 <b>「활성 첨부가 상한인 글 → 409 {@code AT003}, 파일이 남지 않는다」</b>.
	 *
	 * <p>판정은 조건부 UPDATE 이고 파일은 그 앞에 쓴다 — 쓴 파일을 지워서 남지 않는다 (§6.2). 집계와
	 * 활성 행이 상한 그대로다. 지운 첨부는 세지 않으므로, 지운 첨부가 있는 글은 활성이 상한 아래면
	 * 올라간다.
	 */
	@Test
	@DisplayName("활성 첨부가 10개인 글은 409 AT003 이고 쓴 파일이 남지 않으며 집계가 10 그대로다")
	void 상한이면_AT003() throws Exception {
		Post post = 게시글을_만든다(1470L);
		첨부를_둔다(post, 상한);

		올린다(post.getId(), 1470L, USER, 파일("a.png", "image/png", PNG))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("AT003"));

		assertThat(저장된_파일()).as("쓴 파일을 지운다 (sp-docs/domain-model.md §6.2)").isEmpty();
		assertThat(커밋된_집계(post)).isEqualTo(상한);
		assertThat(활성_첨부_수(post)).isEqualTo(상한);
	}

	/** 상한 하나 아래면 올라가고 집계와 활성 행이 상한이 된다 — 위 테스트의 경계 반대편이다. */
	@Test
	@DisplayName("활성 첨부가 9개인 글은 201 이고 집계와 활성 행이 10 이 된다")
	void 상한_하나_아래면_올린다() throws Exception {
		Post post = 게시글을_만든다(1471L);
		첨부를_둔다(post, 상한 - 1);

		올린다(post.getId(), 1471L, USER, 파일("a.png", "image/png", PNG)).andExpect(status().isCreated());

		assertThat(커밋된_집계(post)).isEqualTo(상한);
		assertThat(활성_첨부_수(post)).isEqualTo(상한);
		assertThat(저장된_파일()).hasSize(1);
	}

	/** 검증 표 <b>「활성 첨부가 상한인 글에 SVG → 400 {@code AT002} ({@code AT003} 아님 — 순서), 파일 없음」</b>. */
	@Test
	@DisplayName("활성 첨부가 10개인 글에 SVG 를 올리면 AT003 이 아니라 400 AT002 다")
	void 상한인_글에_SVG_는_AT002() throws Exception {
		Post post = 게시글을_만든다(1472L);
		첨부를_둔다(post, 상한);

		올린다(post.getId(), 1472L, USER, 파일("a.svg", "image/svg+xml", SVG))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("AT002"));

		assertThat(저장된_파일()).isEmpty();
		assertThat(커밋된_집계(post)).isEqualTo(상한);
	}

	// --- 인증 ---

	/**
	 * 검증 표 <b>「claim 이 계약 밖인 토큰 → 401 {@code A002}」</b> — {@code PostControllerTest} 의
	 * {@code role = BOGUS} 테스트와 같은 방식이다. 서명이 유효하므로 필터 체인은 통과하고
	 * {@code @CurrentMember} 리졸버가 끊는다. 무토큰 401 {@code A001} 은 {@code JwtAuthenticationTest}
	 * 의 인증 경로 목록이 본다.
	 *
	 * <p><b>거부한 계층까지 본다.</b> 핸들러가 선택되고 리졸버가 던진 예외가 남아야 리졸버가 끊은 것이다.
	 * {@code file} 파트가 없는 요청에도 {@code C001} 이 아니라 {@code A002} 다 — 인증이 multipart 판정
	 * 앞이다 (§11 의 판정 순서).
	 */
	@ParameterizedTest(name = "file 파트 있음={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("role 이 열거 밖인 토큰이면 리졸버가 401 A002 로 끊고 아무것도 남지 않는다")
	void 열거_밖_role_이면_A002(boolean withFilePart) throws Exception {
		Post post = 게시글을_만든다(1473L);
		MockMultipartFile part = withFilePart
				? 파일("a.png", "image/png", PNG)
				: new MockMultipartFile("image", "a.png", "image/png", PNG);

		MvcResult result = this.mockMvc.perform(multipart(PATH, post.getId())
						.file(part)
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(1473L, "BOGUS")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"))
				.andReturn();

		assertThat(result.getHandler())
				.as("서명이 유효한 토큰은 필터 체인을 통과한다 — 집행 지점은 리졸버다").isNotNull();
		assertThat(result.getResolvedException()).isInstanceOf(BusinessException.class);
		거부된_뒤_아무것도_남지_않는다(post);
	}

	// --- 내려받기 (B-24, sp-docs/api-contract.md §4.7, sp-docs/security.md §8) ---

	/**
	 * B-24 검증 표 <b>「내려받기 → 200, 본문이 올린 바이트, 헤더가 sp-docs/security.md §8 의 표와 같다」</b>와
	 * <b>「비로그인 내려받기 → 200」</b>.
	 *
	 * <p>헤더는 표의 다섯 줄을 값까지 리터럴로 대조한다. {@code Cache-Control} 은 값이 하나뿐인지도 본다 —
	 * Spring Security 의 기본 캐시 헤더({@code no-cache, no-store …})가 섞이면 {@code private} 캐시가
	 * 깨진다. 본문은 봉투가 아니라 올린 바이트 그대로다.
	 */
	@ParameterizedTest(name = "토큰 있음={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("내려받기는 200 이고 본문이 올린 바이트이며 헤더가 security.md §8 의 표와 같다 — 비로그인도 같다")
	void 내려받기는_올린_바이트와_서빙_헤더다(boolean withToken) throws Exception {
		Post post = 게시글을_만든다(1474L);
		long id = idOf(올린다(post.getId(), 1474L, USER, 파일("map.png", "image/jpeg", PNG))
				.andExpect(status().isCreated()).andReturn());

		MockHttpServletRequestBuilder request = get(DOWNLOAD_PATH, id);
		if (withToken) {
			request.header(HttpHeaders.AUTHORIZATION, bearer(1475L, USER));
		}
		MvcResult result = this.mockMvc.perform(request)
				.andExpect(status().isOk())
				.andExpect(content().bytes(PNG))
				.andReturn();

		MockHttpServletResponse response = result.getResponse();
		assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).as("판정한 형식 — 요청의 image/jpeg 가 아니다")
				.isEqualTo("image/png");
		assertThat(response.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
		assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION))
				.containsExactly("inline; filename*=UTF-8''map.png");
		assertThat(response.getHeaders(HttpHeaders.CACHE_CONTROL)).containsExactly("private, max-age=86400");
		assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(PNG.length));
		assertThat(REQUEST_COUNT.get()).as("내려받기는 member 를 부르지 않는다").isZero();
	}

	/**
	 * <b>서빙 헤더는 핸들러가 싣는다 — 보안 필터의 기본값에 기대지 않는다</b> (sp-docs/security.md §8 이
	 * 서빙 응답에 요구한다). Spring Security 의 기본 헤더도 {@code X-Content-Type-Options: nosniff} 를
	 * 싣는다 — 그래서 위 테스트는 핸들러가 그 줄을 빼도 녹색이었다(2026-11-19 B-24 변이 실측). 보안
	 * 설정이 기본 헤더를 끄거나 바꾸면 서빙에서 {@code nosniff} 가 조용히 사라진다.
	 *
	 * <p>필터 없이 이 컨트롤러만 세운 {@link MockMvc} 로 같은 첨부를 받는다. 서비스는 컨텍스트의 실제
	 * 빈이다.
	 */
	@Test
	@DisplayName("보안 필터 없이도 서빙 헤더가 security.md §8 의 표와 같다 — 핸들러가 싣는다")
	void 서빙_헤더는_핸들러가_싣는다() throws Exception {
		Post post = 게시글을_만든다(1488L);
		long id = idOf(올린다(post.getId(), 1488L, USER, 파일("map.png", "image/png", PNG))
				.andExpect(status().isCreated()).andReturn());
		MockMvc 필터_없음 = MockMvcBuilders.standaloneSetup(new AttachmentController(this.attachmentService)).build();

		MockHttpServletResponse response = 필터_없음.perform(get(DOWNLOAD_PATH, id))
				.andExpect(status().isOk())
				.andExpect(content().bytes(PNG))
				.andReturn().getResponse();

		assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("image/png");
		assertThat(response.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
		assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION))
				.containsExactly("inline; filename*=UTF-8''map.png");
		assertThat(response.getHeaders(HttpHeaders.CACHE_CONTROL)).containsExactly("private, max-age=86400");
		assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(PNG.length));
	}

	/**
	 * <b>클라이언트가 끊으면 WARN 한 줄이고 ERROR 가 아니다</b> (sp-docs/conventions.md §6.1 — 클라이언트 쪽
	 * 사정을 마지막 그물로 보내지 않는다). 스트림은 닫힌다.
	 *
	 * <p>끊김은 <b>응답의 출력 스트림이</b> 던진다 — 실 Tomcat 에서 잰 모양 그대로
	 * {@code AsyncRequestNotUsableException}(원인 {@code ClientAbortException})이다(2026-11-19 B-24 리뷰
	 * 실측). 출력 스트림을 바꿔 끼우려고 컨트롤러를 직접 부른다. 로그는 루트에서 모은다.
	 */
	@Test
	@DisplayName("내려받는 도중 클라이언트가 끊으면 WARN 한 줄로 끝내고 ERROR 가 없으며 스트림을 닫는다")
	void 클라이언트가_끊으면_WARN_한_줄이다() throws Exception {
		CloseRecordingStream stream = new CloseRecordingStream(PNG, Integer.MAX_VALUE);
		AttachmentController controller = new AttachmentController(서비스_대역(stream));
		MockHttpServletResponse response = new MockHttpServletResponse() {
			@Override
			public ServletOutputStream getOutputStream() {
				return new DelegatingServletOutputStream(new java.io.OutputStream() {
					@Override
					public void write(int b) throws IOException {
						throw new AsyncRequestNotUsableException("ServletOutputStream failed to write (대역)",
								new ClientAbortException(new IOException("Connection reset by peer (대역)")));
					}
				});
			}
		};

		List<ILoggingEvent> 로그 = 루트_로그(() -> controller.download(7L, response));

		assertThat(stream.closed).as("스트림은 닫힌다").isTrue();
		assertThat(로그).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR);
		assertThat(로그).filteredOn(e -> e.getLevel() == Level.WARN).as("끊김은 컨트롤러의 WARN 한 줄이다")
				.singleElement()
				.satisfies(e -> assertThat(e.getLoggerName()).isEqualTo(AttachmentController.class.getName()));
	}

	/**
	 * <b>저장소 읽기 실패는 서버 결함이다 — 응답이 커밋되기 전이면 500 {@code C005} 봉투와 ERROR 다</b>
	 * (sp-docs/conventions.md §6.1 의 마지막 그물). 써 둔 서빙 헤더는 버려진다 — 봉투에
	 * {@code Content-Disposition} 이 실리지 않는다. 스트림은 닫힌다.
	 *
	 * <p>필터 없이 이 컨트롤러와 {@code GlobalExceptionHandler} 만 세우고, 서비스는 대역이다 — 앞의 4
	 * 바이트를 준 뒤 읽기에서 {@link IOException} 을 내는 스트림을 돌려준다. 4 바이트는 응답 버퍼 안이라
	 * 커밋되지 않았다. 커밋 뒤의 읽기 실패(연결이 닫힌다)는 실 컨테이너가 필요해 여기서 보지 않는다.
	 *
	 * <p><b>끊김처럼 보이는 읽기 예외도 서버 결함이다</b> — "Connection reset by peer"·"Broken pipe" 메시지와
	 * {@link EOFException} 은 Spring 의 끊김 판정이 보는 모양이다. 판정을 쓰기에만 걸지 않으면 이 셋이 200 과
	 * 잘린 본문이 된다(2026-11-19 B-24 3라운드 리뷰 실측). 네트워크 저장소에서는 읽기가 이런 예외를 낸다.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"저장소 읽기 실패 (대역)", "Connection reset by peer", "Broken pipe", "EOF"})
	@DisplayName("커밋 전에 저장소 읽기가 실패하면 — 끊김처럼 보이는 예외여도 — 500 C005 봉투이고 ERROR 를 남기며 스트림을 닫는다")
	void 읽기_실패는_500_C005_와_ERROR(String 실패) throws Exception {
		IOException 읽기_예외 = "EOF".equals(실패) ? new EOFException() : new IOException(실패);
		CloseRecordingStream stream = new CloseRecordingStream(PNG, 4, 읽기_예외, false);
		MockMvc 필터_없음 = MockMvcBuilders.standaloneSetup(new AttachmentController(서비스_대역(stream)))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
		AtomicReference<MockHttpServletResponse> 응답 = new AtomicReference<>();

		List<ILoggingEvent> 로그 = 루트_로그(() -> 응답.set(필터_없음.perform(get(DOWNLOAD_PATH, 7L))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C005"))
				.andReturn().getResponse()));

		assertThat(응답.get().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(응답.get().getHeader(HttpHeaders.CONTENT_DISPOSITION)).as("써 둔 서빙 헤더를 버렸다").isNull();
		assertThat(stream.closed).as("스트림은 닫힌다").isTrue();
		assertThat(로그).extracting(ILoggingEvent::getLevel).as("서버 결함은 마지막 그물의 ERROR 다")
				.contains(Level.ERROR);
	}

	/**
	 * <b>본문을 다 보낸 뒤 닫기만 실패하면 응답은 그대로 200 이고 WARN 한 줄이다</b> — 바이트는 이미 다
	 * 나갔다. 닫기를 흘려보내기의 판정에 섞으면 커밋 전의 작은 파일이 reset 되어 500 이 된다(2026-11-19 B-24
	 * 3라운드 리뷰 실측).
	 */
	@Test
	@DisplayName("본문을 다 보낸 뒤 저장소 스트림 닫기만 실패하면 200 과 온전한 본문이고 ERROR 없이 WARN 한 줄이다")
	void 닫기_실패는_응답을_바꾸지_않는다() throws Exception {
		CloseRecordingStream stream = new CloseRecordingStream(PNG, Integer.MAX_VALUE, null, true);
		MockMvc 필터_없음 = MockMvcBuilders.standaloneSetup(new AttachmentController(서비스_대역(stream)))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
		AtomicReference<MockHttpServletResponse> 응답 = new AtomicReference<>();

		List<ILoggingEvent> 로그 = 루트_로그(() -> 응답.set(필터_없음.perform(get(DOWNLOAD_PATH, 7L))
				.andExpect(status().isOk())
				.andReturn().getResponse()));

		assertThat(응답.get().getContentAsByteArray()).isEqualTo(PNG);
		assertThat(stream.closed).as("닫기를 시도했다").isTrue();
		assertThat(로그).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR);
		assertThat(로그).filteredOn(e -> e.getLevel() == Level.WARN).as("닫기 실패는 컨트롤러의 WARN 한 줄이다")
				.singleElement()
				.satisfies(e -> assertThat(e.getLoggerName()).isEqualTo(AttachmentController.class.getName()));
	}

	private static AttachmentService 서비스_대역(InputStream stream) {
		AttachmentService service = Mockito.mock(AttachmentService.class);
		Mockito.when(service.open(7L)).thenReturn(new AttachmentContent("image/png", "a.png", PNG.length, stream));
		return service;
	}

	/** 작업을 돌리는 동안 루트 로거에 닿은 로그를 모은다 — 어느 로거에서 났든 본다. */
	private static List<ILoggingEvent> 루트_로그(ThrowingRunnable 작업) throws Exception {
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			작업.run();
			return List.copyOf(appender.list);
		}
		finally {
			root.detachAppender(appender);
			appender.stop();
		}
	}

	/**
	 * 닫혔는지 기록하는 스트림. 앞의 {@code failAfter} 바이트를 준 뒤 읽기에서 {@code readFailure} 를 내고,
	 * {@code closeFails} 면 닫기에서 {@link IOException} 을 낸다(닫기를 시도한 것은 기록한다).
	 */
	private static final class CloseRecordingStream extends InputStream {

		private final byte[] content;

		private final int failAfter;

		private final IOException readFailure;

		private final boolean closeFails;

		private int pos;

		private volatile boolean closed;

		CloseRecordingStream(byte[] content, int failAfter) {
			this(content, failAfter, new IOException("저장소 읽기 실패 (대역)"), false);
		}

		CloseRecordingStream(byte[] content, int failAfter, IOException readFailure, boolean closeFails) {
			this.content = content;
			this.failAfter = failAfter;
			this.readFailure = readFailure;
			this.closeFails = closeFails;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (this.pos >= this.failAfter) {
				throw this.readFailure;
			}
			if (this.pos >= this.content.length) {
				return -1;
			}
			int n = Math.min(len, Math.min(this.content.length, this.failAfter) - this.pos);
			System.arraycopy(this.content, this.pos, b, off, n);
			this.pos += n;
			return n;
		}

		@Override
		public void close() throws IOException {
			this.closed = true;
			if (this.closeFails) {
				throw new IOException("저장소 스트림 닫기 실패 (대역)");
			}
		}

	}

	/**
	 * B-24 검증 표 <b>「삭제된 첨부 / 지워진 글의 첨부 / 없는 id → 404 {@code AT001} 봉투」</b>
	 * (sp-docs/domain-model.md §4.6 — 첨부와 글이 둘 다 활성일 때만 파일을 준다).
	 *
	 * <p>앞의 둘은 저장소에 파일이 <b>있는</b> 행이다 — 404 가 파일이 없어서 난 것이 아니다.
	 */
	@Test
	@DisplayName("지운 첨부, 지워진 글의 첨부, 없는 id 의 내려받기는 모두 404 AT001 봉투다")
	void 내려받을_수_없는_첨부는_AT001() throws Exception {
		Post post = 게시글을_만든다(1476L);
		long 지운_첨부 = 첨부_행을_둔다(post, "a.png", true, true);
		Post 지워진_글 = 게시글을_만든다(1476L);
		long 지워진_글의_첨부 = 첨부_행을_둔다(지워진_글, "b.png", false, true);
		지운다(지워진_글);

		for (long id : new long[] {지운_첨부, 지워진_글의_첨부, 없는_첨부}) {
			내려받기는_AT001_봉투다(id);
		}
		assertThat(저장된_파일()).as("파일은 남아 있다").hasSize(2);
	}

	/** B-24 검증 표 <b>「행은 있는데 파일이 없다 → 404 {@code AT001}, WARN」</b> (sp-docs/api-contract.md §4.7). */
	@Test
	@DisplayName("행은 있는데 저장소에 파일이 없으면 404 AT001 이고 WARN 을 남긴다")
	void 파일이_없는_첨부는_AT001_과_WARN() throws Exception {
		Post post = 게시글을_만든다(1477L);
		long id = 첨부_행을_둔다(post, "a.png", false, false);

		List<ILoggingEvent> 로그 = 서비스_로그(() -> 내려받기는_AT001_봉투다(id));

		assertThat(로그).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
	}

	/**
	 * B-24 검증 표 <b>「따옴표·줄바꿈·한글이 든 원본 파일명 → {@code filename*=UTF-8''…} 로만 실리고 헤더가
	 * 깨지지 않는다」</b> (sp-docs/security.md §8).
	 *
	 * <p>올리기는 제어 문자를 버리므로 줄바꿈이 든 파일명은 행으로 직접 둔다 — 서빙이 올리기의 정리에
	 * 기대지 않는지 본다. 기대값은 UTF-8 바이트의 퍼센트 인코딩을 리터럴로 적었다 — 「사」 EC 82 AC,
	 * 「진」 EC A7 84, 공백 20, 따옴표 22, CR 0D, LF 0A, 쌍점 3A, 세미콜론 3B, 퍼센트 25, 작은따옴표 27,
	 * 별표 2A. 마지막 셋은 RFC 5987 의 {@code attr-char} 가 아니다 — {@code %} 는 인코딩의 시작이고
	 * {@code '}·{@code *} 는 {@code filename*=UTF-8''} 의 구분 기호다.
	 */
	@Test
	@DisplayName("따옴표·줄바꿈·한글·%'* 가 든 파일명은 filename*=UTF-8'' 퍼센트 인코딩으로만 실린다")
	void 파일명은_퍼센트_인코딩으로만_싣는다() throws Exception {
		Post post = 게시글을_만든다(1478L);
		long id = 첨부_행을_둔다(post, "사진 \"a\";\r\nX-Evil: 1%'*.png", false, true);

		MockHttpServletResponse response = this.mockMvc.perform(get(DOWNLOAD_PATH, id))
				.andExpect(status().isOk())
				.andReturn().getResponse();

		assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION)).containsExactly(
				"inline; filename*=UTF-8''%EC%82%AC%EC%A7%84%20%22a%22%3B%0D%0AX-Evil%3A%201%25%27%2A.png");
		assertThat(response.getHeaderNames()).doesNotContain("X-Evil");
	}

	// --- 지우기 (B-24, sp-docs/api-contract.md §4.7, sp-docs/domain-model.md §6.2) ---

	/**
	 * B-24 검증 표 <b>「지우기 → 204, {@code attachment_count} −1, 파일이 남는다, 내려받기 404
	 * {@code AT001}」</b>와 <b>「프로필 없는 계정이 자기 글의 첨부를 지우기 → 204, {@code MemberClient}
	 * 호출 0회」</b> — 이 클래스의 member 스텁은 언제나 프로필 미등록이라고 답한다.
	 */
	@Test
	@DisplayName("자기 글의 첨부를 지우면 204, 집계 -1, 파일은 남고 내려받기는 404 AT001, member 호출 0회")
	void 지우기는_204_이고_파일은_남는다() throws Exception {
		Post post = 게시글을_만든다(1479L);
		long 지울_첨부 = idOf(올린다(post.getId(), 1479L, USER, 파일("a.png", "image/png", PNG))
				.andExpect(status().isCreated()).andReturn());
		올린다(post.getId(), 1479L, USER, 파일("b.png", "image/png", PNG)).andExpect(status().isCreated());
		REQUEST_COUNT.set(0);

		지운다(지울_첨부, 1479L, USER)
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(활성_첨부_수(post)).isEqualTo(1);
		assertThat(저장된_파일()).as("파일은 지우지 않는다 (sp-docs/requirements/board.md §11)").hasSize(2);
		assertThat(REQUEST_COUNT.get()).as("지우기는 member 를 부르지 않는다").isZero();
		내려받기는_AT001_봉투다(지울_첨부);
	}

	/**
	 * <b>지워진 글의 활성 첨부를 작성자가 지우면 204 이고 집계가 준다.</b> 지우기의 판정은 없는 첨부(404
	 * {@code AT001}) → 작성자인가(403 {@code P002}) 뿐이고 글의 삭제 여부를 보지 않는다
	 * (sp-docs/requirements/board.md §11 의 판정 순서). 글이 지워졌다고 {@code AT001} 로 막는 변이를 고정한다.
	 *
	 * <p><b>정본이 정하지 않은 경우를 지금 정본대로 고정한 것이다 — 정본이 정해지면 다시 볼 자리다.</b>
	 * sp-docs/plan/phase2-board.md §4.3 「정본 개정으로 넘기는 것」 이 이 질문을 열어 두었다: 「지워진 글의
	 * 활성 첨부를 지울 때의 결과. 404 {@code AT001} 로 정한 것은 없는·이미 지운 첨부뿐이고 (…)
	 * sp-docs/domain-model.md §6.2 의 삭제 흐름은 글이 지워졌는지 보지 않는다. B-24 의 검증표는 이 경우를
	 * 단언하지 않는다」. 지금의 §6.2·§11 판정 순서를 따르면 204 이다.
	 */
	@Test
	@DisplayName("지워진 글의 활성 첨부를 작성자가 지우면 204 이고 집계가 1 준다")
	void 지워진_글의_활성_첨부도_지운다() throws Exception {
		Post post = 게시글을_만든다(1489L);
		long id = 첨부_행을_둔다(post, "a.png", false, true);
		집계를_맞춘다(post, 1);
		지운다(post);

		지운다(id, 1489L, USER).andExpect(status().isNoContent());

		assertThat(커밋된_집계(post)).isZero();
		assertThat(활성_첨부_수(post)).isZero();
		assertThat(저장된_파일()).as("파일은 지우지 않는다").hasSize(1);
	}

	/** B-24 검증 표 <b>「상한이던 글에서 하나를 지운 뒤 올리기 → 201」</b> — 지우기가 집계를 줄였다. */
	@Test
	@DisplayName("활성 첨부가 10개인 글에서 하나를 지우면 다시 올릴 수 있다")
	void 상한에서_하나_지우면_올린다() throws Exception {
		Post post = 게시글을_만든다(1480L);
		첨부를_둔다(post, 상한);
		long 활성_하나 = this.jdbcTemplate.queryForObject(
				"SELECT MIN(id) FROM attachment WHERE post_id = ? AND deleted = FALSE", Long.class, post.getId());

		지운다(활성_하나, 1480L, USER).andExpect(status().isNoContent());
		올린다(post.getId(), 1480L, USER, 파일("a.png", "image/png", PNG)).andExpect(status().isCreated());

		assertThat(커밋된_집계(post)).isEqualTo(상한);
		assertThat(활성_첨부_수(post)).isEqualTo(상한);
	}

	/**
	 * B-24 검증 표 <b>「집계가 0 인데 활성 첨부를 지우기 → 204, 집계 0, WARN」</b> (sp-docs/domain-model.md
	 * §6.2 — 어긋난 상태는 WARN 으로 드러내고 집계를 음수로 내리지 않는다).
	 */
	@Test
	@DisplayName("집계가 0 인 글의 활성 첨부를 지우면 204 이고 집계는 0 그대로이며 WARN 을 남긴다")
	void 집계가_0_이면_204_와_WARN() throws Exception {
		Post post = 게시글을_만든다(1481L);
		long id = 첨부_행을_둔다(post, "a.png", false, false);

		List<ILoggingEvent> 로그 = 서비스_로그(() -> 지운다(id, 1481L, USER).andExpect(status().isNoContent()));

		assertThat(로그).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
		assertThat(커밋된_집계(post)).isZero();
		assertThat(활성_첨부_수(post)).isZero();
	}

	/** B-24 검증 표 <b>「남의 글의 첨부 (USER·ADMIN) → 403 {@code P002}」</b>. 아무것도 바뀌지 않는다. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {USER, ADMIN})
	@DisplayName("남의 글의 첨부를 지우면 USER 도 ADMIN 도 403 P002 이고 첨부와 집계가 그대로다")
	void 남의_글의_첨부는_P002(String role) throws Exception {
		Post post = 게시글을_만든다(1482L);
		long id = 첨부_행을_둔다(post, "a.png", false, true);
		집계를_맞춘다(post, 1);

		지운다(id, 1483L, role)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("P002"));

		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(활성_첨부_수(post)).isEqualTo(1);
	}

	/**
	 * B-24 검증 표 <b>「이미 지운 첨부를 지우기, 그 글도 지워졌다 → 404 {@code AT001} ({@code P001}
	 * 아님)」</b>, <b>「남의 글의 이미 지운 첨부를 지우기 → 404 {@code AT001} ({@code P002} 아님 —
	 * 순서)」</b>, 그리고 없는 id. 선후는 행이 남은 지운 첨부에서만 관측된다 (sp-docs/api-contract.md §4.4
	 * 의 블록인용).
	 */
	@Test
	@DisplayName("이미 지운 첨부는 글이 지워졌어도, 남의 글이어도 404 AT001 이다 — 없는 id 도 같다")
	void 이미_지운_첨부는_AT001() throws Exception {
		Post 지워진_글 = 게시글을_만든다(1484L);
		long 지워진_글의_지운_첨부 = 첨부_행을_둔다(지워진_글, "a.png", true, false);
		지운다(지워진_글);
		Post 남의_글 = 게시글을_만든다(1485L);
		long 남의_지운_첨부 = 첨부_행을_둔다(남의_글, "b.png", true, false);

		지운다(지워진_글의_지운_첨부, 1484L, USER)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("AT001"));
		지운다(남의_지운_첨부, 1486L, USER)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("AT001"));
		지운다(없는_첨부, 1486L, USER)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("AT001"));
	}

	/**
	 * B-24 검증 표 <b>「claim 이 계약 밖인 토큰으로 지우기 → 401 {@code A002}」</b> — 올리기의 같은 줄과
	 * 같은 방식이다. 서명이 유효하므로 필터 체인은 통과하고 {@code @CurrentMember} 리졸버가 끊는다.
	 */
	@Test
	@DisplayName("role 이 열거 밖인 토큰으로 지우면 리졸버가 401 A002 로 끊고 첨부가 그대로다")
	void 열거_밖_role_로_지우면_A002() throws Exception {
		Post post = 게시글을_만든다(1487L);
		long id = 첨부_행을_둔다(post, "a.png", false, false);
		집계를_맞춘다(post, 1);

		MvcResult result = 지운다(id, 1487L, "BOGUS")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A002"))
				.andReturn();

		assertThat(result.getHandler())
				.as("서명이 유효한 토큰은 필터 체인을 통과한다 — 집행 지점은 리졸버다").isNotNull();
		assertThat(result.getResolvedException()).isInstanceOf(BusinessException.class);
		assertThat(커밋된_집계(post)).isEqualTo(1);
		assertThat(활성_첨부_수(post)).isEqualTo(1);
	}

	// --- 도구 ---

	private ResultActions 지운다(long attachmentId, long accountId, String role) throws Exception {
		return this.mockMvc.perform(delete(DOWNLOAD_PATH, attachmentId)
				.header(HttpHeaders.AUTHORIZATION, bearer(accountId, role)));
	}

	/** 비로그인 내려받기가 404 {@code AT001} 봉투다 — 서빙 헤더가 실리지 않는다. */
	private void 내려받기는_AT001_봉투다(long attachmentId) throws Exception {
		MockHttpServletResponse response = this.mockMvc.perform(get(DOWNLOAD_PATH, attachmentId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("AT001"))
				.andReturn().getResponse();
		assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
	}

	/**
	 * 첨부 행 하나를 이 API 를 거치지 않고 둔다 — 집계는 건드리지 않는다. {@code withFile} 이면 그 행의
	 * 저장 이름으로 저장 루트에 PNG 바이트를 쓴다. 원본 파일명은 올리기의 정리를 거치지 않은 값 그대로다.
	 */
	private long 첨부_행을_둔다(Post post, String originalFilename, boolean deleted, boolean withFile)
			throws IOException {
		String storageKey = UUID.randomUUID().toString();
		if (withFile) {
			Files.write(storageRoot.resolve(storageKey), PNG);
		}
		this.jdbcTemplate.update("INSERT INTO attachment (post_id, storage_key, original_filename, "
						+ "content_type, size, deleted, created_at, updated_at) "
						+ "VALUES (?, ?, ?, 'image/png', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
				post.getId(), storageKey, originalFilename, PNG.length, deleted);
		return this.jdbcTemplate.queryForObject(
				"SELECT id FROM attachment WHERE storage_key = ?", Long.class, storageKey);
	}

	private void 집계를_맞춘다(Post post, int count) {
		int updated = this.jdbcTemplate.update(
				"UPDATE post SET attachment_count = ? WHERE id = ?", count, post.getId());
		if (updated != 1) {
			throw new IllegalStateException("attachment_count 픽스처가 1 행이 아니다: " + updated);
		}
	}

	/** 작업을 돌리는 동안 {@link AttachmentService} 가 남긴 로그를 모은다. */
	private static List<ILoggingEvent> 서비스_로그(ThrowingRunnable 작업) throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(AttachmentService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level original = logger.getLevel();
		// 수집 결과가 주변 설정에 좌우되지 않게 수준을 이 안에서 정한다
		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);
		try {
			작업.run();
			return List.copyOf(appender.list);
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(original);
			appender.stop();
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {

		void run() throws Exception;

	}

	private ResultActions 올린다(long postId, long accountId, String role, MockMultipartFile file)
			throws Exception {
		return this.mockMvc.perform(multipart(PATH, postId)
				.file(file)
				.header(HttpHeaders.AUTHORIZATION, bearer(accountId, role)));
	}

	/** 파트 이름 {@code file} 의 파일 (§4.7). */
	private static MockMultipartFile 파일(String filename, String contentType, byte[] content) {
		return new MockMultipartFile("file", filename, contentType, content);
	}

	/** 거부된 요청 뒤: 저장 루트에 파일이 없고, 그 글에 첨부 행이 없고, 집계가 0 이다. */
	private void 거부된_뒤_아무것도_남지_않는다(Post post) throws IOException {
		assertThat(저장된_파일()).as("거부될 요청의 파일을 쓰지 않는다 (sp-docs/domain-model.md §6.2)").isEmpty();
		assertThat(첨부_행_수(post)).isZero();
		assertThat(커밋된_집계(post)).isZero();
	}

	private Post 게시글을_만든다(long writerId) {
		return this.postRepository.saveAndFlush(Post.builder()
				.writerId(writerId)
				.writerNickname("글작성자")
				.title("첨부 대상")
				.content("본문")
				.build());
	}

	/** 다른 커넥션의 자동 커밋으로 글을 논리 삭제한다. 영향 행이 1 이 아니면 그 자리에서 터진다. */
	private void 지운다(Post post) {
		int updated = this.jdbcTemplate.update("UPDATE post SET deleted = TRUE WHERE id = ?", post.getId());
		if (updated != 1) {
			throw new IllegalStateException("글 삭제 픽스처가 1 행이 아니다: " + updated);
		}
	}

	/**
	 * 활성 첨부 행을 {@code count} 개 두고 집계를 그 수만큼 올린다 — 근거 행과 집계가 같은 상태다. 이
	 * API 를 거치지 않고, 파일은 두지 않는다(저장 루트가 빈 채로 시작한다). 지운 첨부를 하나 더 두어
	 * 상한이 활성 행만 센다는 것도 깔아 둔다.
	 */
	private void 첨부를_둔다(Post post, int count) {
		for (int i = 0; i <= count; i++) {
			boolean deleted = i == count;
			this.jdbcTemplate.update("INSERT INTO attachment (post_id, storage_key, original_filename, "
							+ "content_type, size, deleted, created_at, updated_at) "
							+ "VALUES (?, ?, ?, 'image/png', 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
					post.getId(), UUID.randomUUID().toString(), "fixture-" + i + ".png", deleted);
		}
		int updated = this.jdbcTemplate.update(
				"UPDATE post SET attachment_count = ? WHERE id = ?", count, post.getId());
		if (updated != 1) {
			throw new IllegalStateException("attachment_count 픽스처가 1 행이 아니다: " + updated);
		}
	}

	private String 저장된_열(long attachmentId, String column) {
		return this.jdbcTemplate.queryForObject(
				"SELECT " + column + " FROM attachment WHERE id = ?", String.class, attachmentId);
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

	private int 첨부_행_수(Post post) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM attachment WHERE post_id = ?", Integer.class, post.getId());
		return count == null ? 0 : count;
	}

	private static List<Path> 저장된_파일() throws IOException {
		try (Stream<Path> entries = Files.list(storageRoot)) {
			return entries.toList();
		}
	}

	private static long idOf(MvcResult result) throws Exception {
		return ((Number) JsonPath.read(bodyOf(result), "$.data.id")).longValue();
	}

	private static String bearer(long accountId, String role) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, role);
	}

	private static String bodyOf(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static byte[] bytes(int... values) {
		byte[] result = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = (byte) values[i];
		}
		return result;
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
