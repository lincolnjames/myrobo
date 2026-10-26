package com.myrobo.holdings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 화면에서 받은 잔고 스냅샷 (그날 증권사 앱에 보이는 그대로).
 * 검증과 정리(공백 제거, 대문자, 0주 제외)는 저장 전에 {@link #validate(LocalDate)}로 한 번에 한다.
 *
 * @param asOf 잔고 기준일. 비우면 오늘(한국 시간)
 */
public record SnapshotInput(LocalDate asOf, List<Line> holdings, BigDecimal cashKrw, BigDecimal cashUsd) {

    /** 미국 티커(VTI, BRK.B)와 한국 종목코드(005930) 모두 허용. */
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9][A-Z0-9.\\-]{0,11}$");
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    public record Line(String symbol, Long qty, BigDecimal avgPrice) {
    }

    /** 검증을 통과한 결과. holdings에는 수량이 1 이상인 줄만 남는다. */
    public record Clean(LocalDate asOf, List<Line> holdings, BigDecimal cashKrw, BigDecimal cashUsd) {
    }

    public record Result(Clean clean, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Result validate(LocalDate todayKst) {
        List<String> errors = new ArrayList<>();
        LocalDate date = asOf == null ? todayKst : asOf;
        if (date.isAfter(todayKst)) {
            errors.add("기준일이 오늘(" + todayKst + ")보다 뒤일 수 없음");
        }
        if (date.isBefore(EARLIEST)) {
            errors.add("기준일이 너무 이름 (" + date + ")");
        }
        checkCash("원화 현금", cashKrw, errors);
        checkCash("달러 현금", cashUsd, errors);

        List<Line> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Line> lines = holdings == null ? List.of() : holdings;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String row = (i + 1) + "번째 줄";
            String symbol = l.symbol() == null ? "" : l.symbol().trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty() && (l.qty() == null || l.qty() == 0)) {
                continue; // 빈 줄은 무시
            }
            if (!SYMBOL.matcher(symbol).matches()) {
                errors.add(row + ": 종목 '" + symbol + "' 형식이 맞지 않음 (예: VTI, 005930)");
                continue;
            }
            if (!seen.add(symbol)) {
                errors.add(row + ": " + symbol + "이(가) 두 번 입력됨");
                continue;
            }
            if (l.qty() == null || l.qty() < 0) {
                errors.add(row + ": " + symbol + " 수량은 0 이상의 정수여야 함");
                continue;
            }
            if (l.avgPrice() != null && l.avgPrice().signum() <= 0) {
                errors.add(row + ": " + symbol + " 평균 단가는 비우거나 0보다 커야 함");
                continue;
            }
            if (l.qty() > 0) {
                clean.add(new Line(symbol, l.qty(), l.avgPrice()));
            }
        }
        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        return new Result(new Clean(date, List.copyOf(clean), cashKrw, cashUsd), List.of());
    }

    private static void checkCash(String label, BigDecimal v, List<String> errors) {
        if (v == null) {
            errors.add(label + "을(를) 입력해야 함 (없으면 0)");
        } else if (v.signum() < 0) {
            errors.add(label + "은(는) 0 이상이어야 함");
        } else if (v.scale() > 2) {
            errors.add(label + "은(는) 소수 둘째 자리까지만");
        }
    }
}
