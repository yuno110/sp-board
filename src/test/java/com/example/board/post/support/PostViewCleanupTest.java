package com.example.board.post.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import com.example.board.post.repository.PostViewRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * sp-docs/plan/phase2-board.md B-21 의 검증표 중 정리 작업의 두 줄과 완료 기준의 주기다 — 기준 시각이
 * 24시간을 넘은 행만 지우고, 삭제가 던진 예외를 삼키지 않으며, {@code view-count.cleanup-interval} 의
 * 주기로 등록된다. 정본은 sp-docs/requirements/board.md §9.3, sp-docs/tech-stack.md §4.5,
 * sp-docs/nfr.md §4 다.
 *
 * <p><b>주기를 구별되는 값으로 준다.</b> 이 클래스는 {@code view-count.cleanup-interval} 을 97분으로
 * 준다 — sp-docs/tech-stack.md §4.5 의 기본값(1시간)과 달라서 등록된 작업의 주기가 그 설정을 읽었는지가
 * 보인다. 스케줄러는 기동 직후에 한 번 돌고 (B-21 실측) 그 주기 동안 다시 돌지 않는다.
 *
 * <p><b>기준은 실제 시계의 열흘 뒤를 초 단위로 자른 값이다.</b> 스케줄러가 돌리는 정리는 실제 시계의
 * 24시간 전보다 이른 행만 지우므로 이 픽스처를 건드리지 않는다. 대신 이 테스트가 부르는 삭제는 기준의
 * 24시간 전보다 이른 행을 표 전체에서 지운다 — 다른 클래스가 남긴 판정 행도 지워지지만, 테스트는 한
 * JVM 에서 차례로 돌므로 진행 중인 다른 테스트의 픽스처가 아니다 ({@code build.gradle} 의
 * {@code test} 태스크와 테스트 리소스에 병렬 실행 설정이 없다 — 2026-11-16 확인). <b>24시간은
 * 리터럴로 적는다</b> — 판정의 상수를 가져다 쓰면 그 상수를 바꾼 변이에서 픽스처가 함께 움직인다.
 *
 * <p>판정 행의 {@code post_id} 는 -1309~-1301 을 쓴다. {@code post_view.post_id} 에는 FK 가 없고
 * 자동 증가 id 는 음수가 되지 않으므로 어느 글과도 겹치지 않는다. 2026-11-16 에 테스트 소스 전체에서
 * 그 값을 쓰는 곳이 없는 것을 확인했다.
 */
@SpringBootTest(properties = "view-count.cleanup-interval=PT97M")
@ActiveProfiles("test")
class PostViewCleanupTest {

	private static final Duration 시험_주기 = Duration.ofMinutes(97);

	/** 스케줄러의 첫 실행을 기다리는 한도. 넘으면 첫 실행이 끝나지 않은 것이므로 터진다. */
	private static final Duration 대기_한도 = Duration.ofSeconds(10);

