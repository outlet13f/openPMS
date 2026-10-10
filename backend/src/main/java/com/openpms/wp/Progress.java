package com.openpms.wp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Progress rules (architecture.md §3.4 P1–P5, S32 진행률 추적), pure Java. frontend/src/api/fake/progress.ts holds the
 * same rules. mode "work": % is typed, or follows from work and remaining work; "status": % is the status's default.
 * closedSets100: a closed status means 100 % and nothing left.
 */
public final class Progress {
    private Progress() {}

    public record Settings(String mode, boolean closedSets100, String totals) {
        public static final Settings DEFAULT = new Settings("work", false, "weighted");
    }

    public record Values(Integer doneRatio, BigDecimal remainingHours) {}

    /** % and remaining work are not typed by hand in status mode, nor on a closed status with closedSets100. */
    public static boolean locked(Settings s, boolean statusClosed) {
        return "status".equals(s.mode()) || (s.closedSets100() && statusClosed);
    }

    private static BigDecimal remaining(BigDecimal work, int ratio) {
        return work.multiply(BigDecimal.valueOf(100 - ratio)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * What an item ends up with. typedRemaining: the person typed the remaining work (work mode: it sets the %);
     * typedOther: they typed work or % (work mode: the remaining work follows).
     */
    public static Values derive(Settings s, boolean statusClosed, int statusDefaultRatio, BigDecimal work, Integer ratio, BigDecimal remaining,
                                boolean typedRemaining, boolean typedOther) {
        if (s.closedSets100() && statusClosed) return new Values(100, work == null ? null : BigDecimal.ZERO.setScale(2));
        if ("status".equals(s.mode())) return new Values(statusDefaultRatio, work == null ? null : remaining(work, statusDefaultRatio));
        if (typedRemaining && work != null && work.signum() > 0 && remaining != null) {
            int r = work.subtract(remaining).multiply(BigDecimal.valueOf(100)).divide(work, 0, RoundingMode.HALF_UP).intValue();
            return new Values(r, remaining);
        }
        if (typedOther && work != null) return new Values(ratio, remaining(work, ratio == null ? 0 : ratio));
        return new Values(ratio, remaining);
    }

    public record Item(BigDecimal work, BigDecimal remaining, Integer ratio, boolean excluded) {}

    /**
     * P4/P5 over an item and its descendants (excluded statuses left out). For the weighted %, an item with work but
     * no remaining work recorded has done nothing yet (remaining = work), as the work mode would derive it.
     */
    public static com.openpms.api.Dto.Totals totals(Settings s, List<Item> items) {
        var counted = items.stream().filter(i -> !i.excluded()).toList();
        var works = counted.stream().filter(i -> i.work() != null).toList();
        BigDecimal work = works.isEmpty() ? null : works.stream().map(Item::work).reduce(BigDecimal.ZERO, BigDecimal::add);
        var rems = counted.stream().filter(i -> i.remaining() != null).toList();
        BigDecimal rem = rems.isEmpty() ? null : rems.stream().map(Item::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        Integer ratio;
        if ("weighted".equals(s.totals())) {
            var left = works.stream().map(i -> i.remaining() == null ? i.work() : i.remaining()).reduce(BigDecimal.ZERO, BigDecimal::add);
            ratio = work == null || work.signum() == 0 ? null
                    : work.subtract(left).multiply(BigDecimal.valueOf(100)).divide(work, 0, RoundingMode.HALF_UP).intValue();
        } else {
            var rs = counted.stream().filter(i -> i.ratio() != null).toList();
            ratio = rs.isEmpty() ? null : (int) Math.round(rs.stream().mapToInt(Item::ratio).average().orElse(0));
        }
        return new com.openpms.api.Dto.Totals(work == null ? null : work.stripTrailingZeros().doubleValue(),
                rem == null ? null : rem.stripTrailingZeros().doubleValue(), ratio);
    }
}
