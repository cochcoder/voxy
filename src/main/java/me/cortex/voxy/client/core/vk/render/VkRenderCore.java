package me.cortex.voxy.client.core.vk.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.util.RenderBackendServices;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.MinecraftVkHostAdapter;
import me.cortex.voxy.client.core.vk.VkAtlasTextureReader;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkDownloadStream;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.client.core.vk.VulkanBackend;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.RollbackScope;
import me.cortex.voxy.common.world.WorldEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;

//The pure-Vulkan render core: constructed instead of the GL pipeline when MC
// itself runs on Vulkan. Owns every GPU-facing subsystem (streams, geometry,
// models, traversal, terrain renderer, compositor) on MC's adopted device, and
// shares the CPU-side services (node manager, mesh generation, model bakery,
// render-distance tracking) with the GL path.
//
//Everything records into MC's frame command buffer from the render hook
// (MixinSodiumOpaqueVkFrame, TAIL of Sodium's opaque terrain draw), between
// MC's opaque terrain pass and the rest of its frame. No OpenGL is touched.
public class VkRenderCore {
    private final WorldEngine worldIn;
    private final VkFrameCtx frameCtx;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final RenderBackendServices.Registration backendRegistration;

    private final RenderProperties properties;
    private final VkModelStore modelStore;
    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final VkSectionGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final VkNodeCleaner nodeCleaner;
    private final VkTraversal traversal;
    private final VkTerrainRenderer terrainRenderer;
    private final VkCompositor compositor;
    private final VkSSAO ssao;
    private final VkBoundRenderer boundRenderer;
    private final StreamedBoundStore visibleSectionStream;
    private boolean shutDown = false;
    private final RenderDistanceTracker renderDistanceTracker;
    private final ViewportSelector<VkViewport> viewportSelector;

    private record Components(
            WorldEngine world,
            VkFrameCtx frameCtx,
            VkUploadStream uploadStream,
            VkDownloadStream downloadStream,
            RenderBackendServices.Registration backendRegistration,
            RenderProperties properties,
            VkModelStore modelStore,
            ModelBakerySubsystem modelService,
            RenderGenerationService renderGen,
            VkSectionGeometryData geometryData,
            AsyncNodeManager nodeManager,
            VkNodeCleaner nodeCleaner,
            VkTraversal traversal,
            VkTerrainRenderer terrainRenderer,
            VkCompositor compositor,
            VkSSAO ssao,
            VkBoundRenderer boundRenderer,
            StreamedBoundStore visibleSectionStream,
            RenderDistanceTracker renderDistanceTracker,
            ViewportSelector<VkViewport> viewportSelector) {
    }

