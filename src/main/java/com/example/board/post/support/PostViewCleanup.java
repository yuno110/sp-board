package com.example.board.post.support;

import com.example.board.post.repository.PostViewRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오래된 조회수 판정 행의 정리 작업. 정본: sp-docs/requirements/board.md §9.3,
 * sp-docs/tech-stack.md §4.5, sp-docs/security.md §9.
 *
 * <p><b>기준 시각이 24시간을 넘은 행만 지운다.</b> 그런 행은 없는 행과 판정이 같으므로 지워도 판정
 * 결과가 같다 (sp-docs/domain-model.md §4.5). 경계는 {@link ViewCountJudge} 와 맞물린다 — 판정은
 * 기준 시각이 {@code now - 24시간} 이하인 행을 창이 지난 것으로 보고, 이 작업은 그보다 이른 행만
 * 지운다. 경계에 놓인 행은 판정이 다시 쓸 수 있는 행이라 남긴다.
 *
 * <p><b>주기는 {@code view-count.cleanup-interval} 이다</b> (§4.5) — 앞 실행이 끝난 뒤부터 잰다.
 * 판정 행의 보존 상한이 「24시간 + 정리 주기」 인 근거가 이 주기다 (sp-docs/security.md §9).
 * 첫 실행을 늦추지 않는다 — 기동 직후에 한 번 돈다 (2026-11-16 B-21 실측). 주기보다 자주 재기동되는
 * 인스턴스에서도 그 상한이 남게 하려는 것이다.
 *
 * <p><b>실패를 삼키지 않는다</b> (sp-docs/nfr.md §4 의 {@code post_view} 줄). 정리가 멈추면 행이
 * 무한히 쌓이므로 그 실패가 로그로 드러나야 한다. 이 클래스는 예외를 잡지 않는다 — 잡지 않은
 * 예외는 스케줄러의 오류 처리기가 받아 로그를 남기고 다음 주기를 돈다 (2026-11-16 B-21 실측).
 *
 * <p>시각은 앱 시계이고 초 아래를 자른다 — {@link ViewCountJudge} 와 같은 시계다 (§9.2).
 */
@Component
public class PostViewCleanup {

	private final PostViewRepository postViewRepository;

	/** 정리의 DB 트랜잭션. 벌크 DELETE 는 부르는 쪽의 트랜잭션이 필요하다. */
	private final TransactionTemplate cleanupTransaction;

	public PostViewCleanup(PostViewRepository postViewRepository, PlatformTransactionManager transactionManager) {
		this.postViewRepository = postViewRepository;
		this.cleanupTransaction = new TransactionTemplate(transactionManager);
	}

	/** 스케줄러가 부르는 자리. 지금(앱 시계)을 기준으로 {@link #deleteExpired} 한다. */
	@Scheduled(fixedDelayString = "${view-count.cleanup-interval}")
	public void run() {
		deleteExpired(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
	}

	/**
	 * 기준 시각이 {@code now - 24시간} 보다 이른 행을 지우고 지운 행 수를 돌려준다.
	 */
	int deleteExpired(LocalDateTime now) {
		LocalDateTime cutoff = now.minus(ViewCountJudge.WINDOW);
		Integer deleted = this.cleanupTransaction.execute(
				status -> this.postViewRepository.deleteViewedBefore(cutoff));
		return deleted;
	}

}
