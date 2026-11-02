package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 모드 값: 오타로 전송이 켜지거나 꺼지지 않게 모르는 값은 시작 거부. */
class BrokerConfigurationTest {

    @Test
    void modes() {
        assertNull(BrokerConfiguration.mode(null));
        assertNull(BrokerConfiguration.mode(""));
        assertNull(BrokerConfiguration.mode(" OFF "));
        assertEquals(Mode.DRY_RUN, BrokerConfiguration.mode("dry-run"));
        assertEquals(Mode.PAPER, BrokerConfiguration.mode("paper"));
    }

    @Test
    void unknownModeRefusesToStart() {
        assertThrows(IllegalStateException.class, () -> BrokerConfiguration.mode("live"));
        assertThrows(IllegalStateException.class, () -> BrokerConfiguration.mode("papr"));
    }
}
