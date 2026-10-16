-- 정본: sp-docs/domain-model.md §4.2 (컬럼), §4.3 (인덱스)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase1.md §2.4)
--
-- post_id 는 같은 스키마(sp_board)이므로 FK 를 둔다. §4 의 ERD 가 post_id 를 FK 로 표시하고
-- §4.2 가 @ManyToOne(LAZY) 연관관계 매핑을 쓰게 한다.
-- writer_id 는 sp_auth.account.id 의 논리 참조다 — 스키마 경계를 넘으므로 FK 를 두지 않는다
-- (sp-docs/requirements/board.md §3 규칙 4).
--
-- content 는 입력 규칙이 1~500자인데 컬럼이 VARCHAR(500) 으로 여유가 없다
-- (sp-docs/requirements/board.md §4). ddl-auto: validate 는 폭을 보지 않으므로
-- CommentRepositoryTest 가 information_schema 의 실제 폭과 엔티티 선언을 대조한다.
CREATE TABLE comment
(
    -- deleted: 논리 삭제. 행을 남겨 "삭제된 댓글입니다" 로 표시한다
    -- (sp-docs/requirements/board.md §5 규칙 2)
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    post_id         BIGINT       NOT NULL,
    writer_id       BIGINT       NOT NULL,
    writer_nickname VARCHAR(30)  NOT NULL,
    content         VARCHAR(500) NOT NULL,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comment_post_id FOREIGN KEY (post_id) REFERENCES post (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 게시글별 댓글 목록. FK 가 요구하는 post_id 선행 인덱스도 이것이 겸한다
CREATE INDEX idx_comment_post_id ON comment (post_id, created_at);
