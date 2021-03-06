package com.oakinvest.b2g.bitcoin.test.util.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oakinvest.b2g.dto.bitcoin.status.ApplicationStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * Status web socket session handler.
 */
public class StatusWebSocketSessionHandler extends TextWebSocketHandler {

    /**
     * Object to Json mapper.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Last status of the application.
     */
    private ApplicationStatus lastStatus;

    /**
     * True is a new message has been received.
     */
    private boolean newMessageReceived = false;

    @Override
    protected void handleTextMessage(final WebSocketSession session, final TextMessage message) {
        try {
            lastStatus = mapper.readValue(message.getPayload(), ApplicationStatus.class);
            newMessageReceived = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Wait for a new message from a websocket and returns it.
     * @return new status
     */
    public ApplicationStatus getNewMessage() {
        // We wait until a new message is here.
        while (!newMessageReceived) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        newMessageReceived = false;
        return lastStatus;
    }

}
