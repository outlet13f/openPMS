package com.openpms.notification;

import com.openpms.common.Dates;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.db.tables.records.NotificationsRecord;
import com.openpms.wp.Journals;
import java.util.Map;
import org.springframework.stereotype.Component;

/** One line per notification, from the recipient's point of view (ctx is the recipient). Used by the inbox and the mails. */
@Component
public class NotificationTexts {
    private static final Map<String, String> REASON_TEXT = Map.of(
            "mentioned", "%s님이 코멘트에서 나를 언급했습니다",
            "assigned", "%s님이 내가 담당한 작업을 바꿨습니다",
            "responsible", "%s님이 내가 책임자인 작업을 바꿨습니다",
            "watched", "%s님이 주시 중인 작업을 바꿨습니다",
            "shared", "%s님이 나에게 작업을 공유했습니다",
            "commented", "%s님이 코멘트를 남겼습니다",
            "status_changed", "%s님이 상태를 바꿨습니다",
            "priority_changed", "%s님이 우선순위를 바꿨습니다",
            "dates_changed", "%s님이 날짜를 바꿨습니다");

    private final Journals journals;

    public NotificationTexts(Journals journals) {
        this.journals = journals;
    }

    private static boolean listed(Object list, java.util.UUID userId) {
        return list instanceof java.util.List<?> l && l.stream().anyMatch(x -> x instanceof java.util.Map<?, ?> m && userId.toString().equals(String.valueOf(m.get("userId"))));
    }

    public String summary(Ctx ctx, NotificationsRecord n, JournalsRecord j, Snapshot.ProjectRow project) {
        var day = n.getAlertDate() == null ? "" : Dates.format(n.getAlertDate());
        switch (n.getReason()) {
            case "date_alert_start" -> { return "시작일이 다가옵니다 · " + day; }
            case "date_alert_due" -> { return "완료일이 다가옵니다 · " + day; }
            case "date_alert_overdue" -> { return "기한이 지났습니다 · 완료일 " + day; }
            default -> { }
        }
        var actor = n.getActorId() == null ? "누군가" : Refs.user(ctx.s(), n.getActorId()).name();
        if (j != null && "assigned".equals(n.getReason())) {
            var change = journals.changesOf(j).get("assigneeId");
            if (change != null && change.size() > 1 && ctx.userId().toString().equals(String.valueOf(change.get(1)))) return actor + "님이 나를 담당자로 지정했습니다";
            // 공동 담당자: added to the list (not as the main assignee)
            var team = journals.changesOf(j).get("assignees");
            if (team != null && team.size() > 1 && listed(team.get(1), ctx.userId()) && !listed(team.get(0), ctx.userId())) return actor + "님이 나를 공동 담당자로 지정했습니다";
        }
        var text = String.format(REASON_TEXT.getOrDefault(n.getReason(), "작업이 바뀌었습니다"), actor);
        if (j != null && !"mentioned".equals(n.getReason())) {
            var dto = journals.toDto(ctx, j, project);
            var d = dto.details().stream().filter(x -> !x.field().equals(Journals.CREATED)).findFirst().orElse(null);
            if (d != null) text += " · " + d.label() + (d.to() != null ? ": " + (d.from() == null ? "없음" : d.from()) + " → " + d.to() : "");
            else if (journals.changesOf(j).containsKey(Journals.CREATED)) text = actor + "님이 작업을 만들었습니다";
        }
        return text;
    }
}
