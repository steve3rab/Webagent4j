package io.webagent4j.common;

/**
 * Marker interface implemented by locator engine exceptions to allow typed classification without
 * creating a dependency from the API to implementation classes.
 */
public interface ILocatorFailure {

    /** Returns true when this failure represents a safe "not found" outcome. */
    default boolean isNotFound() {
        return false;
    }

    /** Returns true when this failure represents a safe "ambiguous" outcome. */
    default boolean isAmbiguous() {
        return false;
    }
}
