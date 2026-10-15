package com.example.board.global.error;

import com.example.board.global.common.ApiResponse;
import com.example.board.global.common.ErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 정본: sp-docs/api-contract.md §7 §8, sp-docs/conventions.md §6 §6.1.
 *
 * <p>서비스별 {@link ErrorCode} 에 의존하므로 복제하지 않는다
 * (sp-docs/conventions.md §7.2). 다루는 예외 집합·코드 매핑·로그 수준·응답 본문은 §6.1 의 표가
 * 고정하고, 세 서비스의 일치는 sp-docs/plan/integration.md I-04 가 3자 비교한다.
 *
 * <p><b>{@code ResponseEntityExceptionHandler} 를 상속하지 않는다.</b> 상속하면 응답 본문이
 * Spring 의 {@code ProblemDetail} 모양이 되어 §7 의 {@code ApiResponse} 와 달라진다. 대신
 * 다룰 예외를 전부 명시한다 — 빠뜨린 것은 마지막 그물({@code Exception})로 떨어져 500 이 되고
 * {@code log.error} 를 남긴다 (sp-docs/conventions.md §6.1).
 *
 * <p><b>로그 수준을 가른다.</b> 클라이언트 오류는 {@code warn} 이고 마지막 그물만
 * {@code error} 다. 흔한 클라이언트 실수가 ERROR 경보를 만들면 원인이 서버에 있는 500 과
 * 구별되지 않는다 (§6.1).
 *
 * <p>응답 본문에 스택트레이스·SQL·내부 호스트명을 넣지 않는다 (§8). 그래서 예외 메시지를
 * 그대로 내보내지 않고 {@code ErrorCode} 의 고정 메시지만 쓴다.
 *
 * <p>{@code A001}·{@code A002}·{@code A003} 은 여기 없다. 인증 실패는 이 클래스에 닿기 전에
 * Security 필터 체인이 처리하므로 {@code AuthenticationEntryPoint}·{@code AccessDeniedHandler}
 * 가 낸다 — B-04 의 몫이다 (§6.1, sp-docs/api-contract.md §8.5).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		log.warn("business exception: {}", errorCode.getCode());
		return toResponse(errorCode);
	}

	/** {@code @Valid} 검증 실패. 필드별 메시지를 {@code fieldErrors} 에 담는다. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
		// rejectedValue 는 담지 않는다. 사용자가 보낸 원문이 그대로 응답에 실린다
		// (sp-docs/api-contract.md §7).
		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new ErrorResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
				.toList();
		log.warn("validation failed: {} field(s)", fieldErrors.size());
		return toResponse(ErrorCode.INVALID_INPUT_VALUE, fieldErrors);
	}

	/**
	 * 메서드 파라미터 검증 실패 — {@code @RequestParam}·{@code @PathVariable} 에 걸린 제약이
	 * 깨졌다.
	 *
	 * <p><b>클라이언트 오류이므로 400 이다</b> (sp-docs/conventions.md §6.1). 본문
	 * 검증({@link MethodArgumentNotValidException})과 같은 형태로 {@code fieldErrors} 를
	 * 채운다 — 바인딩이 일어난 뒤의 오류이므로 담을 필드가 있다 (§6.1).
	 */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
		// 여기도 rejectedValue 를 담지 않는다 (sp-docs/api-contract.md §7)
		List<ErrorResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new ErrorResponse.FieldError(
								result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
				.toList();
		log.warn("parameter validation failed: {} field(s)", fieldErrors.size());
		return toResponse(ErrorCode.INVALID_INPUT_VALUE, fieldErrors);
	}

	/**
	 * 필수 쿼리 파라미터가 빠졌다.
	 *
	 * <p><b>{@code fieldErrors} 는 빈 배열이다.</b> 바인딩 자체가 일어나지 않아 담을 필드가
	 * 없다 (sp-docs/conventions.md §6.1, sp-docs/api-contract.md §2.3).
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(
			MissingServletRequestParameterException e) {
		log.warn("missing request parameter: {}", e.getParameterName());
		return toResponse(ErrorCode.INVALID_INPUT_VALUE);
	}

	/**
	 * 본문을 읽을 수 없을 때 — 깨진 JSON, 빈 본문, 타입이 맞지 않는 필드.
	 *
	 * <p><b>클라이언트 오류이므로 400 이고 {@code fieldErrors} 는 빈 배열이다</b> — 위와 같이
	 * 바인딩이 일어나지 않았다 (sp-docs/conventions.md §6.1). 이 핸들러가 없으면 마지막 그물에
	 * 걸려 500 {@code C005} 가 되고, 클라이언트가 보낸 잘못된 본문이 서버 장애로 보고된다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
		// 예외 메시지에 본문 일부가 들어 있으므로 로그에도 남기지 않는다
		log.warn("request body not readable");
		return toResponse(ErrorCode.INVALID_INPUT_VALUE);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
		log.warn("type mismatch on parameter: {}", e.getName());
		return toResponse(ErrorCode.INVALID_TYPE_VALUE);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupported(
			HttpRequestMethodNotSupportedException e) {
		log.warn("method not allowed: {}", e.getMethod());
		return toResponse(ErrorCode.METHOD_NOT_ALLOWED);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
		log.warn("no handler for: {}", e.getResourcePath());
		return toResponse(ErrorCode.RESOURCE_NOT_FOUND);
	}

	/**
	 * 요청 본문의 미디어 타입을 읽을 수 없다 — {@code Content-Type} 이 없거나 JSON 이 아니다.
	 *
	 * <p><b>클라이언트 오류이므로 415 다</b> (sp-docs/api-contract.md §8.1). 이 핸들러가 없으면
	 * 마지막 그물에 걸려 <b>500 {@code C005}</b> 가 된다 (정본이 기록한 실측). 상태 코드보다
	 * 로그가 더 문제다 — 마지막 그물은 {@code log.error} 로 스택트레이스를 남기므로 흔한
	 * 클라이언트 실수가 ERROR 경보를 만들고, 원인이 서버에 있는 500 과 구별되지 않는다.
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
		log.warn("unsupported media type: {}", e.getContentType());
		return toResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
	}

	/**
	 * {@code Accept} 로 JSON 을 받을 수 없다.
	 *
	 * <p><b>클라이언트 오류이므로 406 이다</b> (sp-docs/api-contract.md §8.1). 위와 같은 이유로
	 * {@code log.error} 를 쓰지 않는다.
	 *
	 * <p><b>응답의 {@code Content-Type} 을 JSON 으로 못박는다.</b> 그러지 않으면 이 응답 자체가
	 * 같은 협상에 걸려 본문 없이 나간다 — 정본이 기록한 실측이 그랬다(406, 빈 본문).
	 * {@code Content-Type} 이 정해져 있으면 Spring 이 협상을 건너뛰므로 공통 형식
	 * (sp-docs/api-contract.md §7)으로 코드를 알려 줄 수 있다 (§6.1).
	 */
	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
		log.warn("not acceptable: supported {}", e.getSupportedMediaTypes());
		ErrorCode errorCode = ErrorCode.NOT_ACCEPTABLE;
		ErrorResponse error = new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), List.of());
		return ResponseEntity.status(errorCode.getStatus())
				.contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.failure(error));
	}

	/** 마지막 그물. 여기까지 온 것은 예상하지 못한 예외이므로 서버 로그에만 원인을 남긴다. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("unhandled exception", e);
		return toResponse(ErrorCode.INTERNAL_SERVER_ERROR);
	}

	private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
		return toResponse(errorCode, List.of());
	}

	private ResponseEntity<ApiResponse<Void>> toResponse(
			ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
		ErrorResponse error = new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), fieldErrors);
		return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.failure(error));
	}

}
