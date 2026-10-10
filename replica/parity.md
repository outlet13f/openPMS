# 패리티 보고서 (openPMS ↔ OpenProject)

- 작성: 2026-10-10 (`/replica-diff`)
- 기준: `replica/features.csv`(이번 점검에서 3행 수정), `replica/screens/`(원본 17.9.1, 사용자 인스턴스) ↔ `replica/clone-screens/`(가짜 계층, 시드 데이터)
- 도구: `parity.py`, `imgdiff.py`(레이아웃 모드). 결과 이미지·JSON은 `replica/diffs/`
- 설계·구현 대조와 원본 대비 제외 기능은 `replica/review-2026-10-10.md`에 따로 적었습니다.

## 결론

| 항목 | 값 |
| --- | --- |
| **기능 패리티** | **84.0 / 100** (점검 전 82.5. 이미 만든 다크 테마·이탈 경고가 `no`로 남아 있던 것을 고침) |
| must | **64 / 64** 완료 |
| should | 48개 중 yes 30 · partial 6 · no 12 |
| could | 28개 중 yes 11 · partial 2 · no 15 (skip 20개는 집계 제외) |
| 레이아웃 점수 | 27개 화면 평균 **38 / 100** ("different"). 아래 해석 참고 |
| 판정 | **아직 출시 불가(not shippable)** — must는 다 있지만 (1) 초대받은 사용자가 UI로 계정을 활성화할 수 없고(S01 초대 수락 화면이 준비 중), (2) `/replica-test`를 아직 돌리지 않아 열린 S1·S2 버그가 몇 개인지 모릅니다 |

## 영역별 기능 점수 (약한 순)

| 영역 | 점수 | 비고 |
| --- | --- | --- |
| 내보내기 | 0 → 진행 중 | CSV·XLSX는 미커밋 작업으로 거의 완성. PDF 없음 |
| 연동 | 0 | REST API 토큰·웹훅 없음(could) |
| 진행률 | 33 | 계산 모드·계층 합계·닫힘 100% 옵션(S32) 없음 |
| 보드 | 58 | 기본 보드·보드 필터·열 추가/순서·하위 프로젝트·상위-하위 보드 없음 |
| 인증 | 70 | OIDC SSO 없음, 초대 수락 화면 없음 |
| 프로젝트 | 74 | 템플릿·복사·복원·삭제·상위 프로젝트 지정 없음 |
| Gantt | 78 | 날짜 없는 항목에 막대 그리기, 라벨 위치, 전역 Gantt 없음 |
| 공통 | 76 | 날짜·시간 형식 설정, 키보드 단축키, 고대비 테마 없음 |
| 멤버·권한 | 83 | 글로벌 역할 배정 UI, 권한 보고서 없음 |
| 목록·쿼리 | 88 | 그룹화·합계, 기준선, 강조 표시 없음 |
| 작업 패키지 | 89 | 일괄 편집·복사·이동, 내부 코멘트, 활동 필터 없음 |
| 관리 | 89 | S28 양식 구성, S32 진행률 설정 없음 |
| 알림 | 92 | 이유별·프로젝트별 필터 없음 |
| 일정 | 93 | 날짜 대화상자의 선행·후속·하위 탭 없음 |
| 계정 · 관계·계층 · 캘린더 · 팀 플래너 · 검색 · 홈 | 100 | |

## 빠진 기능 — 만들 순서

should부터, 같은 등급이면 영역 점수가 낮은 순입니다.

