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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class DownstreamClientTests {

	private final ChaosState state = new ChaosState();

	private RestClient.Builder builder() {
		return RestClient.builder();
	}

	@Test
	void armedIssuesDelayRequest() {
		RestClient.Builder builder = builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("http://stub/delay/2")).andExpect(method(GET)).andRespond(withSuccess());
		DownstreamClient client = new DownstreamClient(builder, this.state, "http://stub", 2);

		this.state.arm(ActiveChaosFaults.DOWNSTREAM_LATENCY);
		client.callIfArmed();

		server.verify();
	}

	@Test
	void disarmedIssuesNoRequest() {
		RestClient.Builder builder = builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		DownstreamClient client = new DownstreamClient(builder, this.state, "http://stub", 2);

		client.callIfArmed();

		// no expectations set; verify() passes only if zero requests were issued
		server.verify();
	}

	@Test
	void wiresUnderChaosProfileWithAutoConfiguredBuilder() {
		// Regression guard: DownstreamClient needs an auto-configured RestClient.Builder
		// bean.
		// Spring Boot 4's modular layout means spring-boot-starter-webmvc does NOT
		// provide it —
		// the app needs spring-boot-starter-restclient on the main classpath. Boot the
		// bean
		// against the RestClient autoconfig under the chaos profile to prove it wires.
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
			.withBean(ChaosState.class)
			.withUserConfiguration(DownstreamClient.class)
			.withPropertyValues("spring.profiles.active=chaos", "chaos.downstream.base-url=http://stub",
					"chaos.downstream.delay-seconds=2")
			.run((context) -> assertThat(context).hasNotFailed().hasSingleBean(DownstreamClient.class));
	}

}
