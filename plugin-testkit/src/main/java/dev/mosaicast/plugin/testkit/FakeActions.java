// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.ActionCall;
import dev.mosaicast.plugin.api.ActionHandler;
import dev.mosaicast.plugin.api.ActionRejectedException;
import dev.mosaicast.plugin.api.Actions;
import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/**
 * An in-memory {@link Actions}: the plugin registers its handlers as it would on the host, and a test
 * {@link #call(Caller, String, Scope, Object) calls} them as any {@link Caller} (ARCHITECTURE §7.9, §13.5).
 *
 * <p>Obtained from {@link FakePluginContext#withActions(String...)}, which stands in for the manifest's
 * {@code actions.names}. The floors are {@link #withCallableBy(Role)} and {@link #withNameFloor(String, Role)}.
 *
 * <pre>{@code
 * FakePluginContext ctx = new FakePluginContext().withActions("join", "move");
 * plugin.register(ctx);
 *
 * Caller alice = Callers.fan();
 * ctx.actions().call(alice, "join", Scope.episode("s2e04"), Map.of());
 * ActionRejectedException e = assertThrows(ActionRejectedException.class,
 *         () -> ctx.actions().call(alice, "move", Scope.episode("s2e04"), Map.of("cell", 3)));
 * assertEquals("not-your-turn", e.code());
 * }</pre>
 *
 * <p><strong>Synchronous by design.</strong> A call runs its handler on the test's thread and returns its
 * answer as the JSON the host would send. There is no executor, deadline or rate limit — those are the host's
 * to test, not the plugin's — so a 503 {@code action-busy}, 504 {@code action-timeout} or 429 never happens
 * here. Where the host refuses before any plugin code runs, this throws {@link IllegalStateException} naming
 * the status it would answer. {@link ActionRejectedException} propagates as thrown, and so does anything else
 * a handler throws (the host's 500), so a test sees the bug.
 *
 * @since 0.20.0
 */
public final class FakeActions implements Actions {

    private final InMemoryDocStore store;
    private final Supplier<Instant> clock;
    // Declared names → their own callableBy floor (null: the block's).
    private final Map<String, Role> declared = new LinkedHashMap<>();
    private final Map<String, ActionHandler> handlers = new LinkedHashMap<>();
    private Role callableBy = Role.FAN;

    FakeActions(InMemoryDocStore store, Supplier<Instant> clock, String... names) {
        this.store = store;
        this.clock = clock;
        declare(names);
    }

    /** Adds declared names, as more entries in {@code actions.names}. */
    void declare(String... names) {
        Objects.requireNonNull(names, "names");
        for (String name : names) {
            Objects.requireNonNull(name, "name");
            if (!name.matches(NAME_PATTERN)) {
                throw new IllegalArgumentException(
                        "action name '" + name + "' does not match " + NAME_PATTERN + " — the host rejects the manifest at load");
            }
            declared.putIfAbsent(name, null);
        }
    }

    /**
     * Sets the block's {@code callableBy}, as the manifest's {@code actions.callableBy} does. The default is
     * {@link Role#FAN}, as the host's is.
     *
     * @param role the lowest role that may call any action; never {@code null}
     * @return this instance, for chaining
     * @throws IllegalArgumentException if a name's own floor is already below it — the host rejects a floor
     *                                  that lowers at load
     */
    public FakeActions withCallableBy(Role role) {
        Objects.requireNonNull(role, "role");
        declared.forEach((name, floor) -> {
            if (floor != null && Callers.rank(floor) < Callers.rank(role)) {
                throw new IllegalArgumentException("action '" + name + "' declares callableBy " + floor
                        + ", below the block's " + role + " — a name's floor can only raise it");
            }
        });
        this.callableBy = role;
        return this;
    }

    /**
     * Raises one declared name's floor, as {@code "names": { "start": { "callableBy": "podcaster" } }} does.
     *
     * @param name a declared name; never {@code null}
     * @param role the lowest role that may call it; never {@code null}, never below the block's
     * @return this instance, for chaining
     * @throws IllegalArgumentException if the name is not declared or the role is below the block's
     */
    public FakeActions withNameFloor(String name, Role role) {
        Objects.requireNonNull(role, "role");
        requireDeclared(name);
        if (Callers.rank(role) < Callers.rank(callableBy)) {
            throw new IllegalArgumentException("action '" + name + "': callableBy " + role + " is below the block's "
                    + callableBy + " — a name's floor can only raise it (the host rejects the manifest at load)");
        }
        declared.put(name, role);
        return this;
    }

