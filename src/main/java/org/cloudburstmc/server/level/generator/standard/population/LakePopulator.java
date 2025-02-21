package org.cloudburstmc.server.level.generator.standard.population;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.daporkchop.lib.common.pool.handle.Handle;
import net.daporkchop.lib.common.pool.handle.HandledPool;
import net.daporkchop.lib.random.PRandom;
import org.cloudburstmc.api.block.BlockState;
import org.cloudburstmc.api.block.BlockStates;
import org.cloudburstmc.api.level.ChunkManager;
import org.cloudburstmc.api.util.Identifier;
import org.cloudburstmc.server.level.generator.standard.StandardGenerator;
import org.cloudburstmc.server.level.generator.standard.misc.IntRange;
import org.cloudburstmc.server.level.generator.standard.misc.filter.BlockFilter;
import org.cloudburstmc.server.level.generator.standard.misc.selector.BlockSelector;

import java.util.BitSet;
import java.util.Objects;

import static java.lang.Integer.min;

/**
 * @author DaPorkchop_
 */
@JsonDeserialize
public class LakePopulator extends ChancePopulator.Column {
    public static final Identifier ID = Identifier.fromString("cloudburst:lake");

    protected static final HandledPool<BitSet> BITSET_CACHE = HandledPool.threadLocal(() -> new BitSet(2048), 1);

    @JsonProperty
    protected IntRange height = IntRange.WHOLE_WORLD;

    @JsonProperty
    protected BlockSelector block;

    @JsonProperty
    protected BlockSelector border;

    @JsonProperty
    protected BlockFilter surfaceBlocks;

    @JsonProperty
    protected BlockFilter replaceWithSurface;

    @Override
    protected void init0(long levelSeed, long localSeed, StandardGenerator generator) {
        super.init0(levelSeed, localSeed, generator);

        Objects.requireNonNull(this.height, "height must be set!");
        Objects.requireNonNull(this.block, "block must be set!");

        if (this.surfaceBlocks != null || this.replaceWithSurface != null) {
            Objects.requireNonNull(this.surfaceBlocks, "replaceWithSurface requires surfaceBlocks to be set!");
            Objects.requireNonNull(this.replaceWithSurface, "surfaceBlocks requires replaceWithSurface to be set!");
        }
    }

    @Override
    protected void populate0(PRandom random, ChunkManager level, int blockX, int blockZ) {
        blockX -= 8;
        blockZ -= 8;
        final int blockY = min(level.getChunk(blockX >> 4, blockZ >> 4).getHighestBlock(blockX & 0xF, blockZ & 0xF), this.height.rand(random));
        if (blockY <= 1 || blockY >= 247 || !this.height.contains(blockY)) {
            return;
        }

        final BlockState block = this.block.selectWeighted(random);

        try (Handle<BitSet> handle = BITSET_CACHE.get()) {
            //BitSet has 8x greater storage density than a boolean[], so the additional overhead is negligible compared to the better cache utilization
            BitSet points = handle.get();
            points.clear();

            //generate initial lake shape
            for (int layer = random.nextInt(4) + 3; layer >= 0; layer--) {
                double vx = random.nextDouble() * 3.0d + 1.5d;
                double vy = random.nextDouble() * 2.0d + 1.0d;
                double vz = random.nextDouble() * 3.0d + 1.5d;
                double gx = random.nextDouble() * (14.0d - vx * 2.0d) + vx + 1.0d;
                double gy = random.nextDouble() * (4.0d - vy * 2.0d) + vy + 2.0d;
                double gz = random.nextDouble() * (14.0d - vz * 2.0d) + vz + 1.0d;
                vx = 1.0d / vx;
                vy = 1.0d / vy;
                vz = 1.0d / vz;

                //unfortunately this loop can't really be vectorized by HotSpot because it doesn't support vectorization of integer to floating-point conversion
                // (or conditionals for that matter)
                for (int y = 1; y < 7; y++) {
                    for (int x = 1; x < 15; x++) {
                        for (int z = 1; z < 15; z++) {
                            double dx = (x - gx) * vx;
                            double dy = (y - gy) * vy;
                            double dz = (z - gz) * vz;
                            if (dx * dx + dy * dy + dz * dz < 1.0d) {
                                points.set((y << 8) | (x << 4) | z);
                            }
                        }
                    }
                }
            }

            //make sure lake doesn't intersect with any other liquids
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (points.get((y << 8) | (x << 4) | z)) {
                            continue;
                        }

                        //only check the outer layer of blocks around the lake
                        if ((y > 0 && points.get(((y - 1) << 8) | (x << 4) | z))
                                || (y < 7 && points.get(((y + 1) << 8) | (x << 4) | z))
                                || (x > 0 && points.get((y << 8) | ((x - 1) << 4) | z))
                                || (x < 15 && points.get((y << 8) | ((x + 1) << 4) | z))
                                || (z > 0 && points.get((y << 8) | (x << 4) | (z - 1)))
                                || (z < 15 && points.get((y << 8) | (x << 4) | (z + 1)))) {
                            BlockState state = level.getBlockState(blockX + x, blockY + y, blockZ + z, 0);

                            if (y < 4) {
                                if (state != block && !state.getBehavior().isSolid(state)) {
                                    return;
                                }
                            } else {
                                if (state.getBehavior().isLiquid()) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }

            BlockState surface = null;

            //if needed: figure out what the current surface block is
            COMPUTE_SURFACE:
            if (this.surfaceBlocks != null) {
                BlockFilter surfaceBlocks = this.surfaceBlocks;
                for (int y = 4; y < 8; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (points.get((y << 8) | (x << 4) | z) && surfaceBlocks.test(surface = level.getBlockState(blockX + x, blockY + y, blockZ + z, 0))) {
                                break COMPUTE_SURFACE;
                            }
                        }
                    }
                }

                //couldn't find any surface
                surface = null;
            }

            //place actual liquid blocks
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (points.get((y << 8) | (x << 4) | z)) {
                            level.setBlockState(blockX + x, blockY + y, blockZ + z, 0, y >= 4 ? BlockStates.AIR : block);
                        }
                    }
                }
            }

            //if needed: replace ground with surface blocks
            if (surface != null) {
                BlockFilter replaceWithSurface = this.replaceWithSurface;
                for (int y = 4; y < 8; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (points.get((y << 8) | (x << 4) | z) && replaceWithSurface.test(level.getBlockState(blockX + x, blockY + y - 1, blockZ + z, 0))) {
                                level.setBlockState(blockX + x, blockY + y - 1, blockZ + z, 0, surface);
                            }
                        }
                    }
                }
            }

            if (this.border != null) {
                final BlockState border = this.border.selectWeighted(random);

                //place border
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (points.get((y << 8) | (x << 4) | z) || (y >= 4 && random.nextBoolean())) {
                                continue;
                            }

                            if (((y > 0 && points.get(((y - 1) << 8) | (x << 4) | z))
                                    || (y < 7 && points.get(((y + 1) << 8) | (x << 4) | z))
                                    || (x > 0 && points.get((y << 8) | ((x - 1) << 4) | z))
                                    || (x < 15 && points.get((y << 8) | ((x + 1) << 4) | z))
                                    || (z > 0 && points.get((y << 8) | (x << 4) | (z - 1)))
                                    || (z < 15 && points.get((y << 8) | (x << 4) | (z + 1))))
                                    && level.getBlockState(blockX + x, blockY + y, blockZ + z, 0).getType().isSolid()) {
                                level.setBlockState(blockX + x, blockY + y, blockZ + z, 0, border);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public Identifier getId() {
        return ID;
    }
}
