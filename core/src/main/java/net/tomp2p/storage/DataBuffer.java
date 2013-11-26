package net.tomp2p.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DataBuffer {

    private final List<ByteBuffer> buffers;
    private final List<Integer> marks;

    private int alreadyTransferred = 0;
    private int bufferSize = 0;
    
    public DataBuffer(final byte[] buffer) {
        buffers = new ArrayList<ByteBuffer>(1);
        marks = new ArrayList<Integer>(1);
        buffers.add(ByteBuffer.wrap(buffer));
        marks.add(0);
    }

    public DataBuffer(final ByteBuf buf) {
        final ByteBuffer[] byteBuffers = buf.nioBuffers();
        final int len = byteBuffers.length;
        if (len < 1) {
            throw new IllegalArgumentException("cannot convert this netty buffer");
        }
        buffers = new ArrayList<ByteBuffer>(len);
        marks = new ArrayList<Integer>(len);
        for (int i = 0; i < len; i++) {
            buffers.add(byteBuffers[i]);
            marks.add(byteBuffers[i].position());
        }
    }

    public DataBuffer() {
        buffers = new ArrayList<ByteBuffer>(1);
        marks = new ArrayList<Integer>(1);
    }

    private DataBuffer(final List<ByteBuffer> buffers, List<Integer> marks) {
        this.buffers = buffers;
        this.marks = marks;
        final Iterator<ByteBuffer> iterator1 = buffers.iterator();
        final Iterator<Integer> iterator2 = marks.iterator();
        while (iterator1.hasNext() && iterator2.hasNext()) {
            iterator1.next().position(iterator2.next());
        }
    }

    public List<ByteBuffer> bufferList() {
        return buffers;
    }

    // from here, work with shallow copies

    public DataBuffer shallowCopy() {
        return new DataBuffer(buffers, marks);
    }

    public ByteBuf toByteBuffer() {
        return Unpooled.wrappedBuffer(shallowCopy().buffers.toArray(new ByteBuffer[0]));
    }

    /**
     * Transfers the data from this buffer the CompositeByteBuf.
     * 
     * @param buf
     *            The CompositeByteBuf, where the data from this buffer is trasfered to
     */
    public void transferTo(final CompositeByteBuf buf) {
        //set the capacity of the last buffer, otherwise it will be filled with 0
        buf.capacity(buf.writerIndex());
        //TODO: add component only if we have enough bytes, otherwise copy.
        final DataBuffer copy = shallowCopy();
        for (ByteBuffer buffer : copy.bufferList()) {
            buf.addComponent(Unpooled.wrappedBuffer(buffer));
            int size = buffer.limit() - buffer.position();
            buf.writerIndex(buf.writerIndex() + size);
            alreadyTransferred += size;
        }
    }
    
    public int transferFrom(final ByteBuf buf, final int remaining) {
        int size = 0;
        final ByteBuffer[] byteBuffers = buf.nioBuffers();
        final int len = byteBuffers.length;
        if (len < 1) {
            throw new IllegalArgumentException("Buffer count must >= 1.");
        }
        
        for(int i=0;i<len && size < remaining;i++) {
            int toTransfer = byteBuffers[i].limit() - byteBuffers[i].position();
            if(size + toTransfer > remaining) {
                byteBuffers[i].limit(byteBuffers[i].limit() - (size + toTransfer - remaining));
            } 
            buffers.add(byteBuffers[i]);
            marks.add(byteBuffers[i].position());
            size += byteBuffers[i].limit() - byteBuffers[i].position();
        }
        alreadyTransferred += size;
        buf.readerIndex(buf.readerIndex()+size);
        return size;
    }

    public int addBuf(final ByteBuf buf) {
        final ByteBuffer[] byteBuffers = buf.nioBuffers();
        final int len = byteBuffers.length;
        int currentSize = 0;
        for (int i = 0; i < len; i++) {
            ByteBuffer buffer = byteBuffers[i];
            int pos = buffer.position();
            int size = buffer.limit() - pos;
            currentSize += size;
            buffers.add(buffer);
            marks.add(pos);
        }
        buf.readerIndex(buf.readerIndex() + currentSize);
        bufferSize += currentSize;
        return currentSize;
    }

    public int alreadyTransferred() {
        return alreadyTransferred;
    }
    
    public int bufferSize() {
        return bufferSize;
    }

    //use only for debug as its slow
    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof DataBuffer)) {
            return false;
        }
        final DataBuffer m = ((DataBuffer) obj).shallowCopy();
        final DataBuffer copy = shallowCopy();
        
        return m.toByteBuffer().equals(copy.toByteBuffer());
    }
}
