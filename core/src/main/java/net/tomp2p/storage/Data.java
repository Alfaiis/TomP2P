/*
 * Copyright 2009 Thomas Bocek
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
package net.tomp2p.storage;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.BitSet;

import net.tomp2p.connection.DefaultSignatureFactory;
import net.tomp2p.connection.SignatureFactory;
import net.tomp2p.message.SHA1Signature;
import net.tomp2p.p2p.PeerMaker;
import net.tomp2p.peers.Number160;
import net.tomp2p.utils.Timings;
import net.tomp2p.utils.Utils;

/**
 * This class holds the data for the transport. The data is already serialized
 * and a hash may be created. It is reasonable to create the hash on the remote
 * peer, but not on the local peer. The remote peer uses the hash to tell the
 * other peers, which version is stored and its used quite often.
 * 
 * @author Thomas Bocek
 */
public class Data {

	private static final int MAX_BYTE_SIZE = 256;

	/**
	 * Tiny means 8 bit, small means 16bit, medium is 32bit.
	 * 
	 * @author Thomas Bocek
	 * 
	 */
	public enum Type {
		SMALL, MEDIUM, LARGE
	}

	private final Type type;
	private final int length;
	// the buffer contains data without the header
	private final DataBuffer buffer;

	// these flags can be modified
	private boolean basedOnFlag;
	private boolean signed;
	private boolean ttl;
	private boolean flag1;
	private boolean flag2;
	private boolean protectedEntry;

	// can be added later
	private SHA1Signature signature;
	private int ttlSeconds = -1;
	private Number160 basedOn = null;
	private PublicKey publicKey;

	// never serialized over the network in this object
	private final long validFromMillis;
	private SignatureFactory signatureFactory;
	private Number160 hash;

	/**
	 * Create a data object that does have the complete data.
	 * 
	 * @param length
	 *            The expected length of the buffer. This does not include the
	 *            header + size (2, 5, or 9).
	 * @param version
	 *            The version of a data object, optional
	 * @param ttlSeconds
	 *            The TTL of a data object, optional
	 * @param hasHash
	 *            Indication if a hash should also be transmitted
	 * @param isProtectedEntry
	 *            True if this entry is protected
	 */
	public Data(final DataBuffer buffer, final int length) {
		this.length = length;
		if (length < MAX_BYTE_SIZE) {
			this.type = Type.SMALL;
		} else if (length < MAX_BYTE_SIZE * MAX_BYTE_SIZE) {
			this.type = Type.MEDIUM;
		} else {
			this.type = Type.LARGE;
		}
		this.buffer = buffer;
		this.validFromMillis = Timings.currentTimeMillis();
	}

	/**
	 * Creates an empty data object. The data can be filled at a later stage
	 * using {@link #append(ByteBuf)}.
	 * 
	 * @param header
	 *            The 8 bit header
	 * @param length
	 *            The length, which depends on the header values
	 */
	public Data(final int header, final int length) {
		this.flag1 = isFlag1(header);
		this.flag2 = isFlag2(header);
		this.basedOnFlag = hasBasedOn(header);
		this.signed = isSigned(header);
		this.ttl = hasTTL(header);
		this.protectedEntry = isProtectedEntry(header);
		this.type = type(header);

		if (type == Type.SMALL && length > 255) {
			throw new IllegalArgumentException("Type is not small");
		} else if (type == Type.MEDIUM && (length <= 255 || length > (255 * 255))) {
			throw new IllegalArgumentException("Type is not medium");
		} else if (type == Type.LARGE && (length <= 255 * 255)) {
			throw new IllegalArgumentException("Type is not large");
		}

		this.length = length;
		this.buffer = new DataBuffer();
		this.validFromMillis = Timings.currentTimeMillis();
	}

	public Data(final Object object) throws IOException {
		this(Utils.encodeJavaObject(object));
	}

	public Data(final byte[] buffer) {
		this(buffer, 0, buffer.length);
	}
	
	public Data() {
		this(Utils.EMPTY_BYTE_ARRAY);
	}

	/**
	 * Creates a data object from an already existing byte buffer.
	 * 
	 * @param buffer
	 *            The data buffer
	 * @param version
	 *            The version of a data object, optional
	 * @param ttlSeconds
	 *            The ttl of a data object, optional
	 * @param hasHash
	 *            Indication if a hash should also be transmitted
	 * @param isProtectedEntry
	 *            True if this entry is protected
	 */
	public Data(final byte[] buffer, final int offest, final int length) {
		this.buffer = new DataBuffer(buffer, offest, length);
		this.length = length;
		if (length < MAX_BYTE_SIZE) {
			this.type = Type.SMALL;
		} else if (length < MAX_BYTE_SIZE * MAX_BYTE_SIZE) {
			this.type = Type.MEDIUM;
		} else {
			this.type = Type.LARGE;
		}
		this.validFromMillis = Timings.currentTimeMillis();
	}

