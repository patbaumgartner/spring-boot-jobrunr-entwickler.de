package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.application.InvoiceRunRepository.InvoiceRunNotFoundException;
import ch.patbaumgartner.invoicing.application.InvoiceRunService.InvoiceRunEnqueueFailedException;
import ch.patbaumgartner.invoicing.application.InvoiceRunService.InvoiceRunConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps business failures to HTTP problem details.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail notFound(InvoiceRunNotFoundException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler
	ProblemDetail conflict(InvoiceRunConflictException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	/**
	 * An invoice run without a job must not look accepted.
	 */
	@ExceptionHandler
	ProblemDetail notEnqueued(InvoiceRunEnqueueFailedException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
	}

}
