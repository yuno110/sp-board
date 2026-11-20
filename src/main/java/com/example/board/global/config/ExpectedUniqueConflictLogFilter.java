package com.example.board.global.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import java.util.Locale;
import org.slf4j.Marker;

/**
 * 정상 경합의 UNIQUE 충돌에 Hibernate 가 남기는 ERROR 줄만 버린다 (sp-docs/conventions.md §8.1).
 * {@code src/main/resources/logback-spring.xml} 이 로거 컨텍스트에 건다 — 프로파일을 가리지 않는다
 * (sp-docs/tech-stack.md §4.5).
 *
 * <p><b>버리는 것은 한 가지다</b> — {@code org.hibernate.engine.jdbc.spi.SqlExceptionHelper} 의 ERROR
 * 중 아래 제약 이름이 든 줄(대소문자 무시, 부분 일치). 코드가 잡아 정상 응답으로 바꾸는 충돌이다 —
 * 판정 행은 {@code ViewCountJudge}, 좋아요는 {@code LikeService} 가 잡는다. 그 줄의 중복 값에는 판정
 * 키가 실린다. 같은 충돌의 WARN {@code SQL Error: …} 줄, 로거의 수준, 다른 제약의 위반과 다른 SQL
 * 오류의 ERROR 는 그대로다 — 이유는 §8.1 의 표다.
 *
 * <p><b>부분 일치다.</b> MySQL 은 {@code for key 'post_view.uk_post_view_post_viewer'} 로, H2 는 프로젝트의
 * 테스트 URL 에서 {@code "public.uk_post_view_post_viewer_INDEX_7 ON …"} 로 낸다 (2026-11-20 I-11·
 * B-26 실측). 대소문자는 DB 와 설정에 따라 다르다.
 *
 * <p><b>문면이 {@code null} 인 호출은 판정하지 않는다({@link FilterReply#NEUTRAL}).</b> logback 은
 * {@code isErrorEnabled()} 같은 수준 검사에서도 TurboFilter 를 문면 없이 부른다. Hibernate 의
 * {@code logExceptions} 는 그 검사가 거짓이면 WARN 도 남기지 않으므로, 거기서 버리면 WARN 과 다른
 * ERROR 가 모두 사라진다 (§8.1 의 셋째 행). Hibernate 의 줄은 jboss-logging → Log4j API →
 * log4j-to-slf4j 를 지나 완성된 문면으로 오고 인자는 없다 (D-25 리뷰의 jar 읽기).
 *
 * <p><b>제약을 더하려면 정본이 먼저다</b> (§8.1). 목록은 sp-docs/domain-model.md §4.3 의 이름 그대로다.
 */
public class ExpectedUniqueConflictLogFilter extends TurboFilter {

	static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

	/** 코드가 잡아 정상 응답으로 바꾸는 충돌의 제약 — 소문자로 두고 문면을 소문자로 바꿔 맞춘다. */
	static final List<String> EXPECTED_CONFLICT_CONSTRAINTS =
			List.of("uk_post_view_post_viewer", "uk_post_like_post_account");

	@Override
	public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
			Throwable t) {
		if (format == null || level != Level.ERROR || !SQL_EXCEPTION_HELPER.equals(logger.getName())) {
			return FilterReply.NEUTRAL;
		}
		String message = format.toLowerCase(Locale.ROOT);
		for (String constraint : EXPECTED_CONFLICT_CONSTRAINTS) {
			if (message.contains(constraint)) {
				return FilterReply.DENY;
			}
		}
		return FilterReply.NEUTRAL;
	}

}
