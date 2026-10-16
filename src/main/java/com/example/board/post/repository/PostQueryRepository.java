package com.example.board.post.repository;

import static com.example.board.post.entity.QPost.post;

import com.example.board.post.dto.PostSearchCondition;
import com.example.board.post.entity.Post;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 게시글 목록·검색의 동적 쿼리. 정본: sp-docs/requirements/board.md §6 (P-03),
 * sp-docs/api-contract.md §4.1.
 *
 * <p>조건 조합은 {@link BooleanExpression} 으로 한다 (§6). 적용할 조건이 없으면 {@code null} 을
 * 돌려주고, QueryDSL 의 {@code where} 는 {@code null} 인수를 무시한다 — 키워드가 없으면 조건이
 * 붙지 않아 전체 목록이 된다 (§6).
 *
 * <p><b>{@code deleted = false} 는 조건이 아니라 상수다.</b> 삭제된 게시글은 목록에서 제외한다
 * (sp-docs/requirements/board.md §5 규칙 1). 검색 분기마다 붙이면 한 군데가 빠지는 순간 삭제된
 * 글이 노출되므로 목록 쿼리와 건수 쿼리 양쪽에 같은 자리에서 붙인다.
 *
 * <p><b>{@code keyword} 는 부분 일치다</b> — {@code LIKE '%키워드%'} 이고 전방 일치가 아니다
 * (sp-docs/api-contract.md §4.1). {@code idx_post_title} 은 선행 와일드카드가 붙은 이 조건을
 * 돕지 않고, 1차는 그 상태로 둔다 (sp-docs/domain-model.md §4.3). 대소문자 구분은 스키마
 * 콜레이션({@code utf8mb4_general_ci})이 정하므로 {@code lower()} 를 덧씌우지 않는다
 * (sp-docs/tech-stack.md §4.1).
 *
 * <p><b>정렬·검색 대상 컬럼을 정하는 자리는 여기다.</b> enum 쪽에 두면 DTO 가 Q 타입에
 * 의존한다 (sp-docs/conventions.md §1.3 §3). {@code switch} 식을 {@code default} 없이 써서
 * enum 에 상수가 늘면 컴파일이 깨지게 둔다.
 */
@Repository
@RequiredArgsConstructor
public class PostQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 목록·검색. 응답 형식은 sp-docs/api-contract.md §7.1 이고 변환은 서비스가 한다.
	 *
	 * <p>건수는 매번 센다. {@code totalElements}·{@code totalPages} 가 항상 실제 건수에서
	 * 나오므로 마지막 페이지 판정이 응답마다 일관된다.
	 */
	public Page<Post> search(PostSearchCondition condition) {
		Pageable pageable = PageRequest.of(condition.page(), condition.size());
		BooleanExpression keywordCondition = keywordCondition(condition);

		List<Post> content = this.queryFactory
				.selectFrom(post)
				.where(post.deleted.isFalse(), keywordCondition)
				.orderBy(order(condition))
				.offset(pageable.getOffset())
				.limit(pageable.getPageSize())
				.fetch();

		Long total = this.queryFactory
				.select(post.count())
				.from(post)
				.where(post.deleted.isFalse(), keywordCondition)
				.fetchOne();

		return new PageImpl<>(content, pageable, Objects.requireNonNullElse(total, 0L));
	}

	/**
	 * 정렬. {@code latest} 는 {@code created_at DESC, id DESC}, {@code views} 는
	 * {@code view_count DESC, id DESC} 다 (sp-docs/api-contract.md §4.1).
	 *
	 * <p><b>{@code id} 를 마지막 정렬 키로 붙이는 것이 정렬을 전순서로 만든다</b>
	 * (sp-docs/api-contract.md §7.1). {@code OFFSET} 페이징은 정렬이 전순서가 아니면 페이지마다
	 * 다른 순서를 받을 수 있고, 그러면 같은 행이 두 페이지에 나오거나 어느 페이지에도 나오지
	 * 않는다.
	 *
	 * <p><b>동률은 드문 경우가 아니다.</b> MySQL {@code DATETIME} 은 초 단위라 같은 초에 생성된
	 * 행의 {@code created_at} 이 같고, {@code view_count} 는 기본값이 0 이므로 조회가 없는
	 * 목록은 전체가 동률이다 (§7.1).
	 */
	private OrderSpecifier<?>[] order(PostSearchCondition condition) {
		OrderSpecifier<?> primary = switch (condition.sort()) {
			case latest -> post.createdAt.desc();
			case views -> post.viewCount.desc();
		};
		return new OrderSpecifier<?>[] {primary, post.id.desc()};
	}

	/** 검색 조건. 적용할 것이 없으면 {@code null} 이고 {@code where} 가 무시한다. */
	private BooleanExpression keywordCondition(PostSearchCondition condition) {
		if (!condition.hasSearchCondition()) {
			return null;
		}
		String keyword = condition.keyword();
		return switch (condition.searchType()) {
			case TITLE -> post.title.contains(keyword);
			case CONTENT -> post.content.contains(keyword);
			case TITLE_CONTENT -> post.title.contains(keyword).or(post.content.contains(keyword));
			case WRITER -> post.writerNickname.contains(keyword);
		};
	}

}
