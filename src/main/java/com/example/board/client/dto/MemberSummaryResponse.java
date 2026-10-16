package com.example.board.client.dto;

/**
 * 내부 API 가 돌려주는 프로필 요약. 정본: sp-docs/api-contract.md §5 —
 * {@code {accountId, nickname, deleted}}.
 *
 * <p><b>board 가 자체 정의한다.</b> 제공 서비스(member)의 DTO 클래스를 공유하지 않는다
 * (sp-docs/conventions.md §3).
 *
 * <p>탈퇴한 프로필은 {@code deleted = true} 와 닉네임 {@code "탈퇴한 회원"} 으로 온다. 그
 * 닉네임을 스냅샷으로 쓰지 않는다 — {@code deleted = true} 는 글·댓글 생성을 막는
 * 403 {@code S002} 다 (§5.1).
 *
 * @param accountId 응답 키는 단수 {@code accountId} 다 (요청 키는 {@code accountIds})
 * @param nickname 작성자 스냅샷에 복제 저장할 닉네임 (sp-docs/adr/0003)
 * @param deleted 탈퇴 여부
 */
public record MemberSummaryResponse(Long accountId, String nickname, boolean deleted) { }
