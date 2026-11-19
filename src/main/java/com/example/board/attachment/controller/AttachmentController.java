package com.example.board.attachment.controller;

import com.example.board.attachment.dto.AttachmentResponse;
import com.example.board.attachment.service.AttachmentContent;
import com.example.board.attachment.service.AttachmentService;
import com.example.board.global.common.ApiResponse;
import com.example.board.global.security.CurrentMember;
import com.example.board.global.security.LoginMember;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * 첨부 API. 정본: sp-docs/api-contract.md §4.7 (요청·성공·실패) §9.9 (예시).
 *
 * <p>경로별 인가는 {@code SecurityConfig} 가 선언한다 (B-16) — 올리기·지우기는 인증이고 내려받기는
 * 비로그인에 열려 있다.
 *
 * <p>{@code @Transactional} 을 컨트롤러에 붙이지 않는다 (sp-docs/conventions.md §5).
 */
@RestController
@RequiredArgsConstructor
public class AttachmentController {

	private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

	/** {@code Content-Disposition} 의 퍼센트 인코딩에 쓰는 16진수 숫자 — 대문자다(RFC 3986 §2.1 의 권고). */
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private final AttachmentService attachmentService;

	/**
	 * 첨부 올리기 (P-10). <b>201</b> 이고 응답은 첨부 하나의 모양이다 (sp-docs/api-contract.md §4.7).
	 *
	 * <p><b>{@code multipart/form-data} 만 받는다</b>({@code consumes}). 아니면 핸들러를 고르는
	 * 단계에서 415 {@code C006} 이다 (§4.7 의 실패 표). 파트 이름은 {@code file} 이고, 없으면 400
	 * {@code C001} 이다. 크기 한도(413 {@code C008})와 함께 이 메서드 본문에 닿기 전에 나는 예외이고
	 * {@code GlobalExceptionHandler} 가 코드로 바꾼다 (sp-docs/conventions.md §6.1).
	 *
	 * <p><b>{@code @CurrentMember} 를 {@code file} 앞에 둔다.</b> 인자는 선언 순서로 해석되므로, claim
	 * 이 계약 밖인 토큰이 파트가 없는 요청을 보내도 401 {@code A002} 가 400 {@code C001} 보다 먼저다 —
	 * sp-docs/requirements/board.md §11 의 판정 순서가 인증을 multipart 앞에 둔다.
	 *
	 * <p><b>그 순서가 서지 않는 경우가 있다 — claim 이 계약 밖인 토큰이다.</b> 그 토큰은 필터를 지나고
	 * {@code A002} 는 이 인자를 해석할 때 나므로, 그보다 앞 단계의 판정이 먼저 난다. multipart 가 아닌
	 * 본문은 401 {@code A002} 가 아니라 415 {@code C006} 이다(2026-11-18 B-23 리뷰 실측, OSIV 켬·끔 둘
	 * 다). 크기 한도를 넘는 본문도 413 {@code C008} 이 먼저 날 것으로 본다(추정 — 재지 않았다, 통합 측정
	 * 대상). 토큰이 없는 401 {@code A001} 은 필터에서 나므로 언제나 먼저다.
	 */
	@PostMapping(path = "/api/v1/posts/{postId}/attachments",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<AttachmentResponse> upload(
			@PathVariable Long postId,
			@CurrentMember LoginMember loginMember,
			@RequestPart("file") MultipartFile file) {

		return ApiResponse.success(
				this.attachmentService.upload(postId, loginMember.accountId(), file));
	}

	/**
	 * 첨부 내려받기 (P-10). <b>비로그인에 열려 있다</b> — {@code SecurityConfig} 가 이 경로의 GET 을
	 * {@code permitAll} 로 둔다 (sp-docs/requirements/board.md §11 의 「내려받기」 줄).
	 *
	 * <p><b>성공은 봉투가 아니라 파일의 바이트다</b> (sp-docs/api-contract.md §4.7). 파일을 메모리에
	 * 올리지 않고 저장소의 스트림을 응답에 그대로 흘려보낸다. <b>실패는 봉투다</b> — 404 {@code AT001} 은
	 * 서비스가 스트림을 열기 전에 던지므로, 헤더도 본문도 쓰기 전이라 {@code GlobalExceptionHandler} 가
	 * 봉투로 답한다.
	 *
	 * <p><b>본문을 흘려보내는 도중의 실패는 둘로 가른다</b> (2026-11-19 B-24 리뷰의 실 Tomcat 실측).
	 * <ul>
	 * <li><b>클라이언트가 끊었다</b> — <b>응답에 쓸 때</b> 난 예외만 이것으로 본다(실 Tomcat 에서
	 * {@code AsyncRequestNotUsableException}, 원인 {@code ClientAbortException}). 판정은 Spring 의
	 * {@link DisconnectedClientHelper#isClientDisconnectedException} 이고 {@link #copy} 가 쓰기의 예외에만
	 * 건다 — 그 판정은 원인 사슬의 클래스 이름({@code EOFException} 등)과 메시지("Connection reset by
	 * peer"·"Broken pipe")만 보므로, 저장소 <b>읽기</b>의 같은 모양 예외까지 넘기면 서버 결함을 끊김으로
	 * 오판한다(2026-11-19 B-24 3라운드 리뷰 실측 — 200 과 잘린 본문, ERROR 없음). 받을 쪽이 없으므로 WARN
	 * 한 줄로 끝낸다 — 마지막 그물로 보내면 클라이언트 쪽 사정이 ERROR 경보가 된다 (sp-docs/conventions.md §6.1)
	 * <li><b>그 밖의 실패</b>(저장소 읽기 실패 등)는 서버 결함이다. 감추지 않고 다시 던져 마지막 그물의
	 * ERROR 를 남긴다. 응답이 아직 커밋되지 않았으면 먼저 {@code reset()} 으로 써 둔 헤더·본문을 버린다 —
	 * 그래야 500 {@code C005} 봉투로 답할 수 있다(버리지 않으면 이미 정한 {@code image/*} 형식에 봉투를
	 * 쓰지 못한다). {@code reset()} 은 CORS 헤더({@code Access-Control-Allow-Origin}·{@code Vary})도 지운다 —
	 * 교차 출처 {@code fetch} 는 그 500 봉투를 읽지 못한다(같은 리뷰 실측). 갤러리는 {@code <img>} 로 그리므로
	 * (sp-docs/security.md §8) 그대로 둔다. 커밋 뒤면 상태를 바꿀 수 없고, 다시 던지면 컨테이너가 연결을 닫아
	 * 클라이언트가 잘린 본문을 {@code Content-Length} 보다 짧은 EOF 로 알아챈다 — 삼키면 연결이 열린 채
	 * 클라이언트가 나머지를 기다린다(리뷰 실측)
	 * </ul>
	 * 스트림은 어느 경우에도 닫힌다. <b>닫기 실패는 판정 밖이다</b> — 흘려보내기가 끝난 뒤 닫다가 실패해도
	 * 본문은 이미 다 나갔으므로 응답을 바꾸지 않고 WARN 한 줄만 남긴다({@link #closeQuietly}).
	 *
	 * <p>헤더는 sp-docs/security.md §8 의 서빙 응답 헤더 표 그대로다. {@code Content-Length} 는 행의
	 * {@code size} 다 — 올리기가 쓴 바이트 수이고, 같은 id 의 파일은 바뀌지 않는다 (§8 의
	 * {@code Cache-Control} 문단).
	 *
	 * <p><b>응답 객체에 직접 쓴다.</b> {@code ResponseEntity<Resource>} 로 돌려주면 Spring 의 메시지
	 * 변환이 리소스 응답에 {@code Range} 처리를 붙여 {@code Accept-Ranges} 를 싣고 {@code Range} 요청에
	 * 206 으로 답하려 한다 — 그 처리는 스트림의 길이를 알려고 스트림을 끝까지 읽는다. 표에 없는
	 * 동작이므로 들이지 않는다.
	 */
	@GetMapping("/api/v1/attachments/{id}")
	public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
		// 서비스의 예외(404 AT001 등)는 여기서 잡지 않는다 — 응답을 쓰기 전이라 봉투로 나간다
		AttachmentContent attachment = this.attachmentService.open(id);
		InputStream content = attachment.content();
		try {
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(attachment.contentType());
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION, inline(attachment.originalFilename()));
			response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
			response.setContentLengthLong(attachment.size());
			copy(content, response.getOutputStream(), id);
		}
		catch (IOException | RuntimeException ex) {
			// 끊김은 copy 가 이미 걸렀다 — 여기 오는 것은 서버 결함이다
			if (!response.isCommitted()) {
				response.reset();
			}
			throw ex;
		}
		finally {
			closeQuietly(content, id);
		}
	}

	/**
	 * 저장소 스트림을 응답에 흘려보낸다. <b>읽기의 예외는 그대로 던지고, 쓰기의 예외만 끊김인지 본다</b> —
	 * 끊김이면 WARN 한 줄을 남기고 멈춘다. 판정을 쓰기에만 거는 이유는 {@link #download} 의 주석에 있다.
	 */
	private static void copy(InputStream in, OutputStream out, Long id) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			try {
				out.write(buffer, 0, read);
			}
			catch (IOException | RuntimeException ex) {
				if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
					log.warn("첨부를 내려받던 클라이언트가 연결을 끊었다. attachmentId={}, cause={}", id, ex.toString());
					return;
				}
				throw ex;
			}
		}
	}

	/** 저장소 스트림을 닫는다. 실패해도 응답을 바꾸지 않는다 — WARN 한 줄이다({@link #download} 의 주석). */
	private static void closeQuietly(InputStream content, Long id) {
		try {
			content.close();
		}
		catch (IOException ex) {
			log.warn("첨부의 저장소 스트림을 닫지 못했다. attachmentId={}, cause={}", id, ex.toString());
		}
	}

	/**
	 * 첨부 지우기 (P-10). <b>204</b> 이고 본문이 없다 (sp-docs/api-contract.md §4.7).
	 *
	 * <p>판정 순서(404 {@code AT001} → 403 {@code P002})와 흐름은 서비스에 있다. 무토큰 401
	 * {@code A001} 은 필터에서, claim 이 계약 밖인 토큰의 401 {@code A002} 는 이 인자를 해석할 때 난다.
	 */
	@DeleteMapping("/api/v1/attachments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @CurrentMember LoginMember loginMember) {
		this.attachmentService.delete(id, loginMember.accountId());
	}

	/**
	 * {@code inline; filename*=UTF-8''<퍼센트 인코딩>} (sp-docs/security.md §8 의 표, RFC 6266·5987).
	 *
	 * <p><b>{@code filename*} 하나만 싣는다.</b> 따옴표로 감싼 {@code filename=} 을 함께 싣지 않는다 —
	 * Spring 의 {@code ContentDisposition} 은 UTF-8 파일명에 그것을 함께 만든다.
	 *
	 * <p>UTF-8 바이트 가운데 RFC 5987 의 {@code attr-char} 만 그대로 두고 나머지를 모두
	 * {@code %XX} 로 바꾼다. 그래서 헤더 값에는 {@code attr-char} 와 {@code %} 만 남는다 — 따옴표·
	 * 세미콜론·공백·줄바꿈·제어 문자가 헤더로 새지 않는다. 저장된 파일명은 올리기에서 제어 문자를
	 * 버렸지만 그 정리에 기대지 않는다.
	 */
	private static String inline(String filename) {
		StringBuilder value = new StringBuilder("inline; filename*=UTF-8''");
		for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if (isAttrChar(c)) {
				value.append((char) c);
			}
			else {
				value.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
			}
		}
		return value.toString();
	}

	/** RFC 5987 §3.2.1 의 {@code attr-char} — ALPHA·DIGIT 와 {@code !#$&+-.^_`|~}. */
	private static boolean isAttrChar(int c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
				|| "!#$&+-.^_`|~".indexOf(c) >= 0;
	}

}
