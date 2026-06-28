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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * DataSource decorator (chaos profile only) that counts JDBC statement creations on the
 * current thread. The N+1 augmentation reads this per request: one logical owner read
 * that issues N physical queries shows a high count, naming it N+1 — a discriminator the
 * latency signal alone cannot provide. Installed only under the {@code chaos} profile, so
 * the default build never wraps its pool.
 */
public class QueryCountingDataSource extends DelegatingDataSource {

	private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

	public QueryCountingDataSource(DataSource delegate) {
		super(delegate);
	}

	/** Zero the current thread's query counter (called at the start of a request). */
	public static void reset() {
		COUNT.get()[0] = 0;
	}

	/** Read the current thread's query counter. */
	public static int count() {
		return COUNT.get()[0];
	}

	@Override
	public Connection getConnection() throws SQLException {
		return wrap(super.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return wrap(super.getConnection(username, password));
	}

	private static Connection wrap(Connection delegate) {
		return (Connection) Proxy.newProxyInstance(QueryCountingDataSource.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("prepareStatement") || name.equals("prepareCall")
							|| name.equals("createStatement")) {
						COUNT.get()[0]++;
					}
					try {
						return method.invoke(delegate, args);
					}
					catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
	}

}
