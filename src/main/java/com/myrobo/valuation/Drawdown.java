package com.myrobo.valuation;

import com.myrobo.common.config.RoboConfig;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지수 이어 붙이기와 낙폭 경보 단계.
 *
 * <p>단계는 경보선을 넘은 개수다 (0 = 정상). 설정의 경보선(guard.drawdownAlerts) 다음에 목표의 최대 낙폭
 * 한도(goal.maxDrawdownPct)를 마지막 단계로 둔다. 경보선 근처에서 오르내릴 때 매일 경보·해제가 반복되지 않도록,
 * 단계를 내릴 때는 그 경보선보다 {@link #RELEASE_MARGIN_PCT}%p 더 회복해야 한다.
 */
public final class Drawdown {

    public static final BigDecimal START_NAV = BigDecimal.valueOf(100);
    static final BigDecimal RELEASE_MARGIN_PCT = BigDecimal.valueOf(2);
    /** 목표의 최대 낙폭 한도 단계의 동작 이름. */
    public static final String LIMIT = "LIMIT";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Drawdown() {
    }

    /** 경보 단계 하나. level은 1부터. */
    public record Level(int level, BigDecimal atPct, String action) {
    }

    /** 하루 기록. returnPct는 첫 기록이면 null. */
    public record Row(LocalDate date, BigDecimal totalKrw, BigDecimal returnPct, BigDecimal nav, BigDecimal peakNav,
                      LocalDate peakDate, BigDecimal drawdownPct, int level) {
    }

    /** 사람에게 알릴 변화. 올라갔으면 UP(그 단계), 0으로 돌아왔으면 RECOVERED. */
    public record Alert(Kind kind, Level level, BigDecimal drawdownPct, LocalDate peakDate) {

        public enum Kind { UP, RECOVERED }
    }

    /** 설정의 경보선들 + 목표의 최대 낙폭 한도. */
    public static List<Level> levels(RoboConfig c) {
        List<Level> out = new ArrayList<>();
        for (RoboConfig.DrawdownAlert a : c.drawdownAlerts()) {
            out.add(new Level(out.size() + 1, a.atPct(), a.action()));
        }
        out.add(new Level(out.size() + 1, c.goal().maxDrawdownPct(), LIMIT));
        return List.copyOf(out);
    }

    /**
     * 다음 기록을 만든다.
     *
     * @param prev   직전 기록. 없으면 첫 기록(지수 100)
     * @param ret    직전 기록 이후 수익률(비율). 첫 기록이면 무시
     */
    public static Row next(Row prev, LocalDate date, BigDecimal totalKrw, BigDecimal ret, List<Level> levels) {
        if (prev == null) {
            return new Row(date, scale(totalKrw, 2), null, START_NAV, START_NAV, date, BigDecimal.ZERO.setScale(4), 0);
        }
        BigDecimal nav = scale(prev.nav().multiply(BigDecimal.ONE.add(ret, MC), MC), 8);
        BigDecimal peak = prev.peakNav();
        LocalDate peakDate = prev.peakDate();
        if (nav.compareTo(peak) > 0) {
            peak = nav;
            peakDate = date;
        }
        BigDecimal dd = scale(BigDecimal.ONE.subtract(nav.divide(peak, MC), MC).multiply(HUNDRED, MC), 4).max(BigDecimal.ZERO.setScale(4));
        int level = levelFor(dd, levels, prev.level());
        return new Row(date, scale(totalKrw, 2), scale(ret.multiply(HUNDRED, MC), 6), nav, peak, peakDate, dd, level);
    }

    /** 올라갈 때는 즉시, 내려갈 때는 지금 단계의 경보선보다 여유만큼 더 회복해야 내린다. */
    static int levelFor(BigDecimal ddPct, List<Level> levels, int prevLevel) {
        int raw = 0;
        for (Level l : levels) {
            if (ddPct.compareTo(l.atPct()) >= 0) {
                raw = l.level();
            }
        }
        if (raw >= prevLevel || prevLevel > levels.size()) {
            return raw;
        }
        BigDecimal release = levels.get(prevLevel - 1).atPct().subtract(RELEASE_MARGIN_PCT);
        return ddPct.compareTo(release) < 0 ? raw : prevLevel;
    }

    /**
     * 알릴 것이 있으면 돌려준다.
     *
     * @param notifiedLevel 이미 알린 단계 (오늘 이미 기록했으면 그 단계, 아니면 직전 기록의 단계)
     */
    public static Optional<Alert> alert(int notifiedLevel, Row row, List<Level> levels) {
        if (row.level() > notifiedLevel) {
            return Optional.of(new Alert(Alert.Kind.UP, levels.get(row.level() - 1), row.drawdownPct(), row.peakDate()));
        }
        if (row.level() == 0 && notifiedLevel > 0) {
            return Optional.of(new Alert(Alert.Kind.RECOVERED, null, row.drawdownPct(), row.peakDate()));
        }
        return Optional.empty();
    }

    private static BigDecimal scale(BigDecimal v, int digits) {
        return v.setScale(digits, RoundingMode.HALF_UP);
    }
}
