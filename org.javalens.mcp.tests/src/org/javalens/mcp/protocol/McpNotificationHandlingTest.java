package org.javalens.mcp.protocol;

import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How {@link McpProtocolHandler} treats JSON-RPC notifications, pinned through the exact
 * log records it writes. The client-to-server notifications of MCP revision 2024-11-05 (the
 * protocolVersion the server declares) are notifications/initialized,
 * notifications/cancelled, notifications/progress and notifications/roots/list_changed;
 * together with the legacy "initialized" they are recognized and never logged as unknown.
 * Any other method is logged as an unknown notification at WARN. No notification gets a
 * response.
 */
class McpNotificationHandlingTest {

    private RecordingLogger log;
    private McpProtocolHandler handler;

    @BeforeEach
    void setUp() {
        log = new RecordingLogger();
        handler = new McpProtocolHandler(new ToolRegistry(), log);
    }

    @Test
    @DisplayName("notifications/initialized acknowledges the handshake and is not unknown")
    void initializedNotification() {
        assertNull(handler.processMessage("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

        assertEquals(List.of(
                "DEBUG Handling notification: method=notifications/initialized",
                "INFO Client initialization complete"),
            log.records);
    }

    @Test
    @DisplayName("the legacy initialized notification acknowledges the handshake")
    void legacyInitializedNotification() {
        assertNull(handler.processMessage("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}"));

        assertEquals(List.of(
                "DEBUG Handling notification: method=initialized",
                "INFO Client initialization complete"),
            log.records);
    }

    @Test
    @DisplayName("notifications/cancelled is recognized")
    void cancelledNotification() {
        assertNull(handler.processMessage(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3,\"reason\":\"user\"}}"));

        assertEquals(List.of(
                "DEBUG Handling notification: method=notifications/cancelled",
                "DEBUG Request cancelled by client"),
            log.records);
    }

    @Test
    @DisplayName("notifications/progress is recognized")
    void progressNotification() {
        assertNull(handler.processMessage(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"t1\",\"progress\":1,\"total\":2}}"));

        assertEquals(List.of("DEBUG Handling notification: method=notifications/progress"), log.records);
    }

    @Test
    @DisplayName("notifications/roots/list_changed is recognized")
    void rootsListChangedNotification() {
        assertNull(handler.processMessage("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}"));

        assertEquals(List.of("DEBUG Handling notification: method=notifications/roots/list_changed"), log.records);
    }

    @Test
    @DisplayName("a method outside the client notifications is logged as unknown at WARN")
    void unknownNotification() {
        assertNull(handler.processMessage("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/unheard_of\"}"));

        assertEquals(List.of(
                "DEBUG Handling notification: method=notifications/unheard_of",
                "WARN Unknown notification: notifications/unheard_of"),
            log.records);
    }

    @Test
    @DisplayName("a server-to-client notification sent to the server is logged as unknown at WARN")
    void serverToClientNotification() {
        assertNull(handler.processMessage("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"));

        assertEquals(List.of(
                "DEBUG Handling notification: method=notifications/tools/list_changed",
                "WARN Unknown notification: notifications/tools/list_changed"),
            log.records);
    }

    /** Records every call at every level as "LEVEL formatted-message". */
    private static final class RecordingLogger extends LegacyAbstractLogger {
        private static final long serialVersionUID = 1L;

        final List<String> records = new ArrayList<>();

        RecordingLogger() {
            this.name = "recording";
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                                   Object[] arguments, Throwable throwable) {
            records.add(level + " " + MessageFormatter.basicArrayFormat(messagePattern, arguments));
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }
    }
}
