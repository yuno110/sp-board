package com.example.board.attachment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.global.error.BusinessException;
import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import com.example.board.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * sp-docs/plan/phase2-board.md B-23 검증 표 중 HTTP 쪽이다 — 판정 순서·상태 코드·응답 본문·저장된
 * 행과 파일·인증·member 호출 0회. 동시 올리기·INSERT 실패·판정과 UPDATE 사이에 지워진 글은
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

	// --- 도구 ---

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
