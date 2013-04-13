/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.server.namenode.Content.CountsMap.Key;
import org.apache.hadoop.hdfs.server.namenode.snapshot.FileWithSnapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeDirectoryWithSnapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.util.Diff;
import org.apache.hadoop.util.StringUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.SignedBytes;

/**
 * We keep an in-memory representation of the file/block hierarchy.
 * This is a base INode class containing common fields for file and 
 * directory inodes.
 */
@InterfaceAudience.Private
public abstract class INode implements Diff.Element<byte[]> {
  public static final Log LOG = LogFactory.getLog(INode.class);

  /** parent is either an {@link INodeDirectory} or an {@link INodeReference}.*/
  private INode parent = null;

  INode(INode parent) {
    this.parent = parent;
  }

  /** Get inode id */
  public abstract long getId();

  /**
   * Check whether this is the root inode.
   */
  final boolean isRoot() {
    return getLocalNameBytes().length == 0;
  }

  /** Get the {@link PermissionStatus} */
  abstract PermissionStatus getPermissionStatus(Snapshot snapshot);

  /** The same as getPermissionStatus(null). */
  final PermissionStatus getPermissionStatus() {
    return getPermissionStatus(null);
  }

  /**
   * @param snapshot
   *          if it is not null, get the result from the given snapshot;
   *          otherwise, get the result from the current inode.
   * @return user name
   */
  abstract String getUserName(Snapshot snapshot);

  /** The same as getUserName(null). */
  public final String getUserName() {
    return getUserName(null);
  }

  /** Set user */
  abstract void setUser(String user);

  /** Set user */
  final INode setUser(String user, Snapshot latest)
      throws QuotaExceededException {
    final INode nodeToUpdate = recordModification(latest);
    nodeToUpdate.setUser(user);
    return nodeToUpdate;
  }
  /**
   * @param snapshot
   *          if it is not null, get the result from the given snapshot;
   *          otherwise, get the result from the current inode.
   * @return group name
   */
  abstract String getGroupName(Snapshot snapshot);

  /** The same as getGroupName(null). */
  public final String getGroupName() {
    return getGroupName(null);
  }

  /** Set group */
  abstract void setGroup(String group);

  /** Set group */
  final INode setGroup(String group, Snapshot latest)
      throws QuotaExceededException {
    final INode nodeToUpdate = recordModification(latest);
    nodeToUpdate.setGroup(group);
    return nodeToUpdate;
  }

  /**
   * @param snapshot
   *          if it is not null, get the result from the given snapshot;
   *          otherwise, get the result from the current inode.
   * @return permission.
   */
  abstract FsPermission getFsPermission(Snapshot snapshot);
  
  /** The same as getFsPermission(null). */
  public final FsPermission getFsPermission() {
    return getFsPermission(null);
  }

  /** Set the {@link FsPermission} of this {@link INode} */
  abstract void setPermission(FsPermission permission);

  /** Set the {@link FsPermission} of this {@link INode} */
  INode setPermission(FsPermission permission, Snapshot latest)
      throws QuotaExceededException {
    final INode nodeToUpdate = recordModification(latest);
    nodeToUpdate.setPermission(permission);
    return nodeToUpdate;
  }

  /**
   * @return if the given snapshot is null, return this;
   *     otherwise return the corresponding snapshot inode.
   */
  public INode getSnapshotINode(final Snapshot snapshot) {
    return this;
  }

  /** Is this inode in the latest snapshot? */
  public final boolean isInLatestSnapshot(final Snapshot latest) {
    if (latest == null) {
      return false;
    }
    // if parent is a reference node, parent must be a renamed node. We can 
    // stop the check at the reference node.
    if (parent != null && parent.isReference()) {
      return true;
    }
    final INodeDirectory parentDir = getParent();
    if (parentDir == null) { // root
      return true;
    }
    if (!parentDir.isInLatestSnapshot(latest)) {
      return false;
    }
    final INode child = parentDir.getChild(getLocalNameBytes(), latest);
    if (this == child) {
      return true;
    }
    if (child == null || !(child.isReference())) {
      return false;
    }
    return this == child.asReference().getReferredINode();
  }
  
