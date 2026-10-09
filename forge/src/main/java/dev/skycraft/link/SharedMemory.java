package dev.skycraft.link;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import java.lang.invoke.VarHandle;

/** Small little-endian view over the Windows mapping shared with the SKSE plugin. */
public final class SharedMemory {
	private SharedMemory() {}

	public static final Layout<Byte> JAVA_BYTE = new Layout<>(1, 0);
	public static final Layout<Short> JAVA_SHORT = new Layout<>(2, 1);
	public static final Layout<Integer> JAVA_INT = new Layout<>(4, 2);
	public static final Layout<Long> JAVA_LONG = new Layout<>(8, 3);
	public static final Layout<Float> JAVA_FLOAT = new Layout<>(4, 4);
	public static final Layout<Double> JAVA_DOUBLE = new Layout<>(8, 5);

	public static final class Layout<T> {
		private final int size;
		private final int type;
		private Layout(int size, int type) { this.size = size; this.type = type; }
	}

	public static final class Segment {
		private final Pointer pointer;
		private final long size;
		private Segment(Pointer pointer, long size) { this.pointer = pointer; this.size = size; }

		public static Segment ofAddress(Pointer pointer, long size) { return pointer == null ? null : new Segment(pointer, size); }
		public static Segment ofBuffer(ByteBuffer buffer) {
			ByteBuffer copy = buffer.duplicate();
			if (copy.isDirect()) {
				return new Segment(new Pointer(MemoryUtil.memAddress(copy)), copy.remaining());
			}
			Memory memory = new Memory(Math.max(1, copy.remaining()));
			byte[] bytes = new byte[copy.remaining()];
			copy.get(bytes);
			memory.write(0, bytes, 0, bytes.length);
			return new Segment(memory, bytes.length);
		}
		public long byteSize() { return size; }
		public long address() { return Pointer.nativeValue(pointer); }
		public Segment reinterpret(long newSize) { return new Segment(pointer, newSize); }
		public byte[] readBytes(long offset, int bytes) {
			if (offset < 0 || bytes < 0 || offset > size - bytes) throw new IndexOutOfBoundsException("Shared-memory copy outside mapping");
			return pointer.getByteArray(offset, bytes);
		}
		public <T> T get(Layout<T> layout, long offset) {
			Object value = switch (layout.type) {
				case 0 -> pointer.getByte(offset);
				case 1 -> pointer.getShort(offset);
				case 2 -> pointer.getInt(offset);
				case 3 -> pointer.getLong(offset);
				case 4 -> pointer.getFloat(offset);
				case 5 -> pointer.getDouble(offset);
				default -> throw new IllegalArgumentException("Unknown shared-memory layout");
			};
			@SuppressWarnings("unchecked") T typed = (T) value;
			return typed;
		}
		public void set(Layout<?> layout, long offset, Object value) {
			switch (layout.type) {
				case 0 -> pointer.setByte(offset, ((Number) value).byteValue());
				case 1 -> pointer.setShort(offset, ((Number) value).shortValue());
				case 2 -> pointer.setInt(offset, ((Number) value).intValue());
				case 3 -> pointer.setLong(offset, ((Number) value).longValue());
				case 4 -> pointer.setFloat(offset, ((Number) value).floatValue());
				case 5 -> pointer.setDouble(offset, ((Number) value).doubleValue());
				default -> throw new IllegalArgumentException("Unknown shared-memory layout");
			}
		}
		public int getIntAcquire(long offset) { int value = pointer.getInt(offset); VarHandle.acquireFence(); return value; }
		public long getLongAcquire(long offset) { long value = pointer.getLong(offset); VarHandle.acquireFence(); return value; }
		public void setIntRelease(long offset, int value) { VarHandle.releaseFence(); pointer.setInt(offset, value); }
		public void setLongRelease(long offset, long value) { VarHandle.releaseFence(); pointer.setLong(offset, value); }
		public synchronized int getAndSetInt(long offset, int value) { int old = pointer.getInt(offset); VarHandle.fullFence(); pointer.setInt(offset, value); return old; }
		public synchronized long getAndAddLong(long offset, long delta) { long old = pointer.getLong(offset); VarHandle.fullFence(); pointer.setLong(offset, old + delta); return old; }
	}

	public static void copy(Segment source, long sourceOffset, Segment destination, long destinationOffset, long bytes) {
		if (bytes < 0 || sourceOffset < 0 || destinationOffset < 0 || sourceOffset > source.size - bytes || destinationOffset > destination.size - bytes) {
			throw new IndexOutOfBoundsException("Shared-memory copy outside mapping");
		}
		if (bytes > 0) {
			MemoryUtil.memCopy(Pointer.nativeValue(source.pointer) + sourceOffset, Pointer.nativeValue(destination.pointer) + destinationOffset, bytes);
		}
	}
}
