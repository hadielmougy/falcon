package io.loadstorm.api.action;

import io.loadstorm.api.environment.Session;

@FunctionalInterface
public interface Action {

    void execute(Session session) throws Exception;
}
