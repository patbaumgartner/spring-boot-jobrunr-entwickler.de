package ch.patbaumgartner.invoicing.application;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the invoice-run application.
 *
 * @param dataDirectory where artifacts are stored; it has to survive restarts just like
 * the database
 * @param portalUrl base URL of the operations portal
 * @param httpTimeout connect and read timeout of a delivery
 * @param retention how long the files of a delivered invoice run are kept
 */
@ConfigurationProperties("invoicing")
public record InvoiceRunProperties(Path dataDirectory, URI portalUrl, Duration httpTimeout, Duration retention) {
}
