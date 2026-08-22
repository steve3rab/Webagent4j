package io.webagent4j.observation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Structured secret-safe event emitted through an injected listener, never a global bus. */
public sealed interface IObservationEvent
        permits IObservationEvent.ObservationStarted,
                IObservationEvent.ObservationCompleted,
                IObservationEvent.ObservationFailed,
                IObservationEvent.ObservationTruncated {

    /** Event timestamp. */
    Instant timestamp();

    /** Observation started with bounded options. */
    record ObservationStarted(Instant timestamp, ObservationMode mode, ObservationBudget budget)
            implements IObservationEvent {

        /** Validates event data. */
        public ObservationStarted {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /** Observation completed successfully. */
    record ObservationCompleted(
            Instant timestamp,
            ObservationId observationId,
            Duration duration,
            int elementsIncluded,
            int warningCount)
            implements IObservationEvent {

        /** Validates event data. */
        public ObservationCompleted {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(observationId, "observationId");
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration cannot be negative");
            }
            if (elementsIncluded < 0 || warningCount < 0) {
                throw new IllegalArgumentException("event counts cannot be negative");
            }
        }
    }

    /** Observation failed without exposing captured values. */
    record ObservationFailed(Instant timestamp, String reason) implements IObservationEvent {

        /** Validates event data. */
        public ObservationFailed {
            Objects.requireNonNull(timestamp, "timestamp");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /** One or more explicit limits truncated the observation. */
    record ObservationTruncated(Instant timestamp, List<ObservationTruncationType> types)
            implements IObservationEvent {

        /** Validates and copies event data. */
        public ObservationTruncated {
            Objects.requireNonNull(timestamp, "timestamp");
            types = List.copyOf(Objects.requireNonNull(types, "types"));
        }
    }
}
