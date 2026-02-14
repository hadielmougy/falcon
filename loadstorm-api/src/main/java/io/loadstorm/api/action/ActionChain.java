package io.loadstorm.api.action;


import java.util.*;

/**
 * An ordered chain of actions that execute sequentially.
 * Implemented as a linked list: when the first action finishes, the second one starts.
 * Each action in the chain gets its own connection pool.
 */
public final class ActionChain implements Iterable<ActionDefinition> {

    private final List<ActionDefinition> actions;

    private ActionChain(List<ActionDefinition> actions) {
        this.actions = Collections.unmodifiableList(Objects.requireNonNull(actions));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ActionDefinition> actions() {
        return actions;
    }

    public int size() {
        return actions.size();
    }

    public ActionDefinition get(int index) {
        return actions.get(index);
    }

    @Override
    public Iterator<ActionDefinition> iterator() {
        return actions.iterator();
    }

    public static final class Builder {
        private final List<ActionDefinition> actions = new ArrayList<>();

        private Builder() {}

        /**
         * Add an action to the chain with a name.
         *
         * @param name   descriptive name for this action (used in metrics/logs)
         * @param action the action to execute
         * @return this builder
         */
        public Builder then(String name, Action action) {
            actions.add(new ActionDefinition(name, action, actions.size()));
            return this;
        }

        public ActionChain build() {
            if (actions.isEmpty()) {
                throw new IllegalStateException("ActionChain must contain at least one action");
            }
            return new ActionChain(new ArrayList<>(actions));
        }
    }
}
