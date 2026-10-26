package com.myrobo.collector;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 수집 모듈이 주고받는 값들. */
public final class CollectorTypes {

    private CollectorTypes() {
    }

    /**
     * 일봉 하나. adjClose는 분할·배당을 반영한 수정주가로, 신호 계산은 이 값을 쓴다.
     * divCash는 그날 배당락 금액(없으면 0), splitFactor는 분할 비율(없으면 1).
     */
    public record DailyBar(
            String symbol,
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal adjClose,
            Long volume,
            BigDecimal divCash,
            BigDecimal splitFactor) {

        public boolean isSplit() {
            return splitFactor != null && splitFactor.compareTo(BigDecimal.ONE) != 0;
        }

        public boolean isDividend() {
            return divCash != null && divCash.signum() > 0;
        }

        /** 분할이나 배당이 있으면 그 이전 전체 기간의 수정주가가 다시 계산된다. */
        public boolean hasCorporateAction() {
            return isSplit() || isDividend();
        }
    }

    /** 미국 시장 휴장일. tradingHours가 비어 있으면 종일 휴장, 값이 있으면 조기 폐장(거래일). */
    public record MarketHoliday(LocalDate date, String eventName, String tradingHours) {

        public boolean fullDayClosed() {
            return tradingHours == null || tradingHours.isBlank();
        }
    }

    /** 원·달러 환율 한 건. date는 고시 기준일. */
    public record FxQuote(LocalDate date, BigDecimal rate, String source) {
    }

    /** 종목 하나를 수집한 결과. */
    public enum RefreshMode { UP_TO_DATE, BACKFILL, INCREMENTAL, REFETCH_ADJUSTED, SKIPPED, FAILED }

    public record SymbolResult(String symbol, RefreshMode mode, int rows, LocalDate latest, String message) {
    }

    /** 하루 수집 전체 결과. errors가 비어 있으면 성공. */
    public record CollectResult(
            LocalDate session,
            List<SymbolResult> prices,
            FxQuote fx,
            int holidays,
            List<String> warnings,
            List<String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }
}
