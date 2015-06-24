/*
 * Copyright (C) 2015 Google Inc.
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

package com.google.cloud.dataflow.sdk.io;

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * A {@code BlockBasedSource} is a {@link FileBasedSource} where a file consists of blocks of
 * records.
 *
 * <p>{@code BlockBasedSource} should be derived from when a file format does not support efficient
 * seeking to a record in the file, but can support efficient seeking to a block. Alternatively,
 * records in the file cannot be offset-addressed, but blocks can (i.e., it is not possible to say
 * that record i starts at offset m, but it is possible to say that block j starts at offset n).
 *
 * <p>The records that will be read from a {@code BlockBasedSource} that corresponds to a subrange
 * of a file [startOffset, endOffset) are those records such that the record is contained in a
 * block that starts at offset {@code i}, where {@code i >= startOffset} and {@code i < endOffset}.
 * In other words, a record will be read from the source if it is contained in a block that begins
 * within the range described by the source.
 *
 * <p>This entails that it is possible to determine the start offsets of all blocks in a file.
 *
 * <p>Progress reporting for reading from a {@code BlockBasedSource} is inaccurate. A {@link
 * BlockBasedReader} reports its current offset as {@code (offset of current block) + (current block
 * size) * (fraction of block consumed)}. However, only the offset of the current block is required
 * to be accurately reported by subclass implementations. As such, in the worst case, the current
 * offset is only updated at block boundaries.
 *
 * <p>{@code BlockBasedSource} supports dynamic splitting. However, because records in a {@code
 * BlockBasedSource} are not required to have offsets and progress reporting is inaccurate, {@code
 * BlockBasedReader} only supports splitting at block boundaries.
 * In other words, {@link BlockBasedReader#atSplitPoint} returns true iff the current record is the
 * first record in a block. See {@link FileBasedSource.FileBasedReader} for discussion about split
 * points.
 *
 * @param <T> The type of records to be read from the source.
 */
@Experimental(Experimental.Kind.SOURCE_SINK)
public abstract class BlockBasedSource<T> extends FileBasedSource<T> {
  private static final long serialVersionUID = 0;

  /**
   * Creates a {@code BlockBasedSource} based on a file name or pattern. Subclasses must call this
   * constructor when creating a {@code BlockBasedSource} for a file pattern. See
   * {@link FileBasedSource} for more information.
   */
  public BlockBasedSource(String fileOrPatternSpec, long minBundleSize) {
    super(fileOrPatternSpec, minBundleSize);
  }

  /**
   * Creates a {@code BlockBasedSource} for a single file. Subclasses must call this constructor
   * when implementing {@link BlockBasedSource#createForSubrangeOfFile}. See documentation in
   * {@link FileBasedSource}.
   */
  public BlockBasedSource(String fileName, long minBundleSize, long startOffset, long endOffset) {
    super(fileName, minBundleSize, startOffset, endOffset);
  }

  /**
   * Creates a {@code BlockBasedSource} for the specified range in a single file.
   */
  @Override
  public abstract BlockBasedSource<T> createForSubrangeOfFile(
      String fileName, long start, long end);

  /**
   * Creates a {@code BlockBasedReader}.
   */
  @Override
  public abstract BlockBasedReader<T> createSingleFileReader(
      PipelineOptions options, ExecutionContext context);

  /**
   * A {@code Block} represents a block of records that can be read.
   */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  protected abstract static class Block<T> {
    /**
     * Returns the current record.
     */
    public abstract T getCurrentRecord();

    /**
     * Reads the next record from the block and returns true iff one exists.
     */
    public abstract boolean readNextRecord() throws IOException;

    /**
     * Returns the fraction of the block already consumed (i.e., not including the current record),
     * if possible, as a value in [0, 1]. Successive calls to this method must be monotonically
     * non-decreasing.
     *
     * <p>If it is not possible to compute the fraction of the block consumed (e.g., the total
     * number of records is unknown and record offsets are unknown), this method may return zero.
     */
    public abstract double getFractionOfBlockConsumed();
  }

  /**
   * A {@code Reader} that reads records from a {@link BlockBasedSource}. If the source is a
   * subrange of a file, the blocks that will be read by this reader are those such that the first
   * byte of the block is within the range [start, end).
   */
  @Experimental(Experimental.Kind.SOURCE_SINK)
  protected abstract static class BlockBasedReader<T> extends FileBasedReader<T> {
    private Block<T> currentBlock;
    private boolean atSplitPoint;

    protected BlockBasedReader(BlockBasedSource<T> source) {
      super(source);
    }

    /**
     * Read the next block from the input.
     */
    public abstract boolean readNextBlock() throws IOException;

    /**
     * Returns the current block (the block that was read by the previous call to
     * {@link BlockBasedReader#readNextBlock}).
     */
    public abstract Block<T> getCurrentBlock() throws NoSuchElementException;

    /**
     * Returns the size of the current block in bytes as it is represented in the underlying file,
     * if possible. This method may return 0 if the size of the current block is unknown.
     *
     * <p>The size returned by this method must be such that for two successive blocks A and B,
     * {@code offset(A) + size(A) <= offset(B)}. If this is not satisfied, the progress reported
     * by the {@code BlockBasedReader} will be non-monotonic and will interfere with the quality
     * (but not correctness) of dynamic work rebalancing.
     *
     * <p>This method and {@link Block#getFractionOfBlockConsumed} are used to provide an estimate
     * of progress within a block ({@code currentBlock.getFractionOfBlockConsumed() *
     * getCurrentBlockSize()}). It is acceptable for the result of this computation to be 0, but
     * progress estimation will be inaccurate.
     */
    public abstract long getCurrentBlockSize();

    /**
     * Returns the largest offset such that starting to read from that offset includes the current
     * block.
     */
    public abstract long getCurrentBlockOffset();

    @Override
    public final T getCurrent() throws NoSuchElementException {
      return currentBlock.getCurrentRecord();
    }

    /**
     * Returns true if the reader is at a split point. A {@code BlockBasedReader} is at a split
     * point if the current record is the first record in a block. In other words, split points
     * are block boundaries.
     */
    @Override
    protected boolean isAtSplitPoint() {
      return atSplitPoint;
    }

    @Override
    protected final boolean readNextRecord() throws IOException {
      atSplitPoint = false;
      while (currentBlock == null || !currentBlock.readNextRecord()) {
        if (!readNextBlock()) {
          return false;
        }
        currentBlock = getCurrentBlock();
        atSplitPoint = true;
      }
      return true;
    }

    @Override
    public Double getFractionConsumed() {
      if (getCurrentSource().getEndOffset() == Long.MAX_VALUE) {
        return null;
      }
      long currentBlockOffset = getCurrentBlockOffset();
      long startOffset = getCurrentSource().getStartOffset();
      long endOffset = getCurrentSource().getEndOffset();
      double fractionAtBlockStart =
          ((double) (currentBlockOffset - startOffset)) / (endOffset - startOffset);
      double fractionAtBlockEnd =
          ((double) (currentBlockOffset + getCurrentBlockSize() - startOffset)
              / (endOffset - startOffset));
      return Math.min(
          1.0,
          fractionAtBlockStart
          + currentBlock.getFractionOfBlockConsumed()
            * (fractionAtBlockEnd - fractionAtBlockStart));
    }

    @Override
    protected long getCurrentOffset() {
      return getCurrentBlockOffset();
    }
  }
}