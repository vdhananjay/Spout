/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011 Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine.world;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.spout.api.Platform;
import org.spout.api.ServerOnly;
import org.spout.api.Spout;
import org.spout.api.geo.LoadOption;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.cuboid.Region;
import org.spout.api.lighting.LightingManager;
import org.spout.api.material.BlockMaterial;
import org.spout.api.material.block.BlockFace;
import org.spout.api.material.block.BlockFaces;
import org.spout.api.math.IntVector3;
import org.spout.api.scheduler.SnapshotLock;
import org.spout.api.util.Named;
import org.spout.api.util.cuboid.CuboidBlockMaterialBuffer;
import org.spout.api.util.cuboid.CuboidLightBuffer;
import org.spout.api.util.map.concurrent.AtomicBlockStore;
import org.spout.api.util.map.concurrent.palette.AtomicPaletteBlockStore;
import org.spout.engine.scheduler.SpoutScheduler;
import org.spout.engine.util.thread.threadfactory.NamedThreadFactory;
import org.spout.math.GenericMath;

public class RegionGenerator implements Named {
	private final static ExecutorService pool = new ThreadPoolExecutor(1,
		Runtime.getRuntime().availableProcessors() * 3 + 1, 120L, TimeUnit.SECONDS,
		new LinkedBlockingQueue<Runnable>(),
		new NamedThreadFactory("RegionGenerator - async pool", false));

	private final SpoutRegion region;
	private final SpoutServerWorld world;
	private final Lock[][] columnLocks;
	private final AtomicReference<GenerateState>[][] generatedColumns;
	private final int shift;
	private final int width;
	private final int mask;
	private final static AtomicInteger generationCounter = new AtomicInteger(1);
	private final int cx;
	private final int cy;
	private final int cz;

	@SuppressWarnings ("unchecked")
	@ServerOnly
	public RegionGenerator(SpoutRegion region, int width) {
		if (GenericMath.roundUpPow2(width) != width || width > Region.CHUNKS.SIZE || width < 0) {
			throw new IllegalArgumentException("Width must be a power of 2 and can't be more than one region width");
		}

		int sections = Region.CHUNKS.SIZE / width;

		this.width = width;
		this.mask = width - 1;
		this.generatedColumns = new AtomicReference[sections][sections];
		this.columnLocks = new Lock[sections][sections];

		for (int x = 0; x < sections; x++) {
			for (int z = 0; z < sections; z++) {
				this.generatedColumns[x][z] = new AtomicReference<>(GenerateState.NONE);
				this.columnLocks[x][z] = new NamedReentrantLock(x, z);
			}
		}

		this.shift = GenericMath.multiplyToShift(width);
		this.region = region;
		this.world = (SpoutServerWorld) region.getWorld();
		this.cx = region.getChunkX();
		this.cy = region.getChunkY();
		this.cz = region.getChunkZ();
	}

	/**
	 * 
	 * @param chunkX
	 * @param chunkZ
	 * @param wait whether to wait or not
	 */
	public void generateColumn(final int chunkX, final int chunkZ, boolean wait) {
		if (!wait) {
			pool.submit(new Runnable() {
				@Override
				public void run() {
					try {
						generateColumn0(chunkX, chunkZ, false);	
					} catch (Exception e) {
						Spout.warn("Error when generating column", e);
					}
				}
			});
		} else {
			generateColumn0(chunkX, chunkZ, wait);
		}
	}

