/**********************************************************************
* This file is part of the iDempiere Print Template Engine plugin.    *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
*                                                                     *
* This program is distributed in the hope that it will be useful,     *
* but WITHOUT ANY WARRANTY; without even the implied warranty of      *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
* GNU General Public License for more details.                        *
**********************************************************************/
package org.idempiere.print.freemarker;

import java.io.IOException;
import java.io.Writer;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.ServerContext;
import org.adempiere.util.ServerContextPropertiesWrapper;
import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;
import org.compiere.util.Language;

import freemarker.core.Environment;
import freemarker.core._CoreAPI;
import freemarker.template.Template;
import freemarker.template.TemplateException;

/**
 * Runs {@link Template#process} with a limit on the output size and on the time taken, so that a
 * template cannot fill the output or hold a thread forever. Both limits are AD_SysConfig values and apply
 * only to a template of a strict configuration ({@code template.getConfiguration()} is a
 * {@link FreemarkerConfigurationFactory.StrictConfiguration}, built while
 * {@link FreemarkerConfigurationFactory#isStrict()}); the strictness is not read again here, so the
 * sandbox and the limits of a template always agree. Other templates are processed directly.
 * <p>
 * SysConfig: {@code PRT_FREEMARKER_MAX_OUTPUT_KB} and {@code PRT_FREEMARKER_TIMEOUT_SECONDS} are read on
 * every render; the effective value is {@code min(systemValue, clientValue)}, where {@code systemValue}
 * is the client 0 value (else the default) and {@code clientValue} the client's value (else
 * {@code systemValue}); 0 or below counts as not set. A tenant can tighten a limit, never relax it.
 * {@code PRT_FREEMARKER_STRICT} and {@code PRT_FREEMARKER_MAX_CONCURRENT_RENDERS} are read at client 0
 * only, when the configuration is built or the first render starts: a change needs a restart.
 * <p>
 * The output limit counts characters written to the output (1 KB = 1024 characters). It does not cover
 * text captured inside the template with {@code #assign}/{@code #local}/{@code #global} or buffered by
 * {@code #attempt}: FreeMarker 2.3 has no hook for them. The time limit stops such a loop, but not memory
 * use: a capture or a string that grows fast ({@code s = s + s}, {@code ?left_pad}) can run the heap
 * out before the time is up. That residual is left for stage A.
 * <p>
 * The time limit is enforced on a worker thread that is interrupted when the time is up. Templates of a
 * strict configuration check for the interrupt (FreeMarker internal API, see
 * {@link FreemarkerConfigurationFactory}), so a loop that writes nothing ends too; the writer also
 * fails after the cancel. At most {@value #SYSCONFIG_MAX_CONCURRENT_RENDERS} (system level) renders
 * run at the same time; a further one waits for a slot, and the wait counts against its time limit. A
 * worker gives its slot back when its task ends, not when the caller gives up, so a worker that does
 * not stop at once still holds its slot.
 */
public final class GuardedRender {

	/**
	 * Maximum output in KB (1 KB = 1024 characters). A client value can only lower the system
	 * (client 0) value, see {@link #limit(String, int, int)}.
	 */
	public static final String SYSCONFIG_MAX_OUTPUT_KB = "PRT_FREEMARKER_MAX_OUTPUT_KB";
	/**
	 * Maximum render time in seconds. A client value can only lower the system (client 0) value, see
	 * {@link #limit(String, int, int)}.
	 */
	public static final String SYSCONFIG_TIMEOUT_SECONDS = "PRT_FREEMARKER_TIMEOUT_SECONDS";

	static final int DEFAULT_MAX_OUTPUT_KB = 20480;
	static final int DEFAULT_TIMEOUT_SECONDS = 60;

	private static final CLogger log = CLogger.getCLogger(GuardedRender.class);

	/**
	 * System level (client 0) maximum number of renders running at the same time. Read once, when the
	 * first render starts: a change needs a restart. Default {@code max(4, 2 * availableProcessors())}.
	 */
	public static final String SYSCONFIG_MAX_CONCURRENT_RENDERS = "PRT_FREEMARKER_MAX_CONCURRENT_RENDERS";

	private static final String THREAD_PREFIX = "prt-freemarker-render-";

	private static final AtomicInteger s_threadNumber = new AtomicInteger();

	/** Cached pool of daemon workers; its size is bounded by {@link #s_slots}. */
	private static ExecutorService s_executor;
	/** One permit per render that may run; a worker gives its permit back when its task ends. */
	private static Semaphore s_slots;
	private static int s_maxConcurrent;

	private static int defaultMaxConcurrent() {
		return Math.max(4, 2 * Runtime.getRuntime().availableProcessors());
	}