    public static VkRenderCore create(WorldEngine world, ServiceManager sm) {
        world.acquireRef();
        Logger.info("Creating Voxy pure-Vulkan render core");
        try (var rollback = new RollbackScope()) {
            rollback.defer(world::releaseRef);

            var host = MinecraftVkHost.get();
            if (host == null) throw new IllegalStateException("No Minecraft Vulkan host adapter registered");
            var vctx = VulkanBackend.context();//adopts MC's device
            var frameCtx = new VkFrameCtx(vctx);
            rollback.defer(frameCtx::free);

            var uploadStream = new VkUploadStream(frameCtx, 1 << 26);//64 mb, same as GL
            rollback.defer(uploadStream::free);
            var downloadStream = new VkDownloadStream(frameCtx, 1 << 25);//32 mb, same as GL
            rollback.defer(downloadStream::free);

            //Shared rendering code resolves these services globally. Publish one
            //owned registration so initialization is all-or-nothing.
            var backendRegistration = RenderBackendServices.install(
                    new RenderBackendServices.Services(
                            uploadStream,
                            downloadStream,
                            new VkAtlasTextureReader(frameCtx)));
            rollback.defer(backendRegistration::close);

            var properties = RenderProperties.getRenderProperties();

            var modelStore = new VkModelStore(frameCtx, uploadStream);
            var modelStoreCleanup = rollback.defer(modelStore::free);
            var modelService = new ModelBakerySubsystem(world.getMapper(), modelStore);
            rollback.defer(modelService::shutdown);
            modelStoreCleanup.cancel();//ModelBakerySubsystem owns the store now.

            var renderGen = new RenderGenerationService(world, modelService, sm, false);
            rollback.defer(renderGen::shutdown);

            var geometryData = new VkSectionGeometryData(frameCtx, 1 << 20, geometryCapacity());
            rollback.defer(geometryData::free);

            var nodeGpuOps = new VkNodeGpuOps(frameCtx, uploadStream);
            var nodeGpuOpsCleanup = rollback.defer(nodeGpuOps::free);
            var nodeManager = new AsyncNodeManager(1 << 21, geometryData, renderGen, nodeGpuOps);
            rollback.defer(nodeManager::stop);
            nodeGpuOpsCleanup.cancel();//AsyncNodeManager owns the GPU operations now.

            var nodeCleaner = new VkNodeCleaner(frameCtx, uploadStream, downloadStream, nodeManager);
            rollback.defer(nodeCleaner::free);
            var traversal = new VkTraversal(frameCtx, uploadStream, downloadStream,
                    properties, nodeManager, nodeCleaner, renderGen);
            rollback.defer(traversal::free);
            var terrainRenderer = new VkTerrainRenderer(frameCtx, uploadStream, downloadStream,
                    properties, geometryData, modelStore);
            rollback.defer(terrainRenderer::free);
            var compositor = new VkCompositor(frameCtx, uploadStream, properties,
                    VoxyConfig.CONFIG.useEnvironmentalFog);
            rollback.defer(compositor::free);
            var ssao = new VkSSAO(frameCtx, uploadStream, properties, VoxyConfig.CONFIG.getSSAOMode());
            rollback.defer(ssao::free);

            //Depth-bound culling: Sodium's visibility mixins feed the store; the bound
            // renderer rasters visible-chunk AABBs into the depth-bound image so the
            // terrain shaders can discard LOD fragments vanilla terrain will cover.
            var visibleSectionStream = new StreamedBoundStore(size -> new VkBuffer(frameCtx, size));
            rollback.defer(visibleSectionStream::free);
            var boundRenderer = new VkBoundRenderer(frameCtx, uploadStream, properties);
            rollback.defer(boundRenderer::free);

            rollback.defer(() -> detachWorldCallbacks(world));
            world.setDirtyCallback(nodeManager::worldEvent);
            Arrays.stream(world.getMapper().getBiomeEntries()).forEach(modelService::addBiome);
            world.getMapper().setBiomeCallback(modelService::addBiome);
            nodeManager.start();

            var viewportSelector = new ViewportSelector<>(() ->
                    new VkViewport(frameCtx, properties, geometryData.getMaxSectionCount()));
            rollback.defer(viewportSelector::free);

            int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
            int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;
            var renderDistanceTracker = new RenderDistanceTracker(40, minSec, maxSec,
                    nodeManager::addTopLevel, nodeManager::removeTopLevel);
            renderDistanceTracker.setRenderDistance(
                    (int) Math.ceil(VoxyConfig.CONFIG.sectionRenderDistance + 1));

            frameCtx.flushImmediate();
            var core = new VkRenderCore(new Components(
                    world,
                    frameCtx,
                    uploadStream,
                    downloadStream,
                    backendRegistration,
                    properties,
                    modelStore,
                    modelService,
                    renderGen,
                    geometryData,
                    nodeManager,
                    nodeCleaner,
                    traversal,
                    terrainRenderer,
                    compositor,
                    ssao,
                    boundRenderer,
                    visibleSectionStream,
                    renderDistanceTracker,
                    viewportSelector));
            rollback.commit();
            Logger.info("Voxy pure-Vulkan render core created with "
                    + geometryData.getMaxCapacity() + " geometry capacity");
            return core;
        }
    }

    private VkRenderCore(Components components) {
        this.worldIn = components.world;
        this.frameCtx = components.frameCtx;
        this.uploadStream = components.uploadStream;
        this.downloadStream = components.downloadStream;
        this.backendRegistration = components.backendRegistration;
        this.properties = components.properties;
        this.modelStore = components.modelStore;
        this.modelService = components.modelService;
        this.renderGen = components.renderGen;
        this.geometryData = components.geometryData;
        this.nodeManager = components.nodeManager;
        this.nodeCleaner = components.nodeCleaner;
        this.traversal = components.traversal;
        this.terrainRenderer = components.terrainRenderer;
        this.compositor = components.compositor;
        this.ssao = components.ssao;
        this.boundRenderer = components.boundRenderer;
        this.visibleSectionStream = components.visibleSectionStream;
        this.renderDistanceTracker = components.renderDistanceTracker;
        this.viewportSelector = components.viewportSelector;
    }

