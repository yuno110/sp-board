package com.example.board.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.board.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * sp-docs/plan/phase1.md §6 B-02 의 "검증" 표와 sp-docs/conventions.md §6.1 의 예외-코드 표를
 * 테스트로 옮긴 것이다. 기대값(코드·HTTP·메시지)은 운영 상수를 가져오지 않고 정본에서 읽은
 * 리터럴로 적는다 — {@code ErrorCode} 를 참조하면 그 값이 틀렸을 때 함께 틀린다.
 *
 * <p>B-02 에는 아직 컨트롤러가 없으므로 예외를 던질 대상을 이 테스트 안에 둔다. 중첩 클래스는
 * 컴포넌트 스캔 대상이 아니라 {@code @Import} 로 등록한다. {@code GlobalExceptionHandler} 는
 * {@code @WebMvcTest} 슬라이스가 {@code @RestControllerAdvice} 를 포함하므로 그대로 붙는다.
 *
 * <p>{@code addFilters = false} — SecurityConfig 는 B-04 의 산출물이므로 지금은 Boot 의 기본
 * 필터 체인이 모든 요청에 인증을 요구한다. 여기서 검증하는 것은 예외 변환이고 경로별 인가는
 * B-04 가 본다 (sp-docs/security.md §5.1.1).
 *
 * <p><b>{@code controllers} 로 슬라이스를 이 테스트의 컨트롤러 하나로 좁힌다</b>
 * (sp-docs/conventions.md §9.1). 인자 없는 {@code @WebMvcTest} 는 업무 컨트롤러를 올리면서
 * 그 의존(서비스)은 올리지 않으므로, 기능 단계가 컨트롤러를 추가할 때 이 컨텍스트가 기동에
 * 실패한다.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ExceptionTestController.class)
