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

import java.sql.Connection;

import javax.sql.DataSource;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class QueryCountInterceptorTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	/** Increment the shared counter the same way a real query would (via the proxy). */
	private static void simulateQueries(int n) throws Exception {
		Connection delegateConnection = mock(Connection.class);
		DataSource delegate = mock(DataSource.class);
		given(delegate.getConnection()).willReturn(delegateConnection);
		Connection connection = new QueryCountingDataSource(delegate).getConnection();
		for (int i = 0; i < n; i++) {
			connection.createStatement();
		}
	}

	private static MockHttpServletRequest ownerSearchRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/owners");
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/owners");
		return request;
	}

	@Test
	void recordsPerRequestQueryCountIntoSummaryTaggedByUri() throws Exception {
		QueryCountInterceptor interceptor = new QueryCountInterceptor(this.registry);
		MockHttpServletRequest request = ownerSearchRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		interceptor.preHandle(request, response, new Object());
		simulateQueries(4);
		interceptor.afterCompletion(request, response, new Object(), null);

		DistributionSummary summary = this.registry.get("petclinic.owner.search.queries")
			.tag("uri", "/owners")
			.summary();
		assertThat(summary.count()).isEqualTo(1);
		assertThat(summary.totalAmount()).isEqualTo(4.0);
	}

	@Test
	void preHandleResetsCounterFromPreviousRequest() throws Exception {
		QueryCountInterceptor interceptor = new QueryCountInterceptor(this.registry);
		MockHttpServletResponse response = new MockHttpServletResponse();

		// leftover from a prior request on this thread
		simulateQueries(9);
		interceptor.preHandle(ownerSearchRequest(), response, new Object());
		simulateQueries(2);
		interceptor.afterCompletion(ownerSearchRequest(), response, new Object(), null);

		DistributionSummary summary = this.registry.get("petclinic.owner.search.queries")
			.tag("uri", "/owners")
			.summary();
		assertThat(summary.totalAmount()).isEqualTo(2.0);
	}

}
