package io.loadstorm.core;

import io.loadstorm.api.action.ActionChain;
import io.loadstorm.api.action.ActionDefinition;
import io.loadstorm.api.client.ClientType;
import io.loadstorm.core.client.DefaultLoadClient;
import io.loadstorm.core.client.DefaultSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionChainTest {

    @Test
    void shouldBuildChainWithMultipleActions() {
        ActionChain chain = ActionChain.builder()
                .then("login", s -> {})
                .then("browse", s -> {})
                .then("checkout", s -> {})
                .build();

        assertThat(chain.size()).isEqualTo(3);
        assertThat(chain.get(0).name()).isEqualTo("login");
        assertThat(chain.get(0).index()).isEqualTo(0);
        assertThat(chain.get(1).name()).isEqualTo("browse");
        assertThat(chain.get(1).index()).isEqualTo(1);
        assertThat(chain.get(2).name()).isEqualTo("checkout");
        assertThat(chain.get(2).index()).isEqualTo(2);
    }

    @Test
    void shouldNotBuildEmptyChain() {
        assertThatThrownBy(() -> ActionChain.builder().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectBlankActionName() {
        assertThatThrownBy(() -> new ActionDefinition("", s -> {}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBeIterable() {
        ActionChain chain = ActionChain.builder()
                .then("a", s -> {})
                .then("b", s -> {})
                .build();

        int count = 0;
        for (ActionDefinition action : chain) {
            count++;
        }
        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldBuildChainFromClient() {
        var client = DefaultLoadClient.blocking();
        client.execute("step-1", s -> {});
        client.execute("step-2", s -> {});

        ActionChain chain = client.actionChain();
        assertThat(chain.size()).isEqualTo(2);
        assertThat(client.clientType()).isEqualTo(ClientType.BLOCKING);
    }

    @Test
    void sessionShouldPreserveStateBetweenActions() {
        DefaultSession session = new DefaultSession("test-session");
        session.put("key", "value");

        assertThat(session.sessionId()).isEqualTo("test-session");
        assertThat(session.get("key")).contains("value");
        assertThat(session.get("missing")).isEmpty();
        assertThat(session.attributes()).containsEntry("key", "value");
    }

    @Test
    void clientShouldNotAllowActionsAfterBuild() {
        var client = DefaultLoadClient.blocking();
        client.execute("step-1", s -> {});
        client.actionChain(); // triggers build

        assertThatThrownBy(() -> client.execute("step-2", s -> {}))
                .isInstanceOf(IllegalStateException.class);
    }
}
