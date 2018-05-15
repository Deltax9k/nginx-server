package com.evun.axurerp;

import java.io.Serializable;

/**
 * Created by wq on 5/13/18.
 * 一次文件传输的内容
 */
public class TransferFile implements Serializable {
  private static final long serialVersionUID = 1L;

  //文件相对路径
  private String filePath;
  //本次传输的文件开始位置
  private int startPosition;
  //本次传输的有效字节长度
  private int byteLength;
  //本次文件传输的实际文件内容
  private byte[] fileBytes;
  //是否删除该文件, 默认为否, 如果标记为删除,则服务端需要立即删除文件,并关闭本次连接
  private boolean deleted;

  public int getByteLength() {
    return byteLength;
  }

  public void setByteLength(int byteLength) {
    this.byteLength = byteLength;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public void setDeleted(boolean deleted) {
    this.deleted = deleted;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public int getStartPosition() {
    return startPosition;
  }

  public void setStartPosition(int startPosition) {
    this.startPosition = startPosition;
  }

  public byte[] getFileBytes() {
    return fileBytes;
  }

  public void setFileBytes(byte[] fileBytes) {
    this.fileBytes = fileBytes;
  }
}
