package tech.mikhailov.fsm.orch.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket, with SockJS beside it — what replaces the page's three polling timers.
 *
 * <p>WHY A SOCKET AT ALL. A run is 282 markers over 6 to 26 hours and the old page asked for the whole
 * state document every three seconds for the entire time, whether or not anything had moved. That is
 * both stale (up to three seconds behind on a transition anyone is watching for) and expensive (a full
 * table scan and a full serialisation, thirty thousand times a day, almost all of it identical to the
 * answer before it). A push is neither.
 *
 * <p>THE IN-MEMORY BROKER IS THE RIGHT ONE HERE. There is exactly one orchestrator process — the prove
 * is single-flight around one cached workspace — so a relay to an external broker would add a
 * daemon, a port and a failure mode in exchange for fan-out this deployment cannot use.
 *
 * <p>TWO ENDPOINT REGISTRATIONS, ONE PATH. The native registration handles a browser that can hold a
 * WebSocket open, which is every browser on a direct connection; the SockJS one handles the case this
 * dashboard has actually hit before — a reverse proxy that does not forward {@code Upgrade}. Spring maps
 * the native handler at exactly {@code /ws} and SockJS at {@code /ws/**}, so they coexist rather than
 * shadowing each other.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * The ceiling on ONE outbound message.
     *
     * <p>{@link LiveTopics#STATE} carries every marker with its {@code description} and {@code evidence}
     * CLOBs and every artifact with its test, its diff and its drafted PR body. On a 282-marker report
     * that is comfortably past the 512 KB default, and the default does not degrade politely: the
     * session is closed as unreliable and the page goes quiet with no error anywhere. 32 MB is chosen
     * to be past any plausible report rather than as a tuning figure.
     */
    static final int SEND_BUFFER_BYTES = 32 * 1024 * 1024;

    /**
     * Inbound is the opposite: the page sends nothing but {@code /app/refresh} with an empty body, so
     * the default 64 KB is already three orders of magnitude more than it needs. Pinned rather than
     * raised, because this is the direction an untrusted client controls.
     */
    static final int MESSAGE_SIZE_BYTES = 64 * 1024;

    /**
     * How long a single send may take before the session is treated as dead.
     *
     * <p>A browser tab that has been suspended stops reading its socket, and the server's buffer for it
     * fills with snapshots. Without a limit that buffer is held for as long as the tab exists; with one,
     * the slow consumer is dropped and reconnects with a fresh snapshot when it wakes.
     */
    static final int SEND_TIME_LIMIT_MS = 20_000;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(LiveTopics.TOPIC_PREFIX);
        registry.setApplicationDestinationPrefixes(LiveTopics.APP_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // setAllowedOriginPatterns("*") and NOT setAllowedOrigins("*"): the dashboard is served from
        // the same origin as this socket in every deployment, but the page is also opened through
        // whatever host or tunnel an operator happens to have in front of it — and an origin check that
        // rejects those fails as a socket that never connects, i.e. as a page that silently reverts to
        // polling. There is nothing to protect here: every destination is read-only public run state,
        // and the one inbound destination asks for a snapshot of it.
        registry.addEndpoint(LiveTopics.ENDPOINT).setAllowedOriginPatterns("*");
        registry.addEndpoint(LiveTopics.ENDPOINT).setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_BYTES);
        registration.setSendBufferSizeLimit(SEND_BUFFER_BYTES);
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MS);
    }

    // The one INBOUND destination, /app/refresh, is handled by LivePublisher. It is deliberately not
    // mapped here: a @MessageMapping method's arguments are resolved by the messaging argument
    // resolvers, which know about payloads and headers and not about beans — and injecting the
    // publisher into this class instead would make a configurer depend on SimpMessagingTemplate, which
    // this configuration is itself responsible for creating.
}
