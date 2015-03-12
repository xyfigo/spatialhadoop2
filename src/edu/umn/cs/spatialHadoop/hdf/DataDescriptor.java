/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.hdf;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An abstract class for any data descriptor
 * @author Ahmed Eldawy
 *
 */
public abstract class DataDescriptor {
  
  /**Whether the information has been loaded from file or not*/
  protected boolean loaded;
  
  /**Whether this block is extended or not*/
  protected final boolean extended;
  
  /**ID (type) of this tag*/
  public final int tagID;
  
  /**Reference number. Unique ID within this tag*/
  public final int refNo;
  
  /**Offset of this data descriptor in the HDF file*/
  public final int offset;
  
  /**Number of bytes in this data descriptor*/
  private final int length;
  
  /**Only set if this is an extended block with a compressed extension*/
  private int uncompressedLength;
  
  /**The HDFFile that contains this group*/
  protected HDFFile hdfFile;

  DataDescriptor(HDFFile hdfFile, int tagID, int refNo,
      int offset, int length, boolean extended) {
    this.hdfFile = hdfFile;
    this.tagID = tagID;
    this.refNo = refNo;
    this.offset = offset;
    this.length = length;
    this.extended = extended;
    this.loaded = false;
  }
  
  protected void lazyLoad() throws IOException {
    if (!loaded) {
      hdfFile.inStream.seek(offset);
      if (!extended) {
        // Read from the input file directly
        readFields(hdfFile.inStream);
      } else {
        // Extended block. Need to retrieve extended data first
        int extensionType = hdfFile.inStream.readUnsignedShort();
        if (extensionType == HDFConstants.SPECIAL_COMP) {
          readCompressedData();
        } else if (extensionType == HDFConstants.SPECIAL_CHUNKED) {
          // Chunked data
          readChunkedData();
        } else {
          System.err.println("Unsupported extension type "+extensionType);
        }
      }
      loaded = true;
    }
  }

  /**
   * Read extended block that is available as compressed data
   * @throws IOException
   */
  private void readCompressedData() throws IOException {
    int compressionVersion = hdfFile.inStream.readUnsignedShort();
    uncompressedLength = hdfFile.inStream.readInt();
    int linkedRefNo = hdfFile.inStream.readUnsignedShort();
    int modelType = hdfFile.inStream.readUnsignedShort();
    int compressionType = hdfFile.inStream.readUnsignedShort();
    if (compressionType == HDFConstants.COMP_CODE_DEFLATE) {
      int deflateLevel = hdfFile.inStream.readUnsignedShort();
      // Retrieve the associated compressed block
      DDID linkedBlockID = new DDID(HDFConstants.DFTAG_COMPRESSED, linkedRefNo);
      DDCompressedBlock linkedBlock =
          (DDCompressedBlock) hdfFile.retrieveElementByID(linkedBlockID);
      InputStream decompressedData = linkedBlock.decompressDeflate(deflateLevel);
      readFields(new DataInputStream(decompressedData));
    } else {
      System.err.println("Unsupported compression "+compressionType);
    }
  }
  
  /**
   * Read chunked data
   * @throws IOException
   */
  private void readChunkedData() throws IOException {
    int sp_tag_head_len = hdfFile.inStream.readInt();
    int version = hdfFile.inStream.readUnsignedByte();
    int flag = hdfFile.inStream.readInt();
    // Valid logical length of the entire element. The logical physical length
    // is this value multiplied by nt_size. The actual physical length used for
    // storage can be greater than the dataset size due to the presence of ghost
    // areas in chunks. Partial chunks are not distinguished from regular
    // chunks.
    int elem_total_length = hdfFile.inStream.readInt();
    // Logical size of data chunks
    int chunk_size = hdfFile.inStream.readInt();
    // Number type size. i.e., the size of the data type
    int nt_size = hdfFile.inStream.readInt();
    // ID of the chunk table
    int tag = hdfFile.inStream.readUnsignedShort();
    int ref = hdfFile.inStream.readUnsignedShort();
    DDID chunkTableID = new DDID(tag, ref);
    
    DDVDataHeader chunkTable = (DDVDataHeader) hdfFile.retrieveElementByID(chunkTableID);
    int chunks = chunkTable.getEntryCount();
    for (int i = 0; i < chunks; i++) {
      Object chunkData = chunkTable.getEntryAt(i);
      System.out.println(chunkData);
    }
    
    
    tag = hdfFile.inStream.readUnsignedShort();
    ref = hdfFile.inStream.readUnsignedShort();
    // For future use. Speical table for 'ghost' chunks.
    DDID specialTableID = new DDID(tag, ref);
    // Number of dimensions of the chunked element
    int nDims = hdfFile.inStream.readUnsignedShort();
    int[] flags = new int[nDims];
    int[] dimensionLengths = new int[nDims];
    int[] chunkLengths = new int[nDims];
    for (int i = 0; i < nDims; i++) {
      flags[i] = hdfFile.inStream.readInt();
      dimensionLengths[i] = hdfFile.inStream.readInt();
      chunkLengths[i] = hdfFile.inStream.readInt();
    }
    // Read fill value
    int fill_value_num_bytes = hdfFile.inStream.readInt();
    byte[] fill_value = new byte[fill_value_num_bytes];
    hdfFile.inStream.readFully(fill_value);
  }
  
  /**
   * Returns the true length of the underlying data. If this block is extended
   * with a compressed block, the uncompressed size is returned. Otherwise, the
   * size of this block as mentioned in the HDF file will be returned.
   * @return
   */
  public int getLength() {
    return extended && uncompressedLength > 0? uncompressedLength : length;
  }
  
  /**
   * Reads information of this data descriptor from the input.
   * @param in - data input from where to read information
   * @param length - total number of data bytes
   */
  protected abstract void readFields(DataInput input) throws IOException;
  
  /**
   * Reads the raw data into a byte array
   * @param in
   * @return
   * @throws IOException
   */
  protected byte[] readRawData() throws IOException {
    byte[] rawData = new byte[length];
    hdfFile.inStream.seek(offset);
    hdfFile.inStream.readFully(rawData);
    return rawData;
  }

  @Override
  public String toString() {
    return String.format("<%d,%d> offset: %d, length: %d", tagID, refNo, offset, length);
  }
}
