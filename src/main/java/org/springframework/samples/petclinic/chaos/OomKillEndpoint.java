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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Class-E infrastructure fault trigger: when the {@code oomKill} scenario is armed, this
 * endpoint allocates and retains heap, touching every page, until resident memory exceeds
 * the pod's memory limit and the kernel OOM-kills the container (SIGKILL — no
 * {@code OutOfMemoryError}, no application stack trace). The cause is visible only in the
 * Kubernetes event/status stream, not in app logs. Registered only under the
 * {@code chaos} profile (404 otherwise). When disarmed it is a pure no-op, so it is safe
 * to slice-test.
 */
@RestController
@Profile("chaos")
class OomKillEndpoint {

	/** 32 MiB per allocated chunk. */
	private static final int CHUNK_BYTES = 32 * 1024 * 1024;

	/**
	 * Touch one byte per 4 KiB page so the pages become resident (not lazily
	 * zero-backed).
	 */
	private static final int PAGE_BYTES = 4096;

	private static final Log log = LogFactory.getLog(OomKillEndpoint.class);

	/** Retained so the chunks are never garbage-collected. */
	private final List<byte[]> retained = new ArrayList<>();

	private final ChaosFaults chaosFaults;

	OomKillEndpoint(ChaosFaults chaosFaults) {
		this.chaosFaults = chaosFaults;
	}

	@PostMapping("/chaos/oom-kill")
	Map<String, Object> oomKill() {
		if (!this.chaosFaults.shouldOomKill()) {
			return Map.of("armed", false, "allocatedMb", 0);
		}
		log.warn("oomKill armed: exhausting heap to trigger a kernel OOM-kill (the container will be SIGKILLed)");
		int allocatedMb = 0;
		// Allocate until the kernel kills us. -Xmx is set above the pod memory limit, so
		// the
		// cgroup OOM-killer fires before the JVM would throw OutOfMemoryError.
		while (true) {
			byte[] chunk = new byte[CHUNK_BYTES];
			for (int i = 0; i < chunk.length; i += PAGE_BYTES) {
				chunk[i] = 1;
			}
			this.retained.add(chunk);
			allocatedMb += CHUNK_BYTES / (1024 * 1024);
		}
	}

}
