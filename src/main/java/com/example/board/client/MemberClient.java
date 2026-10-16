package com.example.board.client;

import com.example.board.client.dto.MemberBulkRequest;
import com.example.board.client.dto.MemberBulkResponse;
import com.example.board.client.dto.MemberSummaryResponse;
import com.example.board.global.common.ApiResponse;
import com.example.board.global.error.BusinessException;
import com.example.board.global.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * member-service 의 내부 API 호출. 정본: sp-docs/api-contract.md §5 §5.1.
 *
 * <p>작성자 닉네임은 member 소유 데이터여서 조인할 수 없고 JWT Claim 에도 없으므로
 * (sp-docs/api-contract.md §6) 글·댓글 <b>생성</b> 시 이 API 로 얻어 스냅샷으로 복제 저장한다
 * (sp-docs/adr/0003, sp-docs/adr/0012 §6). 조회·수정·삭제 경로에서는 호출하지 않는다 (§4.2).
 *
 * <p><b>호출은 {@code @Transactional} 밖에서 먼저 한다</b> (sp-docs/conventions.md §1.3 §5).
 * 원격 호출을 트랜잭션 안에 두면 member 의 지연이 DB 커넥션 점유로 번진다.
 *
 * <p><b>벌크 엔드포인트 하나만 호출한다</b> (§5). 1건만 필요해도 벌크에 1건을 담는다 — 실패
 * 의미론을 하나로 유지하기 위해서다.
 *
 * <h2>실패 판정 — HTTP 404 를 업무 의미로 쓰지 않는다 (§5.1)</h2>
 *
 * <table>
 * <caption>member 응답과 board 의 처리</caption>
 * <tr><th>member 응답</th><th>처리</th></tr>
 * <tr><td>200, 결과에 그 {@code accountId} 가 있고 {@code deleted = false}</td>
 *     <td>닉네임 반환</td></tr>
 * <tr><td>200, 결과에서 제외됨 · {@code deleted = true}</td>
 *     <td>403 {@code S002}. 재시도 무의미</td></tr>
 * <tr><td>그 밖의 모든 응답 — 4xx, 5xx, 타임아웃, 본문 파싱 실패</td>
 *     <td>503 {@code S001}. <b>경보 대상</b></td></tr>
 * </table>
 *
 * <p><b>404 를 "프로필 없음" 으로 해석하지 않는다.</b> 그렇게 하면 {@code MEMBER_SERVICE_URL}
 * 오설정이나 내부 API 키 거부(401·403)가 업무 오류로 위장되어, 경보도 없이 전 사용자의 쓰기가
 * 조용히 멈춘다 (§5.1, sp-docs/security.md §6).
 *
 * <p>재시도·서킷브레이커는 1차 범위 밖이다 (sp-docs/nfr.md §3).
 */
@Component
public class MemberClient {

	private static final Logger log = LoggerFactory.getLogger(MemberClient.class);

	/** 정본: sp-docs/api-contract.md §5. 벌크 하나뿐이다. */
	private static final String BULK_PATH = "/internal/v1/members/bulk";

	/** 정본: sp-docs/security.md §6, sp-docs/api-contract.md §5. */
	public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

	private static final ParameterizedTypeReference<ApiResponse<MemberBulkResponse>> RESPONSE_TYPE =
			new ParameterizedTypeReference<>() { };

	private final RestClient restClient;

