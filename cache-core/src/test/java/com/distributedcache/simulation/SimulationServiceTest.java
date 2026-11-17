package com.distributedcache.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SimulationServiceTest {
    @Test
    void noMeasuredRequestsHaveNoErrorRateAndDoNotFailReportCreation() {
        assertNull(SimulationService.errorRatePct(0, 0));
    }

    @Test
    void measuredRequestErrorRateIsRoundedToTwoDecimalPlaces() {
        assertEquals(5.0, SimulationService.errorRatePct(5, 100));
        assertEquals(33.33, SimulationService.errorRatePct(1, 3));
    }
}
