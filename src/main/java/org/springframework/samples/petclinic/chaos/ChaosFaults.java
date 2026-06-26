/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.samples.petclinic.chaos;

/**
 * Seam through which domain code routes operations a chaos scenario may perturb.
 * {@link NoOpChaosFaults} preserves production behavior; {@link ActiveChaosFaults} (chaos
 * profile) applies faults when armed.
 */
public interface ChaosFaults {

	/**
	 * Normalize an owner last-name search term. Production behavior: a null term becomes
	 * the empty string (broadest possible search).
	 * @param lastName the raw search term, possibly null
	 * @return the normalized term
	 */
	String normalizeLastName(String lastName);

	/**
	 * Whether the owner-search read path should amplify queries (latency fault).
	 * Production behavior: {@code false} (no amplification).
	 * @return true only when the latency scenario is armed under the chaos profile
	 */
	boolean amplifyOwnerReads();

	/**
	 * Hook at the start of the vet-list request. Production behavior: does nothing. Under
	 * the error-ratio scenario it throws an opaque 5xx (no localizable defect).
	 */
	void maybeFailVetList();

	/**
	 * Corrupt an owner-search term (silent data-corruption fault). Production behavior:
	 * returns the term unchanged.
	 * @param lastName the (already normalized) search term
	 * @return the term to actually query with
	 */
	String corruptSearchTerm(String lastName);

	/**
	 * Guard at the start of a data-access path (database-connectivity fault). Production
	 * behavior: does nothing. Under the {@code dbDown} scenario it throws an
	 * infrastructure-typed
	 * {@link org.springframework.dao.DataAccessResourceFailureException} whose root cause
	 * is a {@link java.net.ConnectException} — a 5xx with a stack trace that points at
	 * the database, not at an application defect.
	 */
	void assertDatabaseReachable();

	/**
	 * Hook at the start of the owner-search handler (thread-pool saturation fault).
	 * Production behavior: returns immediately. Under the {@code threadStarvation}
	 * scenario it parks the calling worker thread for a bounded duration, so concurrent
	 * load exhausts a small pool — a saturation whose cause is only visible in a thread
	 * dump, not in the latency/error metrics.
	 */
	void maybeBlockWorker();

	/**
	 * Hook on the owner-search handler (deadlock fault). Production behavior: does
	 * nothing. Under the {@code deadlock} scenario the first armed invocation spawns two
	 * daemon threads that acquire two monitors in opposite order, producing a permanent
	 * Java-level deadlock — invisible to latency/error metrics, localizable only from a
	 * thread dump. Idempotent and non-blocking for the caller.
	 */
	void triggerDeadlock();

	/**
	 * Whether the owner search should run the index-defeating query form (query-plan
	 * pathology). Production behavior: {@code false} (prefix search, uses the index).
	 * Under the {@code queryPlanRegression} scenario it returns {@code true}, routing the
	 * search through a leading-wildcard {@code LIKE '%term%'} that forces a Seq Scan.
	 * @return true only when the queryPlanRegression scenario is armed under the chaos
	 * profile
	 */
	boolean useRegressedOwnerQuery();

	/**
	 * Whether the OOM-kill endpoint should exhaust container memory (class-E
	 * infrastructure fault). Production behavior: {@code false} (the endpoint is a
	 * no-op). Under the {@code oomKill} scenario it returns {@code true}, so the endpoint
	 * allocates and retains heap beyond the pod memory limit until the kernel OOM-kills
	 * the container — a SIGKILL with no application stack trace.
	 * @return true only when the oomKill scenario is armed under the chaos profile
	 */
	boolean shouldOomKill();

}
