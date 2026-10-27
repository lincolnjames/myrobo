package com.myrobo.web;

import com.myrobo.valuation.ValuationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 일별 평가 기록과 낙폭 경보 단계 조회. 기록은 매일 작업이 만든다. */
@RestController
@RequestMapping(value = "/api/valuation", produces = "application/json;charset=UTF-8")
public class ValuationController {

    private final ValuationService service;

    public ValuationController(ValuationService service) {
        this.service = service;
    }

    /** 최근 기록(최신순)과 경보 단계. */
    @GetMapping
    public ValuationService.View get(@RequestParam(defaultValue = "60") int days) {
        return service.view(Math.max(1, Math.min(days, 1000)));
    }
}