  /**
   * Called by {@link INode#recordModification}. For a reference node and its
   * subtree, the function tells which snapshot the modification should be
   * associated with: the snapshot that belongs to the SRC tree of the rename
   * operation, or the snapshot belonging to the DST tree.
   * 
   * @param latest
   *          the latest snapshot in the DST tree above the reference node
   * @return True: the modification should be recorded in the snapshot that
   *         belongs to the SRC tree. False: the modification should be
   *         recorded in the snapshot that belongs to the DST tree.
   */
  public final boolean isInSrcSnapshot(final Snapshot latest) {
    if (latest == null) {
      return true;
    }
    INodeReference withCount = getParentReference();
    if (withCount != null) {
      int dstSnapshotId = withCount.getParentReference().getDstSnapshotId();
      if (dstSnapshotId >= latest.getId()) {
        return true;
      }
    }
    return false;
  }

  /**
   * This inode is being modified.  The previous version of the inode needs to
   * be recorded in the latest snapshot.
   *
   * @param latest the latest snapshot that has been taken.
   *        Note that it is null if no snapshots have been taken.
   * @return The current inode, which usually is the same object of this inode.
   *         However, in some cases, this inode may be replaced with a new inode
   *         for maintaining snapshots. The current inode is then the new inode.
   */
  abstract INode recordModification(final Snapshot latest)
      throws QuotaExceededException;

  /** Check whether it's a reference. */
  public boolean isReference() {
    return false;
  }

  /** Cast this inode to an {@link INodeReference}.  */
  public INodeReference asReference() {
    throw new IllegalStateException("Current inode is not a reference: "
        + this.toDetailString());
  }

  /**
   * Check whether it's a file.
   */
  public boolean isFile() {
    return false;
  }

  /** Cast this inode to an {@link INodeFile}.  */
  public INodeFile asFile() {
    throw new IllegalStateException("Current inode is not a file: "
        + this.toDetailString());
  }

  /**
   * Check whether it's a directory
   */
  public boolean isDirectory() {
    return false;
  }

  /** Cast this inode to an {@link INodeDirectory}.  */
  public INodeDirectory asDirectory() {
    throw new IllegalStateException("Current inode is not a directory: "
        + this.toDetailString());
  }

  /**
   * Check whether it's a symlink
   */
  public boolean isSymlink() {
    return false;
  }

  /** Cast this inode to an {@link INodeSymlink}.  */
  public INodeSymlink asSymlink() {
    throw new IllegalStateException("Current inode is not a symlink: "
        + this.toDetailString());
  }

  /**
   * Clean the subtree under this inode and collect the blocks from the descents
   * for further block deletion/update. The current inode can either resides in
   * the current tree or be stored as a snapshot copy.
   * 
   * <pre>
   * In general, we have the following rules. 
   * 1. When deleting a file/directory in the current tree, we have different 
   * actions according to the type of the node to delete. 
   * 
   * 1.1 The current inode (this) is an {@link INodeFile}. 
   * 1.1.1 If {@code prior} is null, there is no snapshot taken on ancestors 
   * before. Thus we simply destroy (i.e., to delete completely, no need to save 
   * snapshot copy) the current INode and collect its blocks for further 
   * cleansing.
   * 1.1.2 Else do nothing since the current INode will be stored as a snapshot
   * copy.
   * 
   * 1.2 The current inode is an {@link INodeDirectory}.
   * 1.2.1 If {@code prior} is null, there is no snapshot taken on ancestors 
   * before. Similarly, we destroy the whole subtree and collect blocks.
   * 1.2.2 Else do nothing with the current INode. Recursively clean its 
   * children.
   * 
   * 1.3 The current inode is a {@link FileWithSnapshot}.
   * Call {@link INode#recordModification(Snapshot)} to capture the 
   * current states. Mark the INode as deleted.
   * 
   * 1.4 The current inode is a {@link INodeDirectoryWithSnapshot}.
   * Call {@link INode#recordModification(Snapshot)} to capture the 
   * current states. Destroy files/directories created after the latest snapshot 
   * (i.e., the inodes stored in the created list of the latest snapshot).
   * Recursively clean remaining children. 
   *
   * 2. When deleting a snapshot.
   * 2.1 To clean {@link INodeFile}: do nothing.
   * 2.2 To clean {@link INodeDirectory}: recursively clean its children.
   * 2.3 To clean {@link FileWithSnapshot}: delete the corresponding snapshot in
   * its diff list.
   * 2.4 To clean {@link INodeDirectoryWithSnapshot}: delete the corresponding 
   * snapshot in its diff list. Recursively clean its children.
   * </pre>
   * 
   * @param snapshot
   *          The snapshot to delete. Null means to delete the current
   *          file/directory.
   * @param prior
   *          The latest snapshot before the to-be-deleted snapshot. When
   *          deleting a current inode, this parameter captures the latest
   *          snapshot.
   * @param collectedBlocks
   *          blocks collected from the descents for further block
   *          deletion/update will be added to the given map.
   * @return quota usage delta when deleting a snapshot
   */
  public abstract Quota.Counts cleanSubtree(final Snapshot snapshot,
      Snapshot prior, BlocksMapUpdateInfo collectedBlocks)
      throws QuotaExceededException;
  
