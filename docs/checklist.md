# board-service 구현 체크리스트

`B-xx`·`I-xx` 항목 **상태의 단일 원본**이다. 항목의 범위·완료 기준·검증은 `sp-docs/plan/phase1.md` **§6**(B-xx)와 `sp-docs/plan/integration.md`(I-xx)의 같은 ID를 본다.

줄 형식: `- [ ] <ID> <이름> · 상태 <todo|doing|review|blocked|done> · 커밋 <해시 또는 ->`
`blocked`는 줄 끝에 `· 사유: ...`를 붙인다. 사유는 다음 작업자가 이어받을 수 있을 만큼 구체적으로 적는다.

절차는 `sp-docs/process/dev-workflow.md`를 따른다.

## 상태

| 상태 | 의미 |
| --- | --- |
| `todo` | 시작 전 |
| `doing` | 구현·테스트 중 |
| `review` | **테스트 통과, 리뷰 사이클 안** (리뷰 중이거나 수정 중) |
| `done` | 리뷰 APPROVED + 커밋·푸시 완료 |
| `blocked` | 진행 불가. 사유를 구체적으로 적는다 |

**`doing`에서 곧바로 `done`으로 가지 않는다.** 리뷰를 거치지 않은 항목은 완료가 아니다.
2라운드 이상이면 줄 끝에 `· 리뷰 2라운드`를 붙인다.

**여러 워커가 이 파일을 고칠 수 있다.** 자기 항목의 줄만 수정하고, 파일을 재정렬하거나 다른 줄을 건드리지 않는다 (`sp-docs/process/orchestration.md` §6).

## 기반 단계 (순차)

뒤의 모든 항목이 의존한다. 순서대로 진행한다.

- [x] B-01 프로젝트 스캐폴딩 · 상태 done
- [ ] B-02 공통 기반 · 상태 todo
- [ ] B-03 도메인 기반 · 상태 todo
- [ ] B-04 보안 기반 · 상태 todo

> **B-04에 `MemberClient`가 추가됐다.** JWT Claim에서 `nickname`이 빠져 글·댓글 생성 시 member의 내부 API를 호출해야 한다 (`sp-docs/adr/0012` §6). `M-10`을 기다리지 않고 **스텁으로 테스트**한다.
>
> **`LoginMember`는 `(accountId, role)`이다.** `nickname` 필드가 없다.

## 기능 단계

기반 산출물을 읽기만 하고 자기 파일을 만든다. 기반 경로의 파일을 고쳐야 하면 BLOCKED로 보고한다.

- [ ] B-05 게시글 작성·상세 조회 · 상태 todo
- [ ] B-06 게시글 목록과 QueryDSL 검색 · 상태 todo
- [ ] B-07 게시글 수정·삭제와 권한 · 상태 todo
- [ ] B-08 댓글 · 상태 todo
- [ ] B-09 내가 쓴 글과 마무리 · 상태 todo

> **B-05·B-08은 `MemberClient`를 호출한다.** 생성 경로에서만이다. B-06(목록)·B-07(수정·삭제)은 호출하지 않는다.

## 통합 검증

**AU-11·M-11·B-09가 모두 done이 된 뒤에 시작한다.** 세 서비스가 기동된 상태를 전제하므로 순차로 진행한다.

- [ ] I-01 실제 키 교환과 E2E · 상태 todo
- [ ] I-02 장애 격리 · 상태 todo
- [ ] I-03 시간대 정합 · 상태 todo
- [ ] I-04 응답 형식 계약 일치 · 상태 todo
- [ ] I-05 탈퇴 부분 실패 · 상태 todo

> **I-02의 기대 결과가 바뀌었다.** "member 중지 중 게시글 작성 → 201"이 **"→ 503 `S001`"**이 됐다. 계획된 변경이지 결함이 아니다 (`sp-docs/adr/0012`). auth 중지 시나리오도 추가됐다.

## 선행 조건

B-02를 시작하기 전에 아래가 준비되어야 한다. 준비되지 않았으면 `blocked`로 두고 보고한다.

- [x] **`D-01`(정본 개정)이 `done`이다** (`sp-docs/docs/checklist.md`)
- [x] MySQL 8.0 로컬 설치, `sp_board` 스키마 생성, `SET PERSIST time_zone='+09:00'` (`sp-docs/tech-stack.md` §4.1)
- [x] `application-local.yml` 생성하고 MySQL 비밀번호 기입 (`sp-docs/tech-stack.md` §4.3.1)
- [x] `application-local.yml`에 `INTERNAL_API_KEY` 추가 (member와 같은 값)
- [x] **공개키** `jwt-public.pem`을 `src/main/resources/`에 배치 — **실제 키다.** 개인키는 두지 않는다

> **B-04는 테스트용 키 페어를 만들 필요가 없다.** 실제 공개키가 이미 배치되어 있다. `MemberClient`는 여전히 스텁으로 테스트한다 — M-10을 기다리지 않는다.
- [x] 문서 저장소 클론 (`../sp-docs`)
