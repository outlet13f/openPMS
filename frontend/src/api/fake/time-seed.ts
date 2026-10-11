// 시간 기록 seed for the fake layer (no imports of the database: seed.ts builds it).
import type { Id, IsoDate, TimeActivity } from "../types";

export interface DbTimeEntry {
  id: Id;
  userId: Id;
  projectId: Id;
  wpId: Id;
  activityId: Id | null;
  spentOn: IsoDate;
  hours: number;
  comment: string;
  createdAt: string;
}

export function seedTime(): { timeActivities: TimeActivity[]; timeEntries: DbTimeEntry[] } {
  const names = ["개발", "설계", "회의", "테스트", "문서", "관리", "기타"];
  const timeActivities = names.map((name, i) => ({ id: `ta-${i + 1}`, name, position: i + 1, isDefault: i === 0, active: true }));
  const act = (name: string) => `ta-${names.indexOf(name) + 1}`;
  const rows: [string, number, string, string, number, string][] = [
    ["u-seoyeon", 1, "설계", "2026-10-05", 3, "릴리스 범위 정리"],
    ["u-seoyeon", 3, "회의", "2026-10-05", 1.5, "화면 설계 검토 회의"],
    ["u-seoyeon", 3, "설계", "2026-10-06", 4, ""],
    ["u-seoyeon", 12, "개발", "2026-10-07", 6, "요약 메일 초안"],
    ["u-seoyeon", 1, "관리", "2026-10-08", 2, ""],
    ["u-haneul", 9, "개발", "2026-10-05", 7, "키보드 이동 구현"],
    ["u-haneul", 9, "테스트", "2026-10-06", 3.5, ""],
    ["u-haneul", 4, "개발", "2026-10-06", 4.5, "결제 API 연동"],
    ["u-junho", 2, "문서", "2026-10-05", 2, "요구사항 정리"],
    ["u-junho", 4, "개발", "2026-10-07", 8, ""],
    ["u-junho", 13, "개발", "2026-10-08", 5, "배포 스크립트"],
  ];
  const timeEntries = rows.map(([userId, n, a, spentOn, hours, comment], i) => ({
    id: `te-${i + 1}`, userId, projectId: "p-web", wpId: `wp-${n}`, activityId: act(a), spentOn, hours, comment, createdAt: "2026-10-09T09:00:00Z",
  }));
  return { timeActivities, timeEntries };
}
