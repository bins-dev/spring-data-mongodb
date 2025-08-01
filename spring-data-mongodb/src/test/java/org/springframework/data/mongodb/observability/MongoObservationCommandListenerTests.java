/*
 * Copyright 2002-2025 the original author or authors.
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
package org.springframework.data.mongodb.observability;

import static io.micrometer.core.tck.MeterRegistryAssert.*;
import static org.mockito.Mockito.*;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.observability.MongoObservation.LowCardinalityCommandKeyNames;

import com.mongodb.ConnectionString;
import com.mongodb.RequestContext;
import com.mongodb.ServerAddress;
import com.mongodb.client.SynchronousContextProvider;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;

/**
 * Series of test cases exercising {@link MongoObservationCommandListener}.
 *
 * @author Marcin Grzejszczak
 * @author Greg Turnquist
 * @author Mark Paluch
 * @author François Kha
 * @author Michal Domagala
 */
class MongoObservationCommandListenerTests {

	ObservationRegistry observationRegistry;
	MeterRegistry meterRegistry;

	MongoObservationCommandListener listener;

	@BeforeEach
	void setup() {

		this.meterRegistry = new SimpleMeterRegistry();
		this.observationRegistry = ObservationRegistry.create();
		this.observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));

		this.listener = new MongoObservationCommandListener(observationRegistry);
	}

	@Test
	void commandStartedShouldNotInstrumentWhenAdminDatabase() {

		// when
		listener.commandStarted(new CommandStartedEvent(null, 0, 0, null, "admin", "", null));

		// then
		assertThat(meterRegistry).hasNoMetrics();
	}

	@Test
	void commandStartedShouldNotInstrumentWhenNoRequestContext() {

		// when
		listener.commandStarted(new CommandStartedEvent(null, 0, 0, null, "some name", "", null));

		// then
		assertThat(meterRegistry).hasNoMetrics();
	}

	@Test
	void commandStartedShouldNotInstrumentWhenNoParentSampleInRequestContext() {

		// when
		listener.commandStarted(new CommandStartedEvent(new MapRequestContext(), 0, 0, null, "some name", "", null));

		// then
		assertThat(meterRegistry).hasMeterWithName("spring.data.mongodb.command.active");
	}

	@Test // GH-4994
	void commandStartedShouldAlwaysIncludeCollection() {

		// when
		listener.commandStarted(new CommandStartedEvent(new MapRequestContext(), 0, 0, null, "some name", "hello", null));

		// then
		// although command 'hello' is collection-less, metric must have tag "db.mongodb.collection"
		assertThat(meterRegistry).hasMeterWithNameAndTags(
				"spring.data.mongodb.command.active",
				Tags.of("db.mongodb.collection", "none"));
	}

	@Test
	void successfullyCompletedCommandShouldCreateTimerWhenParentSampleInRequestContext() {

		// given
		Observation parent = Observation.start("name", observationRegistry);
		RequestContext traceRequestContext = getContext();

		// when
		listener.commandStarted(new CommandStartedEvent(traceRequestContext, 0, 0, //
				new ConnectionDescription( //
						new ServerId( //
								new ClusterId("description"), //
								new ServerAddress("localhost", 1234))), "database", "insert", //
				new BsonDocument("collection", new BsonString("user"))));
		listener.commandSucceeded(new CommandSucceededEvent(traceRequestContext, 0, 0, null, "insert", null, null, 0));

		// then
		assertThatTimerRegisteredWithTags();
	}

	@Test
	void successfullyCompletedCommandWithCollectionHavingCommandNameShouldCreateTimerWhenParentSampleInRequestContext() {

		// given
		Observation parent = Observation.start("name", observationRegistry);
		RequestContext traceRequestContext = getContext();

		// when
		listener.commandStarted(new CommandStartedEvent(traceRequestContext, 0, 0, //
				new ConnectionDescription( //
						new ServerId( //
								new ClusterId("description"), //
								new ServerAddress("localhost", 1234))), //
				"database", "aggregate", //
				new BsonDocument("aggregate", new BsonString("user"))));
		listener.commandSucceeded(new CommandSucceededEvent(traceRequestContext, 0, 0, null, "aggregate", null, null, 0));

		// then
		assertThatTimerRegisteredWithTags();
	}

	@Test
	void successfullyCompletedCommandWithoutClusterInformationShouldCreateTimerWhenParentSampleInRequestContext() {

		// given
		Observation parent = Observation.start("name", observationRegistry);
		RequestContext traceRequestContext = getContext();

		// when
		listener.commandStarted(new CommandStartedEvent(traceRequestContext, 0, 0, null, "database", "insert",
				new BsonDocument("collection", new BsonString("user"))));
		listener.commandSucceeded(new CommandSucceededEvent(traceRequestContext, 0, 0, null, "insert", null, null, 0));

		assertThat(meterRegistry).hasTimerWithNameAndTags(MongoObservation.MONGODB_COMMAND_OBSERVATION.getName(),
				KeyValues.of(LowCardinalityCommandKeyNames.MONGODB_COLLECTION.withValue("user"),
						LowCardinalityCommandKeyNames.DB_NAME.withValue("database"),
						LowCardinalityCommandKeyNames.MONGODB_COMMAND.withValue("insert"),
						LowCardinalityCommandKeyNames.DB_SYSTEM.withValue("mongodb")).and("error", "none"));
	}

	@Test
	void commandWithErrorShouldCreateTimerWhenParentSampleInRequestContext() {

		// given
		Observation parent = Observation.start("name", observationRegistry);
		RequestContext traceRequestContext = getContext();

		// when
		listener.commandStarted(new CommandStartedEvent(traceRequestContext, 0, 0, //
				new ConnectionDescription( //
						new ServerId( //
								new ClusterId("description"), //
								new ServerAddress("localhost", 1234))), //
				"database", "insert", //
				new BsonDocument("collection", new BsonString("user"))));
		listener.commandFailed( //
				new CommandFailedEvent(traceRequestContext, 0, 0, null, "db", "insert", 0, new IllegalAccessException()));

		// then
		assertThatTimerRegisteredWithTags();
	}

	@Test // GH-4481
	void completionShouldIgnoreIncompatibleObservationContext() {

		// given
		RequestContext traceRequestContext = getContext();

		Observation observation = mock(Observation.class);
		traceRequestContext.put(ObservationThreadLocalAccessor.KEY, observation);

		// when
		listener.commandSucceeded(new CommandSucceededEvent(traceRequestContext, 0, 0, null, "insert", null, null, 0));

		verify(observation).getContext();
		verifyNoMoreInteractions(observation);
	}

	@Test // GH-4481
	void failureShouldIgnoreIncompatibleObservationContext() {

		// given
		RequestContext traceRequestContext = getContext();

		Observation observation = mock(Observation.class);
		traceRequestContext.put(ObservationThreadLocalAccessor.KEY, observation);

		// when
		listener.commandFailed(new CommandFailedEvent(traceRequestContext, 0, 0, null, "db", "insert", 0, null));

		verify(observation).getContext();
		verifyNoMoreInteractions(observation);
	}

	@Test // GH-4321
	void shouldUseObservationConvention() {

		// given
		MongoHandlerObservationConvention customObservationConvention = new MongoHandlerObservationConvention() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return MongoHandlerObservationConvention.super.supportsContext(context);
			}

			@Override
			public String getName() {
				return "custom.name";
			}
		};
		this.listener = new MongoObservationCommandListener(observationRegistry, mock(ConnectionString.class),
				customObservationConvention);

		// when
		listener.commandStarted(new CommandStartedEvent(new MapRequestContext(), 0, 0, null, "some name", "", null));

		// then
		assertThat(meterRegistry).hasMeterWithName("custom.name.active");
	}

	private RequestContext getContext() {
		return ((SynchronousContextProvider) ContextProviderFactory.create(observationRegistry)).getContext();
	}

	private void assertThatTimerRegisteredWithTags() {

		assertThat(meterRegistry) //
				.hasTimerWithNameAndTags(MongoObservation.MONGODB_COMMAND_OBSERVATION.getName(),
						KeyValues.of(LowCardinalityCommandKeyNames.MONGODB_COLLECTION.withValue("user")));
	}

}