	private void generateColumn0(final int chunkX, final int chunkZ, boolean wait) {
		final int x = (chunkX & (~mask)) & Region.CHUNKS.MASK;
		final int z = (chunkZ & (~mask)) & Region.CHUNKS.MASK;

		AtomicReference<GenerateState> generated = generatedColumns[x >> shift][z >> shift];
		if (generated.get().isDone()) {
			return;
		}

		if (wait) {
			// If someone besides another generator holds the snapshot lock, we know it won't be released
			if (((SpoutScheduler) Spout.getScheduler()).getSnapshotLock().isWriteLocked()/* && generated.get() != GenerateState.COPYING*/) {
				// This is a really really bad place to be. It means we're waiting for the region to finish generating when it can't set the region.
				// It's either this error or a deadlock
				throw new IllegalStateException("Attempt to sync generate a chunk during snapshot lock");
				// This code allows the sync thread to cancel an async generation.
				// However, sync generations should not happen during snapshot lock
				//
				// TODO - simplify this method, assuming no thread can hold a snapshot lock before it is called
				// generated.compareAndSet(GenerateState.IN_PROGRESS_ASYNC, GenerateState.IN_PROGRESS_SYNC);
			}
		}

		// TODO - optimize this by checking if !wait && generated.get().isInProgress()
		final Lock colLock = columnLocks[x >> shift][z >> shift];

		if (wait) {
			colLock.lock();
		} else {
			if (!colLock.tryLock()) {
				return;
			}
		}

		try {
			if (generated.get().isDone()) {
				return;
			}

			int generationIndex = generationCounter.getAndIncrement();

			while (generationIndex == -1) {
				Spout.getLogger().info("Ran out of generation index ids, starting again");
				generationIndex = generationCounter.getAndIncrement();
			}

			LightingManager<?>[] managers = world.getLightingManagers();

			CuboidLightBuffer[][][][] buffers = new CuboidLightBuffer[managers.length][][][];

			if (!generated.compareAndSet(GenerateState.NONE, GenerateState.IN_PROGRESS)) {
				throw new IllegalStateException("Unable to set generate state for column " + x + ", " + z + " int region " + region.getBase().toBlockString() + " to in progress, state is " + generated.get() + " wait is " + wait);
			}

			int cxx = cx + x;
			int czz = cz + z;

			final CuboidBlockMaterialBuffer buffer = new CuboidBlockMaterialBuffer(cxx << Chunk.BLOCKS.BITS, cy << Chunk.BLOCKS.BITS, czz << Chunk.BLOCKS.BITS, Chunk.BLOCKS.SIZE << shift, Region.BLOCKS.SIZE, Chunk.BLOCKS.SIZE << shift);
			world.getGenerator().generate(buffer, world);

			int[][] heights = new int[Chunk.BLOCKS.SIZE << shift][Chunk.BLOCKS.SIZE << shift];

			int[][][][] colHeights = null;
			// We need the column before we gen anything
			for (int colX = 0; colX < width; colX++) {
				int colWorldX = colX + cxx;
				for (int colZ = 0; colZ < width; colZ++) {
					int colWorldZ = colZ + czz;
					SpoutColumn col = world.getColumn(colWorldX, colWorldZ, LoadOption.LOAD_ONLY);
					int[][] generatedHeights = new int[Chunk.BLOCKS.SIZE][Chunk.BLOCKS.SIZE];
					if (col == null) {
						if (colHeights == null) {
							colHeights = new int[width][width][][];
						}
						int[][] genHeights = world.getGenerator().getSurfaceHeight(world, colWorldX, colWorldZ);
						int regionHeight = (genHeights[7][7] >> Region.BLOCKS.BITS);
						if (regionHeight == region.getY()) {
							for (int blockX = 0; blockX < Chunk.BLOCKS.SIZE; blockX++) {
								int blWorldX = blockX + (colWorldX << Chunk.BLOCKS.BITS);
								for (int blockZ = 0; blockZ < Chunk.BLOCKS.SIZE; blockZ++) {
									int blWorldZ = blockZ + (colWorldZ << Chunk.BLOCKS.BITS);
									int topY = (int) buffer.getTop().getY();
									int botY = (int) buffer.getBase().getY();
									generatedHeights[blockX][blockZ] = region.getBlockY() - 1;
									for (int blY = topY - 1; blY >= botY; blY--) {
										BlockMaterial m = buffer.get(blWorldX, blY, blWorldZ);
										if (m.isSurface()) {
											generatedHeights[blockX][blockZ] = blY;
											break;
										}
									}
								}
							}

							colHeights[colX][colZ] = generatedHeights;
						}
					}


					for (int blockX = 0; blockX < Chunk.BLOCKS.SIZE; blockX++) {
						int indexX = (colX << Chunk.BLOCKS.BITS) + blockX;
						for (int blockZ = 0; blockZ < Chunk.BLOCKS.SIZE; blockZ++) {
							int indexZ = (colZ << Chunk.BLOCKS.BITS) + blockZ;
							int h = col == null ? generatedHeights[colX][colZ] : col.getSurfaceHeight(blockX, blockZ);
							heights[indexX][indexZ] = h;
						}
					}
				}

			}

			SnapshotLock snapshotLock = ((SpoutScheduler) Spout.getScheduler()).getSnapshotLock();
			if (colHeights != null) {
				snapshotLock.readLock(colLock);
				try {
					for (int colX = 0; colX < width; colX++) {
						int colWorldX = colX + cxx;
						for (int colZ = 0; colZ < width; colZ++) {
							int colWorldZ = colZ + czz;
							int[][] cur = colHeights[colX][colZ];
							if (cur != null) {
								world.setIfNotGenerated(colWorldX, colWorldZ, cur);
							}
						}
					}
				} finally {
					snapshotLock.readUnlock(colLock);
				}
			}

			for (int i = 0; i < managers.length; i++) {
				buffers[i] = managers[i].bulkInitializeUnchecked(buffer, heights);
			}

			// Since creating SpoutChunks is very resource intensive and slow, we want to do this outside the snapshot lock if we're not waiting
			SpoutChunk[][][] chunks = new SpoutChunk[width][Region.CHUNKS.SIZE][width];
			for (int xx = 0; xx < width; xx++) {
				cxx = cx + x + xx;
				for (int zz = 0; zz < width; zz++) {
					czz = cz + z + zz;
					for (int yy = Region.CHUNKS.SIZE - 1; yy >= 0; yy--) {
						int cyy = cy + yy;
						final CuboidBlockMaterialBuffer chunk = new CuboidBlockMaterialBuffer(cxx << Chunk.BLOCKS.BITS, cyy << Chunk.BLOCKS.BITS, czz << Chunk.BLOCKS.BITS, Chunk.BLOCKS.SIZE, Chunk.BLOCKS.SIZE, Chunk.BLOCKS.SIZE);
						chunk.write(buffer);
						SpoutChunk newChunk = new SpoutChunk(world, region, cxx, cyy, czz, new AtomicPaletteBlockStore(Chunk.BLOCKS.BITS, Spout.getEngine().getPlatform() == Platform.CLIENT, true, 10, chunk.getRawId(), chunk.getRawData()), null);
						newChunk.setGenerationIndex(generationIndex);
						chunks[xx][yy][zz] = newChunk;
					}

				}
			}
			if (generated.get().isDone()) {
				throw new IllegalStateException("Expected IN_PROGESS got COPIED");
			}

			snapshotLock.readLock(colLock);
			if (!generated.compareAndSet(GenerateState.IN_PROGRESS, GenerateState.COPYING)) {
				throw new IllegalStateException("Unable to set generate state for column " + x + ", " + z + " int region " + region.getBase().toBlockString() + " to copying, state is " + generated.get() + " wait is " + wait);
			}

			try {
				for (int xx = 0; xx < width; xx++) {
					cxx = cx + x + xx;
					for (int zz = 0; zz < width; zz++) {
						czz = cz + z + zz;
						for (int yy = Region.CHUNKS.SIZE - 1; yy >= 0; yy--) {
							int cyy = cy + yy;
							SpoutChunk newChunk = chunks[xx][yy][zz];

							for (int i = 0; i < managers.length; i++) {
								CuboidLightBuffer lightBuffer = buffers[i][xx][yy][zz];
								if (newChunk.setIfAbsentLightBuffer(lightBuffer.getManagerId(), lightBuffer) != lightBuffer) {
									Spout.getLogger().info("Unable to set light buffer for new chunk " + newChunk + " as the id is already in use, " + lightBuffer.getManagerId());
								}
							}

							boolean chunkSet = region.setChunkIfNotGeneratedWithoutLock(newChunk, x + xx, yy, z + zz);

							if (chunkSet) {
								newChunk.setModified();
							}
						}
					}
				}

				// We need to set the generated state before we unlock the readLock so waiting generators get the state immediately
				if (!generated.compareAndSet(GenerateState.COPYING, GenerateState.COPIED)) {
					throw new IllegalStateException("Column " + x + ", " + z + " rY=" + region.getChunkY() + " int region " + region.getBase().toBlockString() + " copied twice after generation, generation state is " + generated + " wait is " + wait);
				}
			} finally {
				snapshotLock.readUnlock(colLock);
			}
		} finally {
			colLock.unlock();
		}

		// TODO - what is this for?
		if (wait) {//sync?
			touchChunk(chunkX, 0, chunkZ);
		}
	}