    private static void detachWorldCallbacks(WorldEngine world) {
        world.setDirtyCallback(null);
        world.getMapper().setBiomeCallback(null);
        world.getMapper().setStateCallback(null);
    }

    private static long geometryCapacity() {
        //Conservative fixed allocation (no sparse residency tricks on VK): 2GB, halved on failure inside VkSectionGeometryData
        return 2048L << 20;
    }

    //Renders one Voxy frame into MC's frame command buffer. Called from the
    // render hook right after MC's opaque terrain pass, on the render thread.
    //
    //matrices are Sodium's per-frame ChunkRenderMatrices — the exact
    // projection+modelView MC/Sodium just drew the terrain with, INCLUDING
    // per-frame view bobbing/nausea/portal warps. They must be used instead of
    // the raw cameraRenderState matrices: computeProjectionMat extracts the
    // bob delta as rawMCProj^-1 x base, which collapses to identity if base IS
    // rawMCProj, and viewRotationMatrix is rotation-only — both of which made
    // the LODs bounce relative to vanilla terrain while walking.
    public void renderFrame(RenderTarget target, MinecraftVkHostAdapter adapter, ChunkRenderMatrices matrices,
                            double camX, double camY, double camZ) {
        var frameCmd = adapter.frameCommandBuffer();
        if (frameCmd == null) {
            Logger.warn("Voxy VK: no frame command buffer at hook point, skipping frame");
            return;
        }
        var crs = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (crs == null || !crs.initialized) return;

        this.frameCtx.flushImmediate();
        this.frameCtx.beginFrame(frameCmd);
        try {
            if (me.cortex.voxy.commonImpl.VoxyCommon.IS_MINE_IN_ABYSS) {//same camera trickery as the GL setupViewport
                int sector = (((int) Math.floor(camX) >> 4) + 512) >> 10;
                camX -= sector << 14;//10+4
                camY += (16 + (256 - 32 - sector * 30)) * 16;
            }

            var viewport = this.viewportSelector.getViewport();
            var voxyProjection = VoxyRenderSystem.computeProjectionMat(this.properties, matrices.projection());
            var fog = crs.fogData == null ? null : new FogParameters(
                    crs.fogData.color.x, crs.fogData.color.y, crs.fogData.color.z, crs.fogData.color.w,
                    crs.fogData.environmentalStart, crs.fogData.environmentalEnd,
                    crs.fogData.renderDistanceStart, crs.fogData.renderDistanceEnd);
            viewport.setVanillaProjection(matrices.projection())
                    .setProjection(voxyProjection)
                    .setModelView(matrices.modelView())//setModelView copies into the viewport's own matrix
                    .setCamera(camX, camY, camZ)
                    .setScreenSize(target.width, target.height)
                    .setFogParameters(fog)
                    .update();
            viewport.frameId++;
            if (viewport.width <= 0 || viewport.height <= 0) return;
            viewport.ensureTargets();

            var rt = new VkCompositor.VkViewportRT(viewport,
                    target.getColorTextureView(), target.getDepthTextureView(), target.width, target.height);

            //1. copy MC depth in + stencil mask (also clears the offscreen targets)
            this.compositor.setupDepthStencil(rt);

            //1.5 raster the vanilla-visible chunk bounds into the depth-bound image
            // (sampled by the terrain draws below to cull LOD fragments behind vanilla)
            this.boundRenderer.render(viewport, this.visibleSectionStream);

            //2. opaque LOD terrain (draw calls generated LAST frame)
            this.terrainRenderer.renderOpaque(viewport, false);

            //3. HiZ + node management + hierarchical traversal
            this.compositor.offscreenToSampled(viewport);
            viewport.hiZ.buildMipChain(viewport.depthSampleView, viewport.width, viewport.height);
            this.compositor.offscreenToAttachment(viewport);

            this.downloadStream.tick();
            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            this.nodeCleaner.tick(this.traversal.getNodeBuffer());
            this.traversal.doTraversal(viewport);

            //4. build the draw commands for this frame (prep, raster cull, cmdgen, translucency sort)
            this.terrainRenderer.buildDrawCalls(viewport);

            //5. temporal, then SSAO (reads colour+depth, writes colourSSAO with
            // sanitized alpha), then translucents onto the SSAO output — the same
            // opaque->temporal->SSAO->translucent order as the GL pipeline
            this.terrainRenderer.renderTemporal(viewport);
            this.ssao.compute(viewport, rt);
            this.terrainRenderer.renderTranslucent(viewport);

            //6. composite into MC's frame
            this.compositor.offscreenToSampled(viewport);
            this.compositor.composite(rt);

            //7. dynamic CPU work (uploads recycled, model baking, render distance tracking)
            this.uploadStream.tick();
            this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ);
            this.modelService.tick(900_000);
        } finally {
            this.frameCtx.endFrame();
            this.frameCtx.pollRetired();
        }
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance + 1));
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("VK host mode: " + VulkanBackend.statusLine());
        debug.add("VkBuf [#/Mb]: [" + VkBuffer.getCount() + "/"
                + (VkBuffer.getTotalSize() / 1_000_000) + "]");
        this.modelService.addDebugData(debug);
        this.renderGen.addDebugData(debug);
        this.nodeManager.addDebug(debug);
        this.ssao.addDebugInfo(debug);
    }

    public void shutdown() {
        if (this.shutDown) {
            //Idempotent: a second teardown would double-free / re-idle the device
            return;
        }
        this.shutDown = true;
        Logger.info("Shutting down Voxy pure-Vulkan render core");

        //CPU-only stop first: detach world callbacks and join the node/gen worker
        // threads (both produce CPU data only — GPU upload happens on the render
        // thread), so nothing can enqueue more work while we tear down. The model
        // bakery is NOT stopped here — its shutdown() frees GPU resources
        // (VkModelStore), so it must run AFTER the device is idle.
        try {
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);
            this.nodeManager.stop();
            this.renderGen.shutdown();
        } catch (Exception e) {
            Logger.error("Error stopping VK render core CPU services", e);
        }

        //Only touch the GPU if MC's adopted device is still alive. On full game
        // exit MC's VulkanDevice.close() (which clears the host) can run before
        // the level renderer closes; issuing vkDeviceWaitIdle / vkDestroy*
        // against a destroyed device is a use-after-free in the driver. If the
        // host is already gone the objects are unreachable anyway, so leaking
        // them is strictly safer than a native crash.
        boolean deviceAlive = MinecraftVkHost.get() != null;
        if (deviceAlive) {
            try {
                //Idle the device BEFORE destroying anything, so no destroy races
                // GPU work still referencing these objects
                this.frameCtx.waitIdleRetireAll();
                //modelService.shutdown() joins the (CPU) baking thread and frees
                // the VkModelStore exactly once. It OWNS the store's lifetime —
                // VkRenderCore must not free modelStore itself (double
                // vkDestroySampler, observed NVIDIA SIGSEGV on world unload).
                this.modelService.shutdown();
                this.boundRenderer.free();
                this.visibleSectionStream.free();
                this.traversal.free();
                this.nodeCleaner.free();
                this.geometryData.free();
                this.terrainRenderer.free();
                this.ssao.free();
                this.compositor.free();
                this.viewportSelector.free();
                this.downloadStream.flushWaitClear();
                this.uploadStream.free();
                this.downloadStream.free();
                this.frameCtx.free();
            } catch (Exception e) {
                Logger.error("Error shutting down VK render core GPU resources", e);
            }
        } else {
            Logger.warn("Voxy VK: Minecraft's Vulkan device is already gone at shutdown; "
                    + "skipping GPU teardown to avoid destroying objects on a dead device");
        }

        this.backendRegistration.close();

        this.worldIn.releaseRef();
        Logger.info("VK render core shutdown completed");
    }

    public StreamedBoundStore getVisibleSectionStream() {
        return this.visibleSectionStream;
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