	private static boolean hasEnoughDataForPublicKey(final ByteBuf buf, final int indexPublicKey, final int toRead) {
		final int len = buf.getUnsignedShort(buf.readerIndex() + indexPublicKey);
		if (len > 0 && buf.readableBytes() < toRead + len) {
			return false;
		}
		return true;
	}

	/**
	 * Reads the header. Does not modify the buffer positions if header could
	 * not be fully read.
	 * 
	 * @param buf
	 *            The buffer to read from
	 * @return The data object, may be partially filled
	 */
	public static Data decodeHeader(final ByteBuf buf, final SignatureFactory signatureFactory) {
		// 2 is the smallest packet size, we could start if we know 1 byte to
		// decode the header, but we need always need
		// a second byte. Thus, we are waiting for at least 2 bytes.
		if (buf.readableBytes() < Utils.BYTE_SIZE + Utils.BYTE_SIZE) {
			return null;
		}
		final int header = buf.getUnsignedByte(buf.readerIndex());
		final Data.Type type = Data.type(header);
		final int len;
		final int toRead;
		final int toReadPublicKey;
		final int meta1 = (hasTTL(header) ? Utils.INTEGER_BYTE_SIZE : 0)
				+ (hasBasedOn(header) ? Number160.BYTE_ARRAY_SIZE : 0);
		final int meta2 = (isSigned(header) ? 2 : 0);
		switch (type) {
		case SMALL:
			toReadPublicKey = meta1 + Utils.BYTE_SIZE + Utils.BYTE_SIZE;
			toRead = toReadPublicKey + meta2;
			if (buf.readableBytes() < toReadPublicKey) {
				return null;
			}
			// read the length of the public key
			if (isSigned(header) && !hasEnoughDataForPublicKey(buf, toReadPublicKey, toRead)) {
				return null;
			}
			len = buf.skipBytes(Utils.BYTE_SIZE).readUnsignedByte();
			break;
		case MEDIUM:
			toReadPublicKey = meta1 + Utils.BYTE_SIZE + Utils.SHORT_BYTE_SIZE;
			toRead = toReadPublicKey + meta2;
			if (buf.readableBytes() < toReadPublicKey) {
				return null;
			}
			// read the length of the public key
			if (isSigned(header) && !hasEnoughDataForPublicKey(buf, toReadPublicKey, toRead)) {
				return null;
			}
			len = buf.skipBytes(Utils.BYTE_SIZE).readUnsignedShort();
			break;
		case LARGE:
			toReadPublicKey = meta1 + Utils.BYTE_SIZE + Utils.INTEGER_BYTE_SIZE;
			toRead = toReadPublicKey + meta2;
			if (buf.readableBytes() < toReadPublicKey) {
				return null;
			}
			// read the length of the public key
			if (isSigned(header) && !hasEnoughDataForPublicKey(buf, toReadPublicKey, toRead)) {
				return null;
			}
			len = buf.skipBytes(Utils.BYTE_SIZE).readInt();
			break;
		default:
			throw new IllegalArgumentException("unknown type");
		}
		final Data data = new Data(header, len);
		if (data.ttl) {
			data.ttlSeconds = buf.readInt();
		}
		if (data.basedOnFlag) {
			byte[] me = new byte[Number160.BYTE_ARRAY_SIZE];
			buf.readBytes(me);
			data.basedOn = new Number160(me);
		}
		if (data.signed) {
			data.publicKey = signatureFactory.decodePublicKey(buf);
		}
		return data;
	}
	
	/**
	 * Add data to the byte buffer.
	 * 
	 * @param buf
	 *            The byte buffer to append
	 * @return True if we are done reading
	 */
	public boolean decodeBuffer(final ByteBuf buf) {
		final int already = buffer.alreadyTransferred();
		final int remaining = length() - already;
		// already finished
		if (remaining == 0) {
			return true;
		}
		// make sure it gets not garbage collected. But we need to keep track of
		// it and when this object gets collected,
		// we need to release the buffer
		final int transfered = buffer.transferFrom(buf, remaining);
		return transfered == remaining;
	}