| # | 등급 | 기능 | 지금 상태 | 메모 |
| --- | --- | --- | --- | --- |
| 1 | should | CSV·XLS 내보내기 | 진행 중(미커밋) | 수식 주입 방어·테스트·문서 갱신 후 커밋 |
| 2 | should | 이메일 초대 수락 화면 | partial | **출시 차단.** 백엔드 `GET/POST /auth/invitations/{token}/accept`는 있음. `Api`에 메서드 추가 + S01 화면 |
| 3 | should | 진행률 계산 모드(S32) | no | `progress.mode`·`recalc-progress` 작업 |
| 4 | should | 기본(수동) 보드 · 보드 필터 · 열 추가/순서 | no | `PATCH /boards/{id}`, `/boards/{id}/columns`, `/board-columns/{id}` 3개 경로가 설계에만 있음 |
| 5 | should | 하위 프로젝트·상위-하위 보드 | partial | 선택지는 비활성으로 노출 중 |
| 6 | should | 사내 SSO(OIDC) | no | `users.password_hash` nullable로 스키마는 준비됨 |
| 7 | should | 프로젝트 복원·삭제 | partial | `POST …/unarchive`, `DELETE /projects/{p}` 미구현 |
| 8 | should | 템플릿에서 프로젝트 생성 · 상위 프로젝트 지정 | no / partial | S04 선택지 비활성 |
| 9 | should | Gantt 날짜 없는 항목에 막대 그리기 | no | |
| 10 | should | 날짜·시간 형식과 주 시작 요일 설정 | no | `/admin/settings/{section}` 중 calendar만 있음 |
| 11 | should | 그룹화와 그룹별 합계 | no | 설계 §3.5 실행 순서 6·7 |
| 12 | should | 일괄 편집·이동·복사·삭제 · 작업 복사·이동 | no | `/work-packages/{n}/copy`·`move`·`/bulk` 미구현 |
| 13 | should | 알림 이유별·프로젝트별 필터 | no | |
| 14 | should | 날짜 대화상자 선행·후속·하위 탭 | no | |
| 15 | should | 글로벌 역할을 사람에게 배정하는 UI | partial | `global_role_assignments` 테이블은 있음 |
| 16 | should | 보드 열에서 바로 카드 생성(상태 미리 지정) | partial | |
| — | could | 전역 Gantt | no | **`/gantt`가 준비 중인데 사이드바·빠른 메뉴에 링크돼 있음.** 만들거나 링크를 빼야 함 |
| — | could | PDF 내보내기, 기준선, 강조 표시, 합계 행, 내부 코멘트, 활동 필터, 키보드 단축키, 고대비, 양식 구성(S28), 권한 보고서, 프로젝트 복사, REST API 토큰, 웹훅, 계층 합계, 닫힘 100% | no | |

## 레이아웃 비교 (화면별)

원본은 사용자 인스턴스의 실제 데이터, 클론은 시드 데이터라 **같은 상태가 아닙니다**(행 수·텍스트 길이·열린 패널이 다름). 그래서 점수는 "정보가 같은 자리에 있는가"의 참고치이며, 리브랜드로 색이 바뀐 것은 점수에 들어가지 않습니다. 원본과 같은 데이터 모양으로 다시 찍으면 올라갑니다. 20·37·22 화면(내 페이지·계정 설정·검색)은 클론 캡처가 없어 비교하지 못했습니다.

