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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records the per-request JDBC statement count (from {@link QueryCountingDataSource})
 * into a Micrometer {@code DistributionSummary} tagged by request uri. The mean of this
 * summary is the N+1 augmentation signal: high queries-per-request names a single logical
 * read that fanned out into N physical queries. Chaos profile only.
 */
public class QueryCountInterceptor implements HandlerInterceptor {

	private final MeterRegistry registry;

	private final ConcurrentMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

	public QueryCountInterceptor(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		QueryCountingDataSource.reset();
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		summaryFor(uriOf(request)).record(QueryCountingDataSource.count());
	}

	private DistributionSummary summaryFor(String uri) {
		return this.summaries.computeIfAbsent(uri,
				(key) -> DistributionSummary.builder("petclinic.owner.search.queries")
					.tag("uri", key)
					.register(this.registry));
	}

	private static String uriOf(HttpServletRequest request) {
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		return pattern != null ? pattern.toString() : request.getRequestURI();
	}

}