  /**
   * Destroy self and clear everything! If the INode is a file, this method
   * collects its blocks for further block deletion. If the INode is a 
   * directory, the method goes down the subtree and collects blocks from the 
   * descents, and clears its parent/children references as well. The method 
   * also clears the diff list if the INode contains snapshot diff list.
   * 
   * @param collectedBlocks blocks collected from the descents for further block
   *                        deletion/update will be added to this map.
   */
  public abstract void destroyAndCollectBlocks(
      BlocksMapUpdateInfo collectedBlocks);

  /** Compute {@link ContentSummary}. */
  public final ContentSummary computeContentSummary() {
    final Content.Counts current = computeContentSummary(
        new Content.CountsMap()).getCounts(Key.CURRENT);
    return new ContentSummary(current.get(Content.LENGTH),
        current.get(Content.FILE) + current.get(Content.SYMLINK),
        current.get(Content.DIRECTORY), getNsQuota(),
        current.get(Content.DISKSPACE), getDsQuota());
  }

  /**
   * Count subtree content summary with a {@link Content.CountsMap}.
   *
   * @param countsMap The subtree counts for returning.
   * @return The same objects as the counts parameter.
   */
  public abstract Content.CountsMap computeContentSummary(
      Content.CountsMap countsMap);

  /**
   * Count subtree content summary with a {@link Content.Counts}.
   *
   * @param counts The subtree counts for returning.
   * @return The same objects as the counts parameter.
   */
  public abstract Content.Counts computeContentSummary(Content.Counts counts);
  
  /**
   * Check and add namespace/diskspace consumed to itself and the ancestors.
   * @throws QuotaExceededException if quote is violated.
   */
  public void addSpaceConsumed(long nsDelta, long dsDelta)
      throws QuotaExceededException {
    final INodeDirectory parentDir = getParent();
    if (parentDir != null) {
      parentDir.addSpaceConsumed(nsDelta, dsDelta);
    }
  }

  /**
   * Get the quota set for this inode
   * @return the quota if it is set; -1 otherwise
   */
  public long getNsQuota() {
    return -1;
  }

  public long getDsQuota() {
    return -1;
  }
  
  public final boolean isQuotaSet() {
    return getNsQuota() >= 0 || getDsQuota() >= 0;
  }
  
  /**
   * Count subtree {@link Quota#NAMESPACE} and {@link Quota#DISKSPACE} usages.
   */
  final Quota.Counts computeQuotaUsage() {
    return computeQuotaUsage(new Quota.Counts(), true);
  }

  /**
   * Count subtree {@link Quota#NAMESPACE} and {@link Quota#DISKSPACE} usages.
   * 
   * @param counts The subtree counts for returning.
   * @return The same objects as the counts parameter.
   */
  public abstract Quota.Counts computeQuotaUsage(Quota.Counts counts,
      boolean useCache);
  
