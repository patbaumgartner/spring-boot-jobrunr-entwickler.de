package ch.patbaumgartner.invoicing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The invoice-run application and its local receiving portal stub. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InvoiceRunApplication {

	public static void main(String[] args) {
		SpringApplication.run(InvoiceRunApplication.class, args);
	}

}