	private static synchronized ExecutorService executor() {
		if (s_executor == null) {
			s_maxConcurrent = MSysConfig.getIntValue(SYSCONFIG_MAX_CONCURRENT_RENDERS, defaultMaxConcurrent(), 0);
			if (s_maxConcurrent < 1)
				s_maxConcurrent = defaultMaxConcurrent();
			s_slots = new Semaphore(s_maxConcurrent, true);
			ThreadFactory factory = r -> {
				Thread t = new Thread(r, THREAD_PREFIX + s_threadNumber.incrementAndGet());
				t.setDaemon(true);
				return t;
			};
			s_executor = Executors.newCachedThreadPool(factory);
		}
		return s_executor;
	}

	private static synchronized Semaphore slots() {
		executor();
		return s_slots;
	}

	private static synchronized int maxConcurrent() {
		return s_maxConcurrent;
	}

	/** Stops the workers and forgets the pool and the permits, so the limit is read again. Only for tests. */
	public static synchronized void reset() {
		if (s_executor != null)
			s_executor.shutdownNow();
		s_executor = null;
		s_slots = null;
	}

	private static final String INTERRUPT_CHECKS_ATTRIBUTE = "prt.interruptChecks";

	/**
	 * Adds interrupt checks to the template once. The FreeMarker call (internal API {@code _CoreAPI},
	 * stable since 2.3.20, the only way to make a running template react to Thread.interrupt())
	 * grows the template tree on every call, and cached templates are shared between threads, hence
	 * the marker and the lock.
	 *
	 * @return true if the template was patched by this call, false if it already was
	 */
	public static boolean makeInterruptible(Template template) {
		synchronized (template) {
			if (template.getCustomAttribute(INTERRUPT_CHECKS_ATTRIBUTE) != null)
				return false;
			_CoreAPI.addThreadInterruptedChecks(template);
			template.setCustomAttribute(INTERRUPT_CHECKS_ATTRIBUTE, Boolean.TRUE);
			return true;
		}
	}

	private GuardedRender() {
	}

	/**
	 * Same as {@link #process(Template, Object, Writer, int, Properties)} with the context of the
	 * calling thread.
	 */
	public static void process(Template template, Object dataModel, Writer out, int adClientId)
			throws TemplateException, IOException {
		process(template, dataModel, out, adClientId, ServerContext.getCurrentInstance());
	}

	/**
	 * @param template the template
	 * @param dataModel the data model
	 * @param out where the output goes; it is not closed
	 * @param adClientId client whose SysConfig limits apply (bounded by the system values); strict mode
	 *            comes from the template's configuration
	 * @param ctx context set as ServerContext of the worker thread, so that Env.getCtx() in the data
	 *            model resolves as in the calling thread
	 * @throws AdempiereException when the output or the time limit is exceeded
	 * @throws TemplateException when the template fails
	 * @throws IOException when writing fails
	 */
	public static void process(Template template, Object dataModel, Writer out, int adClientId, Properties ctx)
			throws TemplateException, IOException {
		process(template, dataModel, out, adClientId, ctx, null);
	}