  /**
   * @return null if the local name is null; otherwise, return the local name.
   */
  public final String getLocalName() {
    final byte[] name = getLocalNameBytes();
    return name == null? null: DFSUtil.bytes2String(name);
  }

  /**
   * @return null if the local name is null;
   *         otherwise, return the local name byte array.
   */
  public abstract byte[] getLocalNameBytes();

  @Override
  public final byte[] getKey() {
    return getLocalNameBytes();
  }

  /**
   * Set local file name
   */
  public abstract void setLocalName(byte[] name);

  public String getFullPathName() {
    // Get the full path name of this inode.
    return FSDirectory.getFullPathName(this);
  }
  
  @Override
  public String toString() {
    return getLocalName();
  }

  @VisibleForTesting
  public final String getObjectString() {
    return getClass().getSimpleName() + "@"
        + Integer.toHexString(super.hashCode());
  }

  /** @return a string description of the parent. */
  @VisibleForTesting
  public final String getParentString() {
    final INodeReference parentRef = getParentReference();
    if (parentRef != null) {
      return "parentRef=" + parentRef.getLocalName() + "->";
    } else {
      final INodeDirectory parentDir = getParent();
      if (parentDir != null) {
        return "parentDir=" + parentDir.getLocalName() + "/";
      } else {
        return "parent=null";
      }
    }
  }

  @VisibleForTesting
  public String toDetailString() {
    return toString() + "(" + getObjectString() + "), " + getParentString();
  }

  /** @return the parent directory */
  public final INodeDirectory getParent() {
    return parent == null? null
        : parent.isReference()? getParentReference().getParent(): parent.asDirectory();
  }

  /**
   * @return the parent as a reference if this is a referred inode;
   *         otherwise, return null.
   */
  public INodeReference getParentReference() {
    return parent == null || !parent.isReference()? null: (INodeReference)parent;
  }

  /** Set parent directory */
  public final void setParent(INodeDirectory parent) {
    this.parent = parent;
  }

  /** Set container. */
  public final void setParentReference(INodeReference parent) {
    this.parent = parent;
  }

  /** Clear references to other objects. */
  public void clear() {
    setParent(null);
  }

  /**
   * @param snapshot
   *          if it is not null, get the result from the given snapshot;
   *          otherwise, get the result from the current inode.
   * @return modification time.
   */
  abstract long getModificationTime(Snapshot snapshot);

  /** The same as getModificationTime(null). */
  public final long getModificationTime() {
    return getModificationTime(null);
  }

  /** Update modification time if it is larger than the current value. */
  public abstract INode updateModificationTime(long mtime, Snapshot latest)
      throws QuotaExceededException;

  /** Set the last modification time of inode. */
  public abstract void setModificationTime(long modificationTime);

  /** Set the last modification time of inode. */
  public final INode setModificationTime(long modificationTime, Snapshot latest)
      throws QuotaExceededException {
    final INode nodeToUpdate = recordModification(latest);
    nodeToUpdate.setModificationTime(modificationTime);
    return nodeToUpdate;
  }

  /**
   * @param snapshot
   *          if it is not null, get the result from the given snapshot;
   *          otherwise, get the result from the current inode.
   * @return access time
   */
  abstract long getAccessTime(Snapshot snapshot);

  /** The same as getAccessTime(null). */
  public final long getAccessTime() {
    return getAccessTime(null);
  }

  /**
   * Set last access time of inode.
   */
  public abstract void setAccessTime(long accessTime);

  /**
   * Set last access time of inode.
   */
  public final INode setAccessTime(long accessTime, Snapshot latest)
      throws QuotaExceededException {
    final INode nodeToUpdate = recordModification(latest);
    nodeToUpdate.setAccessTime(accessTime);
    return nodeToUpdate;
  }


  /**
   * Breaks file path into components.
   * @param path
   * @return array of byte arrays each of which represents 
   * a single path component.
   */
  static byte[][] getPathComponents(String path) {
    return getPathComponents(getPathNames(path));
  }

