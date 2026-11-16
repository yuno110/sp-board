package com.example.board.post.dto;

import com.example.board.post.entity.PostCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게시글 작성 요청 본문. 정본: sp-docs/requirements/board.md §4 (title 1~200자 ·
 * 공백만으로 채울 수 없음, content 1~10,000자, category) §8. 위반은 400 {@code C001} 이다
 * (sp-docs/api-contract.md §8.1).
 *
 * <p><b>{@code writerId}·{@code nickname} 필드를 두지 않는다.</b> {@code writer_id} 는 검증된
 * JWT 의 {@code sub} 에서, {@code writer_nickname} 은 member 의 내부 API 응답에서만 온다
 * (sp-docs/requirements/board.md §3 규칙 1·2, sp-docs/architecture.md §5 의 금지 표). 본문에
 * 그 키를 실어 보내도 바인딩될 자리가 없어 무시된다 — Spring Boot 는
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 를 끄므로 400 이 아니라 201 이고, 저장되는 값은
 * 본문과 무관하다 ({@code PostControllerTest} 가 그것을 본다).
 *
 * <h2>{@code @NotBlank} 와 {@code @Size} 가 둘 다 필요하다</h2>
 *
 * <p>하나만으로는 §4 의 규칙을 표현할 수 없다.
 *
 * <table>
 * <caption>한쪽만 걸었을 때 새는 입력</caption>
 * <tr><th>걸린 제약</th><th>통과해 버리는 입력</th></tr>
 * <tr><td>{@code @NotBlank} 만</td><td>제목 201자 — 상한이 없다</td></tr>
 * <tr><td>{@code @Size} 만</td><td>공백 200자 · 빈 문자열 — 길이는 맞다</td></tr>
 * </table>
 *
 * <p>하한은 {@code @Size(min = 1)} 이 아니라 {@code @NotBlank} 가 맡는다. "1자 이상" 과
 * "공백만으로 채울 수 없음" 이 함께 요구되므로 공백을 거르는 쪽이 더 좁고, 빈 문자열은 그쪽에도
 * 걸린다. {@code null} 도 {@code @NotBlank} 가 막는다 — {@code @Size} 는 {@code null} 을
 * 통과시킨다.
 *
 * <h2>{@code category} 는 선택이다</h2>
 *
 * <p>생략하면 {@code null} 이고 글은 {@code FREE} 가 된다 (sp-docs/requirements/board.md §8,
 * sp-docs/api-contract.md §4.4). 그 기본값은 {@code Post} 의 생성자가 채운다 — 여기 다시 적지
 * 않는다.
 *
 * <p>{@link PostCategory} 밖의 값은 JSON 해석이 실패해 400 {@code C001} 이다
 * (sp-docs/requirements/board.md §4). 같은 값이 목록의 쿼리 파라미터로 오면 enum 바인딩
 * 실패라 {@code C002} 다 — 들어온 자리가 코드를 가른다. 앞뒤 공백이 붙은 이름(예: {@code " FREE "})은
 * 그 공백을 떼고 받아들인다 — 본문은 Jackson, 쿼리는 Spring 의 변환이 그렇게 한다(2026-11-16 B-19
 * 리뷰 실측). 정본은 공백 처리를 정하지 않는다.
 *
 * <p><b>숫자도 400 {@code C001} 이다</b> — 따옴표 없는 {@code 1} 도, 따옴표 친 {@code "2"} 도.
 * 이 레코드가 아니라 {@code application.yml} 의
 * {@code spring.jackson.deserialization.fail-on-numbers-for-enums} 가 집행한다. 그 키가 없으면
 * Jackson 이 숫자를 서수로 읽어 둘 다 201 이었다 (2026-11-16 실측, {@code PostControllerTest}).
 *
 * <p>{@code NOTICE} 를 쓸 수 있는지(403 {@code P003})는 요청자의 {@code role} 로 정해지므로 이
 * 레코드가 아니라 {@code PostService} 가 판정한다 (§8).
 *
 * @param title 제목
 * @param content 본문
 * @param category 카테고리. 생략하면 {@code null}
 */
public record PostCreateRequest(

		@NotBlank(message = TITLE_MESSAGE)
		@Size(max = TITLE_MAX_LENGTH, message = TITLE_MESSAGE)
		String title,

		@NotBlank(message = CONTENT_MESSAGE)
		@Size(max = CONTENT_MAX_LENGTH, message = CONTENT_MESSAGE)
		String content,

		PostCategory category) {

	/** sp-docs/requirements/board.md §4. 컬럼 폭도 200 이다 (sp-docs/domain-model.md §4.1). */
	public static final int TITLE_MAX_LENGTH = 200;

	/** sp-docs/requirements/board.md §4. */
	public static final int CONTENT_MAX_LENGTH = 10_000;

	public static final String TITLE_MESSAGE = "제목은 1~200자여야 합니다.";

	public static final String CONTENT_MESSAGE = "내용은 1~10,000자여야 합니다.";

}
