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

import java.lang.reflect.Field;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import sun.misc.Unsafe;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Class-E infrastructure fault trigger: when the {@code oomKill} scenario is armed, this
 * endpoint allocates native (off-heap) memory and writes every page, driving the
 * container's resident set past the pod memory limit until the kernel OOM-kills it
 * (SIGKILL — no {@code OutOfMemoryError}, no application stack trace). The cause is
 * visible only in the Kubernetes event/status stream, not in app logs.
 * <p>
 * Native memory (via {@code sun.misc.Unsafe#allocateMemory}) is used deliberately rather
 * than heap or direct {@link java.nio.ByteBuffer}: both heap ({@code -Xmx}) and direct
 * memory ({@code -XX:MaxDirectMemorySize}) are counted by the Paketo memory calculator,
 * so sizing either above the pod limit makes the buildpack launcher refuse to start the
 * JVM (a crash-loop before any fault can run). Raw native allocation is invisible to that
 * calculator: the JVM boots cleanly within the limit, and only this resident growth
 * crosses the cgroup boundary — producing a genuine kernel kill, not a JVM-visible error.
 * <p>
 * Registered only under the {@code chaos} profile (404 otherwise). When disarmed it is a
 * pure no-op, so it is safe to slice-test.
 */
@RestController
@Profile("chaos")
class OomKillEndpoint {

	/** 32 MiB per allocated chunk. */
	private static final int CHUNK_BYTES = 32 * 1024 * 1024;

	private static final Log log = LogFactory.getLog(OomKillEndpoint.class);

	private final ChaosFaults chaosFaults;

	OomKillEndpoint(ChaosFaults chaosFaults) {
		this.chaosFaults = chaosFaults;
	}

	@PostMapping("/chaos/oom-kill")
	Map<String, Object> oomKill() {
		if (!this.chaosFaults.shouldOomKill()) {
			return Map.of("armed", false, "allocatedMb", 0);
		}
		log.warn("oomKill armed: allocating native (off-heap) memory to drive resident set past the "
				+ "cgroup limit; the kernel will SIGKILL this container (no OutOfMemoryError, no app stack trace)");
		Unsafe unsafe = unsafe();
		// Allocate and fully write native chunks, never freeing them, until
		// resident memory crosses the cgroup limit and the OOM-killer fires.
		while (true) {
			long address = unsafe.allocateMemory(CHUNK_BYTES);
			unsafe.setMemory(address, CHUNK_BYTES, (byte) 1);
		}
	}

	private static Unsafe unsafe() {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (Unsafe) field.get(null);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("sun.misc.Unsafe is unavailable", ex);
		}
	}

}
