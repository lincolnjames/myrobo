package com.myrobo.holdings;

import com.myrobo.common.config.LoadedConfig;
import com.myrobo.common.config.RoboConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인된 설정을 DB 기준 데이터(목표·코어 계좌·코어 종목)에 맞춘다. 설정 승인 검사 다음에 돈다.
 * 설정이 원본이고 DB는 그 사본이라, 시작할 때마다 덮어써도 안전하다.
 */
@Component
@Order(2)
public class ReferenceDataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataBootstrap.class);

    private final HoldingsRepository repo;
    private final LoadedConfig loaded;

    public ReferenceDataBootstrap(HoldingsRepository repo, LoadedConfig loaded) {
        this.repo = repo;
        this.loaded = loaded;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        RoboConfig c = loaded.config();
        long goal = repo.upsertGoal(c.goal().horizon(), c.goal().startDate(), c.goal().maxDrawdownPct(),
                c.goal().baseCurrency());
        long account = repo.upsertCoreAccount(c.coreBroker(), goal);
        repo.syncCoreInstruments(c.instruments());
        log.info("기준 데이터 동기화: 목표 {} ({}), 코어 계좌 {} ({}), 종목 {}",
                goal, c.goal().horizon(), account, c.coreBroker(), c.instruments().values());
    }
}