	public static void shutdownExecutorService() {
		pool.shutdown();
	}

	public static void awaitExecutorServiceTermination() {
		boolean interrupted = false;
		try {
			boolean done = false;
			while (!done) {
				try {
					if (pool.awaitTermination(10, TimeUnit.SECONDS)) {
						done = true;
						break;
					}
					Spout.getLogger().info("Waited 10 seconds for region generator pool to shutdown");
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public void touchChunkNeighbors(SpoutChunk c) {

		if (!c.isObserved()) {
			Spout.getLogger().info("Touched chunk is not observed, " + c);
			return;
		}

		int x = c.getX() & Region.CHUNKS.MASK;
		int z = c.getZ() & Region.CHUNKS.MASK;
		int y = c.getZ() & Region.CHUNKS.MASK;

		touchChunkNeighbors(x, y, z);
	}

	private void touchChunkNeighbors(int x, int y, int z) {

		x &= Region.CHUNKS.MASK;
		z &= Region.CHUNKS.MASK;
		y &= Region.CHUNKS.MASK;

		BlockFace[] faces = BlockFaces.NESW.toArray();

		for (BlockFace face : faces) {
			IntVector3 v = face.getIntOffset();

			final int ox = x + (v.getX() << shift);

			final int oz = z + (v.getZ() << shift);

			if (ox < 0 || ox >= Region.CHUNKS.SIZE || oz < 0 || oz >= Region.CHUNKS.SIZE) {
				SpoutRegion newRegion = region.getLocalRegion(face, LoadOption.NO_LOAD);
				if (newRegion != null) {
					newRegion.getRegionGenerator().touchChunk(ox, y, oz);
				} else {
					final BlockFace finalFace = face;

					pool.submit(new Runnable() {
						@Override
						public void run() {
							SnapshotLock lock = ((SpoutScheduler) Spout.getScheduler()).getSnapshotLock();
							lock.readLock(RegionGenerator.this);
							try {
								SpoutRegion newRegion = region.getLocalRegion(finalFace, LoadOption.LOAD_GEN);
								newRegion.getRegionGenerator().touchChunk(ox, 0, oz);
							} finally {
								lock.readUnlock(RegionGenerator.this);
							}
						}
					}, true);
				}
			} else {
				touchChunk(ox, y, oz);
			}
		}
	}

	protected void touchChunk(final int x, final int y, final int z) {
		pool.submit(new Runnable() {
			@Override
			public void run() {
				if (region.inputStreamExists(x, y, z)) {
					return;
				}
				final int mask = Region.CHUNKS.MASK;

				Chunk c = region.getChunk(x, y, z, LoadOption.NO_LOAD);

				if (c != null) {
					return;
				}
				generateColumn(x & mask, z & mask, false);
			}
		}, true);
	}

	private static enum GenerateState {
		NONE,
		IN_PROGRESS,
		COPYING,
		COPIED;

		public boolean isDone() {
			return this == COPIED;
		}

		public boolean isInProgress() {
			return this == IN_PROGRESS || this == COPYING;
		}

	}

	private class NamedReentrantLock extends ReentrantLock implements Named {
		private static final long serialVersionUID = 1L;
		private final int x;
		private final int z;

		public NamedReentrantLock(int x, int z) {
			this.x = x;
			this.z = z;
		}

		@Override
		public String getName() {
			return "NamedReentrantLock{(" + x + ", " + z + "), " + region + "}";
		}
	}

	@Override
	public String getName() {
		return "RegionGenerator{" + region + "}";
	}
}
