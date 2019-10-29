//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoFragmentTest
{
    private WebSocketServer server;
    private TestFrameHandler serverHandler;
    private URI serverUri;

    private WebSocketCoreClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        serverHandler = new TestFrameHandler();

        server = new WebSocketServer(serverHandler);
        server.start();
        serverUri = new URI("ws://localhost:" + server.getLocalPort());

        client = new WebSocketCoreClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testAutoFragmentToMaxFrameSize() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<FrameHandler.CoreSession> connect = client.connect(clientHandler, serverUri);
        connect.get(5, TimeUnit.SECONDS);

        // Turn off fragmentation on the client.
        clientHandler.coreSession.setMaxFrameSize(0);
        clientHandler.coreSession.setAutoFragment(false);

        // Set the server should fragment to the maxFrameSize.
        int maxFrameSize = 30;
        assertTrue(serverHandler.open.await(5, TimeUnit.SECONDS));
        serverHandler.coreSession.setMaxFrameSize(maxFrameSize);
        serverHandler.coreSession.setAutoFragment(true);

        // Send a message which is too large.
        int size = maxFrameSize * 2;
        byte[] message = new byte[size];
        Arrays.fill(message, 0, size, (byte)'X');
        clientHandler.coreSession.sendFrame(new Frame(OpCode.BINARY, BufferUtil.toBuffer(message)), Callback.NOOP, false);

        // We should not receive any frames larger than the max frame size.
        // So our message should be split into two frames.
        Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.BINARY));
        assertThat(frame.getPayloadLength(), is(maxFrameSize));
        assertThat(frame.isFin(), is(false));

        // Second frame should be final and contain rest of the data.
        frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat(frame.getPayloadLength(), is(maxFrameSize));
        assertThat(frame.isFin(), is(true));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
    }

    @Disabled("permessage-deflate autoFragment not implemented yet")
    @Test
    public void testIncomingAutoFragmentWithPermessageDeflate() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, serverUri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate");
        CompletableFuture<FrameHandler.CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        // Turn off fragmentation on the client.
        clientHandler.coreSession.setMaxFrameSize(0);
        clientHandler.coreSession.setAutoFragment(false);

        // Set a small maxFrameSize on the server.
        int maxFrameSize = 10;
        assertTrue(serverHandler.open.await(5, TimeUnit.SECONDS));
        serverHandler.coreSession.setMaxFrameSize(maxFrameSize);
        serverHandler.coreSession.setAutoFragment(true);

        // Generate a large random payload.
        int payloadSize = 1000;
        Random rand = new Random();
        ByteBuffer payload = BufferUtil.allocate(payloadSize);
        BufferUtil.clearToFill(payload);
        for (int i=0; i<payloadSize; i++)
            payload.put((byte)rand.nextInt(Byte.MAX_VALUE));
        BufferUtil.flipToFlush(payload, 0);

        // Send the large random payload which should be fragmented on the server.
        clientHandler.coreSession.sendFrame(new Frame(OpCode.BINARY, BufferUtil.copy(payload)), Callback.NOOP, false);

        // Assemble the message from the fragmented frames.
        ByteBuffer message = BufferUtil.allocate(payloadSize*2);
        Frame frame = serverHandler.receivedFrames.poll(1, TimeUnit.SECONDS);
        while (frame != null)
        {
            int framePayloadLen = frame.getPayloadLength();
            int append = BufferUtil.append(message, frame.getPayload());
            assertThat(framePayloadLen, lessThanOrEqualTo(maxFrameSize));
            assertThat(append, is(framePayloadLen));
            frame = serverHandler.receivedFrames.poll(1, TimeUnit.SECONDS);
        }

        assertThat(message, is(payload));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOutgoingAutoFragmentWithPermessageDeflate() throws Exception
    {
        // Send a frame smaller than the max frame size that increases in size when compressed.
        // It should then be fragmented by permessage-deflate so no frame is sent larger than the maxFrameSize.

        // Compress a large payload a few times so compressing again will only increase size.
        int payloadSize = 10000;
        byte[] array = new byte[payloadSize];
        Arrays.fill(array, (byte)'X');
        ByteBuffer payload = compress(compress(BufferUtil.toBuffer(array)));
        ByteBuffer compressedPayload = compress(payload);

        // Use a maxFameSize bigger than uncompressed payload but smaller than the compressed payload.
        int maxFrameSize = 37;
        assertThat(payload.remaining(), lessThanOrEqualTo(maxFrameSize));
        assertThat(compressedPayload.remaining(), greaterThan(maxFrameSize));

        // Connect to server with permessage-deflate enabled.
        TestFrameHandler clientHandler = new TestFrameHandler();
        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, serverUri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate");
        CompletableFuture<FrameHandler.CoreSession> connect = client.connect(upgradeRequest);
        connect.get(5, TimeUnit.SECONDS);

        // Turn off fragmentation on the client.
        clientHandler.coreSession.setMaxFrameSize(0);
        clientHandler.coreSession.setAutoFragment(false);

        // Set maxFrameSize and autoFragment on the server.
        assertTrue(serverHandler.open.await(5, TimeUnit.SECONDS));
        serverHandler.coreSession.setMaxFrameSize(maxFrameSize);
        serverHandler.coreSession.setAutoFragment(true);

        // Send the payload which should be fragmented by the server permessage-deflate.
        serverHandler.coreSession.sendFrame(new Frame(OpCode.BINARY, BufferUtil.copy(payload)), Callback.NOOP, false);

        // Assemble the message from the fragmented frames.
        ByteBuffer message = BufferUtil.allocate(payload.remaining()*2);
        Frame frame = clientHandler.receivedFrames.poll(1, TimeUnit.SECONDS);
        int numFrames = 0;
        while (frame != null)
        {
            numFrames++;
            int framePayloadLen = frame.getPayloadLength();
            int append = BufferUtil.append(message, frame.getPayload());
            assertThat(framePayloadLen, lessThanOrEqualTo(maxFrameSize));
            assertThat(append, is(framePayloadLen));
            frame = clientHandler.receivedFrames.poll(1, TimeUnit.SECONDS);
        }

        // We received correct payload in 2 frames.
        assertThat(numFrames, is(2));
        assertThat(message, is(payload));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
    }

    private ByteBuffer compress(ByteBuffer input)
    {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(BufferUtil.copy(input));

        int bufferSize = 1000;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        while (true)
        {
            int compressed = deflater.deflate(buffer, 0 , bufferSize, Deflater.SYNC_FLUSH);
            if (compressed <= 0)
                break;
            out.write(buffer, 0, compressed);
        }

        return BufferUtil.toBuffer(out.toByteArray());
    }
}
