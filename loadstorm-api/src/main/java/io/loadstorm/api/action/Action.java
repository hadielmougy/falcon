package io.loadstorm.api.action;

@FunctionalInterface
public interface Action {

    void execute(Session session) throws Exception;
}
