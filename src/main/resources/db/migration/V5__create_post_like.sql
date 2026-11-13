-- 정본: sp-docs/domain-model.md §4.4 (컬럼), §4.3 (인덱스·제약), §7 (할 것)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase2-board.md §2.2)
--
-- 행 하나가 "이 회원이 이 글을 눌렀다" 이다. 취소하면 행을 지운다(물리 삭제) — 갱신할 것이 없으므로
-- updated_at 이 없다 (sp-docs/domain-model.md §4.4).
--
-- account_id 는 sp_auth.account.id 의 논리 참조다. 스키마 경계를 넘으므로 FK 를 두지 않는다
-- (sp-docs/domain-model.md §4.3·§4.4). post_id 는 같은 스키마이므로 FK 를 둔다.
--
-- uk_post_like_post_account 가 회원당 글 하나에 한 번을 보장하는 최종 판정이다 (§4.4, §6.1).
-- post_id 가 앞에 오므로 FK 의 인덱스를 겸한다 (§4.3).
CREATE TABLE post_like
(
    -- created_at: @CreatedDate 가 채운다. 감사 시각과 같은 초 자르기를 받는다 (§1.1, §4.4)
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    post_id    BIGINT   NOT NULL,
    account_id BIGINT   NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_post_like_post_account UNIQUE (post_id, account_id),
    CONSTRAINT fk_post_like_post_id FOREIGN KEY (post_id) REFERENCES post (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
