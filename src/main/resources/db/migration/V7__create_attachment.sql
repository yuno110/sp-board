-- 정본: sp-docs/domain-model.md §4.6 (컬럼), §4.3 (인덱스·제약), §7 (할 것)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase2-board.md §2.2)
--
-- storage_key 는 서버가 만든 UUID 문자열이다. 원본 파일명은 저장 경로에 들어가지 않고 메타데이터로만
-- 쓴다 — 경로 탐색 차단 (sp-docs/domain-model.md §4.6).
--
-- 폭에 여유가 없다. original_filename 은 규칙과 폭이 같다(255). ddl-auto: validate 는 폭을 보지
-- 않으므로 AttachmentRepositoryTest 가 information_schema 의 실제 폭과 엔티티 선언을 대조한다
-- (§4.6, sp-docs/tech-stack.md §5.2).
--
-- 인덱스를 FK 보다 먼저 만든다. MySQL 은 FK 를 걸 때 그 컬럼이 앞에 오는 인덱스가 없으면 인덱스를
-- 따로 만든다. idx_attachment_post_id 가 먼저 있으면 그것이 FK 의 인덱스를 겸한다 (§4.3).
CREATE TABLE attachment
(
    -- content_type: 매직 바이트로 판정한 형식 (sp-docs/requirements/board.md §11)
    -- deleted: 첨부 삭제. 행을 deleted = true 로만 바꾸고 파일은 지우지 않는다 (§4.6)
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    post_id           BIGINT       NOT NULL,
    storage_key       VARCHAR(36)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(30)  NOT NULL,
    size              BIGINT       NOT NULL,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_attachment_storage_key UNIQUE (storage_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 상세의 첨부 목록
CREATE INDEX idx_attachment_post_id ON attachment (post_id);

ALTER TABLE attachment
    ADD CONSTRAINT fk_attachment_post_id FOREIGN KEY (post_id) REFERENCES post (id);
