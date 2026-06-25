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

}