	public boolean decodeDone(final ByteBuf buf, PublicKey publicKey) {
		if (signed) {
			if(publicKey == PeerMaker.EMPTY_PUBLICKEY) {
				this.publicKey = publicKey;	
			}
			byte[] me = new byte[Number160.BYTE_ARRAY_SIZE];
			buf.readBytes(me);
			Number160 number1 = new Number160(me);
			buf.readBytes(me);
			Number160 number2 = new Number160(me);
			signature = new SHA1Signature(number1, number2);
		}
		return true;
	}

	public boolean verify() throws InvalidKeyException, SignatureException, IOException {
		return verify(publicKey);
	}

	public boolean verify(PublicKey publicKey) throws InvalidKeyException, SignatureException, IOException {
		return signatureFactory().verify(publicKey, buffer.toByteBuf(), signature);
	}

	public void encodeHeader(final AlternativeCompositeByteBuf buf) {
		int header = type.ordinal();
		if (flag1) {
			header |= 0x04;
		}
		if (flag1) {
			header |= 0x08;
		}
		if (protectedEntry) {
			header |= 0x10;
		}
		if (ttl) {
			header |= 0x20;
		}
		if (signed) {
			header |= 0x40;
		}
		if (basedOnFlag) {
			header |= 0x80;
		}

		switch (type) {
		case SMALL:
			buf.writeByte(header);
			buf.writeByte(length);
			break;
		case MEDIUM:
			buf.writeByte(header);
			buf.writeShort(length);
			break;
		case LARGE:
			buf.writeByte(header);
			buf.writeInt(length);
			break;
		default:
			throw new IllegalArgumentException("unknown size");
		}
		if (ttl) {
			buf.writeInt(ttlSeconds);
		}
		if (basedOnFlag) {
			buf.writeBytes(basedOn.toByteArray());
		}
		if (signed) {
			if (publicKey == null) {
				buf.writeShort(0);
			} else {
				signatureFactory().encodePublicKey(publicKey, buf);
			}
		}
		buffer.transferTo(buf);
	}
	
	public boolean encodeBuffer(final AlternativeCompositeByteBuf buf) {
		int already = buffer.alreadyTransferred();

		int remaining = length() - already;
		// already finished
		if (remaining == 0) {
			return true;
		}
		buffer.transferTo(buf);
		return buffer.alreadyTransferred() == length();
	}

	public void encodeDone(final ByteBuf buf) {
		if (signed) {
			if (signature == null) {
				throw new IllegalArgumentException("you need to sign the data object first and add a public key!");
			}
			buf.writeBytes(signature.getNumber1().toByteArray());
			buf.writeBytes(signature.getNumber2().toByteArray());
		}
	}

	public ByteBuf buffer() {
		return buffer.toByteBuf();
	}

	public Object object() throws ClassNotFoundException, IOException {
		return Utils.decodeJavaObject(buffer);
	}

	public long validFromMillis() {
		return validFromMillis;
	}
	
	public Data sign(KeyPair keyPair) throws InvalidKeyException, SignatureException, IOException {
		if (this.signature == null) {
			this.signed = true;
			this.signature = signatureFactory().sign(keyPair.getPrivate(), buffer.toByteBuf());
			this.publicKey = keyPair.getPublic();
		}
		return this;
	}

	public Data sign(PrivateKey privateKey) throws InvalidKeyException, SignatureException, IOException {
		if (this.signature == null) {
			this.signed = true;
			this.signature = signatureFactory().sign(privateKey, buffer.toByteBuf());
		}
		return this;
	}

	public int length() {
		return length;
	}

	public long expirationMillis() {
		return ttlSeconds <= 0 ? Long.MAX_VALUE : validFromMillis + (ttlSeconds * 1000L);
	}

	public int ttlSeconds() {
		return ttlSeconds;
	}

	public Data ttlSeconds(int ttlSeconds) {
		this.ttlSeconds = ttlSeconds;
		this.ttl = true;
		return this;
	}

	public Data basedOn(Number160 basedOn) {
		this.basedOn = basedOn;
		this.basedOnFlag = true;
		return this;
	}

	public Number160 basedOn() {
		return basedOn;
	}

	public SignatureFactory signatureFactory() {
		if (signatureFactory == null) {
			return new DefaultSignatureFactory();
		} else {
			return signatureFactory;
		}
	}

	public Data signatureFactory(SignatureFactory signatureFactory) {
		this.signatureFactory = signatureFactory;
		return this;
	}

	public boolean isProtectedEntry() {
		return protectedEntry;
	}

	public boolean isSigned() {
		return signed;
	}

