package ch.patbaumgartner.invoicing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.patbaumgartner.invoicing.application.InvoiceRunService.InvoiceRunEnqueueFailedException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

class InvoiceRunServiceTest {

	private final JsonMapper json = JsonMapper.builder().build();

	private final InvoiceRunRepository runs = mock(InvoiceRunRepository.class);

	private final JobScheduler scheduler = mock(JobScheduler.class);

	private final InvoiceRunService service = new InvoiceRunService(runs, scheduler, json);

	@Test
	void invoiceRunIsNotAcceptedWhenJobRunrDoesNotTakeTheJob() {
		var stored = storeInMemory();
		when(scheduler.enqueue(any(UUID.class), ArgumentMatchers.<IocJobLambda<InvoiceRunJob>>any()))
			.thenThrow(new IllegalStateException("storage unavailable"));

		assertThatThrownBy(() -> service.submit(new SnapshotCatalog(json).load("standard"), 1, null))
			.isInstanceOf(InvoiceRunEnqueueFailedException.class);

		verify(runs).enqueueFailed(eq(stored.get().id()), contains("storage unavailable"));
		verify(runs, never()).enqueued(any(), any());
	}

	private AtomicReference<InvoiceRun> storeInMemory() {
		var stored = new AtomicReference<InvoiceRun>();
		doAnswer(invocation -> {
			stored.set(invocation.getArgument(0));
			return null;
		}).when(runs).insertIfAbsent(any());
		when(runs.get(anyString(), any(), anyInt())).thenAnswer(invocation -> stored.get());
		return stored;
	}

}
