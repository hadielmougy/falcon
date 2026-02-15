package io.falcon.api.action;

public record ActionDefinition(String name, Action action, int index) {

    public ActionDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Action name must not be blank");
        }
        if (action == null) {
            throw new IllegalArgumentException("Action must not be null");
        }
        if (index < 0) {
            throw new IllegalArgumentException("Index must be non-negative");
        }
    }
}
