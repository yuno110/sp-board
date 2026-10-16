package com.example.board.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게시글 수정 요청 본문 (P-06). 정본: sp-docs/requirements/board.md §4 — 입력 규칙이 작성과
 * 같은 행이다 (title 1~200자 · 공백만으로 채울 수 없음, content 1~10,000자). 위반은 400
 * {@code C001} 이다 (sp-docs/api-contract.md §8.1).
 *
 * <p><b>제약 값을 {@link PostCreateRequest} 의 상수에서 가져온다.</b> §4 의 한 행이 두 경로의
 * 규칙을 함께 정하므로, 숫자와 메시지를 여기 다시 적으면 그 행이 바뀔 때 한쪽만 고쳐질 수
 * 있다. 규칙이 갈라지면 그때 각자 값을 갖는다.
 *
 * <p><b>{@code writerId}·{@code nickname} 필드를 두지 않는다.</b> 작성과 같은 이유에 더해,
 * <b>수정은 작성자 스냅샷을 아예 갱신하지 않는다</b> — {@code writer_id} 도
 * {@code writer_nickname} 도 바뀌지 않는다 (sp-docs/requirements/board.md §3 규칙 7).
 * {@code Post.update(String, String)} 가 그 둘을 건드리지 않는 것이 집행 지점이고, 본문에 그
 * 키를 실어도 바인딩될 자리가 없다. 그래서 수정 경로는 member-service 를 호출하지 않는다
 * (sp-docs/api-contract.md §4.2).
 *
 * <p>{@code @NotBlank} 와 {@code @Size} 가 둘 다 필요한 이유는 {@link PostCreateRequest} 에
 * 적혀 있다 — 한쪽만으로는 §4 의 규칙을 표현할 수 없다.
 *
 * @param title 제목
 * @param content 본문
 */
public record PostUpdateRequest(

		@NotBlank(message = PostCreateRequest.TITLE_MESSAGE)
		@Size(max = PostCreateRequest.TITLE_MAX_LENGTH, message = PostCreateRequest.TITLE_MESSAGE)
		String title,

		@NotBlank(message = PostCreateRequest.CONTENT_MESSAGE)
		@Size(max = PostCreateRequest.CONTENT_MAX_LENGTH, message = PostCreateRequest.CONTENT_MESSAGE)
		String content) {

}
