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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls a stub downstream dependency so a slow call appears as a client span in the trace
 * (W5a). Under the {@code downstreamLatency} scenario {@link #callIfArmed()} issues
 * {@code GET {base-url}/delay/{n}} (n seconds, server-side), so the latency lives in the
 * downstream service and the auto-instrumented {@link RestClient} span captures it. The
 * metric only says the request is slow; the trace localizes it to this span. Chaos
 * profile only.
 */
@Component
@Profile("chaos")
public class DownstreamClient {

	private final ChaosState state;

	private final RestClient restClient;

	private final int delaySeconds;

	public DownstreamClient(RestClient.Builder builder, ChaosState state,
			@Value("${chaos.downstream.base-url}") String baseUrl,
			@Value("${chaos.downstream.delay-seconds:2}") int delaySeconds) {
		this.state = state;
		this.restClient = builder.baseUrl(baseUrl).build();
		this.delaySeconds = delaySeconds;
	}

	/**
	 * When {@code downstreamLatency} is armed, call the stub's {@code /delay/{n}}
	 * endpoint; otherwise do nothing. The request thread blocks for the downstream delay
	 * (that is the point — the slow span).
	 */
	public void callIfArmed() {
		if (this.state.isArmed(ActiveChaosFaults.DOWNSTREAM_LATENCY)) {
			this.restClient.get().uri("/delay/{n}", this.delaySeconds).retrieve().toBodilessEntity();
		}
	}

}
