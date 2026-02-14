package io.loadstorm.api;

@FunctionalInterface
public interface Action {

    void execute(Session session) throws Exception;

}