	private static final LocalDateTime 기준 = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);

	@Autowired
	private PostViewCleanup postViewCleanup;

	/** 삭제가 던지게 만드는 자리다. 바꾸지 않은 메서드는 실제 빈에 위임된다. */
	@MockitoSpyBean
	private PostViewRepository postViewRepository;

	/** 스케줄러가 등록한 작업을 내보이는 쪽이다 — {@code @Scheduled} 를 처리하는 후처리기다. */
	@Autowired
	private List<ScheduledTaskHolder> scheduledTaskHolders;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void 픽스처_정리() {
		this.jdbcTemplate.update("DELETE FROM post_view WHERE post_id BETWEEN -1309 AND -1301");
	}

	/**
	 * 검증표 <b>"정리 작업 → 기준 시각이 24시간을 넘은 행만 지운다"</b>. 경계의 두 행을 둔다 — 정확히
	 * 24시간 된 행은 「넘은」 행이 아니라 남고, 1초 더 된 행은 지운다.
	 */
	@Test
	@DisplayName("정리 작업은 기준 시각이 24시간을 넘은 행만 지우고 정확히 24시간 된 행은 남긴다")
	void 기준_시각이_24시간을_넘은_행만_지운다() {
		판정_행을_둔다(-1301L, 기준.minusHours(24).minusSeconds(1));
		판정_행을_둔다(-1302L, 기준.minusHours(24));

		this.postViewCleanup.deleteExpired(기준);

		assertThat(판정_행_수(-1301L)).as("24시간에서 1초 더 된 행은 지운다").isZero();
		assertThat(판정_행_수(-1302L)).as("정확히 24시간 된 행은 남는다").isEqualTo(1);
	}

	/**
	 * 정리 작업이 {@code view-count.cleanup-interval} 의 주기로 등록됐다 — 완료 기준 「B-16 의 주기로
	 * 지운다」 다 (sp-docs/tech-stack.md §4.5).
	 */
	@Test
	@DisplayName("정리 작업은 view-count.cleanup-interval 의 주기로 스케줄러에 등록된다")
	void 정리_작업은_설정한_주기로_등록된다() {
		assertThat(정리_작업().getIntervalDuration()).isEqualTo(시험_주기);
	}

	/**
	 * 검증표 <b>"정리의 삭제가 예외를 던진다 (대역으로 만든다) → 그 예외를 삼키지 않는다"</b>
	 * (sp-docs/nfr.md §4 의 {@code post_view} 줄). 스케줄러가 부르는 작업을 그대로 돌려 그 예외가
	 * 밖으로 나오는 것을 본다 — 나온 예외는 스케줄러의 오류 처리기가 ERROR 로 남기고 다음 주기가 돈다
	 * (B-21 실측 — 주기를 1초로 준 컨텍스트에서 삭제가 던지게 했다).
	 *
	 * <p><b>스텁은 스케줄러의 첫 실행이 끝난 뒤에 건다.</b> 그 실행이 스케줄러 스레드에서 이 스파이의
	 * 삭제를 부르므로, 스텁을 거는 동안 끼면 스텁이 그 호출에 걸린다 — 매니저 스파이로 커밋을 세던
	 * 테스트가 정리를 5ms 주기로 돌린 프로브에서 스텁을 거는 줄의 {@code UnfinishedStubbingException}
	 * 으로 빨갰다 (2026-11-17 B-21 리뷰·수정 실측). 첫 실행이 끝나면 다음 실행은 {@code 시험_주기} 뒤다.
	 */
	@Test
	@DisplayName("정리의 삭제가 예외를 던지면 스케줄러가 부르는 작업이 그 예외를 그대로 던진다")
	void 정리의_삭제가_던진_예외를_삼키지_않는다() throws InterruptedException {
		첫_실행이_끝나기를_기다린다();
		IllegalStateException 대역_예외 = new IllegalStateException("정리 실패 대역");
		willThrow(대역_예외).given(this.postViewRepository).deleteViewedBefore(any());

		assertThatThrownBy(() -> 정리_작업().getRunnable().run()).isSameAs(대역_예외);
	}

	/** 스케줄러에 등록된 정리 작업. */
	private IntervalTask 정리_작업() {
		ScheduledTask 작업 = 예약된_정리_작업();
		assertThat(작업.getTask()).isInstanceOf(IntervalTask.class);
		return (IntervalTask) 작업.getTask();
	}

	/**
	 * 스케줄러에 예약된 정리 작업. {@code @Scheduled} 메서드의 작업은 이름이 그 메서드의 「클래스.메서드」
	 * 다 — 다른 작업과 그것으로 가른다.
	 */
	private ScheduledTask 예약된_정리_작업() {
		String 이름 = PostViewCleanup.class.getName() + ".run";
		List<ScheduledTask> 작업 = this.scheduledTaskHolders.stream()
				.flatMap(holder -> holder.getScheduledTasks().stream())
				.filter(task -> 이름.equals(task.getTask().getRunnable().toString()))
				.toList();
		assertThat(작업).as("정리 작업이 스케줄러에 하나 등록돼 있다").hasSize(1);
		return 작업.get(0);
	}

	/**
	 * 스케줄러의 첫 실행이 끝나기를 기다린다. {@link ScheduledTask#nextExecution()} 은 다음 실행이
	 * 앞으로 잡혀 있을 때만 값을 주고 (spring-context 6.2.19, 2026-11-17 확인), 고정 지연 작업의 다음
	 * 실행은 앞 실행이 끝난 뒤에 잡힌다 ({@code ScheduledExecutorService#scheduleWithFixedDelay}).
	 */
	private void 첫_실행이_끝나기를_기다린다() throws InterruptedException {
		ScheduledTask 작업 = 예약된_정리_작업();
		long 한도 = System.nanoTime() + 대기_한도.toNanos();
		while (작업.nextExecution() == null) {
			if (System.nanoTime() - 한도 > 0) {
				throw new IllegalStateException("정리 작업의 첫 실행이 " + 대기_한도 + " 안에 끝나지 않았다");
			}
			Thread.sleep(10);
		}
	}

	private void 판정_행을_둔다(long postId, LocalDateTime viewedAt) {
		int inserted = this.jdbcTemplate.update(
				"INSERT INTO post_view (post_id, viewer_key, viewed_at) VALUES (?, ?, ?)",
				postId, "a:" + (-postId), viewedAt);
		if (inserted != 1) {
			throw new IllegalStateException("판정 행 픽스처가 1 행이 아니다: " + inserted);
		}
	}

	private int 판정_행_수(long postId) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM post_view WHERE post_id = ?", Integer.class, postId);
		return count == null ? 0 : count;
	}

}
