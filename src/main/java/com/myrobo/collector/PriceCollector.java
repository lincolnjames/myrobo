package com.myrobo.collector;

import com.myrobo.collector.CollectorStores.PriceStore;
import com.myrobo.collector.CollectorTypes.DailyBar;
import com.myrobo.collector.CollectorTypes.RefreshMode;
import com.myrobo.collector.CollectorTypes.SymbolResult;
import com.myrobo.collector.source.TiingoClient;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 종목 하나의 일봉을 최신으로 맞춘다.
 *
 * <ul>
 *   <li>저장된 게 없으면: 시작일부터 전체 이력 (1회 호출)</li>
 *   <li>이미 최신이면: 호출하지 않음</li>
 *   <li>평소: 마지막 저장일 다음 날부터만 (1회 호출)</li>
 *   <li>새로 받은 날에 분할·배당이 있으면: 전체 이력을 다시 받아 덮어쓴다 (1회 추가).
 *       분할·배당이 생기면 그 이전 모든 날의 수정주가가 다시 계산되므로, 새 날만 받으면
 *       옛 수정주가와 새 수정주가가 섞여 이동평균·수익률이 틀어진다.</li>
 * </ul>
 */
public final class PriceCollector {

    private final TiingoClient tiingo;
    private final PriceStore store;
    private final LocalDate backfillStart;

    public PriceCollector(TiingoClient tiingo, PriceStore store, LocalDate backfillStart) {
        this.tiingo = tiingo;
        this.store = store;
        this.backfillStart = backfillStart;
    }

    public SymbolResult refresh(String symbol, LocalDate session) {
        Optional<LocalDate> last = store.lastDate(symbol);
        if (last.isPresent() && !last.get().isBefore(session)) {
            return new SymbolResult(symbol, RefreshMode.UP_TO_DATE, 0, last.get(), null);
        }

        RefreshMode mode;
        List<DailyBar> bars;
        if (last.isEmpty()) {
            mode = RefreshMode.BACKFILL;
            bars = tiingo.daily(symbol, backfillStart, session);
        } else {
            bars = tiingo.daily(symbol, last.get().plusDays(1), session);
            if (bars.stream().anyMatch(DailyBar::hasCorporateAction)) {
                mode = RefreshMode.REFETCH_ADJUSTED;
                bars = tiingo.daily(symbol, backfillStart, session);
            } else {
                mode = RefreshMode.INCREMENTAL;
            }
        }

        store.saveBars(bars);
        store.saveCorporateActions(bars.stream().filter(DailyBar::hasCorporateAction).toList());

        LocalDate latest = bars.stream().map(DailyBar::date).max(Comparator.naturalOrder())
                .orElse(last.orElse(null));
        String note = latest == null || latest.isBefore(session)
                ? "최근 거래일 " + session + " 데이터가 아직 없음 (마지막 " + latest + ")"
                : null;
        return new SymbolResult(symbol, mode, bars.size(), latest, note);
    }
}