| 화면 | 원본 | 클론 | 점수 | 판정 |
| --- | --- | --- | --- | --- |
| S15 보드(칸반) | 11-boards-kanban | S15 | 54.9 | partly |
| S07 분할 상세 | 06-wp-split | S07 | 53.7 | partly |
| S09 새 작업 | 08-wp-new | S09 | 50.3 | partly |
| S06 작업 목록 | 04-wp-table | S06 | 47.3 | different |
| S23 멤버 | 15-members | S23 | 46.7 | different |
| S29 워크플로 | 16-admin-workflow | S29 | 45.4 | different |
| S10 날짜 대화상자 | 09-datepicker | S10 | 44.5 | different |
| S06 필터 | 05-wp-filters | S06-filters | 43.8 | different |
| S13 Gantt | 10-gantt | S13 | 42.5 | different |
| S26 버전 | 19b-project-settings-versions | S26 | 38.9 | different |
| S31 역할 | 24-admin-roles | S31 | 38.8 | different |
| S24 모듈 | 18-project-settings-modules | S24-modules | 38.0 | different |
| S14 보드 목록 | 11a-boards-list | S14 | 37.5 | different |
| S27 유형 | 22-admin-types | S27 | 37.1 | different |
| S03 프로젝트 목록 | 02-projects | S03 | 36.9 | different |
| S25 작업 설정 | 19-project-settings-wp | S25 | 36.1 | different |
| S08 관계 | 07b-wp-relations | S08-relations | 36.0 | different |
| M1 모바일 목록 | 29-mobile-wp-list | S06-mobile | 35.4 | different |
| S05 프로젝트 개요 | 03-project-overview | S05 | 34.3 | different |
| M2 모바일 상세 | 30-mobile-wp-view | S07-mobile | 32.3 | different |
| S04 새 프로젝트 | 27-project-new | S04 | 32.2 | different |
| S08 전체 상세 | 07-wp-full | S08 | 30.9 | different |
| S30 상태 | 23-admin-statuses | S30 | 29.6 | different |
| S33 근무일 | 26-admin-working-days | S33 | 28.8 | different |
| S19 알림 | 14-notifications | S19 | 28.6 | different |
| S16 새 보드 | 11d-boards-new | S16 | 25.6 | different |
| S02 홈 | 01-home | S02 | 22.6 | different |

가장 낮은 S02(홈)·S16(새 보드)·S19(알림)는 원본이 넓은 위젯·카드 격자인데 클론은 한 열 목록이라 구조가 다릅니다. 전환하는 사용자가 익숙해지려면 S02·S19는 원본의 2열 구조에 가깝게 맞추는 편이 낫고, 나머지는 데이터 모양을 맞춘 뒤 다시 재야 의미가 있습니다.

## 동작 차이 (코드 조사로 확인한 것 — 브라우저 흐름 점검은 `/replica-test` 몫)

| 흐름 | 원본 | 클론 | 조치 |
| --- | --- | --- | --- |
| 초대 → 계정 활성화 | 메일 링크 → 비밀번호 설정 → 로그인 | 메일은 가지만 링크가 "준비 중" 화면 | **고침** (빠진 기능 #2) |
| 전역 Gantt | 모든 프로젝트 타임라인 | 사이드바 링크 → 준비 중 | 만들거나 링크 제거 |
| 내보내기 | PDF·XLS·CSV, 큰 파일은 백그라운드 | CSV·XLSX만, 5000행 초과 422 | 유지(사내 규모) + 설계 문서 갱신 |
| 첨부 | 작업 생성 중에도 첨부 가능 | 생성 후 파일 탭에서만 | 유지, 문서에 기록 |
| 작업 공유 | Enterprise 전용 | Community 범위에서 구현 | 유지 (원본보다 나은 점) |
| 팀 플래너 | Enterprise 전용 | 구현 | 유지 (원본보다 나은 점) |
| 주시 해제 | — | `DELETE …/watchers/me` 별칭 | 유지 |
| 관계 lag 수정 | 관계 편집 | `PATCH /relations/{id}` 없음, lag 입력 UI 없음 | should로 올릴지 결정 |

## 다음에 만들 다섯 가지

1. **S01 초대 수락·비밀번호 재설정 화면** — 백엔드는 있고 화면만 없는데, 없으면 새 배포에서 아무도 못 들어옵니다.
2. **S12 내보내기 마무리** — 수식 주입 방어(`=`·`+`·`-`·`@` 앞에 `'`), 전역 목록의 공유 작업 포함, 테스트, 커밋.
3. **S32 진행률 계산 모드 + 계층 합계** — 설계 P2–P5가 통째로 없음. 진행률 영역 33점.
4. **보드 마무리** — 기본 보드, 열 추가·순서, 필터. 설계 경로 3개 추가 구현.
5. **전역 Gantt** 또는 링크 제거 — 준비 중 화면으로 가는 링크는 버그로 보입니다.

그다음 `/replica-test`로 S1·S2 버그 수를 확정하고, 패리티가 자리 잡으면 `/replica-entrepreneur`로 "원본보다 나은 점"을 정합니다.
