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
  private String fileName;
  private String targetDirname;
  //本次传输的文件开始位置
  private int startPosition;
  //本次传输的有效字节长度
  private int byteLength;
  //本次文件传输的实际文件内容
  private byte[] fileBytes;
  //是否删除该文件, 默认为否, 如果标记为删除,则服务端需要立即删除文件,并关闭本次连接
  private boolean deleted;
  //文件是否传输完成,为true时,代表客户端确认了文件已经全部写出
  private boolean transferFinished;

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public String getTargetDirname() {
    return targetDirname;
  }

  public void setTargetDirname(String targetDirname) {
    this.targetDirname = targetDirname;
  }

  public boolean isTransferFinished() {
    return transferFinished;
  }

  public void setTransferFinished(boolean transferFinished) {
    this.transferFinished = transferFinished;
  }

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

  //出于安全性考虑,将所有的../或者./等符号全部去除,同时删除/和\开头作为开头
  public void setFilePath(String filePath) {
    if (filePath != null) {
      filePath = filePath.replaceAll("[\\.]+[\\\\/]+", "");
      while (filePath.startsWith("\\") ||
          filePath.startsWith("/")) {
        filePath = filePath.substring(1);
      }
    }
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

//  public static void main(String[] args) throws Exception {
//    TransferFile file = new TransferFile();
//    file.setFilePath("./a/..\\//b");
//    String filePath = file.getFilePath();
//    System.out.printf(filePath);
//  }
}
