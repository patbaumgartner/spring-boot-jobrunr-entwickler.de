package ch.patbaumgartner.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceSnapshotTest {

	@Test
	void quantityMustBePositive() {
		var line = new InvoiceSnapshot.LineItem("line-1", "Licence", 0, 7900, 0, 8);

		assertThatIllegalArgumentException().isThrownBy(() -> snapshot(line)).withMessageContaining("Quantity");
	}

	@Test
	void discountMustBeAValidPercentage() {
		var line = new InvoiceSnapshot.LineItem("line-1", "Licence", 1, 7900, 101, 8);

		assertThatIllegalArgumentException().isThrownBy(() -> snapshot(line)).withMessageContaining("Discount");
	}

	@Test
	void ownBillingDateKeepsTheSameInputAndAnotherDateIsNewInput() {
		var snapshot = snapshot(new InvoiceSnapshot.LineItem("line-1", "Licence", 1, 7900, 0, 8));

		assertThat(snapshot.forBillingDate(snapshot.billingDate())).isEqualTo(snapshot);
		assertThat(snapshot.forBillingDate(LocalDate.of(2026, 12, 31))).isNotEqualTo(snapshot);
	}

	private InvoiceSnapshot snapshot(InvoiceSnapshot.LineItem line) {
		var customer = new InvoiceSnapshot.Customer("C-1", "Example GmbH", List.of(line));
		return new InvoiceSnapshot("test", "Alpine Software GmbH", LocalDate.of(2026, 11, 30),
				InvoiceCalculator.RULE_VERSION, "EUR", List.of(customer));
	}

}