	/**
	 * Same as {@link #process(Template, Object, Writer, int, Properties)} in a document language: the
	 * render's locale and default date formats follow it
	 * ({@link FreemarkerConfigurationFactory#applyLanguage}).
	 *
	 * @param language document language; null keeps the configuration's locale and formats
	 */
	public static void process(Template template, Object dataModel, Writer out, int adClientId, Properties ctx,
			Language language) throws TemplateException, IOException {
		if (!(template.getConfiguration() instanceof FreemarkerConfigurationFactory.StrictConfiguration)) {
			run(template, dataModel, out, adClientId, language);
			return;
		}
		int maxKb = limit(SYSCONFIG_MAX_OUTPUT_KB, DEFAULT_MAX_OUTPUT_KB, adClientId);
		int timeout = limit(SYSCONFIG_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS, adClientId);
		// Env.getCtx() is a wrapper over the thread's ServerContext: set on the worker it would
		// delegate to itself, so take the real Properties of the calling thread
		final Properties workerCtx = ctx == null || ctx instanceof ServerContextPropertiesWrapper
				? ServerContext.getCurrentInstance() : ctx;
		final CappedWriter capped = new CappedWriter(out, maxKb, timeout);

		// FreeMarker internal API, stable since 2.3.20 and the only route: makes the main template
		// stop on Thread.interrupt(); includes and imports are patched by the strict Configuration
		makeInterruptible(template);

		// waiting for a slot counts against the time limit, so the whole call stays bounded
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
		final ExecutorService executor;
		final Semaphore slots;
		synchronized (GuardedRender.class) {
			executor = executor();
			slots = slots();
		}
		final int max = maxConcurrent();
		try {
			if (!slots.tryAcquire(timeout, TimeUnit.SECONDS))
				throw new AdempiereException("Too many template renders running ("
						+ SYSCONFIG_MAX_CONCURRENT_RENDERS + "=" + max + ")");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AdempiereException("Template render interrupted: " + template.getName());
		}
		// whoever claims first owns the permit: the task when it starts, else the caller giving up on
		// a task that never ran; a worker that started keeps its permit until its template ends
		final AtomicBoolean claimed = new AtomicBoolean();
		Future<?> future;
		try {
			future = executor.submit(() -> {
				if (!claimed.compareAndSet(false, true))
					return null;
				ServerContext.setCurrentInstance(workerCtx);
				try {
					run(template, dataModel, capped, adClientId, language);
					return null;
				} finally {
					ServerContext.dispose();
					slots.release();
				}
			});
		} catch (RejectedExecutionException e) {
			slots.release();
			throw new AdempiereException("Too many template renders running ("
					+ SYSCONFIG_MAX_CONCURRENT_RENDERS + "=" + max + ")");
		}
		try {
			future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch (TimeoutException e) {
			stop(capped, future, claimed, slots);
			log.log(Level.WARNING, "Template '" + template.getName() + "' exceeded "
					+ SYSCONFIG_TIMEOUT_SECONDS + "=" + timeout + "; worker interrupted");
			throw new AdempiereException(
					"Template render exceeded " + SYSCONFIG_TIMEOUT_SECONDS + "=" + timeout);
		} catch (InterruptedException e) {
			stop(capped, future, claimed, slots);
			Thread.currentThread().interrupt();
			throw new AdempiereException("Template render interrupted: " + template.getName());
		} catch (ExecutionException e) {
			throw unwrap(e.getCause());
		}
	}

	/** One render: the environment of the template, in the document language when there is one. */
	private static void run(Template template, Object dataModel, Writer out, int adClientId, Language language)
			throws TemplateException, IOException {
		Environment env = template.createProcessingEnvironment(dataModel, out);
		FreemarkerConfigurationFactory.applyLanguage(env, language, adClientId);
		env.process();
	}

	/** Stops a render the caller gives up on; frees the permit only if the task never started. */
	private static void stop(CappedWriter capped, Future<?> future, AtomicBoolean claimed, Semaphore slots) {
		capped.cancel();
		future.cancel(true);
		if (claimed.compareAndSet(false, true))
			slots.release();
	}

	private static int positive(int value, int fallback) {
		return value > 0 ? value : fallback;
	}

	/**
	 * Effective limit: {@code min(systemValue, clientValue)}. {@code systemValue} is the client 0 value,
	 * else the default; {@code clientValue} is the value of the client, else {@code systemValue}. A value
	 * of 0 or below counts as not set. A tenant can tighten a limit, never relax it.
	 */
	static int limit(String name, int defaultValue, int adClientId) {
		int systemValue = positive(MSysConfig.getIntValue(name, defaultValue, 0), defaultValue);
		if (adClientId <= 0)
			return systemValue;
		int clientValue = positive(MSysConfig.getIntValue(name, systemValue, adClientId), systemValue);
		return Math.min(systemValue, clientValue);
	}

	/** Rethrows the failure of the worker as the type the caller of Template.process would see. */
	private static RuntimeException unwrap(Throwable cause) throws TemplateException, IOException {
		// FreeMarker may wrap what the writer throws
		for (Throwable t = cause; t != null; t = t.getCause()) {
			if (t instanceof LimitExceededException)
				throw (LimitExceededException) t;
		}
		if (cause instanceof TemplateException)
			throw (TemplateException) cause;
		if (cause instanceof IOException)
			throw (IOException) cause;
		if (cause instanceof RuntimeException)
			throw (RuntimeException) cause;
		if (cause instanceof Error)
			throw (Error) cause;
		throw new AdempiereException(cause);
	}

	/** Marks the failures raised by this class, so they are not mistaken for template errors. */
	private static final class LimitExceededException extends AdempiereException {
		private static final long serialVersionUID = 1L;

		LimitExceededException(String message) {
			super(message);
		}
	}

	/** Writer that fails once more than the limit of characters went through it. */
	private static final class CappedWriter extends Writer {
		private final Writer delegate;
		private final int maxKb;
		private final long maxChars;
		private final int timeout;
		private long written;
		private volatile boolean cancelled;

		CappedWriter(Writer delegate, int maxKb, int timeout) {
			this.timeout = timeout;
			this.delegate = delegate;
			this.maxKb = maxKb;
			this.maxChars = maxKb * 1024L;
		}

		/** Makes every further write fail; does not depend on the interrupt flag, which code may clear. */
		void cancel() {
			cancelled = true;
		}

		private void count(int length) {
			if (cancelled || Thread.currentThread().isInterrupted())
				throw new LimitExceededException(
						"Template render exceeded " + SYSCONFIG_TIMEOUT_SECONDS + "=" + timeout);
			written += length;
			if (written > maxChars)
				throw new LimitExceededException(
						"Template output exceeds " + SYSCONFIG_MAX_OUTPUT_KB + "=" + maxKb);
		}

		@Override
		public void write(char[] cbuf, int off, int len) throws IOException {
			count(len);
			delegate.write(cbuf, off, len);
		}

		@Override
		public void write(String str, int off, int len) throws IOException {
			count(len);
			delegate.write(str, off, len);
		}

		@Override
		public void write(int c) throws IOException {
			count(1);
			delegate.write(c);
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}
}
