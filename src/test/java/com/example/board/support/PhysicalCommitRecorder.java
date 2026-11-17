package com.example.board.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * 한 번의 호출이 그 호출의 스레드에서 커밋한 물리 트랜잭션을 모은다 — 「한 번의 호출에 커밋이 정확히
 * 한 번」 을 관측하는 수단이다 (sp-docs/process/review-policy.md §4.1).
 *
 * <p><b>트랜잭션 매니저를 스파이로 바꾸지 않는다.</b> 그 절의 표는 매니저 스파이를 수단으로 적지만,
 * 판정 행의 정리 작업({@code PostViewCleanup}, B-21)이 컨텍스트가 뜬 직후부터 주기마다 스케줄러
 * 스레드에서 같은 매니저로 트랜잭션을 연다. 정리를 5ms 주기로 돌린 프로브에서 매니저 스파이로
 * 커밋을 세던 테스트가 두 가지로 빨갰다 (2026-11-17 B-21 리뷰·수정 실측) — 정리의 커밋이 함께
 * 세어져 집계가 1 을 넘었고, 스텁을 거는 줄에서 {@code UnfinishedStubbingException} 이 났다. 뒤의
 * 것은 리뷰의 진단으로 스텁을 거는 동안 스케줄러 스레드가 그 스파이를 부른 것이고, 예외가 스텁을
 * 거는 줄에서 나므로 스텁의 답에서 스레드를 걸러서는 막지 못한다.
 *
 * <p>이 기록기는 매니저가 실행의 시작과 끝을 알리는 {@link TransactionExecutionListener} 다 — 스텁이
 * 없으므로 다른 스레드의 호출과 겨루지 않는다. Boot 가 이 타입의 빈을 모아 JPA 트랜잭션 매니저에
 * 붙인다: spring-boot-autoconfigure 3.5.16 의 {@code TransactionManagerCustomizationAutoConfiguration}
 * 이 빈을 모으고 {@code JpaBaseConfiguration#transactionManager} 가 그것을 매니저에 적용한다
 * (2026-11-17 확인). 붙지 않으면 목록이 비어 「정확히 한 번」 의 단언이 빨개진다.
 *
 * <p><b>{@link #물리_커밋} 을 부른 스레드의 커밋만 모은다.</b> 관측하는 호출은 그 스레드에서 돌고,
 * 정리 작업의 커밋은 스케줄러 스레드에서 난다. <b>그래서 관측하는 호출이 커밋 하나를 다른 스레드로 넘기는
 * 결함은 세지 않는다</b> — 그 호출 안의 연쇄 삭제를 다른 스레드의 트랜잭션으로 넘긴 변이에서 매니저 스파이
 * 판은 「2」 로 빨갰고 이 기록기 판은 녹색이었다 (2026-11-17 B-21 리뷰 2라운드 실측). review-policy §4.1 의
 * C 는 같은 스레드에서 연산마다 커밋하는 모양이고, 그 모양은 잡는다.
 *
 * <p><b>{@link TransactionExecution#isNewTransaction()} 이 참인 커밋만 모은다</b> — 그 절이 요구하는
 * 필터다. 매니저는 바깥 트랜잭션에 참여한 실행에는 커밋을 알리지 않고 savepoint 의 해제는 알린다
 * (spring-tx 6.2.19 의 {@code AbstractPlatformTransactionManager#processCommit}, 2026-11-17 확인) —
 * 어느 쪽도 물리 커밋이 아니다. 커밋이 실패한 실행도 모으지 않는다.
 */
public class PhysicalCommitRecorder implements TransactionExecutionListener {

	private final List<String> 커밋 = new CopyOnWriteArrayList<>();

	@Nullable
	private volatile Thread 기록할_스레드;

	/**
	 * 작업을 부르는 스레드에서 돌리고, 그동안 그 스레드에서 커밋된 물리 트랜잭션의 이름을 커밋된 순서로
	 * 돌려준다. 이름이 없는 실행은 {@code (이름 없음)} 으로 적는다. 앞의 기록은 버린다.
	 */
	public List<String> 물리_커밋(Runnable 작업) {
		this.커밋.clear();
		this.기록할_스레드 = Thread.currentThread();
		try {
			작업.run();
		}
		finally {
			this.기록할_스레드 = null;
		}
		return List.copyOf(this.커밋);
	}

	@Override
	public void afterCommit(TransactionExecution transaction, @Nullable Throwable commitFailure) {
		if (commitFailure == null && transaction.isNewTransaction()
				&& Thread.currentThread() == this.기록할_스레드) {
			String 이름 = transaction.getTransactionName();
			this.커밋.add(이름.isEmpty() ? "(이름 없음)" : 이름);
		}
	}

	/**
	 * 기록기를 컨텍스트에 더한다. {@code @TestConfiguration} 이므로 {@code @SpringBootConfiguration}
	 * 탐색을 막지 않고 덧붙는다.
	 */
	@TestConfiguration
	public static class Config {

		@Bean
		PhysicalCommitRecorder physicalCommitRecorder() {
			return new PhysicalCommitRecorder();
		}

	}

}
