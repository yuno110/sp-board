package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 버리는 조건을 MySQL 의 문면으로 고정한다 (sp-docs/plan/phase2-board.md B-26 의 검증 표 「단위」).
 * H2 의 녹색은 MySQL 의 문면에서 맞춘다는 근거가 아니다 — 실제 출력은 {@link LogbackSpringConfigTest}
 * 가 H2 로, 앱으로는 I-12 가 실 MySQL 로 잰다.
 *
 * <p>문면은 2026-11-20 I-11 이 잰 ERROR 줄의 모양({@code Duplicate entry '…' for key
 * '<테이블>.<제약>'})이다. <b>중복 값은 지어낸 것이다</b> — 판정 키를 픽스처에 옮기지 않는다.
 *
 * <p>로거 이름과 제약 이름은 리터럴로 적는다 — 필터의 상수를 가져다 쓰면 그 상수를 바꾼 변이에서
 * 기대가 함께 움직인다.
 */
class ExpectedUniqueConflictLogFilterTest {

	private static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

	private final LoggerContext context = new LoggerContext();

	private final ExpectedUniqueConflictLogFilter filter = new ExpectedUniqueConflictLogFilter();

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"Duplicate entry '7-i:00000000000000000000000000000000000000000000000000000000000000ab' "
					+ "for key 'post_view.uk_post_view_post_viewer'",
			"Duplicate entry '7-12345' for key 'post_like.uk_post_like_post_account'",
			"Duplicate entry '7-12345' for key 'POST_LIKE.UK_POST_LIKE_POST_ACCOUNT'",
			"Duplicate entry '7-a:42' for key 'POST_VIEW.UK_POST_VIEW_POST_VIEWER'"})
	@DisplayName("그 로거의 ERROR 중 두 제약 이름이 든 줄은 대소문자와 무관하게 버린다")
	void 두_제약의_ERROR_는_버린다(String message) {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.ERROR, message)).isEqualTo(FilterReply.DENY);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"Duplicate entry '00000000-0000-4000-8000-000000000001' for key 'attachment.uk_attachment_storage_key'",
			"Cannot add or update a child row: a foreign key constraint fails (`sp_board`.`post_like`, "
					+ "CONSTRAINT `fk_post_like_post_id` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`))",
			"Deadlock found when trying to get lock; try restarting transaction"})
	@DisplayName("그 로거의 다른 제약 위반·다른 SQL 오류의 ERROR 는 버리지 않는다")
	void 다른_ERROR_는_버리지_않는다(String message) {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.ERROR, message)).isEqualTo(FilterReply.NEUTRAL);
	}

	@Test
	@DisplayName("같은 문면이라도 다른 로거의 ERROR 는 버리지 않는다")
	void 다른_로거는_버리지_않는다() {
		String message = "Duplicate entry '7-12345' for key 'post_like.uk_post_like_post_account'";

		assertThat(decide("com.example.board.global.error.GlobalExceptionHandler", Level.ERROR, message))
				.isEqualTo(FilterReply.NEUTRAL);
		assertThat(decide("org.hibernate.engine.jdbc.spi", Level.ERROR, message))
				.as("그 로거의 부모 이름도 그 로거가 아니다")
				.isEqualTo(FilterReply.NEUTRAL);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"WARN", "INFO", "DEBUG"})
	@DisplayName("그 로거의 같은 문면이라도 ERROR 가 아닌 수준은 버리지 않는다")
	void ERROR_가_아닌_수준은_버리지_않는다(String level) {
		String message = "Duplicate entry '7-12345' for key 'post_view.uk_post_view_post_viewer'";

		assertThat(decide(SQL_EXCEPTION_HELPER, Level.toLevel(level), message)).isEqualTo(FilterReply.NEUTRAL);
	}

	/**
	 * 검증 표 「단위 — 문면이 {@code null} 인 호출(수준 검사)」. logback 은 {@code isErrorEnabled()} 에서
	 * TurboFilter 를 문면 없이 부른다 — 거기서 버리면 {@code logExceptions} 의 진입 검사가 거짓이 되어
	 * WARN·다른 ERROR 가 모두 사라진다 (sp-docs/conventions.md §8.1).
	 */
	@Test
	@DisplayName("문면이 null 인 호출(수준 검사)은 버리지 않는다")
	void 문면이_없는_수준_검사는_버리지_않는다() {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.ERROR, null)).isEqualTo(FilterReply.NEUTRAL);

		Logger logger = this.context.getLogger(SQL_EXCEPTION_HELPER);
		this.filter.start();
		this.context.addTurboFilter(this.filter);
		assertThat(logger.isErrorEnabled()).as("필터를 건 컨텍스트에서도 그 로거의 ERROR 는 켜져 있다").isTrue();
		assertThat(logger.isWarnEnabled()).isTrue();
	}

	private FilterReply decide(String loggerName, Level level, String message) {
		return this.filter.decide(null, this.context.getLogger(loggerName), level, message, null, null);
	}

}
