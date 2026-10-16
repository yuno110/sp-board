package com.example.board.client.dto;

import java.util.List;

/**
 * 내부 API 응답 봉투의 {@code data} 부분. 정본: sp-docs/api-contract.md §5 —
 * {@code {"success": true, "data": {"members": [...]}, "error": null}} 이고
 * <b>배열이 최상위가 아니다.</b>
 *
 * <p>이 한 겹을 빼고 배열을 바로 받으면 member(M-10)가 실제로 돌려주는 모양과 달라
 * 본문 파싱이 실패하고, §5.1 이 그것을 503 {@code S001} 로 분류하므로 <b>board 의 쓰기가
 * 멈춘 뒤에야</b> 드러난다 (sp-docs/plan/phase1.md §6 B-04).
 *
 * <p>요청 순서와 응답 순서가 맞지 않으므로 소비 측이 {@code accountId} 로 매칭한다 (§5).
 * 존재하지 않는 {@code accountId} 는 이 목록에서 <b>빠진다</b> — 404 가 아니다.
 *
 * @param members 프로필 요약 목록
 */
public record MemberBulkResponse(List<MemberSummaryResponse> members) { }
