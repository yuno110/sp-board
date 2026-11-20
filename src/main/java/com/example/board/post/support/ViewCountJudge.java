package com.example.board.post.support;

import com.example.board.post.entity.PostView;
import com.example.board.post.repository.PostRepository;
import com.example.board.post.repository.PostViewRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조회수 판정 (P-05). 정본: sp-docs/requirements/board.md §9.2 §9.3, sp-docs/domain-model.md §4.5 §6.
 *
 * <p><b>같은 판정 키·같은 글은 마지막으로 집계된 조회부터 24시간 안에 한 번 센다</b> (§9.2). 24시간
 * 안의 조회는 그 창을 밀지 않는다.
 *
 * <p><b>순서는 §9.3 의 판정이다</b> — 조건부 UPDATE({@link PostViewRepository#renewViewedAt})가
 * 먼저이고, 0 행이면 존재 확인, 없을 때만 INSERT 한다. {@code INSERT IGNORE}·
 * {@code ON DUPLICATE KEY UPDATE} 를 쓰지 않는다 — 영향 행 수가 H2 와 MySQL 에서 같다는 근거가 없다
 * (§9.3).
 *
 * <p><b>판정 트랜잭션의 격리 수준은 READ COMMITTED 다</b> (§9.3 의 표, sp-docs/domain-model.md §4.5
 * — 2026-11-20 {@code D-24}). MySQL 의 기본 격리(반복 읽기)에서는 1) 의 0 행 조건부 UPDATE 가
 * {@code uk_post_view_post_viewer} 의 끝 간격(supremum)에 next-key 락을 남겨, 동시 첫 조회끼리 —
 * 같은 글이 아니어도 — 서로의 INSERT 를 막고 교착했다 (2026-11-19 I-07 실측, 상세 조회가 500
 * {@code C005}). READ COMMITTED 에서는 그 UPDATE 가 간격 락을 남기지 않는다. 수치와 버린 대안
 * (INSERT 먼저·재시도), 남는 위험(처음 INSERT 한 트랜잭션이 되돌아갈 때의 교착)은 §9.3 의 블록인용이
 * 적는다. 순서와 UNIQUE 충돌의 처리는 격리 수준과 무관하게 그대로다.
 *
 * <p><b>이 트랜잭션에만 건다.</b> 서버·데이터소스의 전역 격리 수준을 바꾸지 않는다 — 상세의 읽기를
 * 포함한 다른 트랜잭션은 서버 기본 그대로다 (§9.3, sp-docs/tech-stack.md §4.5). 선언은 연결에 닿고
 * 트랜잭션이 끝날 때 되돌아간다 — {@code HibernateJpaDialect} 가 시작에서 물리 연결의 격리 수준을
 * 바꾸고({@code DataSourceUtils.prepareConnectionForTransaction}) 정리에서 원래 값으로 돌린다
 * ({@code resetConnectionAfterTransaction}). 그 경로의 전제는 {@code JpaDialect} 의 prepareConnection 이
 * 켜져 있고(기본) Hibernate 의 연결 해제 모드가 {@code ON_CLOSE} 인 것이고, {@code HibernateJpaVendorAdapter} 의 기본
 * ({@code DELAYED_ACQUISITION_AND_HOLD})이 그렇다 — 다른 모드면 시작에서
 * {@code InvalidIsolationLevelException} 이 난다. open-in-view(Boot 기본 켜짐)에서는 같은 요청의 404
 * 확인·판정·상세 읽기가 한 물리 연결을 쓰므로, 상세 읽기의 격리는 이 트랜잭션 끝의 되돌림이 정한다
 * (B-25 의 코드 읽기, spring-orm 6.2.19). 실 MySQL 의 관측은 I-11 이다.
 *
 * <p><b>전제 — binlog 형식.</b> 바이너리 로그가 켜져 있고 {@code binlog_format} 이 {@code STATEMENT}
 * 이면 MySQL 이 READ COMMITTED 트랜잭션의 쓰기를 거부해 판정이 실패한다(MySQL 의 동작 — 이
 * 프로젝트에서 재지 않았다). {@code ROW} 또는
 * {@code MIXED} 여야 한다 (sp-docs/tech-stack.md §4.5).
 *
 * <p><b>집계될 때만 {@code view_count} 를 좁은 UPDATE({@link PostRepository#increaseViewCount})로
 * +1 하고, 그 +1 도 이 판정의 트랜잭션 안이다</b> (§9.3). 판정 행이 생기거나 기준 시각이 바뀐
 * 트랜잭션에서만 +1 이 남는다 (sp-docs/domain-model.md §6 의 표).
 *
 * <p><b>이 트랜잭션은 상세 조회의 읽기와 다르다</b> (§9.3). 판정 INSERT 의 UNIQUE 충돌은 정상
 * 결과인데 그 트랜잭션을 되돌림 전용으로 만든다 — 상세의 읽기와 한 트랜잭션이면 응답 직전의 커밋이
 * 실패한다. 그래서 {@code @Transactional} 이 아니라 템플릿이고, 이 클래스를 부르는 쪽
 * ({@code PostService.getDetail})은 트랜잭션 밖에서 부른다. 템플릿은 기본 전파라서 부르는 쪽에
 * 트랜잭션이 있으면 그것에 참여한다 — 분리는 부르는 쪽이 트랜잭션 밖이라는 것으로 성립한다.
 *
 * <p><b>시각은 앱 시계다</b> (§9.2) — DB 의 {@code NOW()} 를 쓰지 않고 문장에는 인자로 넘긴다. 초
 * 아래를 자른다. 컬럼이 {@code DATETIME} 이고 초 단위로 충분하다 (sp-docs/domain-model.md §4.5) —
 * 자르면 판정에 쓴 값이 저장되는 기준 시각과 같다.
 */
@Component
public class ViewCountJudge {

	/** 같은 조회자·같은 글을 한 번 세는 창 (sp-docs/requirements/board.md §9.2 — 사용자 결정). */
	static final Duration WINDOW = Duration.ofHours(24);

	private final PostViewRepository postViewRepository;

	/** {@code view_count} 를 바꾸는 지점 — 좁은 UPDATE 다 (sp-docs/domain-model.md §6). */
	private final PostRepository postRepository;

	/** 판정의 DB 트랜잭션. READ COMMITTED 다. 템플릿인 이유와 격리 수준의 이유는 클래스 주석에 있다. */
	private final TransactionTemplate judgeTransaction;

	public ViewCountJudge(
			PostViewRepository postViewRepository,
			PostRepository postRepository,
			PlatformTransactionManager transactionManager) {

		this.postViewRepository = postViewRepository;
		this.postRepository = postRepository;
		this.judgeTransaction = new TransactionTemplate(transactionManager);
		// 판정 트랜잭션에만 건다 — 이유는 클래스 주석 (sp-docs/requirements/board.md §9.3)
		this.judgeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	/**
	 * 지금(앱 시계, 초 단위)으로 이 조회를 판정하고 커밋까지 끝낸다. 404 판정은 부르는 쪽이 먼저 한다
	 * — 없는·삭제된 글의 판정 행을 만들지 않는다 (sp-docs/domain-model.md §4.5).
	 *
	 * @param viewerKey {@link ViewerKeyFactory} 가 만든 판정 키
	 */
	public void judge(Long postId, String viewerKey) {
		judge(postId, viewerKey, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
	}

	/**
	 * {@code now} 를 판정 시각으로 쓴다. 기준 시각이 {@code now - 24시간} 이하인 행은 창이 지난 것이다
	 * — §9.3 의 조건 「기준 시각이 24시간 전 이하」 다.
	 *
	 * <ol>
	 * <li>조건부 UPDATE — 1 행이면 창이 지난 조회다. 기준 시각이 {@code now} 가 되고 +1 한다
	 * <li>존재 확인 — 행이 있으면 24시간 안이다. 집계하지 않고 INSERT 하지도 않는다
	 * <li>INSERT — 성공하면 +1 한다. UNIQUE 충돌이면 같은 키의 동시 조회가 먼저 넣은 것이고 집계하지
	 * 않는다 ({@link #insertView})
	 * </ol>
	 */
	void judge(Long postId, String viewerKey, LocalDateTime now) {
		this.judgeTransaction.executeWithoutResult(status -> {
			// 1) 창이 지난 행이면 기준 시각을 옮기고 센다
			if (this.postViewRepository.renewViewedAt(postId, viewerKey, now, now.minus(WINDOW)) == 1) {
				this.postRepository.increaseViewCount(postId);
				return;
			}
			// 2) 0행 — 행이 있으면 24시간 안이다
			if (this.postViewRepository.existsByPostIdAndViewerKey(postId, viewerKey)) {
				return;
			}
			// 3) 없을 때만 넣는다. UNIQUE 충돌이면 이 트랜잭션은 쓴 것이 없으므로 되돌린다
			if (!insertView(postId, viewerKey, now)) {
				status.setRollbackOnly();
				return;
			}
			this.postRepository.increaseViewCount(postId);
		});
	}

	/**
	 * 판정 행을 넣는다. UNIQUE 충돌이면 {@code false} 다 — sp-docs/requirements/board.md §9.3 이 그
	 * 충돌을 같은 키의 동시 조회에서만 나는 정상 결과로 정했고 집계하지 않는다.
	 *
	 * <p><b>충돌한 트랜잭션은 이미 되돌림 전용이다.</b> 리포지터리의 트랜잭션 어드바이스가 참여한
	 * 트랜잭션에서 예외를 보고 전체를 그렇게 표시한다. 부르는 쪽이
	 * {@link TransactionStatus#setRollbackOnly()} 로 템플릿의 트랜잭션에도 되돌림을 표시해야 템플릿이
	 * 부르는 커밋이 예외 없이 되돌림이 된다 — 표시를 뺀 변이에서 그 충돌을 겪은 상세 조회가 500 이었다
	 * (B-21 실측).
	 *
	 * <p><b>UNIQUE 위반만 잡는다.</b> Hibernate 가 분류한 위반의 종류({@link ConstraintKind#UNIQUE})로
	 * 가른다 — 그 밖의 무결성 위반은 그대로 던진다. 삼키면 실패가 「집계하지 않은 조회」 로 위장된다
	 * (sp-docs/AGENTS.md 불변식 9).
	 *
	 * <p>{@code saveAndFlush} 다 — INSERT 가 이 {@code try} 안에서 나가야 충돌을 여기서 받는다.
	 *
	 * <p>충돌은 Hibernate 의 SQL 오류 로그를 남긴다 — 그 로그 수준은 실 MySQL 측정 대상이고 이 격리
	 * 수준 뒤에 I-11 이 잰다 (sp-docs/requirements/board.md §9.3 의 블록인용).
	 */
	private boolean insertView(Long postId, String viewerKey, LocalDateTime now) {
		try {
			this.postViewRepository.saveAndFlush(PostView.builder()
					.postId(postId)
					.viewerKey(viewerKey)
					.viewedAt(now)
					.build());
			return true;
		}
		catch (DataIntegrityViolationException ex) {
			if (isUniqueViolation(ex)) {
				return false;
			}
			throw ex;
		}
	}

	/** 원인 사슬에서 Hibernate 의 제약 위반을 찾아 그 종류가 UNIQUE 인지 본다. */
	private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintKind.UNIQUE;
			}
		}
		return false;
	}

}
