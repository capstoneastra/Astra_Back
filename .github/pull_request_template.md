## 관련 이슈
- close #

## 작업 내용
- 

## 확인한 것
- [ ] `./gradlew build` 통과 (테스트 포함)
- [ ] Swagger에서 직접 호출해서 정상 응답 확인
- [ ] 권한별로 한 번씩 확인 (기업 / 기관 담당자 / 관리자)

## 다른 사람에게 영향이 있는 변경 (해당하면 체크하고 채팅에 공지)
- [ ] API 요청·응답 형식 또는 enum 값 변경: `docs/api-spec.md` 수정함
- [ ] DB 변경: 새 마이그레이션 `V__` 추가 + `docs/design/schema.sql` 수정함
- [ ] `global/` 공통 코드 변경
- [ ] 의존성 추가·버전 변경 (`build.gradle`)
