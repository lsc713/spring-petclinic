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

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Installs the N+1 query-count augmentation under the {@code chaos} profile only: a
 * {@link BeanPostProcessor} wraps the autoconfigured pool in
 * {@link QueryCountingDataSource}, and {@link QueryCountInterceptor} records the
 * per-request count. Under the default profile this configuration is not loaded, so the
 * pool is never wrapped (A1 invariant).
 *
 * <p>
 * The {@link MeterRegistry} is injected via {@link ObjectProvider} so this configuration
 * still constructs in web-slice tests ({@code @WebMvcTest}) that auto-instantiate
 * {@link WebMvcConfigurer} beans without supplying a meter registry; the interceptor is
 * registered only when a registry is actually present.
 * </p>
 */
@Configuration
@Profile("chaos")
public class QueryCountChaosConfig implements WebMvcConfigurer {

	private final ObjectProvider<MeterRegistry> meterRegistry;

	public QueryCountChaosConfig(ObjectProvider<MeterRegistry> meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Static so it is instantiated early enough to wrap the DataSource without forcing
	 * the rest of this configuration to initialize first.
	 */
	@Bean
	static BeanPostProcessor queryCountingDataSourceWrapper() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof DataSource && !(bean instanceof QueryCountingDataSource)) {
					return new QueryCountingDataSource((DataSource) bean);
				}
				return bean;
			}
		};
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		this.meterRegistry.ifAvailable((meters) -> registry.addInterceptor(new QueryCountInterceptor(meters)));
	}

}
