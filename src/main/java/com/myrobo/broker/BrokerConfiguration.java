package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.collector.http.Sleeper;
import com.myrobo.ops.Notifier;
import com.myrobo.ops.Periodic;
import com.myrobo.plan.ExecutionService;
import com.myrobo.plan.PlanRepository;
import com.myrobo.plan.PlanService;
import com.myrobo.plan.PlanViews.ExecutionResult;
import com.myrobo.plan.PlanViews.ExecutionStatus;
import com.myrobo.plan.PlanViews.PlanView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** 증권사 전송·체결 반영 조립. 꺼져 있으면(기본) 키가 없어도 앱이 뜨고 아무것도 보내지 않는다. */
@Configuration
@EnableConfigurationProperties(BrokerProperties.class)
public class BrokerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BrokerConfiguration.class);

    /** 모드와 KIS 연결. 토큰을 하나만 받도록 전송과 체결 반영이 같은 클라이언트를 쓴다. */
    record Broker(Mode mode, Supplier<KisClient> kis) {
    }

    @Bean
    Broker broker(BrokerProperties p, Clock clock) {
        Mode mode = mode(p.mode());
        if (mode == null) {
            log.info("증권사 전송 꺼짐 (robo.broker.mode=off)");
            return new Broker(null, () -> {
                throw new IllegalStateException("증권사 전송이 꺼져 있음");
            });
        }
        BrokerProperties.Kis k = p.kis();
        if (!"paper".equalsIgnoreCase(k.env() == null ? "" : k.env().strip())) {
            throw new IllegalStateException("robo.broker.mode=" + p.mode()
                    + "는 모의투자 키(KIS_MODE=paper)로만 켤 수 있어요. 실전은 아직 지원하지 않아요");
        }
        KisClient client = new KisClient(new JdkKisTransport(Duration.ofSeconds(15)), KisClient.Env.PAPER,
                k.appKey(), k.appSecret(), k.account(), clock, Sleeper.real());
        log.info("증권사 전송 켜짐: {} (한국투자증권 모의투자, 지정가 여유 {}%, 한 건 ${}, 하루 ${})",
                mode, p.limitBufferPct(), p.maxOrderUsd(), p.maxDailyUsd());
        return new Broker(mode, () -> client);
    }

    @Bean
    OrderDispatcher orderDispatcher(Broker b, BrokerProperties p, JdbcClient jdbc, PlanService plans,
                                    PlanRepository repo, Notifier notifier, Clock clock) {
        return new OrderDispatcher(b.mode(), b.kis(), new JdbcDispatchStore(jdbc), planSource(plans, repo), notifier,
                new OrderPlanner.Limits(p.limitBufferPct(), p.maxOrderUsd(), p.maxDailyUsd(), exchanges(p)), clock);
    }

    @Bean
    FillReconciler fillReconciler(Broker b, BrokerProperties p, JdbcClient jdbc, PlanService plans, PlanRepository repo,
                                  ExecutionService executions, PlatformTransactionManager txm, Notifier notifier,
                                  Clock clock) {
        JdbcDispatchStore store = new JdbcDispatchStore(jdbc);
        TransactionTemplate tx = new TransactionTemplate(txm);
        FillReconciler.Ledger ledger = (planId, input, at, brokerQty, records) -> tx.execute(s -> {
            ExecutionResult r = executions.saveFromBroker(planId, input, at, brokerQty);
            if (r.status() == ExecutionStatus.SAVED) {
                store.recordFills(records);
            }
            return r;
        });
        return new FillReconciler(b.mode(), b.kis(), store, planSource(plans, repo), ledger, notifier,
                Set.copyOf(exchanges(p).values()), clock);
    }

    @Bean
    Periodic brokerDispatch(OrderDispatcher dispatcher) {
        return periodic("증권사 전송", dispatcher::tick);
    }

    @Bean
    Periodic brokerFills(FillReconciler reconciler) {
        return periodic("체결 반영", reconciler::tick);
    }

    private static Periodic periodic(String name, Runnable task) {
        return new Periodic() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void tick() {
                task.run();
            }
        };
    }

    private static OrderDispatcher.PlanSource planSource(PlanService plans, PlanRepository repo) {
        return new OrderDispatcher.PlanSource() {
            @Override
            public Optional<PlanView> openPlan() {
                return Optional.ofNullable(plans.overview().open());
            }

            @Override
            public MarketCalendar calendar() {
                return new MarketCalendar(repo.fullDayClosures());
            }
        };
    }

    private static Map<String, String> exchanges(BrokerProperties p) {
        return p.exchanges() == null ? Map.of() : Map.copyOf(p.exchanges());
    }

    /** off(또는 빈 값)면 null. 모르는 값이면 시작을 거부한다 (오타로 실제 전송이 켜지거나 꺼지지 않게). */
    static Mode mode(String v) {
        String s = v == null ? "" : v.strip().toLowerCase();
        return switch (s) {
            case "", "off" -> null;
            case "dry-run" -> Mode.DRY_RUN;
            case "paper" -> Mode.PAPER;
            default -> throw new IllegalStateException("robo.broker.mode는 off, dry-run, paper 중 하나여야 해요: " + v);
        };
    }
}