  /** Convert strings to byte arrays for path components. */
  static byte[][] getPathComponents(String[] strings) {
    if (strings.length == 0) {
      return new byte[][]{null};
    }
    byte[][] bytes = new byte[strings.length][];
    for (int i = 0; i < strings.length; i++)
      bytes[i] = DFSUtil.string2Bytes(strings[i]);
    return bytes;
  }

  /**
   * Splits an absolute path into an array of path components.
   * @param path
   * @throws AssertionError if the given path is invalid.
   * @return array of path components.
   */
  static String[] getPathNames(String path) {
    if (path == null || !path.startsWith(Path.SEPARATOR)) {
      throw new AssertionError("Absolute path required");
    }
    return StringUtils.split(path, Path.SEPARATOR_CHAR);
  }

  /**
   * Given some components, create a path name.
   * @param components The path components
   * @param start index
   * @param end index
   * @return concatenated path
   */
  static String constructPath(byte[][] components, int start, int end) {
    StringBuilder buf = new StringBuilder();
    for (int i = start; i < end; i++) {
      buf.append(DFSUtil.bytes2String(components[i]));
      if (i < end - 1) {
        buf.append(Path.SEPARATOR);
      }
    }
    return buf.toString();
  }

  @Override
  public final int compareTo(byte[] bytes) {
    final byte[] name = getLocalNameBytes();
    final byte[] left = name == null? DFSUtil.EMPTY_BYTES: name;
    final byte[] right = bytes == null? DFSUtil.EMPTY_BYTES: bytes;
    return SignedBytes.lexicographicalComparator().compare(left, right);
  }

  @Override
  public final boolean equals(Object that) {
    if (this == that) {
      return true;
    }
    if (that == null || !(that instanceof INode)) {
      return false;
    }
    return Arrays.equals(this.getLocalNameBytes(),
        ((INode)that).getLocalNameBytes());
  }

  @Override
  public final int hashCode() {
    return Arrays.hashCode(getLocalNameBytes());
  }
  
  /**
   * Dump the subtree starting from this inode.
   * @return a text representation of the tree.
   */
  @VisibleForTesting
  public final StringBuffer dumpTreeRecursively() {
    final StringWriter out = new StringWriter(); 
    dumpTreeRecursively(new PrintWriter(out, true), new StringBuilder(), null);
    return out.getBuffer();
  }

  @VisibleForTesting
  public final void dumpTreeRecursively(PrintStream out) {
    dumpTreeRecursively(new PrintWriter(out, true), new StringBuilder(), null);
  }

  /**
   * Dump tree recursively.
   * @param prefix The prefix string that each line should print.
   */
  @VisibleForTesting
  public void dumpTreeRecursively(PrintWriter out, StringBuilder prefix,
      Snapshot snapshot) {
    out.print(prefix);
    out.print(" ");
    out.print(getLocalName());
    out.print("   (");
    out.print(getObjectString());
    out.print("), ");
    out.print(getParentString());
    out.print(", " + getPermissionStatus(snapshot));
  }
  
  /**
   * Information used for updating the blocksMap when deleting files.
   */
  public static class BlocksMapUpdateInfo {
    /**
     * The list of blocks that need to be removed from blocksMap
     */
    private List<Block> toDeleteList;
    
    public BlocksMapUpdateInfo(List<Block> toDeleteList) {
      this.toDeleteList = toDeleteList == null ? new ArrayList<Block>()
          : toDeleteList;
    }
    
    public BlocksMapUpdateInfo() {
      toDeleteList = new ArrayList<Block>();
    }
    
    /**
     * @return The list of blocks that need to be removed from blocksMap
     */
    public List<Block> getToDeleteList() {
      return toDeleteList;
    }
    
    /**
     * Add a to-be-deleted block into the
     * {@link BlocksMapUpdateInfo#toDeleteList}
     * @param toDelete the to-be-deleted block
     */
    public void addDeleteBlock(Block toDelete) {
      if (toDelete != null) {
        toDeleteList.add(toDelete);
      }
    }
    
    /**
     * Clear {@link BlocksMapUpdateInfo#toDeleteList}
     */
    public void clear() {
      toDeleteList.clear();
    }
  }
}