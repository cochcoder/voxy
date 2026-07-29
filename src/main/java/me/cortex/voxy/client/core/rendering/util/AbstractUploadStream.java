package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.common.util.MemoryBuffer;

//Backend-neutral streaming-upload API. The contract (shared by the GL and VK
// implementations, and relied on by NodeManager/geometry/model code):
//
//  - upload(IDeviceBuffer, long, long) returns a CPU-writable pointer into a
//    persistently-mapped staging area; the data is copied into the target buffer
//    at commit().
//  - rawUploadAddress(int) allocates staging space without a copy target;
//    callers bind the staging buffer region directly as an SSBO (scatter /
//    multi-memcpy paths) via backend-specific code.
//  - tick() retires frames whose GPU work completed, recycling staging space.
//    Must be called once per frame on the render thread.
public abstract class AbstractUploadStream {
    public void upload(IDeviceBuffer buffer, long destOffset, MemoryBuffer data) {
        data.cpyTo(this.upload(buffer, destOffset, data.size));
    }

    public long uploadTo(IDeviceBuffer buffer) {
        return this.upload(buffer, 0, buffer.sizeBytes());
    }

    public abstract long upload(IDeviceBuffer buffer, long destOffset, long size);

    public long rawUpload(int size) {
        return this.getBaseAddress() + this.rawUploadAddress(size);
    }

    public abstract long rawUploadAddress(int size);

    public abstract void commit();

    public abstract void tick();

    /** CPU base address of the persistently-mapped staging buffer. */
    public abstract long getBaseAddress();

    /** GL name of the staging buffer; only valid on the OpenGL backend. */
    public int getRawBufferId() {
        throw new UnsupportedOperationException("Staging buffer has no GL id on this backend");
    }

    //Allocation-block alignment of this stream's staging arena.
    public abstract int baseAlignment();

    public final int alignUpAlloc(int val) {
        int a = this.baseAlignment();
        return ((val + a - 1) / a) * a;
    }

    public abstract void free();

    public static AbstractUploadStream INSTANCE() {
        return RenderBackendServices.current().uploadStream();
    }

    public static long alignUp(long val, long alignment) {
        return ((val + alignment - 1) / alignment) * alignment;
    }

    public static int alignUp(int val, int alignment) {
        return ((val + alignment - 1) / alignment) * alignment;
    }
}
