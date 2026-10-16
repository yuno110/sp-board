package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.env.Environment;

/**
 * 테스트 실행 환경 자체를 검증한다. B-03 의 검증 표에서 환경에 대한 것들을 본다 — 실행 중
 * DataSource URL, 저장된 테이블명, 스키마 출처 (sp-docs/plan/phase1.md §6 B-03).
 *
 * <p>이것들은 조용히 어긋나는 것들이라 개별 테스트가 초록이어도 실제로는 검증이 꺼져 있을 수
 * 있다 (sp-docs/tech-stack.md §5).
 *
 * <p><b>{@code @ActiveProfiles} 를 일부러 붙이지 않았다.</b> {@code build.gradle} 의
 * {@code systemProperty 'spring.profiles.active', 'test'} 가 프로파일을 기본값으로 만들었는지
 * 확인하기 위해서다 (sp-docs/tech-stack.md §5.1 §5.2). 모든 클래스에 {@code @ActiveProfiles} 를
 * 붙여두면 그 한 줄을 지워도 스위트가 초록이고, opt-in 제거가 일하는지 확인할 길이 없다.
 *
 * <p>엔티티를 저장하지 않으므로 {@code @Import(JpaConfig.class)} 는 붙이지 않는다 — 감사가
 * 쓰이지 않는 테스트에 그 이유를 적용하지 않는다 (sp-docs/conventions.md §9.3).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TestDatabaseEnvironmentTest {

	/** sp-docs/tech-stack.md §5.3 의 정본. 스키마만 서비스별로 다르다. */
	private static final String CANONICAL_URL =
			"jdbc:h2:mem:sp_board;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Environment environment;

	@Test
	@DisplayName("테스트가 도는 DataSource URL 이 tech-stack.md §5.3 정본과 문자열까지 같다")
	void 정본_URL_로_돈다() {
		assertThat(dataSource)
				.as("@AutoConfigureTestDatabase(replace = NONE) 이 없으면 jdbc:h2:mem:<uuid> 로 교체된다")
				.isInstanceOf(HikariDataSource.class);

		assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(CANONICAL_URL);
	}

	@Test
	@DisplayName("테이블명이 MySQL 과 같이 소문자 post·comment 로 저장된다")
	void 테이블명이_소문자다() throws Exception {
		List<String> tables = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				ResultSet rs = connection.getMetaData()
						.getTables(null, null, "%", new String[] {"TABLE"})) {
			while (rs.next()) {
				tables.add(rs.getString("TABLE_NAME"));
			}
		}

		assertThat(tables)
				.as("DATABASE_TO_LOWER 가 빠지면 POST·COMMENT 로 대문자 저장된다")
				.contains("post", "comment")
				.doesNotContain("POST", "COMMENT");
	}

	/**
	 * 스키마 출처를 Flyway 하나로 고정한다 (sp-docs/tech-stack.md §5.1).
	 *
	 * <p>{@code ddl-auto} 가 사라지면 임베디드 기본값 {@code create-drop} 이 Flyway 가 만든
	 * 테이블을 지우고 엔티티에서 다시 만든다. 그러면 {@code information_schema} 가 엔티티의
	 * 길이를 돌려주므로 <b>폭 비교가 엔티티를 자기 자신과 견주는 것</b>이 되어 공허하게
	 * 통과한다 (sp-docs/tech-stack.md §5.2). 그래서 폭 테스트와 짝으로 여기서 출처를 본다.
	 *
	 * <p>엔티티에만 있는 컬럼으로 기동이 실패하는지는 <b>완료 기준</b>이고 손으로 확인한다 —
	 * 테스트가 자기 엔티티를 바꿀 수 없다 (sp-docs/process/review-policy.md §5.3).
	 */
	@Test
	@DisplayName("스키마는 Flyway 가 만들고 ddl-auto 는 validate 다")
	void 스키마_출처가_Flyway_하나다() throws Exception {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
				.as("application-test.yml 의 ddl-auto 가 실제 컨텍스트에 실려 있다")
				.isEqualTo("validate");

		List<String> applied = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("select version from flyway_schema_history "
						+ "where success = true order by installed_rank")) {
			while (rs.next()) {
				applied.add(rs.getString("version"));
			}
		}

		assertThat(applied)
				.as("V1__create_post.sql 과 V2__create_comment.sql 이 두 테이블을 만든다")
				.contains("1", "2");
	}

}