    @Override
    public void handle(String name, ActionHandler handler) {
        Objects.requireNonNull(handler, "handler");
        requireDeclared(name);
        handlers.put(name, handler);
    }

    /**
     * The names the plugin registered a handler for — for asserting {@code register()} wired every one.
     *
     * @return the names, in registration order
     */
    public Set<String> handled() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(handlers.keySet()));
    }

    /**
     * Calls an action as {@code caller} would through {@code ctx.actions.call}, after the checks the host makes
     * first.
     *
     * @param caller  who calls; never {@code null}
     * @param name    the action; never {@code null}
     * @param scope   the scope the call is made in; never {@code null}, never {@link ScopeType#USER}
     * @param payload the payload, serialized as the browser would send it; may be {@code null}
     * @return the handler's answer as the JSON body, or {@code null} for a 204
     * @throws ActionRejectedException  as the handler threw it (the host's 422)
     * @throws IllegalStateException    where the host refuses before the handler: an undeclared name (404),
     *                                  a caller below the floor (403), a body over
     *                                  {@link Actions#MAX_BODY_BYTES} (413), or no handler registered (503)
     * @throws IllegalArgumentException if the scope is {@link ScopeType#USER}
     */
    public JsonNode call(Caller caller, String name, Scope scope, Object payload) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        if (!declared.containsKey(name)) {
            throw new IllegalStateException("action '" + name + "' is not declared (the host answers 404 unknown-action)");
        }
        Role floor = declared.get(name) == null ? callableBy : declared.get(name);
        if (Callers.rank(caller.role()) < Callers.rank(floor)) {
            throw new IllegalStateException("a " + caller.role() + " cannot call '" + name + "': its callableBy is "
                    + floor + " (the host answers 403)");
        }
        if (scope.type() == ScopeType.USER) {
            throw new IllegalArgumentException("an action is called in a page scope, never USER");
        }
        ObjectMapper mapper = store.mapper();
        JsonNode body = payload == null ? NullNode.getInstance() : mapper.valueToTree(payload);
        if (mapper.writeValueAsBytes(body).length > MAX_BODY_BYTES) {
            throw new IllegalStateException("action '" + name + "': the payload exceeds " + MAX_BODY_BYTES
                    + " bytes (the host answers 413)");
        }
        ActionHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalStateException("action '" + name + "' is declared but no handler is registered "
                    + "(the host answers 503 action-unavailable)");
        }
        Object answer = handler.handle(new Call(caller, scope, body, clock.get(), store.actingFor(caller.userId()), mapper));
        return answer == null ? null : mapper.valueToTree(answer);
    }

    /**
     * {@link #call(Caller, String, Scope, Object)}, with the answer converted to a type — as the frontend would
     * read the JSON.
     *
     * @param caller  who calls; never {@code null}
     * @param name    the action; never {@code null}
     * @param scope   the scope; never {@code null}
     * @param payload the payload; may be {@code null}
     * @param type    the type to read the answer as; never {@code null}
     * @param <T>     the answer type
     * @return the answer, or {@code null} for a 204
     */
    public <T> T call(Caller caller, String name, Scope scope, Object payload, Class<T> type) {
        Objects.requireNonNull(type, "type");
        JsonNode answer = call(caller, name, scope, payload);
        return answer == null ? null : store.mapper().treeToValue(answer, type);
    }

    private void requireDeclared(String name) {
        Objects.requireNonNull(name, "name");
        if (!declared.containsKey(name)) {
            throw new IllegalArgumentException("action '" + name + "' is not declared in the manifest's actions.names "
                    + "— declare it with FakePluginContext.withActions");
        }
    }

    /** One call, as a handler sees it. */
    private record Call(Caller caller, Scope scope, JsonNode payload, Instant receivedAt, DocStore store,
                        ObjectMapper mapper) implements ActionCall {

        @Override
        public <T> T payload(Class<T> type) {
            Objects.requireNonNull(type, "type");
            if (payload.isNull()) {
                return null;
            }
            try {
                return mapper.treeToValue(payload, type);
            } catch (JacksonException e) {
                throw new IllegalArgumentException("payload is not a " + type.getSimpleName() + ": " + e.getMessage(), e);
            }
        }
    }
}
