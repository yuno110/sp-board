# board-service 구현 체크리스트

`B-xx`·`I-xx` 항목 **상태의 단일 원본**이다. 항목의 범위·완료 기준·검증은 `simple-docs/plan/phase1.md`(B-xx)와 `simple-docs/plan/integration.md`(I-xx)의 같은 ID를 본다.

줄 형식: `- [ ] <ID> <이름> · 상태 <todo|doing|review|blocked|done> · 커밋 <해시 또는 ->`
`blocked`는 줄 끝에 `· 사유: ...`를 붙인다. 사유는 다음 작업자가 이어받을 수 있을 만큼 구체적으로 적는다.

절차는 `simple-docs/process/dev-workflow.md`를 따른다.

**여러 워커가 이 파일을 고칠 수 있다.** 자기 항목의 줄만 수정하고, 파일을 재정렬하거나 다른 줄을 건드리지 않는다 (`simple-docs/process/orchestration.md` §6).

## 기반 단계 (순차)

뒤의 모든 항목이 의존한다. 순서대로 진행한다.

- [ ] B-01 프로젝트 스캐폴딩 · 상태 todo · 커밋 -
- [ ] B-02 공통 기반 · 상태 todo · 커밋 -
- [ ] B-03 도메인 기반 · 상태 todo · 커밋 -
- [ ] B-04 보안 기반 · 상태 todo · 커밋 -

## 기능 단계

기반 산출물을 읽기만 하고 자기 파일을 만든다. 기반 경로의 파일을 고쳐야 하면 BLOCKED로 보고한다.

- [ ] B-05 게시글 작성·상세 조회 · 상태 todo · 커밋 -
- [ ] B-06 게시글 목록과 QueryDSL 검색 · 상태 todo · 커밋 -
- [ ] B-07 게시글 수정·삭제와 권한 · 상태 todo · 커밋 -
- [ ] B-08 댓글 · 상태 todo · 커밋 -
- [ ] B-09 내가 쓴 글과 마무리 · 상태 todo · 커밋 -

## 통합 검증

**M-11과 B-09가 모두 done이 된 뒤에 시작한다.** 두 서비스가 기동된 상태를 전제하므로 순차로 진행한다.

- [ ] I-01 실제 키 교환과 E2E · 상태 todo · 커밋 -
- [ ] I-02 장애 격리 · 상태 todo · 커밋 -
- [ ] I-03 시간대 정합 · 상태 todo · 커밋 -
- [ ] I-04 응답 형식 계약 일치 · 상태 todo · 커밋 -

## 선행 조건

B-01을 시작하기 전에 아래가 준비되어야 한다. 준비되지 않았으면 `blocked`로 두고 보고한다.

- [ ] MySQL 8.0 로컬 설치, `board_db` 스키마 생성 (`simple-docs/tech-stack.md` §4.1)
- [ ] 문서 저장소 클론 (`../simple-docs`)
