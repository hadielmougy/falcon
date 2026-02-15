package io.falcon.api.action;

@FunctionalInterface
public interface Action {

    void execute(Session session) throws Exception;
}
