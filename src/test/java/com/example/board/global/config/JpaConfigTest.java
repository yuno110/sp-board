package com.example.board.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.post.entity.Post;
import com.example.board.post.repository.PostRepository;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;

/**
 * 감사 시각이 <b>초로 잘려서</b> 들어가는지 본다 (D-11 완료 기준).
 *
 * <p>고치기 전에는 쓰기 응답의 {@code createdAt} 이 <b>저장된 행에 없는 초</b>를 보고했다 —
 * 컬럼이 {@code DATETIME} 이라 MySQL 이 소수부를 반올림하는데 쓰기 응답은 flush 전 엔터티의
 * JVM 값으로 조립되기 때문이다. 글 12개를 연달아 만들어 재니 6개에서 초가 갈렸다
 * (2026-10-20 실측, sp-docs/api-contract.md §4.3).
 *
 * <p><b>그 어긋남 자체는 H2 로 재현되지 않는다.</b> H2 에서 생기는 차이는 초를 넘기지 않기
 * 때문이다(D-11 3라운드 리뷰 실측) — 넘기는 것은 MySQL 의 반올림이다. <b>그래서 board 의
 * 테스트가 전부 녹색인 채로 이 결함이 살아 있었다.</b> 그러므로 여기서 단언하는 것은
 * "두 값이 같다" 가 아니라 <b>소수부가 0 이라는 것</b>이다. 그것이 DB 와 무관하게 성립하는
 * 불변이고, 성립하면 반올림할 것이 없어 운영에서도 두 값이 갈리지 않는다.
 *
 * <p><b>{@code @Transactional} 이 없으면 이 테스트가 남긴 행이 다른 테스트를 깨뜨린다.</b>
 * {@code PostQueryRepositoryTest} 는 <b>테이블 전체</b>를 보고 비어 있음을 단언한다 —
 * 첫 판에서 그 두 건이 실제로 깨졌다. 커밋하고 끝나는 테스트를 두지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("JpaConfig - 감사 시각")
class JpaConfigTest {

	@Autowired
	private DateTimeProvider dateTimeProvider;

	@Autowired
	private PostRepository postRepository;

	@Test
	@DisplayName("DateTimeProvider 가 주는 시각은 소수부가 0 이다")
	void providerTruncatesToSeconds() {
		Optional<TemporalAccessor> now = this.dateTimeProvider.getNow();

		assertThat(now).isPresent();
		assertThat(now.get().get(ChronoField.NANO_OF_SECOND)).isZero();
	}

	@Test
	@DisplayName("저장된 엔터티의 createdAt 도 소수부가 0 이다 — 감사가 그 provider 를 쓴다")
	void auditingUsesTruncatedProvider() {
		Post saved = this.postRepository.saveAndFlush(Post.builder()
				.writerId(1L)
				.writerNickname("감사시각")
				.title("createdAt 소수부 확인")
				.content("D-11 회귀 테스트")
				.build());

		LocalDateTime createdAt = saved.getCreatedAt();

		assertThat(createdAt).isNotNull();
		assertThat(createdAt.getNano()).isZero();
		assertThat(createdAt).isEqualTo(createdAt.withNano(0));
	}

}
