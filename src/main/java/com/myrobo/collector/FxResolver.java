package com.myrobo.collector;

import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.source.FrankfurterClient;
import com.myrobo.collector.source.KoreaEximClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 원·달러 환율 정하기. 수출입은행 매매기준율을 먼저 쓰고, 키가 없거나 실패하면 Frankfurter.
 * 수출입은행은 영업일 11시경에야 그날 환율을 고시하므로, 오늘부터 하루씩 거슬러 올라가며
 * 가장 최근 고시를 찾는다 (주말은 건너뛰고 최대 7일).
 */
public final class FxResolver {

    private static final int LOOKBACK_DAYS = 7;

    private final KoreaEximClient exim;
    private final FrankfurterClient frankfurter;

    public FxResolver(KoreaEximClient exim, FrankfurterClient frankfurter) {
        this.exim = exim;
        this.frankfurter = frankfurter;
    }

    /** warnings에는 예비 경로로 넘어간 이유를 남긴다. */
    public FxQuote resolve(LocalDate todayKst, List<String> warnings) {
        if (exim.configured()) {
            try {
                for (int i = 0; i <= LOOKBACK_DAYS; i++) {
                    LocalDate d = todayKst.minusDays(i);
                    if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                        continue;
                    }
                    var q = exim.usdKrw(d);
                    if (q.isPresent()) {
                        return q.get();
                    }
                }
                warnings.add("수출입은행: 최근 " + LOOKBACK_DAYS + "일 고시 없음 → Frankfurter 사용");
            } catch (RuntimeException e) {
                warnings.add("수출입은행 실패 → Frankfurter 사용: " + e.getMessage());
            }
        } else {
            warnings.add("KOREAEXIM_KEY 없음 → Frankfurter 사용");
        }
        return frankfurter.usdKrw();
    }
}
