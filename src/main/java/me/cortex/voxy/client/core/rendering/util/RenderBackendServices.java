package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.model.bakery.IAtlasTextureReader;

import java.util.Objects;

/**
 * Process-wide render services used by backend-neutral rendering code.
 *
 * <p>Vulkan installs all services as one owned unit before constructing shared
 * renderer components. OpenGL keeps the existing lazy initialization behavior.
 */
public final class RenderBackendServices {
    public record Services(
            AbstractUploadStream uploadStream,
            AbstractDownloadStream downloadStream,
            IAtlasTextureReader atlasTextureReader) {
        public Services {
            Objects.requireNonNull(uploadStream, "uploadStream");
            Objects.requireNonNull(downloadStream, "downloadStream");
            Objects.requireNonNull(atlasTextureReader, "atlasTextureReader");
        }
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final Object LOCK = new Object();
    private static volatile Services current;

    private RenderBackendServices() {
    }

    public static Services current() {
        var services = current;
        if (services != null) {
            return services;
        }
        synchronized (LOCK) {
            services = current;
            if (services == null) {
                services = new Services(
                        new UploadStream(1 << 26),
                        new DownloadStream(1 << 25),
                        IAtlasTextureReader.createGlDefault());
                current = services;
            }
            return services;
        }
    }

    public static Registration install(Services services) {
        Objects.requireNonNull(services, "services");
        synchronized (LOCK) {
            if (current != null) {
                throw new IllegalStateException("render backend services are already initialized");
            }
            current = services;
            return new OwnedRegistration(services);
        }
    }

    private static final class OwnedRegistration implements Registration {
        private final Services services;
        private boolean closed;

        private OwnedRegistration(Services services) {
            this.services = services;
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (this.closed) {
                    return;
                }
                if (current != this.services) {
                    throw new IllegalStateException("render backend registration no longer owns the active services");
                }
                current = null;
                this.closed = true;
            }
        }
    }
}
