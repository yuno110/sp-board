package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 2차 마이그레이션이 <b>그 전에 있던 행</b>을 어떻게 남기는지 본다 (sp-docs/plan/phase2-board.md
 * B-14 의 검증 표, sp-docs/domain-model.md §4.1·§4.2·§7).
 *
 * <p>테스트 DB 는 컨텍스트가 뜰 때 Flyway 가 끝까지 적용한 뒤라 "V2 까지의 스키마에 넣은 행" 을
 * 만들 수 없다. 그래서 이 클래스는 <b>자기 DB 를 따로 만들고</b> Flyway 의 {@code target} 으로 두
 * 번에 나눠 적용한다 — V2 까지 적용, 행을 넣음, 나머지 적용.
 *
 * <p>URL 은 sp-docs/tech-stack.md §5.3 의 정본에서 <b>스키마 이름과 {@code DB_CLOSE_DELAY} 만</b>
 * 다르다. 이름이 달라 다른 클래스가 보는 {@code sp_board} 를 건드리지 않는다. {@code DB_CLOSE_DELAY}
 * 를 빼고 테스트 동안 커넥션 하나를 쥐고 있어 DB 를 유지한다 — 그 커넥션이 닫히면 DB 가 사라지므로
 * 지우는 문장 없이 정리된다. 나머지 파라미터는 마이그레이션이 정본과 같은 조건에서 돌게 둔다.
 */
class MigrationBackfillTest {

	private static final String URL = "jdbc:h2:mem:sp_board_backfill;MODE=MySQL;"
			+ "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

	private static final String USER = "sa";

	private static final String PASSWORD = "";

	@Test
	@DisplayName("V2 까지의 스키마에 넣은 행은 나머지를 적용한 뒤 FREE·0·0 이고 그 글의 댓글 parent_id 는 NULL 이다")
	void V2_까지의_행이_나머지_적용_뒤_기본값을_갖는다() throws Exception {
		try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
			Flyway untilV2 = flyway().target("2").load();
			untilV2.migrate();
			assertThat(untilV2.info().current().getVersion().getVersion())
					.as("V2 에서 멈춰야 그 다음에 넣는 행이 '그 전에 있던 행' 이다")
					.isEqualTo("2");

			long postId = insertPost(connection);
			long commentId = insertComment(connection, postId);

			Flyway rest = flyway().load();
			rest.migrate();
			assertThat(rest.info().pending()).as("나머지가 모두 적용됐다").isEmpty();

			try (PreparedStatement select = connection.prepareStatement(
					"select category, like_count, attachment_count from post where id = ?")) {
				select.setLong(1, postId);
				try (ResultSet rs = select.executeQuery()) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getString("category")).isEqualTo("FREE");
					assertThat(rs.getInt("like_count")).isZero();
					assertThat(rs.getInt("attachment_count")).isZero();
				}
			}
			try (PreparedStatement select = connection.prepareStatement(
					"select parent_id from comment where id = ?")) {
				select.setLong(1, commentId);
				try (ResultSet rs = select.executeQuery()) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getObject("parent_id")).as("1차의 댓글은 모두 최상위다").isNull();
				}
			}
		}
	}

	private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
		return Flyway.configure().dataSource(URL, USER, PASSWORD);
	}

	private static long insertPost(Connection connection) throws Exception {
		try (PreparedStatement insert = connection.prepareStatement(
				"insert into post (writer_id, writer_nickname, title, content, created_at, updated_at) "
						+ "values (971, '이전작성자', '이전 글', '본문', current_timestamp, current_timestamp)",
				Statement.RETURN_GENERATED_KEYS)) {
			insert.executeUpdate();
			return generatedId(insert);
		}
	}

	private static long insertComment(Connection connection, long postId) throws Exception {
		try (PreparedStatement insert = connection.prepareStatement(
				"insert into comment (post_id, writer_id, writer_nickname, content, created_at, updated_at) "
						+ "values (?, 972, '이전댓글작성자', '이전 댓글', current_timestamp, current_timestamp)",
				Statement.RETURN_GENERATED_KEYS)) {
			insert.setLong(1, postId);
			insert.executeUpdate();
			return generatedId(insert);
		}
	}

	private static long generatedId(PreparedStatement insert) throws Exception {
		try (ResultSet keys = insert.getGeneratedKeys()) {
			assertThat(keys.next()).isTrue();
			return keys.getLong(1);
		}
	}

}
