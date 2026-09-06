@AGENTS.md

## 이 저장소

`board-service`다. 게시글과 댓글을 담당한다. 기본 패키지는 `com.example.board`, 포트는 8082, DB는 `board_db`다.

## 정본 문서

정본은 **문서 저장소** `yuno110/simple-docs`에 있다. 이 저장소에는 정본을 두지 않는다.

```bash
# 없으면 클론한다
git clone https://github.com/yuno110/simple-docs.git ../simple-docs
```

진입점은 `simple-docs/README.md`이며, **"작업별 읽을 문서" 표에서 필요한 문서만 고른다. 전부 읽지 않는다.**

- 값(엔드포인트, 컬럼, 에러 코드, 버전)은 `api-contract.md`·`domain-model.md`·`tech-stack.md`에서만 가져온다
- 결정의 이유가 궁금하면 `adr/`를 본다. 구현 중에는 대개 필요 없다
- **구현 워커는 정본 문서를 수정하지 않는다.** 문서가 틀렸다고 판단되면 고치지 말고 BLOCKED로 보고한다

## 작업 시작

1. `docs/checklist.md`에서 상태가 `doing`인 항목을 찾는다. 있으면 그것을 이어서 한다
2. 없으면 순서상 다음 `todo`를 고른다. **의존 항목이 모두 `done`이어야 한다**
3. 고른 항목의 상태를 `doing`으로 바꾼다
4. `simple-docs/plan/phase1.md`에서 그 항목의 산출물·참조·완료 기준·검증을 읽는다
5. `simple-docs/process/dev-workflow.md`의 절차를 따른다

이 저장소가 담당하는 항목은 **B-01 ~ B-09**이고, 1차 완료 후 **I-01 ~ I-04**(통합 검증)도 여기서 추적한다. `M-xx`(member) 항목을 처리하지 않는다.

## 작업 규칙

- 구현 → 테스트 작성 → `./gradlew test` → 완료 기준 확인 → 커밋 → 상태 갱신. 이 순서를 건너뛰지 않는다
- 테스트 3회 실패 시 중단하고 BLOCKED로 보고한다. 계속 시도하지 않는다
- **실패하는 테스트를 `@Disabled`로 넘기거나 단언을 약화하지 않는다**
- 완료 기준을 전부 충족하지 못했으면 `done`이 아니다. `blocked`로 두고 사유를 구체적으로 적는다
- 산출물 목록 밖의 파일을 수정하지 않는다
- 금지 사항 전체는 `simple-docs/process/dev-workflow.md` §3에 있다

## 워크트리 작업

여러 워커가 동시에 이 저장소에서 일한다. 각자 별도 워크트리에서 작업하고 병합한다.

- **자기 워크트리 밖의 파일을 고치지 않는다**
- **산출물 목록 밖의 파일을 고치지 않는다.** 다른 워커의 작업과 충돌한다
- `docs/checklist.md`는 **자기 항목의 줄만** 수정한다. 파일을 재정렬하거나 다른 줄을 건드리지 않는다
- **자기 항목이 소유한 경로에만 쓴다.** 경로 소유 지도는 `simple-docs/plan/phase1.md` §2에 있다
- 공유 지점(`SecurityConfig`, `Service`, `Controller`, `build.gradle`)은 §2.3의 닫힌 목록이다. 동시에 수정하지 않는다
- 마이그레이션 버전 번호는 계획이 배정한다(§2.4). **스스로 정하지 않는다**
- 소유하지 않은 경로에 파일을 만들어야 하면 진행하지 말고 BLOCKED로 보고한다(§2.5)
- 다른 워커의 브랜치에 커밋하지 않는다

워크트리는 작업 중에만 격리한다. **같은 파일을 고치면 병합에서 충돌한다.**

## 이 서비스의 경계 — 중요

- **`member_db`를 조회하지 않는다.** 같은 MySQL 인스턴스에 있어도 크로스 스키마 조인 금지
- **`post.writer_id`에 FK 제약을 걸지 않는다**
- `@Transactional` 안에서 member-service를 호출하지 않는다
- member-service를 호출하지 않는다. 1차에서 호출 경로가 없다
- 시간대는 `Asia/Seoul`로 명시 설정한다

### 작성자 정보 — 스냅샷

작성자 닉네임은 member-service 소유 데이터이므로 조인할 수 없다. **JWT Claim에서 가져와 복제 저장한다.**

```
POST /api/v1/posts
  JWT Claim { sub: "3", nickname: "홍길동" }
    -> INSERT INTO post (writer_id, writer_nickname, ...) VALUES (3, '홍길동', ...)
```

- `writer_id`, `writer_nickname`은 **JWT Claim에서만** 가져온다. 요청 본문의 값을 쓰지 않는다
- 닉네임 변경이 과거 글에 반영되지 않는 것은 **의도된 동작**이다. 1차에서 해결하지 않는다

배경은 `simple-docs/adr/0003-writer-snapshot.md`에 있다.

### JWT 검증

- **검증만** 한다. RSA 공개키만 갖는다. 개인키를 이 저장소에 두지 않는다
- Spring Security `oauth2-resource-server`를 쓴다. **JWT 필터를 직접 만들지 않는다**
- B-03 단계에서는 **테스트용 키 페어**로 자체 검증한다. M-05를 기다리지 않는다. 계약이 `api-contract.md §5`에 확정되어 있다
- 실제 공개키 교체는 I-01(통합 검증)에서 한다

## 보안

- 공개키는 커밋해도 된다. **개인키는 어떤 경우에도 이 저장소에 두지 않는다**
- `.env`는 `.gitignore`에 먼저 넣는다
- 비밀 값은 환경변수로만 주입하고 기본값을 두지 않는다
- 토큰·키를 로그에 남기지 않는다

## 상태 갱신

작업을 끝내면 `docs/checklist.md`의 해당 줄을 `[x]`와 `done`으로 바꾸고 커밋 해시를 적는다. **상태를 갱신하지 않은 채 다음 항목으로 넘어가지 않는다.**

`B-xx`·`I-xx` 상태의 원본은 이 저장소의 `docs/checklist.md` 하나뿐이다. 다른 곳에 상태를 적지 않는다.
