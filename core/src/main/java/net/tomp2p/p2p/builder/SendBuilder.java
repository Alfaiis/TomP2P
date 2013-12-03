/*
 * Copyright 2012 Thomas Bocek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.tomp2p.p2p.builder;

import net.tomp2p.futures.FutureSend;
import net.tomp2p.futures.ProgressListener;
import net.tomp2p.message.Buffer;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.Number160;
import net.tomp2p.rpc.SendDirectBuilderI;

public class SendBuilder extends DHTBuilder<SendBuilder> implements SendDirectBuilderI {

    private final static FutureSend FUTURE_SHUTDOWN = new FutureSend(null)
            .setFailed("send builder - peer is shutting down");

    private Buffer buffer;

    private Object object;

    //
    private boolean cancelOnFinish = false;

    private boolean streaming = false;

    private ProgressListener progressListener;

    public SendBuilder(Peer peer, Number160 locationKey) {
        super(peer, locationKey);
        self(this);
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public SendBuilder setBuffer(Buffer buffer) {
        this.buffer = buffer;
        return this;
    }

    public Object getObject() {
        return object;
    }

    public SendBuilder setObject(Object object) {
        this.object = object;
        return this;
    }

    public boolean isCancelOnFinish() {
        return cancelOnFinish;
    }

    public SendBuilder setCancelOnFinish(boolean cancelOnFinish) {
        this.cancelOnFinish = cancelOnFinish;
        return this;
    }

    public SendBuilder setCancelOnFinish() {
        this.cancelOnFinish = true;
        return this;
    }

    public boolean isRaw() {
        return object == null;
    }

    public SendBuilder streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public boolean streaming() {
        return streaming;
    }

    public SendBuilder setStreaming() {
        this.streaming = true;
        return this;
    }

    public FutureSend start() {
        if (peer.isShutdown()) {
            return FUTURE_SHUTDOWN;
        }
        preBuild("send-builder");
        return peer.getDistributedHashMap().direct(this);
    }

    public SendBuilder progressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
        return this;
    }

    public ProgressListener progressListener() {
        return progressListener;
    }
}
