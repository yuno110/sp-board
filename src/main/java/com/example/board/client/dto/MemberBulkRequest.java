package com.example.board.client.dto;

import java.util.List;

/**
 * 내부 API 의 벌크 조회 요청 본문. 정본: sp-docs/api-contract.md §5 —
 * 요청 키는 {@code accountIds} 다.
 *
 * <p><b>board 가 자체 정의한다.</b> 제공 서비스(member)의 DTO 클래스를 공유하지 않는다
 * (sp-docs/conventions.md §3).
 *
 * <p>member 는 이 필드에 {@code @NotNull} 을 걸어 <b>누락·{@code null} 을 400 {@code C001}</b>
 * 로 돌려주고 <b>빈 배열은 200 과 빈 {@code members}</b> 로 돌려준다 (§5). 400 은 §5.1 이
 * 503 {@code S001} 경보로 분류하므로, 호출 측은 이 목록을 비우거나 {@code null} 로 보내지
 * 않는다. 건수 상한은 없다.
 *
 * @param accountIds 조회할 {@code accountId} 목록
 */
public record MemberBulkRequest(List<Long> accountIds) { }
