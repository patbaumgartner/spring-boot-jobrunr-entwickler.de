package ch.patbaumgartner.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class InvoiceRunScheduleTest {

	@Test
	void monthlyRunBillsTheMonthBeforeItsCreationInZurichTime() {
		// 23:30 UTC on 31 December is already 1 January in Zurich.
		assertThat(InvoiceRunSchedule.monthBefore(Instant.parse("2026-12-31T23:30:00Z")))
			.isEqualTo(YearMonth.of(2026, 12));
		assertThat(InvoiceRunSchedule.monthBefore(Instant.parse("2027-03-01T05:00:00Z")))
			.isEqualTo(YearMonth.of(2027, 2));
	}

}