	/**
	 * @param baseUrl member 의 주소. 편의값이므로 기본값이 있다 (sp-docs/tech-stack.md §4.3)
	 * @param internalApiKey 내부 API 인증 키. <b>비밀 값이므로 기본값이 없다</b> (§4.3).
	 *         환경변수가 아예 없으면 {@code @Value} 가 placeholder 를 해석하지 못해 이 빈 생성이
	 *         실패하고 기동이 멈춘다. <b>빈 문자열로 설정된 경우는 해석이 성공하고 기동도
	 *         성공하므로</b>(M-10 실측, §4.3) 아래 가드가 그 구멍을 막는다 — 빈 키로 기동하면
	 *         모든 생성 요청이 member 에서 거부되어 503 {@code S001} 경보로만 드러난다
	 * @param connectTimeout connect 타임아웃. 정본값 1초 (sp-docs/nfr.md §3)
	 * @param readTimeout read 타임아웃. 정본값 3초 (§3). <b>기본값(무제한)으로 두지 않는다</b>
	 */
	public MemberClient(
			RestClient.Builder restClientBuilder,
			@Value("${member-service.url}") String baseUrl,
			@Value("${member-service.internal-api-key}") String internalApiKey,
			@Value("${member-service.connect-timeout}") Duration connectTimeout,
			@Value("${member-service.read-timeout}") Duration readTimeout) {

		if (!StringUtils.hasText(internalApiKey) || internalApiKey.startsWith("${")) {
			// 메시지에 키 값을 넣지 않는다 (sp-docs/conventions.md §8)
			throw new IllegalStateException(
					"INTERNAL_API_KEY 가 없다. 내부 API 키는 환경변수로 주입한다 "
							+ "(sp-docs/security.md §6, sp-docs/tech-stack.md §4.3).");
		}

		ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
				.withConnectTimeout(connectTimeout)
				.withReadTimeout(readTimeout);

		this.restClient = restClientBuilder
				.baseUrl(baseUrl)
				// 호출 측은 키를 싣는 쪽이다. 검증 필터는 제공 측(member)에 있다
				// (sp-docs/security.md §6)
				.defaultHeader(INTERNAL_API_KEY_HEADER, internalApiKey)
				.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
				.build();
	}

	/**
	 * 글·댓글 작성자의 닉네임 스냅샷을 얻는다.
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}. 요청 본문의 값을 쓰지 않는다
	 *         (sp-docs/adr/0012 §6)
	 * @return 활성 프로필의 닉네임
	 * @throws BusinessException {@code S002} 프로필이 없거나 탈퇴했다 (403) ·
	 *         {@code S001} 내부 API 호출이 실패했다 (503, 경보 대상)
	 */
	public String findActiveNickname(long accountId) {
		MemberBulkResponse body = requestSummaries(accountId);

		if (body == null || body.members() == null) {
			// 200 이지만 봉투의 data 가 없다. 계약(§5)과 다른 본문이므로 파싱 실패와 같다
			log.error("member 내부 API 응답에 data.members 가 없다. accountId={}", accountId);
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
		}

		// 요청 순서와 응답 순서를 맞추지 않으므로 accountId 로 매칭한다 (§5)
		MemberSummaryResponse summary = body.members().stream()
				.filter(member -> Objects.equals(member.accountId(), accountId))
				.findFirst()
				// 결과에서 제외됐다 = 프로필 미등록. 업무 오류이고 재시도는 무의미하다 (§5.1)
				.orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_REQUIRED));

		if (summary.deleted()) {
			throw new BusinessException(ErrorCode.PROFILE_REQUIRED);
		}
		if (!StringUtils.hasText(summary.nickname())) {
			// 활성 프로필에 닉네임이 없는 것은 계약(§5)에 없는 응답이다. null 을 스냅샷으로
			// 저장하면 그 결함이 게시글에 남는다
			log.error("member 내부 API 가 활성 프로필에 닉네임을 주지 않았다. accountId={}", accountId);
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
		}
		return summary.nickname();
	}

	/**
	 * 벌크 조회 한 번. <b>실패는 전부 {@code S001} 이다</b> (§5.1) — 4xx·5xx·타임아웃·본문 파싱
	 * 실패·미처리 예외가 모두 여기로 떨어진다. 그래서 {@code RuntimeException} 까지 받는다.
	 *
	 * <p><b>404 를 따로 가르지 않는다.</b> 가르면 경로 오설정이 "프로필 없음" 으로 위장된다.
	 */
	private MemberBulkResponse requestSummaries(long accountId) {
		try {
			ApiResponse<MemberBulkResponse> response = this.restClient.post()
					.uri(BULK_PATH)
					.body(new MemberBulkRequest(List.of(accountId)))
					.retrieve()
					.body(RESPONSE_TYPE);
			return response == null ? null : response.data();
		}
		catch (RuntimeException e) {
			// 경보 대상이므로 error 로 남긴다. 키·토큰은 남기지 않는다
			// (sp-docs/conventions.md §8)
			log.error("member 내부 API 호출이 실패했다. accountId={}, cause={}",
					accountId, e.getClass().getName());
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
		}
	}

}