@Import(GlobalExceptionHandlerTest.ExceptionTestController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("BusinessException(P001) 은 404 와 P001 로 변환된다")
	void 업무_예외_P001() throws Exception {
		mockMvc.perform(get("/test/error/post-not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("P001"))
				.andExpect(jsonPath("$.error.message").value("게시글을 찾을 수 없습니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	@Test
	@DisplayName("BusinessException(P002) 은 403 과 P002 로 변환된다")
	void 업무_예외_P002() throws Exception {
		mockMvc.perform(get("/test/error/post-forbidden"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("P002"))
				.andExpect(jsonPath("$.error.message").value("게시글에 대한 권한이 없습니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	@Test
	@DisplayName("BusinessException(CM001) 은 404 와 CM001 로 변환된다")
	void 업무_예외_CM001() throws Exception {
		mockMvc.perform(get("/test/error/comment-not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CM001"))
				.andExpect(jsonPath("$.error.message").value("댓글을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("BusinessException(CM002) 은 403 과 CM002 로 변환된다")
	void 업무_예외_CM002() throws Exception {
		mockMvc.perform(get("/test/error/comment-forbidden"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("CM002"))
				.andExpect(jsonPath("$.error.message").value("댓글에 대한 권한이 없습니다."));
	}

	@Test
	@DisplayName("@Valid 검증 실패는 400 C001 이고 fieldErrors 에 필드별 메시지가 담긴다")
	void 검증_실패는_C001() throws Exception {
		mockMvc.perform(post("/test/valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\": \"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.message").value("잘못된 입력값입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(greaterThan(0))))
				.andExpect(jsonPath("$.error.fieldErrors[*].field").exists())
				.andExpect(jsonPath("$.error.fieldErrors[*].message").exists());
	}

	@Test
	@DisplayName("검증 실패 응답에 입력값이 되돌아오지 않는다")
	void 검증_실패는_입력값을_되돌려주지_않는다() throws Exception {
		MvcResult result = mockMvc.perform(post("/test/valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\": \"너무-긴-제목-원문-그대로-되돌아오면-안-된다\"}"))
				.andExpect(status().isBadRequest())
				.andReturn();

		assertThat(result.getResponse().getContentAsString())
				.as("rejectedValue 를 담으면 사용자가 보낸 원문이 응답에 실린다 (§7)")
				.doesNotContain("너무-긴-제목-원문-그대로-되돌아오면-안-된다");
	}

	/**
	 * {@code @RequestParam} 에 걸린 제약이 깨졌다 —
	 * {@code HandlerMethodValidationException}.
	 *
	 * <p>바인딩이 일어난 뒤의 오류이므로 {@code fieldErrors} 가 1건 이상이다
	 * (sp-docs/conventions.md §6.1). 아래 "필수 파라미터 누락" 과 짝을 이룬다 — 두 케이스가
	 * {@code fieldErrors} 의 빈 배열과 1건 이상을 가른다.
	 */
	@Test
	@DisplayName("파라미터 제약 위반은 400 C001 이고 fieldErrors 가 비어있지 않다")
	void 파라미터_제약_위반은_C001() throws Exception {
		mockMvc.perform(get("/test/validated-param").param("keyword", ""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.message").value("잘못된 입력값입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(greaterThan(0))))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("keyword"));
	}

	/**
	 * 필수 쿼리 파라미터가 빠졌다 — {@code MissingServletRequestParameterException}.
	 *
	 * <p><b>{@code fieldErrors} 는 빈 배열이다.</b> 바인딩 자체가 일어나지 않아 담을 필드가
	 * 없다 (sp-docs/conventions.md §6.1, sp-docs/api-contract.md §2.3).
	 */
	@Test
	@DisplayName("필수 파라미터 누락은 400 C001 이고 fieldErrors 가 빈 배열이다")
	void 필수_파라미터_누락은_C001_빈_fieldErrors() throws Exception {
		mockMvc.perform(get("/test/required-param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.message").value("잘못된 입력값입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	/**
	 * 본문을 읽을 수 없다 — {@code HttpMessageNotReadableException}.
	 *
	 * <p>이 핸들러가 없으면 마지막 그물에 걸려 <b>500 {@code C005}</b> 가 된다 — 클라이언트가
	 * 보낸 깨진 본문을 서버 장애로 보고하는 셈이다. {@code fieldErrors} 는 빈 배열이다.
	 */
	@Test
	@DisplayName("깨진 JSON 본문은 400 C001 이고 fieldErrors 가 빈 배열이며 본문이 되돌아오지 않는다")
	void 깨진_본문은_C001() throws Exception {
		MvcResult result = mockMvc.perform(post("/test/valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\": \"되돌아오면-안-되는-원문\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.message").value("잘못된 입력값입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)))
				.andReturn();

		assertThat(result.getResponse().getContentAsString())
				.as("파싱 실패 메시지에는 본문 일부가 들어 있다. 그대로 내보내면 입력 원문이 실린다")
				.doesNotContain("되돌아오면-안-되는-원문");
	}

	@Test
	@DisplayName("성공 응답은 success = true 이고 error 가 null 이다")
	void 성공_응답() throws Exception {
		mockMvc.perform(get("/test/success"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.value").value("ok"))
				.andExpect(jsonPath("$.error").isEmpty());
	}

	@Test
	@DisplayName("예상치 못한 RuntimeException 은 500 C005 이고 본문에 스택트레이스·SQL 이 없다")
	void 예상치_못한_예외는_C005() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/error/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("C005"))
				.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
				.andReturn();

		assertThat(result.getResponse().getContentAsString())
				.as("예외 메시지를 그대로 내보내면 SQL·내부 정보가 새어 나간다 (§8)")
				.doesNotContain("select", "writer_nickname", "com.example.board", "java.lang");
	}

	@Test
	@DisplayName("파라미터 타입이 맞지 않으면 400 C002 다")
	void 타입_불일치는_C002() throws Exception {
		mockMvc.perform(get("/test/type-mismatch").param("postId", "not-a-number"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C002"))
				.andExpect(jsonPath("$.error.message").value("잘못된 타입의 값입니다."));
	}

	@Test
	@DisplayName("지원하지 않는 HTTP 메서드는 405 C003 이다")
	void 지원하지_않는_메서드는_C003() throws Exception {
		mockMvc.perform(delete("/test/success"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error.code").value("C003"))
				.andExpect(jsonPath("$.error.message").value("지원하지 않는 HTTP 메서드입니다."));
	}

	@Test
	@DisplayName("매핑되지 않은 경로는 404 C004 다")
	void 없는_경로는_C004() throws Exception {
		mockMvc.perform(get("/test/no-such-path"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("C004"))
				.andExpect(jsonPath("$.error.message").value("요청한 리소스를 찾을 수 없습니다."));
	}

	/**
	 * 본문이 있는데 {@code Content-Type} 이 없다 — {@code HttpMediaTypeNotSupportedException}.
	 *
	 * <p><b>클라이언트 오류이므로 415 다</b> (sp-docs/api-contract.md §8.1). 이 핸들러가 없으면
	 * 마지막 그물에 걸려 <b>500 {@code C005}</b> 가 된다. {@code log.error} 를 남기지 않는 것은
	 * 아래 로그 수준 테스트가 본다.
	 */
	@Test
	@DisplayName("본문이 있는데 Content-Type 이 없으면 415 C006 이다")
	void content_type_없음은_C006() throws Exception {
		mockMvc.perform(post("/test/valid")
						.content("{\"title\": \"제목\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("C006"))
				.andExpect(jsonPath("$.error.message").value("지원하지 않는 미디어 타입입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	@Test
	@DisplayName("JSON 이 아닌 Content-Type 도 415 C006 이다")
	void json_이_아닌_content_type_은_C006() throws Exception {
		mockMvc.perform(post("/test/valid")
						.contentType(MediaType.TEXT_PLAIN)
						.content("{\"title\": \"제목\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("C006"))
				.andExpect(jsonPath("$.error.message").value("지원하지 않는 미디어 타입입니다."));
	}

	/**
	 * {@code Accept} 로 JSON 을 받을 수 없다 — {@code HttpMediaTypeNotAcceptableException}.
	 *
	 * <p><b>응답의 {@code Content-Type} 을 JSON 으로 못박는다.</b> 협상에 맡기면 이 핸들러의
	 * 응답 자체가 다시 실패해 본문 없이 나간다 (sp-docs/conventions.md §6.1). 그래서 응답
	 * 헤더까지 단언한다 — 상태만 보면 못박기를 지워도 통과한다.
	 */
	@Test
	@DisplayName("Accept 로 JSON 을 받을 수 없으면 406 C007 이고 본문이 JSON 으로 나간다")
	void 받을_수_없는_accept_는_C007() throws Exception {
		mockMvc.perform(get("/test/success").accept(MediaType.APPLICATION_XML))
				.andExpect(status().isNotAcceptable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C007"))
				.andExpect(jsonPath("$.error.message").value("응답할 수 있는 형식이 없습니다."));
	}

	/**
	 * <b>상태 코드보다 로그가 더 문제다</b> (sp-docs/api-contract.md §8.1). 마지막 그물은
	 * {@code log.error} 로 스택트레이스를 남긴다.
	 *
	 * <p><b>로그 수준은 양쪽 방향을 다 단언한다</b> (sp-docs/conventions.md §6.1). 클라이언트
	 * 오류가 ERROR 가 아니라는 단언만 두면 반대 방향이 열린다 — 마지막 그물을 {@code warn} 으로
	 * 낮추는 변경을 아무것도 잡지 못하고, 그러면 원인이 서버에 있는 500 이 ERROR 경보를 전혀
	 * 만들지 않는다.
	 *
	 * <p>상태 코드와 응답 본문은 위의 테스트들이 본다. 여기서는 로그 수준만 본다.
	 *
	 * <p>수집한 수준을 한 묶음으로 합치면 어느 요청이 낸 것인지 알 수 없으므로, 요청 묶음마다
	 * 따로 수집해 각 묶음에 단언을 붙인다.
	 */
	@Test
	@DisplayName("미디어 타입 예외는 ERROR 로그를 남기지 않고 마지막 그물은 ERROR 로그를 남긴다")
	void 로그_수준은_마지막_그물에서만_ERROR_다() throws Exception {
		Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level original = handlerLogger.getLevel();
		// 수집 결과가 주변 설정에 좌우되지 않게 수준을 이 테스트 안에서 정한다
		handlerLogger.setLevel(Level.TRACE);
		handlerLogger.addAppender(appender);
		List<Level> 미디어_타입_수준;
		List<Level> 마지막_그물_수준;
		try {
			mockMvc.perform(post("/test/valid").content("{}"));
			mockMvc.perform(get("/test/success").accept(MediaType.APPLICATION_XML));
			미디어_타입_수준 = 수집된_수준(appender);

			appender.list.clear();
			mockMvc.perform(get("/test/error/unexpected"));
			마지막_그물_수준 = 수집된_수준(appender);
		} finally {
			handlerLogger.detachAppender(appender);
			handlerLogger.setLevel(original);
			appender.stop();
		}

		assertThat(미디어_타입_수준)
				.as("둘 다 핸들러에 걸려야 한다. 비어 있으면 로그 수집이 안 된 것이다")
				.hasSize(2);
		assertThat(미디어_타입_수준)
				.as("클라이언트 실수가 ERROR 경보를 만들면 안 된다")
				.doesNotContain(Level.ERROR);
		assertThat(마지막_그물_수준)
				.as("마지막 그물을 warn 으로 낮추면 원인이 서버에 있는 500 이 ERROR 경보를 만들지 않는다")
				.containsExactly(Level.ERROR);
	}

	private static List<Level> 수집된_수준(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream().map(ILoggingEvent::getLevel).toList();
	}

	@RestController
	@RequestMapping("/test")
	static class ExceptionTestController {

		@GetMapping("/error/post-not-found")
		void postNotFound() {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}

		@GetMapping("/error/post-forbidden")
		void postForbidden() {
			throw new BusinessException(ErrorCode.POST_FORBIDDEN);
		}

		@GetMapping("/error/comment-not-found")
		void commentNotFound() {
			throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
		}

		@GetMapping("/error/comment-forbidden")
		void commentForbidden() {
			throw new BusinessException(ErrorCode.COMMENT_FORBIDDEN);
		}

		@GetMapping("/error/unexpected")
		void unexpected() {
			throw new IllegalStateException("select writer_nickname from post where id = 1");
		}

		@GetMapping("/type-mismatch")
		void typeMismatch(@RequestParam Long postId) {
		}

		@GetMapping("/required-param")
		void requiredParam(@RequestParam String keyword) {
		}

		@GetMapping("/validated-param")
		void validatedParam(@RequestParam @NotBlank @Size(max = 10) String keyword) {
		}

		@GetMapping("/success")
		ApiResponse<Payload> success() {
			return ApiResponse.success(new Payload("ok"));
		}

		@PostMapping("/valid")
		ApiResponse<Payload> valid(@Valid @RequestBody SampleRequest request) {
			return ApiResponse.success(new Payload("ok"));
		}

		record Payload(String value) { }

		record SampleRequest(@NotBlank @Size(max = 10) String title) { }

	}

}
