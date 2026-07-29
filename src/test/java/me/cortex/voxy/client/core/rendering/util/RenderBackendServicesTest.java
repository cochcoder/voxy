package me.cortex.voxy.client.core.rendering.util;

import com.mojang.blaze3d.textures.GpuTexture;
import me.cortex.voxy.client.core.model.bakery.IAtlasTextureReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderBackendServicesTest {
    @Test
    void installsAllServicesAsOneOwnedRegistration() {
        var services = fakeServices();

        try (var registration = RenderBackendServices.install(services)) {
            assertSame(services, RenderBackendServices.current());
            assertSame(services.uploadStream(), AbstractUploadStream.INSTANCE());
            assertSame(services.downloadStream(), AbstractDownloadStream.INSTANCE());
            assertSame(services.atlasTextureReader(), IAtlasTextureReader.INSTANCE());
            assertThrows(IllegalStateException.class,
                    () -> RenderBackendServices.install(fakeServices()));

            registration.close();
            registration.close();
        }
    }

    private static RenderBackendServices.Services fakeServices() {
        return new RenderBackendServices.Services(
                new AbstractUploadStream() {
                    @Override
                    public long upload(IDeviceBuffer buffer, long destOffset, long size) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public long rawUploadAddress(int size) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void commit() {
                    }

                    @Override
                    public void tick() {
                    }

                    @Override
                    public long getBaseAddress() {
                        return 0;
                    }

                    @Override
                    public int baseAlignment() {
                        return 1;
                    }

                    @Override
                    public void free() {
                    }
                },
                new AbstractDownloadStream() {
                    @Override
                    public void download(IDeviceBuffer buffer, long downloadOffset, long size,
                                         DownloadResultConsumer resultConsumer) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void commit() {
                    }

                    @Override
                    public void tick() {
                    }

                    @Override
                    public void waitDiscard() {
                    }

                    @Override
                    public void flushWaitClear() {
                    }

                    @Override
                    public void free() {
                    }
                },
                new IAtlasTextureReader() {
                    @Override
                    public int[] read(GpuTexture atlas, int width, int height) {
                        return new int[width * height];
                    }
                });
    }
}