	public Data protectedEntry(boolean protectedEntry) {
		this.protectedEntry = protectedEntry;
		return this;
	}

	public Data setProtectedEntry() {
		protectedEntry = true;
		return this;
	}

	public boolean isFlag1() {
		return flag1;
	}

	public Data flag1(boolean flag1) {
		this.flag1 = flag1;
		return this;
	}

	public Data setFlag1() {
		this.flag1 = true;
		return this;
	}

	public boolean isFlag2() {
		return flag2;
	}

	public Data flag2(boolean flag2) {
		this.flag2 = flag2;
		return this;
	}

	public Data setFlag2() {
		this.flag2 = true;
		return this;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Data[l:");
		sb.append(length).append(",t:");
		sb.append(ttlSeconds()).append(",hasPK:");
		sb.append(publicKey != null).append(",h:");
		sb.append(signature).append("]");
		return sb.toString();
	}

	

	

	public void resetAlreadyTransferred() {
		buffer.resetAlreadyTransferred();
	}

	/**
	 * @return A shallow copy where the data is shared but the reader and writer
	 *         index is not shared
	 */
	public Data duplicate() {
		Data data = new Data(buffer.shallowCopy(), length).publicKey(publicKey).signatureFactory(signatureFactory)
				.signature(signature).basedOn(basedOn).ttlSeconds(ttlSeconds);
		// set all the flags. Although signature, basedOn, and ttlSeconds set a
		// flag, they will be overwritten with the data from this class
		data.flag1 = flag1;
		data.flag2 = flag2;
		data.basedOnFlag = basedOnFlag;
		data.signed = signed;
		data.ttl = ttl;
		data.protectedEntry = protectedEntry;
		return data;
	}

	public static Type type(final int header) {
		return Type.values()[header & 0x3];
	}

	private static boolean isFlag1(final int header) {
		return (header & 0x04) > 0;
	}

	private static boolean isFlag2(final int header) {
		return (header & 0x08) > 0;
	}

	private static boolean isProtectedEntry(final int header) {
		return (header & 0x10) > 0;
	}

	private static boolean hasTTL(final int header) {
		return (header & 0x20) > 0;
	}

	private static boolean isSigned(final int header) {
		return (header & 0x40) > 0;
	}

	private static boolean hasBasedOn(final int header) {
		return (header & 0x80) > 0;
	}

	/**
	 * @return The byte array that is the payload. Here we copy the buffer
	 */
	public byte[] toBytes() {
		// we do copy the buffer here
		ByteBuf buf = buffer.toByteBuf();
		byte[] me = new byte[buf.readableBytes()];
		buf.readBytes(me);
		return me;
	}

	/**
	 * @return The ByteBuffers that is the payload. We do not make a copy here
	 */
	public ByteBuffer[] toByteBuffers() {
		return buffer.toByteBuffer();
	}

	public PublicKey publicKey() {
		return publicKey;
	}

	public Data publicKey(PublicKey publicKey) {
		this.publicKey = publicKey;
		return this;
	}

	public SHA1Signature signature() {
		return signature;
	}

	public Data signature(SHA1Signature signature) {
		this.signature = signature;
		return this;
	}

	@Override
	public int hashCode() {
		BitSet bs = new BitSet(4);
		bs.set(0, signed);
		bs.set(1, ttl);
		bs.set(2, basedOnFlag);
		bs.set(3, protectedEntry);
		int hashCode = bs.hashCode() ^ ttlSeconds ^ type.ordinal() ^ length;
		if (basedOn != null) {
			hashCode = hashCode ^ basedOn.hashCode();
		}
		// This is a slow operation, use with care!
		return hashCode ^ buffer.hashCode();
	}

	@Override
	public boolean equals(final Object obj) {
		if (!(obj instanceof Data)) {
			return false;
		}
		if (obj == this) {
			return true;
		}
		Data d = (Data) obj;
		if (d.signed != signed || d.ttl != ttl || d.basedOnFlag != basedOnFlag || d.protectedEntry != protectedEntry) {
			return false;
		}
		if (d.ttlSeconds != ttlSeconds || d.type != type || d.length != length) {
			return false;
		}
		return Utils.<Number160> equals(basedOn, d.basedOn) && Utils.<SHA1Signature> equals(signature, d.signature)
				&& d.buffer.equals(buffer); // This is a slow operation, use
											// with care!
	}

	public Number160 hash() {
		if (hash == null) {
			hash = Utils.makeSHAHash(buffer.toByteBuf());
		}
		return hash;
	}

}
