package com.example.board.like.dto;

/**
 * 좋아요·취소의 응답. 정본: sp-docs/api-contract.md §4.6 — 그 요청 뒤의 상태다 (§9.8 의 예시).
 *
 * <p><b>누르기({@code PUT})와 취소({@code DELETE})가 이 한 모양을 쓴다.</b> 둘 다 200 인 것도 두
 * 메서드의 응답을 한 모양으로 두기 위해서다 (§4.6).
 *
 * <p>컴포넌트는 §4.6 의 표와 같은 순서로 둔다.
 *
 * @param liked 요청한 계정이 지금 그 글을 눌렀는가. {@code PUT} 이면 언제나 {@code true},
 *              {@code DELETE} 면 언제나 {@code false} 다 (§4.6) — 이번 요청이 상태를 바꿨는지가
 *              아니다
 * @param likeCount 그 글의 좋아요 수. 쓴 뒤(되돌렸으면 되돌린 뒤) 읽은 값이다
 *                  (sp-docs/domain-model.md §6.1)
 */
public record LikeResponse(
		boolean liked,
		int likeCount
) {

}
