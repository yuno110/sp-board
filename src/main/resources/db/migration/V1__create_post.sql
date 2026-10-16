-- 정본: sp-docs/domain-model.md §4.1 (컬럼), §4.3 (인덱스)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase1.md §2.4)
--
-- writer_id 는 sp_auth.account.id 의 논리 참조다. 스키마 경계를 넘으므로 FK 를 두지 않는다
-- (sp-docs/requirements/board.md §3 규칙 4, sp-docs/architecture.md §5).
-- writer_nickname 은 작성 시점 닉네임의 스냅샷이다. member 를 조인하지 않는다 (§3).
--
-- 폭에 여유가 없다. title 은 입력 규칙이 1~200자인데 컬럼이 VARCHAR(200) 이다
-- (sp-docs/requirements/board.md §4). ddl-auto: validate 는 이 폭을 보지 않으므로
-- PostRepositoryTest 가 information_schema 의 실제 폭과 엔티티 선언을 대조한다
-- (sp-docs/tech-stack.md §5.2).
CREATE TABLE post
(
    -- content: TEXT 다. 입력 규칙 1~10,000자가 utf8mb4 최악 4바이트로도 TEXT(65,535바이트)에 들어간다
    -- view_count / comment_count: 기본값 0. comment_count 는 목록 N+1 방지용 비정규화다
    -- deleted: 논리 삭제. 삭제된 글은 목록·상세에서 제외한다 (requirements/board.md §5 규칙 1)
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    writer_id       BIGINT       NOT NULL,
    writer_nickname VARCHAR(30)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,
    view_count      BIGINT       NOT NULL DEFAULT 0,
    comment_count   INT          NOT NULL DEFAULT 0,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 최신순 목록
CREATE INDEX idx_post_created_at ON post (created_at DESC);
-- 내가 쓴 글, 2차 Kafka 이벤트의 UPDATE post SET writer_nickname=? WHERE writer_id=?
CREATE INDEX idx_post_writer_id ON post (writer_id);
-- 제목 앞부분 검색. LIKE '%키워드%' 는 돕지 않는다 (sp-docs/domain-model.md §4.3)
CREATE INDEX idx_post_title ON post (title);
