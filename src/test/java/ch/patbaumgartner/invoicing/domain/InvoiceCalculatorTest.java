package ch.patbaumgartner.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceCalculatorTest {

	private final InvoiceCalculator calculator = new InvoiceCalculator();

	@Test
	void appliesQuantityDiscountAndVatPerLine() {
		var plan = calculator.calculate(snapshot(customer("C-1", line("line-1", 12, 7900, 15, 8))));

		assertThat(plan.invoices()).singleElement().satisfies(invoice -> {
			assertThat(invoice.invoiceNumber()).isEqualTo("INV-2026-11-C-1");
			assertThat(invoice.lines().getFirst().discountCents()).isEqualTo(14220);
			assertThat(invoice.netCents()).isEqualTo(80580);
			assertThat(invoice.vatCents()).isEqualTo(6446);
			assertThat(invoice.grossCents()).isEqualTo(87026);
		});
	}

	@Test
	void roundsHalfACentUp() {
		var plan = calculator.calculate(snapshot(customer("C-1", line("line-1", 1, 995, 10, 8))));

		var line = plan.invoices().getFirst().lines().getFirst();
		// 10 % of 9.95 is 0.995, 8 % of 8.95 is 0.716
		assertThat(line.discountCents()).isEqualTo(100);
		assertThat(line.netCents()).isEqualTo(895);
		assertThat(line.vatCents()).isEqualTo(72);
	}

	@Test
	void summaryAddsUpInvoicesAndGroupsVatByRate() {
		var plan = calculator.calculate(snapshot(customer("C-1", line("line-1", 1, 10000, 0, 8)),
				customer("C-2", line("line-2", 2, 5000, 10, 8), line("line-3", 1, 2000, 0, 3))));

		var summary = InvoiceSummary.of(plan);

		assertThat(summary.invoiceCount()).isEqualTo(2);
		assertThat(summary.totalDiscountCents()).isEqualTo(1000);
		assertThat(summary.totalNetCents()).isEqualTo(21000);
		assertThat(summary.totalVatCents()).isEqualTo(1580);
		assertThat(summary.totalGrossCents()).isEqualTo(22580);
		assertThat(summary.customers()).extracting(InvoiceSummary.CustomerTotal::grossCents)
			.containsExactly(10800L, 11780L);
		assertThat(summary.vatRates()).containsExactly(new InvoiceSummary.VatRateTotal(3, 2000, 60),
				new InvoiceSummary.VatRateTotal(8, 19000, 1520));
	}

	private static InvoiceSnapshot.LineItem line(String id, int quantity, long unitPriceCents, int discount, int vat) {
		return new InvoiceSnapshot.LineItem(id, "Licence", quantity, unitPriceCents, discount, vat);
	}

	private static InvoiceSnapshot.Customer customer(String id, InvoiceSnapshot.LineItem... lines) {
		return new InvoiceSnapshot.Customer(id, "Example GmbH", List.of(lines));
	}

	private static InvoiceSnapshot snapshot(InvoiceSnapshot.Customer... customers) {
		return new InvoiceSnapshot("test", "Alpine Software GmbH", LocalDate.of(2026, 11, 30),
				InvoiceCalculator.RULE_VERSION, "EUR", List.of(customers));
	}

}
